(ns enki.core.unit-tests
  (:require [clojure.edn :as edn]
            [clojure.java.io :as io]
            [clojure.spec.alpha :as s]
            [clojure.spec.gen.alpha :as gen]
            [clojure.tools.logging :as log]
            [clojure.test :refer [deftest is use-fixtures testing]]
            [enki.core :as sut :refer :all])
  (:import  java.nio.file.DirectoryNotEmptyException
            org.apache.kafka.common.serialization.Serdes
            [org.apache.kafka.streams StreamsConfig TopologyTestDriver]
            org.apache.kafka.test.TestUtils
            org.apache.kafka.streams.test.ConsumerRecordFactory))
;;
;; DEFS
;;
(def KEY "key")
(def VALUE "value")

(def NEW_KEY_PREFIX "new-")
(def TRANSFORMED_KEY_PREFIX "transformed-")
(def XFORM_KEY "transformed key")
(def SAMPLE_VERSION_SET_UUID "1234")

(def FIRST_VALUE "new value")
(def SECOND_VALUE "updated stored value")
(def THIRD_VALUE "a value")

(def  kafka-configuration {:applicationid         "my-cool-microservice"
                           :bootstrap-servers     "bootstrap:9092"
                           :input-topic           "db.input.topic"
                           :output-topic          "streaming.output.topic"
                           :punctuate.interval.ms 10
                           :store-name            "theStore"})

(def properties
  (let [properties (java.util.Properties.)]
    (.put properties StreamsConfig/APPLICATION_ID_CONFIG (str "some-microservice" (rand)))
    (.put properties StreamsConfig/BOOTSTRAP_SERVERS_CONFIG "dummy:9092")
    (.put properties StreamsConfig/DEFAULT_KEY_SERDE_CLASS_CONFIG (.getName (.getClass (Serdes/String))))
    (.put properties StreamsConfig/DEFAULT_VALUE_SERDE_CLASS_CONFIG (.getName (.getClass (Serdes/String))))
    (.put properties StreamsConfig/PROCESSING_GUARANTEE_CONFIG StreamsConfig/EXACTLY_ONCE)
    (.put properties StreamsConfig/STATE_DIR_CONFIG (.getAbsolutePath (TestUtils/tempDirectory)))
    properties))

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
  (println "setup")

  ;; generate unique application id's for every test until TopologyTestDriver is fixed on Windows.
  (swap! sut/streams-config conj (assoc kafka-configuration :applicationid (first (gen/sample (s/gen ::sut/applicationid))))))

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
(defn xform-fn
  [kv-pair get-fn put-fn]
  {:pre [(and (string? (:key kv-pair)) (string? (:producer-record kv-pair)))]}
  (log/info "[xform-fn] called by processor with: " kv-pair)
  (let [key                  (:key kv-pair)
        record               (:producer-record kv-pair)
        current-stored-value (get-fn (str NEW_KEY_PREFIX key))]
    (if (nil? current-stored-value)
      (do
        ;; do something with the new key/record pair
        (log/info "[xform-fn] no existing value found")
        (put-fn (str NEW_KEY_PREFIX key) FIRST_VALUE))
      (do
        ;; do something with the old values or the new ones, or both.
        (log/infof "[xform-fn] found existing key: %s in the state store" key)
        (put-fn (str TRANSFORMED_KEY_PREFIX key) SECOND_VALUE)))))

(deftest the-processor-api-test
  ;; ARRANGE
  ;; TODO: associate new app ids for each test until Kafka devs can be bothered with fixing cleanup issues on Windows.
  (let [topology             (sut/the-topology xform-fn)
        topology-test-driver (TopologyTestDriver. topology properties)
        serializer           (.serializer (. Serdes String))
        deserializer         (.deserializer (. Serdes String))
        input-topic          (:input-topic kafka-configuration)
        output-topic         (:output-topic kafka-configuration)
        factory              (ConsumerRecordFactory. input-topic serializer serializer)]
    (testing "Test that the second time through we can use our store accessor to detect that a value already exists."

      ;; ACT

      (.pipeInput topology-test-driver (.create factory KEY VALUE))
      (.pipeInput topology-test-driver (.create factory KEY VALUE))
      (.pipeInput topology-test-driver (.create factory SAMPLE_VERSION_SET_UUID THIRD_VALUE))

      (let [state-store (.getStateStore topology-test-driver (:store-name kafka-configuration))
            count       (.approximateNumEntries state-store)]

        (log/infof "[the-processor-api-test] state store count:  %s" count)
        (log/debug (pr-str (iterator-seq (.all state-store))))

        ;; ASSERT
        (is (= 3 count))
        (is (= SECOND_VALUE (.get state-store (str TRANSFORMED_KEY_PREFIX KEY))))
        (is (= FIRST_VALUE (.get state-store (str NEW_KEY_PREFIX SAMPLE_VERSION_SET_UUID))))))

    (try
      (.close topology-test-driver)

        ;; TODO [https://issues.apache.org/jira/browse/KAFKA-6647] actually handle the cleanup until they patch this (or we go fix it)
      (catch org.apache.kafka.streams.errors.StreamsException ex
        (log/info "[the-processor-api-test] Kafka streams test utilities failed to clean up the temp directory")))))