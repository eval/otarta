(ns otarta.format
  (:require
   [cljs.reader :as reader]
   [huon.log :refer [debug info warn error]]
   [goog.crypt :as crypt]))


(defprotocol PayloadFormat
  (read [formatter arraybuffer])
  (write [formatter value]))


(def raw
  (reify PayloadFormat
    (read [_ arraybuffer]
      arraybuffer)
    (write [_ value]
      value)))


(def string
  (reify PayloadFormat
    (read [_ arraybuffer]
      (info :read-string)
      (crypt/utf8ByteArrayToString arraybuffer))
    (write [_ value]
      (info :write-string {:value value})
      (.from js/Uint8Array (crypt/stringToUtf8ByteArray value)))))


(def json
  (reify PayloadFormat
    (read [_ arraybuffer]
      (info :read-json)
      (let [s (read string arraybuffer)]
        (info :read-json {:string s})
        (->> s js/JSON.parse js->clj)))
    (write [_ value]
      (info :write-json {:value value})
      (->> value clj->js js/JSON.stringify (write string)))))


(def edn
  (reify PayloadFormat
    (read [_ arraybuffer]
      (info :read-edn)
      (let [s (read string arraybuffer)]
        (info :read-edn {:string s})
        (reader/read-string s)))
    (write [_ value]
      (info :write-edn {:value value})
      (->> value prn-str (write string)))))

#_(prn (write edn #"some regex"))
