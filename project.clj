(defproject enki "0.1.2-SNAPSHOT"
  :description "A kafka streams processor"
  :url "https://github.com/Activeghost/enki"
  :license {:name "EPL-2.0 OR GPL-2.0-or-later WITH Classpath-exception-2.0"
            :url  "https://www.eclipse.org/legal/epl-2.0/"}
  :dependencies [
                 [expound "0.7.2"]                          ;; https://github.com/bhb/expound  ...for nice spec messages
                 [org.apache.kafka/kafka-streams "2.1.0"]   ;; https://search.maven.org/artifact/org.apache.kafka/kafka-streams/2.1.0/jar
                 [org.apache.kafka/kafka-streams-test-utils "2.1.0"] ;; https://mvnrepository.com/artifact/org.apache.kafka/kafka-streams-test-utils
                 [org.apache.kafka/kafka-clients "2.1.0"]   ;; https://mvnrepository.com/artifact/org.apache.kafka/kafka-clients
                 [org.apache.kafka/kafka-clients "1.1.0" :classifier "test"]
                 [org.clojure/core.async "0.4.490"]         ;; https://github.com/clojure/core.async
                 [org.clojure/clojure "1.9.0"]
                 [org.clojure/tools.logging "0.5.0-alpha"]  ;; https://search.maven.org/artifact/org.clojure/tools.logging/0.5.0-alpha/jar
                 [org.slf4j/slf4j-log4j12 "1.7.1"]          ;; 
                 ]

  :monkeypatch-clojure-test false
  :profiles {:cloverage {:plugins   [[lein-cloverage "1.0.13"]]
                         :cloverage {:test-ns-regex [#"^((?!(e2e|integration_tests)).)*$"]}}
             :dev       {:dependencies [[org.clojure/test.check "0.9.0"]]}
             :uberjar   {:aot :all}}
    :repl-options {:init (do
                           (require '[enki.core :refer :all])
                           (require '[clojure.repl :refer :all])
                           (require '[clojure.pprint :as pp])
                           (require '[clojure.spec.alpha      :as s])
                           (require '[clojure.spec.gen.alpha :as gen])
                           (require '[clojure.spec.test.alpha :as stest])
                           (require '[expound.alpha :as e])
                           (require '[clojure.java.javadoc :as jdoc])
                           (require '[clojure.inspector :as insp])
                           (require '[clojure.reflect :as reflect]))}
  :resource-paths ["resources"]
  :target-path "target/%s"
  )
