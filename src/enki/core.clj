(ns enki.core
  ;; IMPORTANT: Make sure that advertised listeners is uncommented in your server properties
  (:gen-class)
  (:require [clojure.core]
            [clojure.edn :as edn]
            [clojure.spec.alpha :as s]
            [clojure.spec.gen.alpha :as gen]
            [clojure.string :as str]
            [clojure.tools.logging :as log]
            [expound.alpha :as e])
  (:import (org.apache.kafka.streams.processor Processor ProcessorSupplier PunctuationType Punctuator)
           (org.apache.kafka.streams KafkaStreams Topology StreamsConfig)
           (org.apache.kafka.common.serialization Serdes)
           (org.apache.kafka.streams.state Stores)))
;;
;; SPECS
;;
(def ip-regex #"^(([0-9]{1,3}.){3}([0-9]{1,3})|localhost):\d+$")
(def hostname-regex #"^(([a-zA-Z]|[a-zA-Z][a-zA-Z0-9\-]*[a-zA-Z0-9])\.)*([A-Za-z]|[A-Za-z][A-Za-z0-9\-]*[A-Za-z0-9]):\d+$")
(def filename-regex #"((?:[^/]*/)*)(.*)")

;; TODO: Decide whether to move common specs to common place (are these common entities the same? I think so.)
(s/def ::file-location (s/and ::significant-string #(re-matches filename-regex %)))
(s/def ::significant-string (s/with-gen
                              (s/and string? #(not= % nil?) #(not= % ""))
                              (fn [] (gen/such-that #(not= % "")
                                                    (gen/string-alphanumeric)))))

;; TODO add regex constrations to input/output topics to enforce naming schema and possibly constrain commit interval
(s/def ::applicationid ::significant-string)
(s/def ::punctuate.interval.ms int?)
(s/def ::bootstrap-servers-by-ip #(re-matches ip-regex %))
(s/def ::bootstrap-servers-by-hostname #(re-matches hostname-regex %))
(s/def ::bootstrap-servers (s/and string?
                                 (or ::bootstrap-servers-by-hostname ::bootstrap-servers-by-ip)))
(s/def ::input-topic ::significant-string)
(s/def ::output-topic ::significant-string)
(s/def ::store-name ::significant-string)
(s/def ::kafka-configuration (s/keys :req-un [::applicationid ::bootstrap-servers ::input-topic ::output-topic ::punctuate.interval.ms ::store-name]) )

;;
;; CONSTANTS
;;
(def SOURCE "SOURCE")
(def PROCESSOR "Process")
(def SINK "SINK")

;;
;; STATE
;;
(def streams-config (atom {}))
(def state (ref {}))

;;
;; STREAM PROCESSING
;;
(defn get-kafka-config
  "Get the kafka configuration"
  []
  (let [maybe-correct-config  @streams-config
        kafka-config      (s/conform ::kafka-configuration maybe-correct-config)]
    (when (= ::s/invalid kafka-config)
      (do
        (e/expound ::kafka-configuration maybe-correct-config)
        (throw (ex-info "The configuration provided did not match spec." (s/explain-data ::kafka-configuration maybe-correct-config)))))
    kafka-config))

(defn state-store->put
  [key value]
  )

(defn punctuator-forward-message
  [timestamp kvstore context]
  (reify
    Punctuator
    (punctuate [_ timestamp]
      (let [iter (iterator-seq (.all @kvstore))]
        (log/infof "[punctuator-forward-message] Iter %s" (pr-str iter))
        (dorun (map #(.forward @context (.key %) (str (.value %))) iter))
        (.commit @context)))))

(defn the-processor
  [store-name client-processor-callback]

  (let [store     (atom {})
        context   (atom nil)
        timestamp (:punctuate.interval.ms (get-kafka-config))]

    ;; Reify the Processor to override (substituting our own behavior) some of it's behavior
    (reify Processor
      (close [_])
      (init [this processor-context]
        "Required: called by the Kafka Streams library during task construction phase with metadata of the current record"
            ;; set the values of our mutable atoms (without regard to what is already there)
        (reset! context processor-context)
        (reset! store (.getStateStore @context store-name))

            ;; scheduling logic
        (.schedule @context
                   timestamp
                   PunctuationType/STREAM_TIME
                   (punctuator-forward-message timestamp store context)))

      (process [_ key record]
        "called on each of the received records"
        (log/infof "[the-processor::process] key: %s record: %s" key record)

        (try 
          (client-processor-callback {:key    key
                                      :producer-record record}
                                     (fn [key] 
                                       (log/debugf "[anon-store-getter] getting key: '%s' from state store" key)
                                       (.get @store key))
                                     (fn [key value] 
                                       (log/debugf "[anon-store-put] putting key:'%s' value: '%s' into state store" key value)
                                       (.put @store key value)))
          (catch Exception ex (log/error ex)))))))

(defn processor-supplier
  [store-name client-processor-callback]
  (reify
    ProcessorSupplier
    (get [_] (the-processor store-name client-processor-callback))))

;; TODO: Integrate avro key/value serialization and deserialization and schema registry access.
(defn the-topology
  "build the stream processing topology. See https://kafka.apache.org/11/documentation/streams/developer-guide/processor-api.html#streams-developer-guide-state-store"
  [client-processor-callback]
  (log/info "[the-topology] Configuring topology")
  (let [kafka-config      (get-kafka-config)]
    (let [ input-topic (s/conform ::input-topic (:input-topic kafka-config))
          output-topic (s/conform ::output-topic (:output-topic kafka-config))
          builder    (Topology.)
          store-name (s/conform ::store-name (:store-name kafka-config))
          store      (Stores/keyValueStoreBuilder
                     (Stores/persistentKeyValueStore store-name)
                     (Serdes/String)
                     (Serdes/String))]
      (-> builder
          ; add the source processing node that takes the db.draft.created topic as input
          (.addSource SOURCE (into-array String [input-topic]))

        ; add the tag PROCESSOR with the source PROCESSOR as it's upstream (this does the work)
          (.addProcessor PROCESSOR
                       (processor-supplier store-name client-processor-callback)
                       (into-array String [SOURCE]))

        ;; add our state store associated with the tag PROCESSOR
          (.addStateStore store (into-array String [PROCESSOR]))

        ;; add the SINK that streams the upstream tag PROCESSOR output into the streaming.calais.tags topic.
          (.addSink SINK output-topic (into-array String [PROCESSOR]))))))

;;
;; STREAM CONFIG AND LIFECYCLE
;;
(defn start->streams
  [client-processor-callback kafka-config]
  {:pre [(s/valid? ::kafka-configuration kafka-config)]}
  (log/info "[start->streams] Starting streams using client configuration: " kafka-config)

  (when (not (instance? KafkaStreams (:streams @state)))
      (try
        ;; if we get to a place where we can restart the kafka streams from a stopped state then move this outside in case clients pass new configs.
        (do (swap! streams-config conj kafka-config)
            (let [kafka-config            (get-kafka-config)
                  stream-processing-props {StreamsConfig/APPLICATION_ID_CONFIG            (:applicationid kafka-config)
                                           StreamsConfig/BOOTSTRAP_SERVERS_CONFIG         (:bootstrap-servers kafka-config)
                                           StreamsConfig/DEFAULT_KEY_SERDE_CLASS_CONFIG   (.getName (.getClass (Serdes/String)))
                                           StreamsConfig/DEFAULT_VALUE_SERDE_CLASS_CONFIG (.getName (.getClass (Serdes/String)))
                                           StreamsConfig/PROCESSING_GUARANTEE_CONFIG      StreamsConfig/EXACTLY_ONCE}]
              (log/debugf "[start->streams] creating kafka stream with StreamsConfig: %s" stream-processing-props)
              (dosync
               (alter state conj (-> {:streams (KafkaStreams. (the-topology client-processor-callback) (StreamsConfig. stream-processing-props))})))
              (log/infof "[start->streams] streams instance created")))
        (catch Exception e (log/error e))))
  (let [streams      (:streams @state)
        stream-state (.state streams)]
    (if (= stream-state "CREATED")
      (do (log/info "[start->streams] KafkaStreams instance started.") 
          (.start (:streams @state)))
      (log/warnf "[start->streams] KafkaStreams is not in a valid state to attempt starting. State: %s" stream-state))))

(defn close->streams
  []
  (log/info "[close->streams] STOP: streams processing")
  (.close  (:streams @state))
  (dosync
   (alter state conj (-> {:streams nil}))))

(.addShutdownHook
 (Runtime/getRuntime)
 (Thread.
  #(do
     (log/info "[shutdown hook] Shutting down")
     (when (not (nil?  (:streams @state))) (close->streams))
     (log/info "Bye!")
     (flush))))
    