(ns otarta.core
  (:require-macros
   [cljs.core.async.macros :refer [go go-loop]])
  (:require
   [cljs.core.async :as async :refer [<! >! take! put! chan]]
   [clojure.string :as string]
   [haslett.client :as stream]
   [haslett.format :as ws-fmt]
   [huon.log :refer [debug info warn error]]
   [lambdaisland.uri :as uri]
   [otarta.packet :as packet]
   [otarta.util :as util :refer-macros [<err->]]))


(def mqtt-format
  "Read and write mqtt-packets"
  (reify ws-fmt/Format
    (read  [_ s]
      (info :mqtt-format :read)
      (let [pkt (packet/decode s)]
        (info :mqtt-format :packet pkt)
        pkt))
    (write [_ v]
      (info :mqtt-format :write v)
      (js/Uint8Array. (.-buffer (packet/encode v))))))


(defn parse-broker-url [url]
  (let [parsed     (uri/parse url)
        ws-url-map (select-keys parsed [:scheme :host :port :path])
        ws-url     (str (uri/map->URI. ws-url-map))]
    (cond-> {:ws-url ws-url}
      (:user parsed)
      (assoc :username (:user parsed))

      (:password parsed)
      (assoc :password (:password parsed)))))


(defn publish-pkt->msg [{{:keys [retain? dup? qos]} :first-byte
                         {topic :topic}             :remaining-bytes
                         {payload :payload}         :extra}]
  {:dup?      dup?
   :payload   payload
   :qos       qos
   :retained? retain?
   :topic     topic})


(defn topic-filter-matches-topic? [topic-filter topic]
  (let [re        (re-pattern (str "^"
                                   (-> topic-filter
                                       (string/replace #"\$" "\\$")
                                       (string/replace #"\+" "[^/]*")
                                       (string/replace #"/?\#$" "/?.*"))
                                   "$"))
        dollar-re (re-pattern (str "^" (some-> (re-find #"^\$[^/]*" topic)
                                               (string/replace #"\$" "\\$"))))]
    ;; a sub-filter '#' should not match a broker-internal topic like '$SYS/foo'
    ;; you need a more explicit filter, eg '$SYS/+'
    (or (nil? topic)
        (and (re-find dollar-re topic-filter)
             (re-find re topic)))))



(defn send-and-await-response
  ""
  ([pkt sink source]
   (send-and-await-response pkt sink source {}))
  ([pkt sink source {:keys [max-wait] :or {max-wait 1000} :as _opts}]
   (info ::send-and-await-response)
   (go
     (>! sink pkt)
     (let [[v ch] (async/alts! [(async/timeout max-wait) source])]
       (async/close! source)
       (if v
         [nil v]
         [:response-timeout {:packet pkt :timeout max-wait}])))))


(defn tap-ch [from-ch new-ch]
  (async/tap (util/async-mult from-ch) new-ch))


(defn packet-filter
  "Yield xf that filters a packet against matchers.

  Matchers come in a map, like:
  - {[:a :b] 2} match {:a {:b 2}}
  - {[:a 0 :b] 2} match {:a [{:b 2}]}
  - {[:a] 1 [:b] 3} does *not* match  {:a 1 :b 2}
  - {[:a] nil} does *not* match {} (cause there's no :a)

  You can provide a predicate:
  - {[:a :b] (partial < 3)} matches {:a {:b 2}}
"
  [matchers]
  (let [matches-all? (fn [matchers pkt]
                       (every? (fn [[att match]]
                                 (let [match-fn (if (fn? match) match (partial = match))
                                       contains-nested-key? (not= :not-found
                                                                  (get-in pkt att :not-found))]
                                   (and contains-nested-key?
                                        (match-fn (get-in pkt att))))) matchers))]
    (filter (partial matches-all? matchers))))


(defn capture-first-packet
  "Yield promise-chan with xf that filters packet to capture from ch.
  Example matchers:
  - {[:a :b] 2} matches {:a {:b 2}}

  You can provide a predicate:
  - {[:a :b] (partial < 3)} matches {:a {:b 2}}
"
  [ch xf]
  (tap-ch ch (async/promise-chan xf)))


(defn capture-all-packets
  "Yield chan with xf that filters packets to capture from ch.
  Example matchers:
  - {[:a :b] 2} matches a packet like {:a {:b 2}}"
  [ch xf]
  (tap-ch ch (async/chan (async/sliding-buffer 1) xf)))


(defn stream-connected? [stream]
  (-> stream :close-status (async/poll!) (nil?)))


(defn stream-connect [{{url :ws-url} :config :as mqtt-conn}]
  ;; yields tuple with either stream or error
  (info :stream-connect :url url)
  (go
    (let [stream (<! (stream/connect url {:protocols ["mqtt"]
                                          :format    mqtt-format}))]
      (if (stream-connected? stream)
        [nil (and (update mqtt-conn :stream reset! stream) mqtt-conn)]
        [:stream-connect-failed (<! (:close-status stream))]))))


(defn stream-disconnect [mqtt-conn]
  (info :stream-disconnect)
  (go
    (let [close-status (<! (stream/close (deref (:stream mqtt-conn))))]
      (info :stream-disconnect :close-status close-status))
    [nil mqtt-conn]))


(defn mqtt-connect [{:keys [stream config] :as mqtt-conn}]
  (info :mqtt-connect :mqtt-conn mqtt-conn)
  (go
    (let [{:keys [sink source]} @stream
          next-connack          (capture-first-packet
                                 source (packet-filter {[:first-byte :type] :connack}))
          connect-pkt           (packet/connect config)
          [err {{return-code :return-code} :remaining-bytes}]
          (<! (send-and-await-response connect-pkt sink next-connack))]
      (cond
        err                       [err nil]
        (not (zero? return-code)) [:broker-refused {:return-code return-code}]
        :else                     [nil mqtt-conn]))))


(defn mqtt-disconnect [{stream :stream :as mqtt-conn}]
  (info :mqtt-disconnect)
  (go
    (>! (:sink @stream) (packet/disconnect))
    [nil mqtt-conn]))


(defn start-pinger
  "Keeps mqtt-connection alive by sending pingreq's every `keep-alive`
  seconds."
  [{stream                   :stream
    {keep-alive :keep-alive} :config :as mqtt-conn}]
  (go
    (let [{:keys [sink source]} @stream
          control-ch            (async/promise-chan)]
      (info :start-pinger)
      (go-loop [n 0]
        (info :start-pinger :sending-ping n)
        (let [next-pingresp (capture-first-packet source
                                                 (packet-filter {[:first-byte :type] :pingresp}))
              [err resp]    (<! (send-and-await-response (packet/pingreq) sink next-pingresp))]
          (info :start-pinger :pong-received? (not (boolean err)))
          (if err
            (error err)
            (do (info :start-pinger :wait-keepalive-or-interrupt)
                (let [[v ch] (async/alts! [control-ch (async/timeout (* 1000 keep-alive))])]
                  (if v
                    (info :start-pinger :stopping)
                    (recur (inc n))))))))
      (update mqtt-conn :pinger reset! control-ch)
      [nil mqtt-conn])))


(defn stop-pinger [{pinger :pinger :as mqtt-conn}]
  (info :stop-pinger :pinger (pr-str @pinger))
  (go
    (and @pinger (>! @pinger :stop))
    [nil mqtt-conn]))


(defn client [{:keys [broker-url] :as opts}]
  (let [default-opts {:keep-alive 60 :client-id (str "otarta-" (random-uuid))}
        config (-> broker-url
                   (parse-broker-url)
                   (merge default-opts)
                   (merge (select-keys opts [:client-id :keep-alive])))]
    {:config config :stream (atom nil) :pinger (atom nil)}))


(defn connect
  "Connect with broker. Idempotent.
  No need to call this as on publishing/subscribing this is called."
  [client]
  (info :connect)
  ;; naive way for now
  (let [connected? (-> client :stream deref)]
    (info :connect :needed? (not connected?))
    (if connected?
      (go [nil client])
      (<err-> client
              (stream-connect)
              (mqtt-connect)
              (start-pinger)))))


(defn publish* [client topic msg]
  (info :publish :client client :topic topic :msg msg)
  (go
    (let [pkt (packet/publish {:topic topic :payload msg})]
      (>! (-> client :stream deref :sink) pkt)
      [nil {}])))


(defn publish
  "Yields async-channel that returns [err result] when msg is published.

  Currently `err` is always nil as no check is done whether the
  underlying connection is active, nor whether the broker received
  the message (ie qos 0)."
  [client topic msg]
  (<err-> client
          connect
          (publish* topic msg)))


(defn subscribe*
  [{stream :stream :as client} topic-filter]
  (info :subscribe :topic-filter topic-filter)
  (go
    (let [{:keys [sink source]} @stream

          pkt-filter  (packet-filter {[:remaining-bytes :topic]
                                      (partial topic-filter-matches-topic? topic-filter)})
          result-chan (capture-all-packets source (comp pkt-filter (map publish-pkt->msg)))
          sub-pkt     (packet/subscribe {:topic-filter      topic-filter
                                         :packet-identifier 1})
          next-suback (capture-first-packet source
                                            (packet-filter {[:first-byte :type] :suback}))
          [err {{{:keys [max-qos failure?] :as sub-result} :payload} :remaining-bytes}]
          (<! (send-and-await-response sub-pkt sink next-suback))]
      (if err
        (error :subscribe err)
        (info :subscribe :sub-result sub-result))
      (cond
        err      [err nil]
        failure? [:broker-refused-sub nil]
        :else    [nil {:chan result-chan}]))))


(defn subscribe
  "Yields async-channel that returns [err result] when subscribing was successful.

  `err` is a keyword indicating what went wrong, or nil when all is
  fine.  
  `result` is a map like {:chan channel}
"
  [client topic-filter]
  (<err-> client
          connect
          (subscribe* topic-filter)))


(defn disconnect
  ""
  [client]
  (info :disconnect :client client)
  (<err-> client
          stop-pinger
          mqtt-disconnect
          stream-disconnect))
