# enki

A simple kafka streams processor library

## Installation
Using Leiningen / Clojars:

[![Clojars Project](https://img.shields.io/clojars/v/enki.svg)](https://clojars.org/enki)

## Usage
Include this library in your application and call `(start->streams yourFn configuration)`,
where yourFn is a function with a arity 3 in the form of:

`(defn fn [kv-pair-map get-fn put-fn] ...)`

Spec'd similar to:

```
(s/def ::producer-record #(instance? ProducerRecord %))
(s/def ::kv-pair (s/keys :req-un [::key ::producer-record]))

(s/fdef get-fn 
        :args (s/cat :key string?)
        :ret ::producer-record)

(s/fdef put-fn
  :args (s/cat :key string? :value any?))

(s/fdef xform-fn
        :args (s/cat :kv-pair ::kv-pair 
                     :get-fn ::get-fn 
                     :put-fn ::put-function))
```

Example (from tests): 

```
(defn xform-fn
  [kv-pair get-fn put-fn]
  {:pre [(and (= KEY (:key kv-pair)) (= VALUE (:producer-record kv-pair)))]}
  (log/info "[xform-fn] called by processor with: " kv-pair)
  (let [current-stored-value (get-fn XFORM_KEY)]
    (if (nil? current-stored-value)
      (do
        ;; do something with the new key/record pair
        (log/info "[xform-fn] no existing value found")
        (put-fn XFORM_KEY XFORM_VALUE))
      (do
        ;; do something based on the fact the key exists in our store.
        (log/info "[xform-fn] found an existing value in the state store")
        (put-fn XFORM_KEY FOUND_VALUE)))))

```
...which will be called for every message. Where the kv-pair-map has the form {:key key :record value} for whatever keys and values you are processing from the input topic.

The expected configuration spec has the form of:

```
(def  stream-config { :applicationid           "my-cool-microservice"
                      :bootstrap-servers       "bootstrap:9092"
                      :input-topic             "db.input.topic"
                      :output-topic            "streaming.output.topic"
                      :punctuate.interval.ms   5000 ;; milliseconds
                      :store-name              "theStore"})
```

To stop streams processing call `(close->streams)`. 

### Bugs


## License

Copyright © 2019 Christopher Lester

This program and the accompanying materials are made available under the
terms of the Eclipse Public License 2.0 which is available at
http://www.eclipse.org/legal/epl-2.0.

This Source Code may also be made available under the following Secondary
Licenses when the conditions for such availability set forth in the Eclipse
Public License, v. 2.0 are satisfied: GNU General Public License as published by
the Free Software Foundation, either version 2 of the License, or (at your
option) any later version, with the GNU Classpath Exception which is available
at https://www.gnu.org/software/classpath/license.html.
