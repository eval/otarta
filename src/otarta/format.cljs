(ns otarta.format
  (:require
   [cljs.reader :as reader]
   [cognitect.transit :as transit]
   [goog.crypt :as crypt]
   [huon.log :refer [debug info warn error]]))


(defprotocol PayloadFormat
  "Implement read and write operation for reading and writing data to MQTT.

  When implementing these functions keep in mind that:
  - you don't have take the null-message (\"\") into account
  - an error should be thrown when reading/writings fails (or would write non-readable data),
  this signals to the caller that the formatting failed."
  (read [format arraybuffer])
  (write [format value]))


(def raw
  (reify PayloadFormat
    (read [_ buff] buff)
    (write [_ v] v)))


(def string
  (reify PayloadFormat
    (read [_ buff]
      (info :read-string)
      (crypt/utf8ByteArrayToString buff))
    (write [_ v]
      (info :write-string {:value v})
      (assert (string? v))
      (.from js/Uint8Array (crypt/stringToUtf8ByteArray v)))))


(def json
  "Read and write data encoded in json"
  (reify PayloadFormat
    (read [_ buff]
      (info :read-json)
      (let [s (read string buff)]
        (info :read-json {:string s})
        (->> s js/JSON.parse js->clj)))
    (write [_ v]
      (info :write-json {:value v})
      (->> v clj->js js/JSON.stringify (write string)))))


(def edn
  "Read and write data encoded in edn.
`write` is strict in that it checks whether what it will write is actually readable.
This makes writing records impossible."
  (reify PayloadFormat
    (read [_ buff]
      (info :read-edn)
      (let [s (read string buff)]
        (debug :read-edn {:string s})
        (reader/read-string s)))
    (write [_ v]
      (info :write-edn {:value v})
      (let [to-write  (prn-str v)
            readable? (partial reader/read-string)]
        (readable? to-write)
        (write string to-write)))))


(def transit
  "Read and write data encoded in transit+json."
  (reify PayloadFormat
    (read  [_ buff]
      (->> buff
           (read string)
           (transit/read (transit/reader :json))))
    (write [_ v]
      (->> v
           (transit/write (transit/writer :json))
           (write string)))))
