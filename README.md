# Otarta

[![pipeline status](https://gitlab.com/eval/otarta/badges/master/pipeline.svg)](https://gitlab.com/eval/otarta/commits/master)
[![Clojars Project](https://img.shields.io/clojars/v/eval/otarta.svg)](https://clojars.org/eval/otarta)


An MQTT-library for ClojureScript.

_NOTE: this is pre-alpha software with an API that will change (see the [CHANGELOG](./CHANGELOG.md) for breaking changes)_

## Installation

Leiningen:
```clojure
[eval/otarta "0.3.0"]
```

Deps:
```clojure
eval/otarta {:mvn/version "0.3.0"}
```

## Examples

* [CI-Dashboard](https://eval.gitlab.io/ci-dashboard/) (source: https://gitlab.com/eval/ci-dashboard)

## Usage

The following code assumes:
- being in a browser (ie `js/WebSockets` exists. For Node.js [see below](README.md#nodejs).)
- a websocket-enabled MQTT-broker on `localhost:9001` (eg via `docker run --rm -ti -p 9001:9001 toke/mosquitto`)

```clojure
(ns example.core
  (:require-macros [cljs.core.async.macros :refer [go go-loop]])
  (:require [cljs.core.async :as a :refer [<!]]
            [otarta.core :as mqtt]))

(defonce client (mqtt/client "ws://localhost:9001/mqtt#weather-sensor"))

(defn subscription-handler [ch]
  (go-loop []
    (when-let [m (<! ch)]
      ;; example m: {:topic "temperature/current" :payload "12.1" :retain? false :qos 0}
      (prn "Received:" m)
      (recur))))

(go
  (let [[err {sub-ch :ch}] (<! (mqtt/subscribe client "temperature/#"))]
    (if err
      (println "Failed to subscribe:" err)
      (do
        (println "Subscribed!")
        (subscription-handler sub-ch))))

  (mqtt/publish client "temperature/current" "12.1"))
```

### client

### broker-url

The first argument (the `broker-url`) should be of the form `ws(s):://(user:pass@)host.org:1234/path(#some/root/topic)`.

The fragment contains the `root-topic` and indicates the topic relative to which the client publishes and subscribes. This allows for pointing the client to a specific subtree of the broker (eg where it has read/write-permissions, or where it makes sense given the stage: `ws://some-broker/mqtt#acceptance/sensor1`).

When you write a client that receives its broker-url from outside (ie as an environment variable), it might lack a root-topic. In order to prevent unwanted effects in that case (eg the client subscribing to "#" essentially subscribing to the *root of the broker*) you can provide a `default-root-topic`:
```clojure
(mqtt/client config.broker-url {:default-root-topic "weather-sensor"})
```
The client will then treat the broker-url `ws://localhost:9001/mqtt` like `ws://localhost:9001/mqtt#weather-sensor`.
When `config.broker-url` does contain a `root-topic`, the `default-root-topic` is ignored (but gives a nice hint as to what the `root-topic` could look like, eg `acceptance/weather-sensor`).


### messages

Messages have the following shape:

```clojure
{:topic "temperature/current" ;; topic relative to `root-topic`
 :payload "12.1"    ;; formatted payload
 :retain? false     ;; whether this message was from the broker's store or 'real-time' from publisher
 :qos 0}            ;; quality of service (0: at most once, 1: at least once, 2: exactly once) 
```

#### retain?

NOTE: `retain?` is not so much a property of the sent message, but tells you *when* you received it: typically you receive messages with `{:retain? true}` directly after subscribing. But when you're subscribed and a message is published with the retain-flag set, the message you'll received has `{:retain? false}`. This as you received it 'first hand' from the publisher, not from the broker's store.  

### formats

When publishing or subscribing you can specify a format. Available formats are: `string` (default), `raw`, `json`, `edn` and `transit`:
```clojure
(go
  (let [[err {sub-ch :ch}] (<! (mqtt/subscribe client "temperature/#" {:format :transit}))]
    (if err
      (println "Failed to subscribe:" err)
      (do
        (prn (<! sub-ch))))) ;; prints: {:created-at #inst "2018-09-27T13:13:21.932-00:00", :value 12.1}

  (mqtt/publish client "temperature/current" {:created-at (js/Date.) :value 12.1} {:format :transit}))
```

Incoming messages with a payload that is not readable, won't appear on the subscription-channel.  
Similarly, when formatting fails when publishing, you'll receive an error:

```clojure
(let [[err _] (<! (mqtt/publish client "foo" #"not transit!" {:format :transit}))]
  (when err
    (println err)))
```

You can provide your own format:
```clojure
(ns example.core
  (:require [otarta.format :as mqtt-fmt]))

(defn extract-temperature []
  ...)

;; this format piggybacks on the string-format
;; after which extract-temperature will get the relevant data.
;; Otarta will catch any exceptions that occur when reading/writing.
(def custom-format
  (reify mqtt-fmt/PayloadFormat
    (-read [_fmt buff]
      (->> buff (mqtt-fmt/-read mqtt-fmt/string) extract-temperature))
    (-write [_fmt v]
      (->> v (mqtt-fmt/-write mqtt-fmt/string)))))
```

### Node.js

You should provide a W3C compatible websocket when running via Node.js.  
I've had good experience with [this websocket-library (>= v1.0.28)](https://www.npmjs.com/package/websocket).

With the library included in your project (see https://clojurescript.org/guides/webpack for details), the following will initialize `js/WebSocket`:

```clojure
(ns example.core
  (:require [websocket]))

(set! js/WebSocket (.-w3cwebsocket websocket))
```

## Limitations

- only QoS 0
- only clean-session
- no reconnect built-in
- untested for large payloads (ie more than a couple of KB)

## Development

### Testing

Via [cljs-test-runner](https://github.com/Olical/cljs-test-runner/):

```bash
# once
$ clojure -Atest

# watching
$ clojure -Atest-watch

# specific tests
(deftest ^{:focus true} only-this-test ...)
$ clojure -Atest-watch -i :focus

# more options:
$ clojure -Atest-watch --help
```

### Figwheel

```bash
# start figwheel
$ make figwheel

# wait till compiled and then from other shell:
$ node target/app.js

# then from emacs:
# M-x cider-connect with host: localhost and port: 7890
# from repl:
user> (figwheel/cljs-repl)
;; prompt changes to:
cljs.user>
;; to quickly see what otarta can do
;; you could evaluate the otarta.main namespace
;; and eval the comment-section at the bottom line by line.
```

See [CIDER docs](https://cider.readthedocs.io/en/latest/interactive_programming/) what you can do.


## Release

### Install locally

- (ensure no CLJ_CONFIG and MAVEN_OPTS env variables are set - this to target ~/.m2)
- ensure dependencies in pom.xml up to date
  - clj -Spom
- bump version in pom.xml
- make mvn-install
- testdrive locally

### Deploy to Clojars

- commit pom.xml to master
- push to CI


## License

Copyright (c) 2018 Gert Goet, ThinkCreate
Copyright (c) 2018 Alliander N.V. 
See [LICENSE](./LICENSE).

For licenses of third-party software that this software uses, see [LICENSE-3RD-PARTY](./LICENSE-3RD-PARTY).
