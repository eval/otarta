(ns otarta.octet-spec
  (:require [goog.crypt :as crypt]
            [huon.log :as log]
            [octet.buffer :as buffer]
            [octet.core :as buf]
            [octet.spec :as spec]
            [otarta.util :as util]))

(defn bitmask
  ""
  [mapping]
  {:pre [((fn fits-in-one-byte? [m]
            (every?
             (fn [[_att [start size]]]
               (>= 8 ((fnil + 1) size start))) m)) mapping)]}
  (letfn [(all-values-fit? [m v]
            (every?
             (fn [[k [position size]]]
               (let [available-bits (or size 1)
                     max-value      (dec (nth util/powers-of-2 available-bits))
                     actual-value   (get v k 0)]
                 (>= max-value actual-value))) m))]
    ;; coercers: [read-coercer, write-coercer]
    (let [coercers {:bool [(partial = 1) #(if (true? %) 1 0)]}]
      (reify
        spec/ISpecSize
        (size [_]
          1)

        spec/ISpec
        (read [_ buff pos]
          (let [rawdata (buffer/read-ubyte buff pos)
                coerced (reduce
                         (fn [acc [key [position size coercer]]]
                           (log/debug :bitmask-read {:acc acc})
                           (let [size      (or size 1)
                                 coerce-fn (if coercer (first (coercer coercers)) identity)
                                 ;; size -> bitmask
                                 ;; 1 -> 2r1, 2 -> 2r11, 3 -> 2r111 (ie powers-of-2 minus 1)
                                 bitmask   (dec (nth util/powers-of-2 size))
                                 val       (bit-and (bit-shift-right rawdata position) bitmask)]
                             (assoc acc key (coerce-fn val))))
                         {} mapping)]
            [1 coerced]))

        (write [_ buff pos m]
          (assert (all-values-fit? mapping m) "Some values won't fit given mapping")
          (let [byte-value (reduce
                            (fn [acc [k v]]
                              (let [[position size coercer] (get mapping k)
                                    size                    (or size 1)
                                    coerce-fn               (if coercer
                                                              (second (coercer coercers))
                                                              identity)]
                                (bit-or acc (bit-shift-left (coerce-fn v) position))))
                            2r0 m)]
            (buffer/write-ubyte buff pos byte-value)
            1))))))


(defn utf8-encoded-string [string]
  (let [data      (crypt/stringToUtf8ByteArray string)
        data-size (count data)]
    (reify
      spec/ISpecSize
      (size [_]
        (+ 2 data-size))


      spec/ISpec
      (read [_ buff pos]
        (assert false "use utf8-encoded-string* for reading arbitrary strings"))


      (write [_ buff pos value]
        (buffer/write-ushort buff pos data-size)
        (buffer/write-bytes buff (+ pos 2) data-size data)
        (+ 2 data-size)))))


(def ^{:doc "Arbitrary length utf8-encoded string type spec."}
  utf8-encoded-string*
  (reify
    cljs.core/IFn
    (-invoke [s] s)

    spec/ISpecDynamicSize
    (size* [_ data]
      (let [data (crypt/stringToUtf8ByteArray data)]
        (+ 2 (count data))))


    spec/ISpec
    (read [_ buff pos]
      (let [datasize (buffer/read-ushort buff pos)
            #_#_data     (buf/read buff (buf/repeat datasize buf/ubyte) {:offset (+ 2 pos)})
            bytes (js/Uint8Array. (.-buffer buff) (+ 2 pos) datasize)
            data     (crypt/utf8ByteArrayToString bytes)]
        [(+ datasize 2) data]))


    (write [_ buff pos value]
      (let [input  (crypt/stringToUtf8ByteArray value)
            length (count input)]
        (buffer/write-ushort buff pos length)
        (buffer/write-bytes buff (+ pos 2) length input)
        (+ length 2)))))


(defn variable-byte-integer [int]
  (let [[head & tail] (util/base10-convert int 128)
        data          (reduce
                       #(cons (+ 128 %2) %1)
                       [head]
                       tail)
        size          (count data)]
    (reify
      spec/ISpecSize
      (size [_]
        size)


      spec/ISpec
      (read [_ buff pos]
        (assert false "use variable-byte-integer* for reading arbitrary integers"))


      (write [_ buff pos value]
        (mapv #(buffer/write-ubyte buff (+ pos %1) %2) (range) data)
        size)
)))

;; see v5 spec: http://docs.oasis-open.org/mqtt/mqtt/v5.0/cs02/mqtt-v5.0-cs02.html#_Toc514345283
(def ^{:doc "Arbitrary length utf8-encoded string type spec."}
  variable-byte-integer*
  (reify
    cljs.core/IFn
    (-invoke [s] s)

    spec/ISpecDynamicSize
    (size* [_ number]
      (count (util/base10-convert number 128)))


    spec/ISpec
    (read [_ buff pos]
      (let [[readed-count readed-bytes]
            (loop [offset 0
                   acc    []]
              (let [byte            (buffer/read-ubyte buff (+ pos offset))
                    rem-length-end? (< byte 128)]
                (if rem-length-end?
                  [(inc offset) (conj acc byte)]
                  (recur (inc offset) (conj acc byte)))))
            nums-max-127 (map (partial bit-and 127) readed-bytes)
            number       (apply + (map * nums-max-127 util/powers-of-128))]
        [readed-count number]))


    (write [_ buff pos number]
      (let [[head & tail] (util/base10-convert number 128)
            input         (reduce
                           #(cons (+ 128 %2) %1)
                           [head]
                           tail)
            length        (count input)]
        (mapv #(buffer/write-ubyte buff (+ pos %1) %2) (range) input)
        length))))
