(ns otarta.format-test
  (:require
   [cljs.test :refer [deftest is testing are]]
   [otarta.format :as sut]
   [goog.crypt :as crypt]))


#_(println (crypt/stringToUtf8ByteArray "{\"a\":1}"))


(defn- write-and-read [fmt]
  #(->> % (sut/write fmt) (sut/read fmt)))


#_(deftest string-test
  (let [fut (write-and-read sut/string)]
    (are [value] (= value (fut value))
      "MQTT"
      "👽"
      "some long string"
)))


#_(deftest json-test
  (testing "reading"
    (are [buff expected] (= expected (sut/read sut/json buff))
      #js [123 34 97 34 58 49 125]
      {"a" 1}

      #js [123 34 117 110 100 101 114 115 99 111 114 101 100 95 107 101 121 34 58 49 125]
      {"underscored_key" 1}))

  (testing "writing"
    (are [expected value] (zero? (compare expected (sut/write sut/json value)))
      ;; stringified and keywordize gets lost in translation
      #js [123 34 97 34 58 49 125]
      {"a" 1}
      #js [123 34 97 34 58 49 125]
      {:a 1}

      #js [123 34 117 110 100 101 114 115 99 111 114 101 100 95 107 101 121 34 58 49 125]
      {"underscored_key" 1})))


(deftest edn-test
  (let [fut (write-and-read sut/edn)]
    (are [value] (= value (fut value))
      {:a 1}
      {"a" 1}
      {"underscored_key" {:a {:b 2}}}
      {:a {:b {:c ["👽" '(1 2 3)]}}})))
