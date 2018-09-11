(ns haslett.client
  "A namespace for opening WebSockets in ClojureScript."
  (:require [cljs.core.async :as a :refer [<! >!]]
            [haslett.format :as fmt]
            [huon.log :refer [debug info warn error]]
            [otarta.util :refer [empty-chan]])
  (:require-macros [cljs.core.async.macros :refer [go-loop]]))

(defn connect
  "Create a WebSocket to the specified URL, and returns a 'stream' map of four
  keys:

    :socket       - contains the WebSocket object
    :close-status - a promise channel that contains the final close status
    :source       - a core.async channel to read from
    :sink         - a core.async channel to write to

  Takes the following options:

    :format      - a formatter from haslett.format
    :source      - a custom channel to use as the source
    :sink        - a custom channel to use as the sink
    :protocols   - passed to the WebSocket, a vector of protocol strings
    :binary-type - passed to the WebSocket, may be :blob or :arraybuffer"
  ([url]
   (connect url {}))
  ([url options]
   (info ::connect {:url url :options options})
   (let [protocols (into-array (:protocols options []))
         socket    (js/WebSocket. url protocols)
         source    (:source options (a/chan))
         sink      (:sink   options (a/chan))
         format    (:format options fmt/identity)
         status    (a/promise-chan)
         return    (a/promise-chan)
         stream    {:socket socket, :source source, :sink sink, :close-status status}]
     (set! (.-binaryType socket) (name (:binary-type options :arraybuffer)))
     (set! (.-onopen socket)     (fn [_]
                                   (info ::onopen)
                                   (a/put! return stream)))
     (set! (.-onmessage socket)  (fn [e]
                                   (info ::onmessage)
                                   (a/put! source (fmt/read format (.-data e)))))
     (set! (.-onerror socket)    (fn [e]
                                   (info ::onerror {:error e})))
     (set! (.-onclose socket)    (fn [e]
                                   (let [stat {:reason (.-reason e) :code (.-code e)}]
                                     (info ::onclose stat)
                                     (a/put! return stream)
                                     (a/put! status stat)
                                     (empty-chan source)
                                     (empty-chan sink))))
     (go-loop []
       (when-let [msg (<! sink)]
         (debug ::before-sending-msg)
         (.send socket (fmt/write format msg))
         (debug ::after-sending-msg)
         (recur)))
     return)))

(defn close
  "Close a stream opened by connect."
  [stream]
  (info ::close)
  (.close (:socket stream))
  (:close-status stream))
