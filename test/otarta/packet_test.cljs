(ns otarta.packet-test
  (:require-macros
   [cljs.core.async.macros :refer [go go-loop]])
  (:require
   [cljs.core.async :as async :refer [<! >! take! put! chan]]
   [cljs.test :refer [deftest is testing are]]
   [huon.log :as log]
   [octet.core :as buf]
   [octet.spec :as spec]
   [otarta.packet :as sut]
   [otarta.test-helpers :as helpers :refer [sub? test-async test-within]]))


(defn data->byte-array [data]
  (let [buf (sut/encode data)]
    (buf/read buf (buf/repeat (buf/get-capacity buf) buf/ubyte))))


(defn byte-array->data [ba]
  (sut/decode (.-buffer (js/Uint8Array. ba))))


(deftest connect-write-spec-test
  (let [data-fn sut/connect]
    (testing "writes correct bytes"
      (are [input expected] (= expected (data->byte-array (data-fn input)))
        {:keep-alive 10 :client-id "foo"}
        [16 15 0 4 77 81 84 84 4 2 0 10 0 3 102 111 111]

        {:keep-alive 10 :client-id "clientid"}
        [16 20 0 4 77 81 84 84 4 2 0 10 0 8 99 108 105 101 110 116 105 100]

        {:keep-alive 10 :client-id "clientid" :username "alfa" :password "wachtwoord"}
        [16 38 0 4 77 81 84 84 4 194 0 10 0 8 99 108 105 101 110 116 105 100 0 4 97 108 102 97 0 10 119 97 99 104 116 119 111 111 114 100]
))))


(deftest connack-read-spec-test
  (testing "reads array-buffer correctly"
    (are [byte-array expected] (helpers/sub? expected (byte-array->data byte-array))
      [32 2 0 0] {:first-byte {:type :connack} :remaining-length 2}

      [32 2 1 0] {:remaining-bytes {:acknowledge-flags {:session-present? 1}}}

      [32 2 0 0] {:remaining-bytes {:return-code 0}}
      [32 2 0 1] {:remaining-bytes {:return-code 1}}
      [32 2 0 5] {:remaining-bytes {:return-code 5}}
)))


(deftest publish-write-spec-test
  (let [data-fn sut/publish]
    (testing "writes correct bytes"
      (are [input expected] (= expected (data->byte-array (data-fn input)))
        ;; with qos 0, no packet identifier
        {:topic "some/topic" :payload (js/Uint8Array. [77 81 84 84])}
        [48 16 0 10 115 111 109 101 47 116 111 112 105 99 77 81 84 84]

        ;; if qos 1, then with packet identifier
        {:qos 1 :packet-identifier 1
         :topic "some/topic" :payload (js/Uint8Array. [77 81 84 84])}
        [52 18 0 10 115 111 109 101 47 116 111 112 105 99 0 1 77 81 84 84]

        ;; strings are converted
        {:topic "some/topic" :payload (js/Uint8Array. [77 81 84 84])}
        [48 16 0 10 115 111 109 101 47 116 111 112 105 99 77 81 84 84]

))))

(deftest publish-read-spec-test
  (testing "reads array-buffer correctly"
    (are [byte-array expected] (helpers/sub? expected (byte-array->data byte-array))
      [48 16 0 10 115 111 109 101 47 116 111 112 105 99 77 81 84 84]
      {:first-byte {:type :publish} :remaining-length 16}

      [48 16 0 10 115 111 109 101 47 116 111 112 105 99 77 81 84 84]
      {:remaining-bytes {:topic "some/topic"}}

      [48 19 0 13 100 101 118 47 102 111 111 47 104 101 108 108 111 77 81 84 84]
      {:remaining-bytes {:topic "dev/foo/hello"}}
))

  (testing "yields payload as byte array"
    (let [read-payload (get-in (byte-array->data [48 16 0 10 115 111 109 101 47 116 111 112 105 99 77 81 84 84]) [:extra :payload])]
      (is (= (type read-payload) js/Uint8Array))
      (is (= (.toString read-payload) "77,81,84,84"))
)))


(deftest subscribe-write-spec-test
  (let [data-fn sut/subscribe]
    (testing "writes correct bytes"
      (are [input expected] (= expected (data->byte-array (data-fn input)))
        {:packet-identifier 1
         :subscriptions     [{:topic-filter "some/topic/filter/+"
                              :qos          1}]}
        [130 24 0 1 0 19 115 111 109 101 47 116 111 112 105 99 47 102 105 108 116 101 114 47 43 1]

        ;; multiple
        {:packet-identifier 1
         :subscriptions     [{:topic-filter "some/topic/filter/+" :qos 1}
                             {:topic-filter "other/topic/filter/+" :qos 2}]}
        [130 47 0 1
         0 19 115 111 109 101 47 116 111 112 105 99 47 102 105 108 116 101 114 47 43 1
         0 20 111 116 104 101 114 47 116 111 112 105 99 47 102 105 108 116 101 114 47 43 2]

        {:packet-identifier 1
         :subscriptions [{:topic-filter      (str "dev/prefix/" (apply str (interpose "/" (repeat 5 "some/topic/filter/that/is/longer/than/256/characters"))))
                          :qos               2}]}
        [130 152 2 0 1 1 19 100 101 118 47 112 114 101 102 105 120 47 115 111 109 101 47 116 111 112 105 99 47 102 105 108 116 101 114 47 116 104 97 116 47 105 115 47 108 111 110 103 101 114 47 116 104 97 110 47 50 53 54 47 99 104 97 114 97 99 116 101 114 115 47 115 111 109 101 47 116 111 112 105 99 47 102 105 108 116 101 114 47 116 104 97 116 47 105 115 47 108 111 110 103 101 114 47 116 104 97 110 47 50 53 54 47 99 104 97 114 97 99 116 101 114 115 47 115 111 109 101 47 116 111 112 105 99 47 102 105 108 116 101 114 47 116 104 97 116 47 105 115 47 108 111 110 103 101 114 47 116 104 97 110 47 50 53 54 47 99 104 97 114 97 99 116 101 114 115 47 115 111 109 101 47 116 111 112 105 99 47 102 105 108 116 101 114 47 116 104 97 116 47 105 115 47 108 111 110 103 101 114 47 116 104 97 110 47 50 53 54 47 99 104 97 114 97 99 116 101 114 115 47 115 111 109 101 47 116 111 112 105 99 47 102 105 108 116 101 114 47 116 104 97 116 47 105 115 47 108 111 110 103 101 114 47 116 104 97 110 47 50 53 54 47 99 104 97 114 97 99 116 101 114 115 2]
))))


(deftest suback-read-spec-test
  (testing "reads array-buffer correctly"
    (are [byte-array expected] (helpers/sub? expected (byte-array->data byte-array))
      [144 3 0 1 0] {:first-byte {:type :suback} :remaining-length 3}

      [144 3 0 1 0] {:remaining-bytes {:packet-identifier 1}}
      [144 3 0 1 1] {:remaining-bytes
                     {:subscriptions [{:max-qos 1 :failure? false}]}}

      ;; receiving multiple confirmations
      [144 4 0 1
       1 128] {:remaining-bytes {:subscriptions [{:max-qos 1 :failure? false}
                                                 {:max-qos 0 :failure? true}]}}
)))


(deftest unsubscribe-write-spec-test
  (let [data-fn sut/unsubscribe]
    (testing "writes correct bytes"
      (are [input expected] (= expected (data->byte-array (data-fn input)))
        {:packet-identifier 1
         :topic-filter      "some/topic/filter/+"}
        [162 23 0 1 0 19 115 111 109 101 47 116 111 112 105 99 47 102 105 108 116 101 114 47 43]

        {:packet-identifier 1
         :topic-filter (str "dev/prefix/" (apply str (interpose "/" (repeat 5 "some/topic/filter/that/is/longer/than/256/characters"))))}
        [162 151 2 0 1 1 19 100 101 118 47 112 114 101 102 105 120 47 115 111 109 101 47 116 111 112 105 99 47 102 105 108 116 101 114 47 116 104 97 116 47 105 115 47 108 111 110 103 101 114 47 116 104 97 110 47 50 53 54 47 99 104 97 114 97 99 116 101 114 115 47 115 111 109 101 47 116 111 112 105 99 47 102 105 108 116 101 114 47 116 104 97 116 47 105 115 47 108 111 110 103 101 114 47 116 104 97 110 47 50 53 54 47 99 104 97 114 97 99 116 101 114 115 47 115 111 109 101 47 116 111 112 105 99 47 102 105 108 116 101 114 47 116 104 97 116 47 105 115 47 108 111 110 103 101 114 47 116 104 97 110 47 50 53 54 47 99 104 97 114 97 99 116 101 114 115 47 115 111 109 101 47 116 111 112 105 99 47 102 105 108 116 101 114 47 116 104 97 116 47 105 115 47 108 111 110 103 101 114 47 116 104 97 110 47 50 53 54 47 99 104 97 114 97 99 116 101 114 115 47 115 111 109 101 47 116 111 112 105 99 47 102 105 108 116 101 114 47 116 104 97 116 47 105 115 47 108 111 110 103 101 114 47 116 104 97 110 47 50 53 54 47 99 104 97 114 97 99 116 101 114 115]
))))

(deftest unsuback-read-spec-test
  (testing "reads array-buffer correctly"
    (are [byte-array expected] (helpers/sub? expected (byte-array->data byte-array))
      [176 2 0 1] {:first-byte {:type :unsuback} :remaining-length 2}

      [176 2 0 1] {:remaining-bytes {:packet-identifier 1}}
)))


(deftest pingreq-write-spec-test
  (testing "writes correct bytes"
    (is (= [192 0] (data->byte-array (sut/pingreq))))))


(deftest pingresp-read-spec-test
  (testing "reads array-buffer correctly"
    (is (helpers/sub? {:first-byte {:type :pingresp} :remaining-length 0}
                      (byte-array->data [208 0])))))


(deftest disconnect-write-spec-test
  (testing "writes correct bytes"
    (is (= [224 0] (data->byte-array (sut/disconnect))))))
