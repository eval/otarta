(ns otarta.format
  (:require
   [cljs.reader :as reader]
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
      (crypt/utf8ByteArrayToString arraybuffer))
    (write [_ value]
      (crypt/stringToUtf8ByteArray value))))


(def json
  (reify PayloadFormat
    (read [_ arraybuffer]
      (->> arraybuffer (read string) js/JSON.parse js->clj))
    (write [_ value]
      (->> value clj->js js/JSON.stringify (write string)))))


(def edn
  (reify PayloadFormat
    (read [_ arraybuffer]
      (->> arraybuffer (read string) reader/read-string))
    (write [_ value]
      (->> value prn-str (write string)))))

#_(prn (write edn #"some regex"))
