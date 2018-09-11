(ns otarta.test-helpers
  (:require-macros
   [cljs.core.async.macros :refer [go]])
  (:require
   [cljs.core.async :as async :refer [<! >! take! put!]]
   [cljs.test :as test]))


;; NOTE: this can be used only *once* in a deftest.
;; See also: https://clojurescript.org/tools/testing#async-testing
;; SOURCE: https://stackoverflow.com/a/30781278
(defn test-async
  "Asynchronous test awaiting ch to produce a value or close."
  [ch]
  (test/async done
              (take! ch (fn [_] (done)))))


;; SOURCE: https://stackoverflow.com/a/30781278
(defn test-within
  "Asserts that ch does not close or produce a value within ms. Returns a
  channel from which the value can be taken."
  [ms ch]
  (go
    (let [t      (async/timeout ms)
          [v ch] (async/alts! [ch t])]
      (test/is (not= ch t)
          (str "Test should have finished within " ms "ms."))
      v)))


;; SOURCE https://github.com/rill-event-sourcing/wheel/blob/36bf725fbee3b29a1989e95e4eb10bb599a2d4a0/src/rill/wheel/testing.clj#L7
(defn sub?
  "true if `sub` is either `nil`, equal to `x`, or a recursive
  subcollection of `x`.
  If sub is a sequential collection of size N the first N elements of
  `x` are tested. If sub is a map every value in sub is tested with
  the corresponding value in `x`. If `sub` is a set every *key* in sub
  should exist in `x`.
  **Examples:**
      (sub? nil
            {:anything :at-all})
      (sub? [:a :b]
            [:a :b :c])
      (not (sub? [:a :b :c]
                 [:a :b]))
      (sub? {:a [1 2 3]}
            {:a [1 2 3 4] :b 2})
      (sub? {:a [1 nil 3]}
            {:a [1 2 3 4] :b 2})
      (not (sub? {:a [1 2 3 4]}
                 {:a [1 2 3] :b 2}))
      (sub? #{:a}
            {:a 1 :b 2})
      (sub? #{:a}
            #{:a :b})
      (not (sub? #{:a :c}
                 #{:a :b}))
      (sub? :something
            :something)
      (sub? [:1 :2 :3]
            (list :1 :2 :3))
      (sub? [:1 :2]
            (list :1 :2 :3))
      (sub? (list :1 :2 :3)
            [:1 :2 :3])
      (not (sub? (list nil 2)
                 [:1 :2 :3]))
  "
  [sub x]
  (cond (nil? sub)
        true
        (sequential? sub)
        (and (<= (count sub)
                 (count x))
             (every? (fn [[i el]]
                       (sub? el (nth x i)))
                     (map-indexed vector sub)))
        (map? sub)
        (every? (fn [[k v]] (sub? v (get x k)))
                sub)
        (set? sub)
        (every? #(contains? x %)
                sub)
        :else
        (= sub x)))
