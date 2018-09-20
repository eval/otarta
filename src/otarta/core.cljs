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
   [otarta.payload-format :as otarta-fmt]
   [otarta.packet :as packet]
   [otarta.util :as util :refer-macros [<err-> err->]]))


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


(def payload-formats
  {:edn     otarta-fmt/edn
   :raw     otarta-fmt/raw
   :string  otarta-fmt/string
   :transit otarta-fmt/transit
   :json    otarta-fmt/json})


(defn- find-payload-format [fmt]
  (second (cond
     (satisfies? otarta-fmt/PayloadFormat fmt) [nil fmt]
     (contains? payload-formats fmt) [nil (get payload-formats fmt)]
     :else [:unknown-format nil])))


(defn parse-broker-url [url]
  (let [parsed     (uri/parse url)
        ws-url-map (select-keys parsed [:scheme :host :port :path])
        ws-url     (str (uri/map->URI. ws-url-map))]
    (cond-> {:ws-url ws-url}
      (:user parsed)
      (assoc :username (:user parsed))

      (:password parsed)
      (assoc :password (:password parsed)))))


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


(defn client [{:keys [broker-url] :as opts}]
  (let [default-opts {:keep-alive 60 :client-id (str "otarta-" (random-uuid))}
        config (-> broker-url
                   (parse-broker-url)
                   (merge default-opts)
                   (merge (select-keys opts [:client-id :keep-alive])))]
    {:config config :stream (atom nil) :pinger (atom nil)}))


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


(defn- format-publish-payload
  "Applies function `payload-writer` to payload if payload is not empty.
When writer-fn fails [err nil] is returned. Else [nil updated-all]."
  [{pl :payload :as all} payload-writer]
  (let [empty-msg? (or (nil? pl) (= pl ""))]
    (if empty-msg?
      [nil ""]
      (try
        [nil (update all :payload payload-writer)]
        (catch js/Error _
          (error :payload-writing-error)
          [:payload-writing-error nil])))))



;; steps (read and write):
;; - :json
;; - vind-format
;; - maak writer/reader
;;   - partial fmt/read-or-write fmt
;;   - wrapped in try
;;   - bypassing via empty
;; - pas toe op {:topic ... :payload ...}

(defn- publish* [{stream :stream :as client} topic msg {:keys [format] :or {format :string}}]
  (info :publish :client client :topic topic :msg msg :format format)
  (go
    ;; pipeline:
    ;; - {:format :string}
    ;; - {:formatter ...} or {:error "unknown format"}
    ;; - {:topic "" :payload "{"a":1}"} or {:error "error formatting"}
    ;; - pkt

    (comment
      (let [empty-msg? (or (nil? msg) (= "" msg))
            to-send {:topic topic :payload msg :empty? empty-msg?}
            sink (-> stream deref :sink)]
        (while-not-> :error
          {:format format}
          (construct-formatter2 :write)
          (-> :formatter (apply to-send))
          (packet/publish)
          (#(>! sink %)))))
    (if-let [pl-format (find-payload-format format)]
      (let   [payload-writer   (partial otarta-fmt/write pl-format)
              [err to-publish] (err-> {:topic topic :payload msg}
                                      (format-publish-payload payload-writer))]
        (if err
          (do (error :publish :format format :error err) [err nil])
          (let [pkt (packet/publish to-publish)]
            (info :publish* {:pkt pkt})
            (>! (-> stream deref :sink) pkt)
            [nil {}])))
      [:unkown-format nil])))


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


(defn construct-formatter [format read-write]
  (let [rw             (if (= read-write :read) otarta-fmt/read otarta-fmt/write)
        make-rw-fn     #(vector nil (partial rw %))
        wrap-rw-fn     #(vector nil (fn [payload]
                                      (try (% payload) (catch js/Error _))))
        make-formatter #(vector nil (fn [{e? :empty? :as msg}]
                                      (update msg :payload (if e? (constantly "") %))))]
    (err-> format
           (find-payload-format)
           (make-rw-fn)
           (wrap-rw-fn)
           (make-formatter))))

(defn construct-formatter2 [{format :format} read-write]
  (if-let [payload-format (find-payload-format format)]
    (let [rw        (if (= read-write :read) otarta-fmt/read otarta-fmt/write)
          formatter (fn [{e? :empty? :as to-send}]
                      (let [try-format        #(try (rw payload-format %)
                                                    (catch js/Error _))
                            update-fn         (if e? (constantly "") try-format)
                            formatted-payload (-> to-send :payload update-fn)]
                        (if (nil? formatted-payload)
                          (assoc to-send :error :format-error)
                          (assoc to-send :payload formatted-payload))))]
      {:formatter formatter}
      #_(some-> format
              (find-payload-format)
              (#(fn [{e? :empty? :as msg}]
                  (let [try-format (fn [v] (try (rw % v) (catch js/Error _)))
                        update-fn  (if e? (constantly "") try-format)]
                    (update msg :payload update-fn))))))
    {:error :unkown-format}))

(defn- subscription-chan [source topic-filter payload-reader]
  (let [pkts-for-topic-filter (packet-filter
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
                                 :topic     topic})
        try-reading           #(try
                                 (info :reading-payload {:payload %})
                                 (payload-reader %)
                                 (catch js/Error _
                                   (error :reading-payload)
                                   nil))
        format-payload        #(if (:empty? %)
                                 (update % :payload (constantly ""))
                                 (update % :payload try-reading))
        subscription-xf       (comp pkts-for-topic-filter
                                    (map pkt->msg)
                                    (map format-payload))]
    (capture-all-packets source subscription-xf)))


(defn- subscribe*
  [{stream :stream :as client} topic-filter {:keys [format] :or {format :string}}]
  (info :subscribe :topic-filter topic-filter)
  (go
    (if-let [fmt (find-payload-format format)]
      (let [{:keys [sink source]} @stream
            format-reader         (partial otarta-fmt/read fmt)
            sub-pkt               (packet/subscribe {:topic-filter      topic-filter
                                                     :packet-identifier 1})
            next-suback           (capture-first-packet source
                                                        (packet-filter {[:first-byte :type] :suback}))
            ;; ensure sub-ch exists before waiting for suback
            ;; as broker (may) send(s) messages before sending suback
            sub-ch                (subscription-chan source topic-filter format-reader)
            [err {{{:keys [_max-qos failure?] :as sub-result} :payload} :remaining-bytes}]
            (<! (send-and-await-response sub-pkt sink next-suback))]
        (if err
          (error :subscribe err)
          (info :subscribe :sub-result sub-result))
        (cond
          err      [err nil]
          failure? [:broker-refused-sub nil]
          :else    [nil {:ch sub-ch}]))
      [:unknown-format nil])))


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
