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
   [otarta.payload-format :as payload-fmt :refer [PayloadFormat]]
   [otarta.packet :as packet]
   [otarta.util :as util :refer-macros [<err-> err-> err->>]]))


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


(defn- app-topic->broker-topic [{{root-topic :root-topic} :config} app-topic]
  (if root-topic
    (str root-topic "/" app-topic)
    app-topic))


(defn- broker-topic->app-topic [{{root-topic :root-topic} :config} broker-topic]
  (if root-topic
    (string/replace broker-topic (re-pattern (str "^" root-topic "/")) "")
    broker-topic))


(defn- next-packet-identifier [{pktid-atom :packet-identifier :as _client}]
  (swap! pktid-atom inc))


(defn parse-broker-url
  ([url] (parse-broker-url url {}))
  ([url {:keys [default-root-topic]}]
   (let [parsed     (uri/parse url)
         ws-url-map (select-keys parsed [:scheme :host :port :path])
         ws-url     (str (uri/map->URI. ws-url-map))]
     (cond-> {:ws-url ws-url}
       (:user parsed)
       (assoc :username (:user parsed))

       (:password parsed)
       (assoc :password (:password parsed))

       default-root-topic
       (assoc :root-topic default-root-topic)

       (-> parsed :fragment (string/blank?) not)
       (assoc :root-topic (:fragment parsed))))))


(defn topic-filter-matches-topic? [topic-filter topic]
  (let [re        (re-pattern (str "^"
                                   (-> topic-filter
                                       (string/replace #"\$" "\\$")
                                       (string/replace #"\+" "[^/]*")
                                       (string/replace #"/?\#$" "/?.*"))
                                   "$"))
        dollar-re (re-pattern (str "^" (some-> (re-find #"^\$[^/]*" topic)
                                               (string/replace #"\$" "\\$"))))]
    ;; [MQTT-4.7.2-1] a sub-filter '#' should not match a broker-internal topic like '$SYS/foo'
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
  - {[:a :b] odd?} matches {:a {:b 3}}
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
  "Typically used with xf via `packet-filter`."
  [ch xf]
  (tap-ch ch (async/promise-chan xf)))


(defn capture-all-packets
  "Typically used with xf via `packet-filter`."
  [ch xf]
  (tap-ch ch (async/chan (async/sliding-buffer 1024) xf)))


(defn stream-connected? [stream]
  (-> stream :close-status (async/poll!) (nil?)))


(defn stream-connect [{{url :ws-url} :config :as client}]
  ;; yields tuple with either stream or error
  (info :stream-connect :url url)
  (go
    (let [stream (<! (stream/connect url {:protocols ["mqtt"]
                                          :format    mqtt-format}))]
      (if (stream-connected? stream)
        [nil (and (update client :stream reset! stream) client)]
        [:stream-connect-failed (<! (:close-status stream))]))))


(defn stream-disconnect [client]
  (info :stream-disconnect)
  (go
    (let [close-status (<! (stream/close (deref (:stream client))))]
      (info :stream-disconnect :close-status close-status))
    [nil client]))


(defn mqtt-connect [{:keys [stream config] :as client}]
  (info :mqtt-connect :client client)
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
        :else                     [nil client]))))


(defn mqtt-disconnect [{stream :stream :as client}]
  (info :mqtt-disconnect)
  (go
    (>! (:sink @stream) (packet/disconnect))
    [nil client]))


(defn start-pinger
  "Keeps mqtt-connection alive by sending pingreq's every `keep-alive`
  seconds."
  [{stream                   :stream
    {keep-alive :keep-alive} :config :as client}]
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
      (update client :pinger reset! control-ch)
      [nil client])))


(defn stop-pinger [{pinger :pinger :as client}]
  (info :stop-pinger :pinger (pr-str @pinger))
  (go
    (and @pinger (>! @pinger :stop))
    [nil client]))


(defn client
  "Accepts the following parameters:
  - broker-url (required) - url of the form ws(s)://(user:password@)host:12345/path(#some/root-topic).
The root-topic is prepended to all subscribes/publishes and ensures that the client only needs to care about topics that are relevant for the application, e.g. \"temperature/current\" (instead of \"staging/sensor0/temperature/current\"). You can provide a default-topic-root.
  - default-root-topic - root-topic used when broker-url does not contain one. This e.g. allows the client-logic to subscribe to \"#\" knowing that it won't subscribe to the root of a broker.
  - keep-alive (default 60) - maximum seconds between pings.
  - client-id (default \"otarta-<random-uuid>\") - Client Identifier used to connect to broker.
"
  [{:keys [broker-url default-root-topic] :as opts}]
  {:pre [broker-url]}
  (let [default-opts {:keep-alive 60 :client-id (str "otarta-" (random-uuid))}
        config (-> broker-url
                   (parse-broker-url {:default-root-topic default-root-topic})
                   (merge default-opts)
                   (merge (select-keys opts [:client-id :keep-alive])))]
    {:config config :stream (atom nil) :pinger (atom nil) :packet-identifier (atom 0)}))


(defn connect
  "Connect with broker. Idempotent.
  Typically there's no need to call this as it's called from `publish` and `subscribe`."
  [client]
  (info :connect)
  ;; naive way for now
  (let [stream-present? (-> client :stream deref)]
    (debug :connect :stream-connect-required? (not stream-present?))
    (if stream-present?
      (go [nil client])
      (<err-> client
              (stream-connect)
              (mqtt-connect)
              (start-pinger)))))



(defn- publish* [{stream :stream :as client} app-topic msg {:keys [format] :or {format :string}}]
  (info :publish :client client :app-topic app-topic :msg msg :format format)
  (go
    (let [{sink :sink}        @stream
          empty-msg?          (or (nil? msg) (= "" msg))
          to-publish          {:topic   (app-topic->broker-topic client app-topic)
                               :payload msg
                               :empty?  empty-msg?}
          [fmt-err formatted] (payload-fmt/write format to-publish)]
      (if fmt-err
        [fmt-err nil]
        (do (>! sink (packet/publish formatted))
            [nil {}])))))


(defn publish
  "Yields async-channel that returns [err result] when msg is published.

  Currently `err` is always nil as no check is done whether the
  underlying connection is active, nor whether the broker received
  the message (ie qos 0)."
  ([client topic msg] (publish client topic msg {}))
  ([client topic msg opts]
   (<err-> client
           connect
           (publish* topic msg opts))))


(defn- subscription-chan [{stream :stream :as client} topic-filter payload-formatter]
  (let [{source :source} @stream
        pkts-for-topic-filter (packet-filter
                               {[:remaining-bytes :topic]
                                (partial topic-filter-matches-topic? topic-filter)})
        pkt->msg              (fn [{{:keys [retain? dup? qos]} :first-byte
                                    {topic :topic}             :remaining-bytes
                                    {payload :payload}         :extra}]
                                {:dup?      dup?
                                 :empty?    (-> payload .-byteLength zero?)
                                 :payload   payload
                                 :qos       qos
                                 :retained? retain?
                                 :topic     (broker-topic->app-topic client topic)})
        subscription-xf       (comp pkts-for-topic-filter
                                    (map pkt->msg)
                                    (map (comp second payload-formatter))
                                    (remove nil?))]
    [nil (capture-all-packets source subscription-xf)]))


(defn- subscribe*
  [{stream :stream :as client} app-topic-filter {:keys [format] :or {format :string}}]
  (info :subscribe :app-topic-filter app-topic-filter)
  (go
    (let [{:keys [sink source]} @stream
          topic-filter          (app-topic->broker-topic client app-topic-filter)
          [sub-err sub-ch]      (err->> format
                                        (payload-fmt/read)
                                        (subscription-chan client topic-filter))
          pktid                 (next-packet-identifier client)
          sub-pkt               (packet/subscribe {:topic-filter      topic-filter
                                                   :packet-identifier pktid})
          next-suback           (capture-first-packet source
                                                      (packet-filter {[:first-byte :type] :suback
                                                                      [:remaining-bytes :packet-identifier] pktid}))
          [mqtt-err {{{:keys [_max-qos failure?] :as _sub-result} :payload} :remaining-bytes}]
          (<! (send-and-await-response sub-pkt sink next-suback))]
      (cond
        sub-err  [sub-err nil]
        mqtt-err [mqtt-err nil]
        failure? [:broker-refused-sub nil]
        :else    [nil {:ch sub-ch}]))))


(defn subscribe
  "Yields async-channel that returns [err result] when subscribing was successful.

  `err` is a keyword indicating what went wrong, or nil when all is
  fine.  
  `result` is a map like {:chan channel}
"
  ([client topic-filter] (subscribe client topic-filter {}))
  ([client topic-filter opts]
   (<err-> client
           connect
           (subscribe* topic-filter opts))))


(defn disconnect
  ""
  [client]
  (info :disconnect :client client)
  (<err-> client
          stop-pinger
          mqtt-disconnect
          stream-disconnect))
