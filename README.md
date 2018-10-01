# Otarta

[![pipeline status](https://gitlab.com/eval/otarta/badges/master/pipeline.svg)](https://gitlab.com/eval/otarta/commits/master)
[![Clojars Project](https://img.shields.io/clojars/v/eval/otarta.svg)](https://clojars.org/eval/otarta)


An MQTT-library for ClojureScript.

_NOTE: this is pre-alpha software with an API that will change_

## Installation

Leiningen:
```clojure
[eval/otarta "0.3.0"]
```

Deps:
```clojure
eval/otarta {:mvn/version "0.3.0"}
```

## Usage

The following code assumes:
- being in a browser (ie `js/WebSockets` exists. For Node.js [see below](README.md#nodejs).)
- a websocket-enabled MQTT-broker on `localhost:9001` (eg via `docker run --rm -ti -p 9001:9001 toke/mosquitto`)

```clojure
(ns example.core
  (:require-macros [cljs.core.async.macros :refer [go go-loop]])
  (:require [cljs.core.async :as a :refer [<!]]
            [otarta.core :as mqtt]))

(defonce client (mqtt/client "ws://localhost:9001/mqtt"))

(defn subscription-handler [ch]
  (go-loop []
    (when-let [m (<! ch)]
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

The first argument (the `broker-url`) should be of the form `ws(s):://(user:pass@)host.org:1234/path(#some/root/topic)`.

The fragment contains the `root-topic` and indicates the topic relative to which the client publishes and subscribes. This allows for easy configuration of your client (e.g. "ws://some-broker/mqtt#staging/sensor1").

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

## Development

### Testing

```bash
# once
$ clojure -Atest

# watching
$ clojure -Atest-watch
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

Copyright (c) 2018 Alliander N.V. See [LICENSE](./LICENSE).

For licenses of third-party software that this software uses, see [LICENSE-3RD-PARTY](./LICENSE-3RD-PARTY).
