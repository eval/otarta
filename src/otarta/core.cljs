(ns otarta.core
  (:require-macros
   [cljs.core.async.macros :refer [go go-loop]])
  (:require
   [cljs.core.async :as async :refer [<! >! take! put! chan]]
   [cljs.core.async.impl.dispatch :as async-dispatch]
   [clojure.string :as string]
   [haslett.client :as stream]
   [haslett.format :as ws-fmt]
   [huon.log :refer [debug info warn error]]
   [lambdaisland.uri :as uri]
   [otarta.format :as fmt :refer [PayloadFormat]]
   [otarta.packet :as packet]
   [otarta.util :as util :refer-macros [<err-> err-> err->>]]))


(defn timeout-channels-wont-prevent-nodejs-exit!
  "By default any timeout will keep the Node.js event loop active.  
This means for example that timeout-channels (involved in pinging every keep-alive seconds) will prevent the cli from exiting, even though the connection to the broker is terminated.

This function ensures that if there's no other activity keeping the event loop running besides timeout-channels, the process may exit."
  []
  (set! async-dispatch/queue-delay
        (fn [f delay]
          (.unref (js/setTimeout f delay)))))


(extend-type js/Uint8Array
  ICounted
  (-count [uia]
    (.-length uia)))


(defn- empty-payload?
  "Examples:
  ;; when receiving:
  (empty-payload? (js/Uint8Array.) ;; => true

  ;; when sending:
  (empty-payload? nil) ;; => true
  (empty-payload? \"\") ;; => true
  (empty-payload? \"   \") ;; => false"
  [pl]
  (zero? (count pl)))


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


(defn stream-disconnect [{:keys [stream] :as client}]
  (info :stream-disconnect)
  (go
    (when @stream
      (let [close-status (<! (stream/close @stream))]
        (info :stream-disconnect :close-status close-status)
        (reset! stream nil)))
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
    (when @stream
      (>! (:sink @stream) (packet/disconnect)))
    [nil client]))


(defn start-pinger
  "Keeps mqtt-connection alive by sending pingreq's every `keep-alive`
  seconds."
  [{stream                   :stream
    {keep-alive :keep-alive} :config :as client}
   {:keys [delay] :or {delay 0}}]
  (go
    (let [{:keys [sink source]} @stream
          control-ch            (async/promise-chan)
          delay-ms              (* 1000 delay)]
      (info :start-pinger {:delay-ms delay-ms})
      (go-loop [n 0]
        (when (zero? n)
          (<! (async/timeout delay-ms)))
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
    (when @pinger
      (>! @pinger :stop))
    [nil client]))


(defn- client-id
  "Yields `client-id` if provided. Otherwise generated of `client-id-prefix` (default \"otarta\") and random characters. The generated id is compliant with [MQTT-3.1.3-5] (ie matches [0-9a-zA-Z]{1-23})."
  [{:keys [client-id client-id-prefix]}]
  (if (some? client-id)
    client-id
    (let [prefix (or client-id-prefix "otarta")]
      (-> prefix
          (str (random-uuid))
          (string/replace #"-" "")
          (subs 0 23)))))


(defn client
  "Initialize a client for publish/subscribe.

  Arguments:
  - broker-url - url of the form ws(s)://(user:password@)host:12345/path(#some/root-topic).
  The root-topic is prepended to all subscribes/publishes and ensures that the client only needs to care about topics that are relevant for the application, e.g. \"temperature/current\" (instead of \"staging/sensor0/temperature/current\"). You can provide a default-root-topic.

  Accepts the following options:
  - default-root-topic - root-topic used when broker-url does not contain one. This e.g. allows the client-logic to subscribe to \"#\" knowing that it won't subscribe to the root of a broker.
  - keep-alive (default 60) - maximum seconds between pings.
  - client-id (default \"<client-id-prefix><random>\" (max. 23 characters as per MQTT-spec)) - Client Identifier used to connect to broker. This should be unique accross all connected clients.
WARNING: Connecting with a client-id that's already in use results in the existing client being disconnected.
  - client-id-prefix (default \"otarta\") - convenient to see in the logs of your broker where the client originates from without running the risk of clashing with existing client-id's.
"
  ([broker-url] (client broker-url {}))
  ([broker-url {:keys [default-root-topic] :as opts}]
   (let [default-opts {:keep-alive 60}
         config       (-> broker-url
                          (parse-broker-url {:default-root-topic default-root-topic})
                          (assoc :client-id (client-id opts))
                          (merge default-opts)
                          (merge (select-keys opts [:keep-alive])))]
     {:config            config
      :stream            (atom nil)
      :pinger            (atom nil)
      :packet-identifier (atom 0)})))


(defn connect
  "Connect with broker. Idempotent.
  Typically there's no need to call this as it's called from `publish` and `subscribe`."
  [{config :config :as client}]
  (info :connect)
  ;; naive way for now
  (let [stream-present? (-> client :stream deref)]
    (debug :connect :stream-connect-required? (not stream-present?))
    (if stream-present?
      (go [nil client])
      (<err-> client
              (stream-connect)
              (mqtt-connect)
              (start-pinger {:delay (:keep-alive config)})))))



(defn- publish* [{stream :stream :as client} app-topic payload {:keys [format retain?] :or {format :string retain? false}}]
  (info :publish :client client :app-topic app-topic :payload payload :format format)
  (go
    (let [{sink :sink}        @stream
          to-publish          {:topic   (app-topic->broker-topic client app-topic)
                               :retain? retain?
                               :payload payload
                               :empty?  (empty-payload? payload)}
          [fmt-err formatted] (fmt/write format to-publish)]
      (if fmt-err
        [fmt-err nil]
        (do (>! sink (packet/publish formatted))
            [nil {}])))))


(defn publish
  "Yields async-channel that returns [err result] when msg is published.

  Currently `err` is always nil as no check is done whether the
  underlying connection is active, nor whether the broker received
  the message (ie qos 0)."
  ([client topic payload] (publish client topic payload {}))
  ([client topic payload opts]
   (<err-> client
           connect
           (publish* topic payload opts))))


(defn- subscription-chan [{stream :stream :as client} topic-filter msg-reader]
  (let [{source :source}      @stream
        pkts-for-topic-filter (packet-filter
                               {[:remaining-bytes :topic]
                                (partial topic-filter-matches-topic? topic-filter)})
        pkt->msg              (fn [{{:keys [retain? dup? qos]} :first-byte
                                    {topic :topic}             :remaining-bytes
                                    {payload :payload}         :extra}]
                                {:dup?    dup?
                                 :empty?  (empty-payload? payload)
                                 :payload payload
                                 :qos     qos
                                 :retain? retain?
                                 :topic   (broker-topic->app-topic client topic)})
        subscription-xf       (comp pkts-for-topic-filter
                                    (map pkt->msg)
                                    (map (comp second msg-reader))
                                    (remove nil?))]
    [nil (capture-all-packets source subscription-xf)]))


(defn- subscribe*
  [{stream :stream :as client} app-topic-filter {:keys [format] :or {format :string}}]
  (info :subscribe :app-topic-filter app-topic-filter)
  (go
    (let [{:keys [sink source]} @stream
          topic-filter          (app-topic->broker-topic client app-topic-filter)
          [sub-err sub-ch]      (err->> format
                                        fmt/read
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
