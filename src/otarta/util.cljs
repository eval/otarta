(ns otarta.util
  (:require-macros
   [cljs.core.async.macros :refer [go go-loop]])
  (:require
   [cljs.core.async :as async :refer [<! >! take! put! chan]]
   [huon.log :refer [debug info warn error]]))


(defn in?
  "true if elm is in coll.
  (in? '(1 2 3) 1) ;; => true
  (in? '(1 2 3) 4) ;; => false"
  [coll elm]
  (some #(= elm %) coll))


(def async-mult (memoize async/mult))

(def async-mix (memoize async/mix))


(defn empty-chan [ch]
  (debug :empty-chan :channel ch)
  (async/close! ch)
  (go-loop []
    (when-let [v (<! ch)]
      (debug :empty-chan :discarding)
      (recur))))


(defn powers-of-n [n]
  (iterate (partial * n) 1))

(def powers-of-2 (powers-of-n 2))
(def powers-of-128 (powers-of-n 128))

(defn base10-convert
  ([n base] (base10-convert n base {:min-length 1}))
  ([n base {:keys [min-length]}]
   (let [quotmod                  (fn [n div] [(quot n div) (mod n div)])
         base-powers              (iterate (partial * base) 1)
         relevant-powers-reversed (reverse (take-while (partial >= n) base-powers))
         [_ result]               (reduce (fn [[rest total] power]
                                            (let [[div new-rest] (quotmod rest power)]
                                              [new-rest (conj total div)]))
                                          [n []]
                                          relevant-powers-reversed)]
     (apply vector
            (take-last (max min-length (count result))
                       (concat (repeat min-length 0) result))))))
