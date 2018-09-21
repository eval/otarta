(ns otarta.packet
  (:require [clojure.set :refer [index]]
            [goog.crypt :as crypt]
            [huon.log :refer [debug info warn error]]
            [octet.core :as buf]
            [octet.spec :as spec]
            [otarta.octet-spec :as octet-spec]))

(def packets #{{:value 1 :name :connect}
               {:value 2 :name :connack}
               {:value 3 :name :publish}
               {:value 4 :name :puback}
               {:value 5 :name :pubrec}
               {:value 6 :name :pubrel}
               {:value 7 :name :pubcomp}
               {:value 8 :name :subscribe}
               {:value 9 :name :suback}
               {:value 10 :name :unsubscribe}
               {:value 11 :name :unsuback}
               {:value 12 :name :pingreq}
               {:value 13 :name :pingresp}
               {:value 14 :name :disconnect}})


(defn find-by-name [name]
  (first (get (index packets [:name]) {:name name})))


(defn find-by-value [value]
  (first (get (index packets [:value]) {:value value})))


(defmulti encode-spec (fn [pkt] (get-in pkt [:first-byte :type])))


(defmulti decode-spec (fn [buffer]
                        (-> (buf/read buffer buf/ubyte)
                            (bit-shift-right 4)
                            (find-by-value)
                            :name)))


;;;; CONNECT

(defn connect [{:keys [username password client-id keep-alive]}]
  {:pre [(and client-id keep-alive)]}
  (cond-> {:first-byte      {:type :connect}
           :remaining-bytes {:proto-name    "MQTT"
                             :proto-level   4
                             :connect-flags {:username (if username 1 0)
                                             :password (if password 1 0)
                                             :clean-session? true}
                             :keep-alive    keep-alive
                             :client-id     client-id}}
    (some? username)
    (assoc-in [:remaining-bytes :username] username)

    (some? password)
    (assoc-in [:remaining-bytes :password] password)))


(defmethod encode-spec :connect [{{:keys [proto-name client-id username password]}
                                  :remaining-bytes :as _pkt}]
  (let [remaining-bytes (cond-> [:proto-name    (octet-spec/utf8-encoded-string proto-name)
                                 :proto-level   buf/byte
                                 :connect-flags (octet-spec/bitmask {:username [7]
                                                                     :password [6]
                                                                     :clean-session? [1 1 :bool]})
                                 :keep-alive    buf/uint16
                                 :client-id     (octet-spec/utf8-encoded-string client-id)]
                          (some? username)
                          (conj :username (octet-spec/utf8-encoded-string username))

                          (some? password)
                          (conj :password (octet-spec/utf8-encoded-string password)))]
    {:first-byte      (octet-spec/bitmask {:type [4 4]})
     :remaining-bytes remaining-bytes}))


;;;; CONNACK

(defmethod decode-spec :connack [_]
  {:first-byte      (octet-spec/bitmask {:type [4 4]})
   :remaining-bytes [:acknowledge-flags (octet-spec/bitmask {:session-present? [0]})
                     :return-code       buf/byte]})


;;;; PUBLISH

(defn publish [{:keys [dup? qos retain?
                       topic packet-identifier payload] :or {dup?    false
                                                             qos     0
                                                             retain? false}}]
  {:pre [(if (zero? qos)
           (nil? packet-identifier)
           (some? packet-identifier))
         (= js/Uint8Array (type  payload))]}
  {:first-byte      {:type :publish :dup?    dup?
                     :qos  qos      :retain? retain?}
   :remaining-bytes (cond-> {:topic    topic
                             :payload  payload}
                      packet-identifier
                      (assoc :packet-identifier packet-identifier))})


(defmethod encode-spec :publish [{{:keys [topic payload]} :remaining-bytes
                                  {:keys [qos]}           :first-byte}]
  (info :encode-spec :topic topic :payload payload)
  {:first-byte      (octet-spec/bitmask {:type [4 4]
                                         :dup? [3 1 :bool] :qos [2 2] :retain? [0 1 :bool]})
   :remaining-bytes (cond-> [:topic (octet-spec/utf8-encoded-string topic)]
                      (not (zero? qos))
                      (conj :packet-identifier (buf/uint16))

                      true
                      (conj :payload (buf/bytes (.-byteLength payload))))})


(defmethod decode-spec :publish [buf]
  (let [fb-spec                (octet-spec/bitmask {:type    [4 4]
                                                    :dup?    [3 1 :bool]
                                                    :qos     [2 2]
                                                    :retain? [0 1 :bool]})
        qos-gt-0?              (-> buf (buf/read fb-spec) :qos zero? not)
        [rem-len-size rem-len] (buf/read* buf octet-spec/variable-byte-integer* {:offset 1})
        topic-len              (buf/read buf buf/uint16 {:offset (+ rem-len-size 1)})
        payload-offset         (+ 1 rem-len-size (+ 2 topic-len))
        payload-len            (- rem-len (+ (+ 2 topic-len) (if qos-gt-0? 2 0)))
        rb-spec                (cond-> [:topic octet-spec/utf8-encoded-string*]
                                 qos-gt-0?
                                 (conj :packet-identifier (buf/uint16)))]
    {:first-byte      fb-spec 
     :remaining-bytes rb-spec
     :extra           {:payload (js/Uint8Array. (.-buffer buf) payload-offset payload-len)}}))


;;;; PUBACK (TODO)
;;;; PUBREC (TODO)
;;;; PUBREL (TODO)
;;;; PUBCOMP (TODO)


;;;; SUBSCRIBE

(defn subscribe [{:keys [packet-identifier topic-filter qos] :or {qos 0}}]
  {:pre [(and packet-identifier topic-filter)]}
  {:first-byte      {:type :subscribe :reserved 2}
   :remaining-bytes {:packet-identifier packet-identifier
                     :topic-filter      topic-filter
                     :qos               qos}})


(defmethod encode-spec :subscribe [{{:keys [topic-filter]} :remaining-bytes}]
  {:first-byte      (octet-spec/bitmask {:type     [4 4]
                                         :reserved [0 4]})
   :remaining-bytes [:packet-identifier buf/uint16
                     :topic-filter (octet-spec/utf8-encoded-string topic-filter)
                     :qos buf/byte]})

;;;; SUBACK

(defmethod decode-spec :suback [_]
  {:first-byte      (octet-spec/bitmask {:type [4 4]})
   :remaining-bytes [:packet-identifier buf/uint16
                     :payload (octet-spec/bitmask {:max-qos  [0 2]
                                                   :failure? [7 1 :bool]})]})

;;;; UNSUBSCRIBE

(defn unsubscribe [{:keys [packet-identifier topic-filter]}]
  {:pre [(and packet-identifier topic-filter)]}
  {:first-byte      {:type :unsubscribe :reserved 2}
   :remaining-bytes {:packet-identifier packet-identifier
                     :topic-filter      topic-filter}})


(defmethod encode-spec :unsubscribe [{{:keys [topic-filter]} :remaining-bytes}]
  {:first-byte      (octet-spec/bitmask {:type     [4 4]
                                         :reserved [0 4]})
   :remaining-bytes [:packet-identifier buf/uint16
                     :topic-filter (octet-spec/utf8-encoded-string topic-filter)]})

;;;; UNSUBACK

(defmethod decode-spec :unsuback [_]
  {:first-byte      (octet-spec/bitmask {:type [4 4]})
   :remaining-bytes [:packet-identifier buf/uint16]})


;;; PINGREQ

(defn pingreq []
  {:first-byte {:type :pingreq}})


(defmethod encode-spec :pingreq [_]
  {:first-byte (octet-spec/bitmask {:type [4 4]})})


;;;; PINGRESP

(defmethod decode-spec :pingresp [_]
  {:first-byte (octet-spec/bitmask {:type [4 4]})})


;;;; DISCONNECT

(defn disconnect []
  {:first-byte {:type :disconnect}})


(defmethod encode-spec :disconnect [_]
  {:first-byte (octet-spec/bitmask {:type [4 4]})})


;;;; read & write

(defn encode [pkt]
  (info :encode {:pkt pkt})
  (let [{:keys [first-byte remaining-bytes]} (encode-spec pkt)
        remaining-bytes-spec                 (apply buf/spec remaining-bytes)
        remaining-length                     (buf/size remaining-bytes-spec)
        packet-type-value                    (-> pkt
                                                 (get-in [:first-byte :type])
                                                 (find-by-name)
                                                 :value)
        data                                 (-> pkt
                                                 (assoc-in [:first-byte :type] packet-type-value)
                                                 (assoc :remaining-length remaining-length))]
    (info :encode {:data data})
    (buf/into
     (buf/spec :first-byte first-byte
               :remaining-length (octet-spec/variable-byte-integer remaining-length)
               :remaining-bytes remaining-bytes-spec)
     data)))


(defn decode
  "Turns array-buffer into data.

  Sample data:
  {:first-byte {:type :pingresp}
   :remaining-length 2
   :remaining-bytes {:packet-identifier 1}}
"
  [array-buffer]
  (let [buffer (js/DataView. array-buffer)
        {:keys [first-byte remaining-bytes extra]} (decode-spec buffer)

        spec             (buf/spec :first-byte first-byte
                                   :remaining-length octet-spec/variable-byte-integer*
                                   :remaining-bytes (apply buf/spec remaining-bytes))
        data             (buf/read buffer spec)
        packet-type-name (-> data :first-byte :type find-by-value :name)]
    (cond-> data
      true
      (assoc-in [:first-byte :type] packet-type-name)

      extra
      (assoc :extra extra))))
