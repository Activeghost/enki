(ns enki.core.unit-tests
  (:require [clojure.edn :as edn]
            [clojure.java.io :as io]
            [clojure.tools.logging :as log]
            [clojure.test :refer [deftest is use-fixtures testing]]
            [enki.core :as sut :refer :all])
  (:import  java.nio.file.DirectoryNotEmptyException
            org.apache.kafka.common.serialization.Serdes
            [org.apache.kafka.streams StreamsConfig TopologyTestDriver]
            org.apache.kafka.test.TestUtils
            org.apache.kafka.streams.test.ConsumerRecordFactory))

;;
;; FIXTURES
;;
(defn one-time-setup []
  (println "one time setup"))

(defn one-time-teardown []
  (println "one time teardown"))

(defn once-fixture [f]
  (one-time-setup)
  (println (type f))
  (f)
  (one-time-teardown))

(defn setup []
  (println "setup"))

(defn teardown []
  (println "teardown"))

(defn each-fixture [f]
  (setup)
  (println (type f))
  (f)
  (teardown))

(use-fixtures :once once-fixture)
(use-fixtures :each each-fixture)

;;
;; TESTS
;;
(def KEY "key")
(def VALUE "value")
(def XFORM_KEY "transformed key")
(def XFORM_VALUE "transformed value")

(def properties
  (let [properties (java.util.Properties.)]
    (.put properties StreamsConfig/APPLICATION_ID_CONFIG (str "some-microservice" (rand)))
    (.put properties StreamsConfig/BOOTSTRAP_SERVERS_CONFIG "dummy:9092")
    (.put properties StreamsConfig/COMMIT_INTERVAL_MS_CONFIG  (* 10 1000))
    (.put properties StreamsConfig/DEFAULT_KEY_SERDE_CLASS_CONFIG (.getName (.getClass (Serdes/String))))
    (.put properties StreamsConfig/DEFAULT_VALUE_SERDE_CLASS_CONFIG (.getName (.getClass (Serdes/String))))
    (.put properties StreamsConfig/PROCESSING_GUARANTEE_CONFIG StreamsConfig/EXACTLY_ONCE)
    (.put properties StreamsConfig/STATE_DIR_CONFIG (.getAbsolutePath (TestUtils/tempDirectory)))
    properties))

(def  kafka-configuration {:applicationid           "open-calais-tagging-microservice"
                            :bootstrap-servers       "bootstrap:9092"
                            :auto.commit.interval.ms 10000 ;; milliseconds
                            :input-topic             "db.input.topic"
                            :output-topic            "streaming.output.topic"
                            :store-name              "theStore"})

(defn xform-fn
  [kv-pair]
  {:pre [(and (= KEY (:key kv-pair)) (= VALUE (:record kv-pair)))]}
  (log/info "[xform-fn] called by processor with: " kv-pair)
  {:key   XFORM_KEY
   :value XFORM_VALUE})

;; TODO: FIX test to pass in the config.
(deftest the-processor-api-test
    ;; ARRANGE
  (swap! sut/streams-config conj kafka-configuration)
  (let [topology             (sut/the-topology xform-fn)
        topology-test-driver (TopologyTestDriver. topology properties)
        serializer           (.serializer (. Serdes String))
        deserializer         (.deserializer (. Serdes String))
        input-topic          (:input-topic kafka-configuration)
        output-topic         (:output-topic kafka-configuration)
        factory              (ConsumerRecordFactory. input-topic serializer serializer)
        ]

    (testing "Test that a valid key/value pair can be read from the output topic given valid inputs"

      ;; ACT
      (.pipeInput topology-test-driver (.create factory KEY VALUE))

      (let [output       (.readOutput topology-test-driver output-topic deserializer deserializer)]

        (log/infof "[the-processor-api-test] recieved:  %s" output)

        ;; ASSERT
        (is (= XFORM_KEY (.key output)))
        (is (= XFORM_VALUE (.value output))))

      (try
        (.close topology-test-driver)

        ;; TODO [https://issues.apache.org/jira/browse/KAFKA-6647] actually handle the cleanup until they patch this (or we go fix it)
        (catch org.apache.kafka.streams.errors.StreamsException ex
          (log/info "[the-processor-api-test] Kafka streams test utilities failed to clean up the temp directory"))))))

