(ns otarta.format
  (:refer-clojure :exclude [empty -write])
  (:require
   [cljs.reader :as reader]
   [cognitect.transit :as transit]
   [goog.crypt :as crypt]
   [otarta.util :refer-macros [err-> err->>]]
   [huon.log :refer [debug info warn error]]))


(defprotocol PayloadFormat
  "Implement read and write operation for reading and writing data to Uint8Array.

  When implementing these functions keep in mind that:
  - the format is bypassed for messages containing {:empty? true} (ie no need to handle writing/reading \"\")
  - an error should be thrown when reading/writings fails (or would write non-readable data). This signals to the caller that the formatting failed."
  (-read [format arraybuffer])
  (-write [format value]))


(def empty
  (reify PayloadFormat
    (-read [_ _] "")
    (-write [_ _] (js/Uint8Array.))))


(def raw
  (reify PayloadFormat
    (-read [_ buff] buff)
    (-write [_ v] v)))


(def string
  (reify PayloadFormat
    (-read [_ buff]
      (info :read-string)
      (crypt/utf8ByteArrayToString buff))
    (-write [_ v]
      (info :write-string {:value v})
      (assert (string? v))
      (.from js/Uint8Array (crypt/stringToUtf8ByteArray v)))))


(def json
  "Read and write data encoded in json"
  (reify PayloadFormat
    (-read [_ buff]
      (info :read-json)
      (let [s (-read string buff)]
        (info :read-json {:string s})
        (->> s js/JSON.parse js->clj)))
    (-write [_ v]
      (info :write-json {:value v})
      (->> v clj->js js/JSON.stringify (-write string)))))


(def edn
  "Read and write data encoded in edn.
`write` is strict in that it checks whether what it will write is actually readable.
This makes writing records impossible."
  (reify PayloadFormat
    (-read [_ buff]
      (info :read-edn)
      (let [s (-read string buff)]
        (debug :read-edn {:string s})
        (reader/read-string s)))
    (-write [_ v]
      (info :write-edn {:value v})
      (let [to-write  (prn-str v)
            readable? (partial reader/read-string)]
        (readable? to-write)
        (-write string to-write)))))


(def transit
  "Read and write data encoded in transit+json."
  (reify PayloadFormat
    (-read  [_ buff]
      (info :read-transit)
      (->> buff
           (-read string)
           (transit/read (transit/reader :json))))
    (-write [_ v]
      (info :write-transit)
      (->> v
           (transit/write (transit/writer :json))
           (-write string)))))


(def payload-formats
  {:edn     edn
   :empty   empty
   :raw     raw
   :string  string
   :transit transit
   :json    json})


(defn find-payload-format [fmt]
  (info :find-payload-format :fmt fmt)
  (cond
    (satisfies? PayloadFormat fmt)  fmt
    (contains? payload-formats fmt) (get payload-formats fmt)))


(defn msg-formatter [rw format]
  (if-let [payload-format (find-payload-format format)]
    (let [formatter (fn [{e? :empty? :as msg}]
                      (info :formatter)
                      (let [try-format        #(try (rw payload-format %)
                                                    (catch js/Error _
                                                      (error :format-error)
                                                      nil))
                            format-fn         (if e? (partial rw empty) try-format)
                            formatted-payload (-> msg :payload format-fn)]
                        (if (nil? formatted-payload)
                          [:format-error nil]
                          [nil (assoc msg :payload formatted-payload)])))]
      [nil formatter])
    [:unkown-format nil]))


(defn write
  "Applies `format` to :payload of `msg` or yields a writer when no `msg` given.
  When `msg` has [:empty? true], the empty-format is used instead of `format`.

  `format` can be one of `payload-formats`, or a reify of PayloadFormat.

  Yields [err msg-with-formatted-payload]
  Possible err's:
  - :unknown-format
  - :format-error

  Examples:
  (write :json {:payload \"a\")
  ;; => [nil {:payload #object[Uint8Array 34,97,34]}]

  (write :json {:empty? true :payload \"a\"}
  ;; => [nil {:payload #object[Uint8Array ]}]
"
  ([format]
   (err->> format
           (msg-formatter -write)))
  ([format msg]
   (err-> format
          (write)
          (apply (list msg)))))


(defn read
  "Applies `format` to :payload of `msg` or yields a reader when no `msg` given.
  When `msg` contains [:empty? true], the empty-format is used instead of `format`.

  `format` can be one of payload-formats, or a reify of PayloadFormat.

  Yields [err msg-with-formatted-payload]
  Possible err's:
  - :unknown-format
  - :format-error

  Examples:
  (read :json {:payload #js [34,97,34])
  ;; => [nil {:payload \"a\"}]

  (read :json {:empty? true :payload #js [34,97,34])
  ;; => [nil {:empty? true :payload \"\"}]
"
  ([format]
   (err->> format
           (msg-formatter -read)))
  ([format msg]
   (err-> format
          read
          (apply (list msg)))))
