(ns otarta.format-test
  (:require
   [cljs.test :refer [deftest is testing are]]
   [goog.crypt :as crypt]
   [goog.object]
   [huon.log :as log :refer [debug info warn error]]
   [otarta.format :as sut]))


(comment
  ;; handy to create assertions:
  (println (crypt/stringToUtf8ByteArray "#\"regex\"")))


(defn- write-and-read [fmt]
  #(->> % (sut/write fmt) (sut/read fmt)))


(deftest string-test
  (testing "handling strings"
    (let [fut (write-and-read sut/string)]
      (are [value] (= value (fut value))
        "MQTT"
        "👽"
        "some long string")))

  (testing "handling non-strings"
    (let [fut (partial sut/write sut/string)]
      (is (thrown? js/Error (fut 1)))
      (is (thrown? js/Error (fut []))))))


(deftest json-test
  (testing "reading"
    (are [buff expected] (= expected (sut/read sut/json buff))
      #js [123 34 97 34 58 49 125]
      {"a" 1}

      #js [123 34 117 110 100 101 114 115 99 111 114 101 100 95 107 101 121 34 58 49 125]
      {"underscored_key" 1}))

  (testing "reading non-json throws error"
    (are [s] (thrown? js/Error (sut/read sut/json (sut/write sut/string s)))
      ";; comment"
      "hello"))

  (testing "writing"
    (are [expected value] (.equals goog.object
                                   expected (sut/write sut/json value))
      ;; stringified and keywordize gets lost in translation
      #js [123 34 97 34 58 49 125]
      {"a" 1}
      #js [123 34 97 34 58 49 125]
      {:a 1}

      #js [123 34 117 110 100 101 114 115 99 111 114 101 100 95 107 101 121 34 58 49 125]
      {"underscored_key" 1})))


(deftest edn-test
  (testing "success"
    (let [fut (write-and-read sut/edn)]
      (are [value] (= value (fut value))
        {:a 1}
        {"a" 1}
        {"underscored_key" {:a {:b 2}}}
        {:a {:b {:c ["👽" '(1 2 3)]}}})))

  (testing "writing non-edn"
    (defrecord Foo [a])
    (are [s] (thrown? js/Error (sut/write sut/edn s))
      #"regex"
      (fn [])
      (->Foo 1)))

  (testing "reading non-edn"
    (are [s] (thrown? js/Error (sut/read sut/edn (sut/write sut/string s)))
      "#\"regex\""
      "/nonsense/")))


(deftest transit-test
  (testing "success"
    (let [fut (write-and-read sut/transit)]
      (are [value] (= value (fut value))
        {:a 1}
        {"a" 1}
        {"underscored_key" {:a {:b 2}}}
        {:a {:b {:c ["👽" '(1 2 3)]}}})))

  (testing "writing non-transit"
    (defrecord Bar [a])
    (are [s] (thrown? js/Error (sut/write sut/transit s))
      #"regex"
      (fn [])
      (->Bar 1)))

  (testing "reading non-transit"
    (are [s] (thrown? js/Error (sut/read sut/transit (sut/write sut/string s)))
      "#\"regex\""
      "/nonsense/")))
