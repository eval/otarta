(ns otarta.core-test
  (:require-macros
   [cljs.core.async.macros :refer [go go-loop]])
  (:require
   [cljs.core.async :as async :refer [<! >! take! put! chan]]
   [cljs.test :as test :refer [deftest is testing are]]
   [goog.crypt :as crypt]
   [huon.log :as log :refer [debug info warn error]]
   [otarta.core :as sut]
   [otarta.payload-format :as fmt]
   [otarta.packet :as pkt]
   [otarta.test-helpers :as helpers :refer [test-async sub?]]))

#_(log/enable!)

(deftest parse-broker-url-test
  (testing "contains :ws-url"
    (are [broker-url ws-url] (sub? {:ws-url ws-url} (sut/parse-broker-url broker-url))
      "ws://host/path"      "ws://host/path"
      "ws://user@host/path" "ws://host/path"))

  (testing "contains credentials"
    (are [broker-url creds] (sub? creds (sut/parse-broker-url broker-url))
      "ws://user@host/path"           {:username "user"}
      "ws://user:some-pass@host/path" {:username "user"}
      "ws://user:password@host/path"  {:password "password"})))


(deftest topic-filter-matches-topic?-test
  (testing "matching samples"
    (are [tfilter topic expected] (= expected
                                     (some? (sut/topic-filter-matches-topic? tfilter topic)))
      ;; Simple
      "foo/one" "foo/one" true

      ;; Plus
      "foo/+"       "foo/one"       true
      "foo/+"       "foo"           false
      "foo/+/hello" "foo/bar/hello" true
      "+/b/c"       "a/b/c"         true

      ;; Hash
      "foo/#" "foo/bar"      true
      "foo/#" "foo/bar/moar" true
      "foo/#" "foo"          true ;; !!

      ;; broker-internal topics
      "$SYS/#"     "$SYS/broker/load/publish/sent/15min" true
      ;; [MQTT-4.7.2-1] wildcards should not match $-topics
      "#"          "$SYS/broker/load/publish/sent/15min" false ;; !!
      "+/broker/#" "$SYS/broker/load/publish/sent/15min" false ;; !!
)))


(deftest packet-filter-test
  (testing "matches a map *iff* all are matching"
    (are [matchers pkt expected] (= expected
                                    (not (empty?
                                          (into [] (sut/packet-filter matchers) [pkt]))))
      ;; simple
      {[:a] 1}         {:a 1}      true
      {[:a] 1 [:b] 2}  {:a 1 :b 2} true
      {[:a] 1 [:b] 10} {:a 1 :b 2} false
      {[:a] 1 [:b] 2}  {:a 1}      false
      {[:a] nil}       {}          false

      ;; nested
      {[:a :b] 1}   {:a {:b 1}} true
      {[:a :b] nil} {:a {:c 1}} false

      ;; matchers can be predicates
      {[:a] odd?}          {:a 5} true
      {[:a] odd?}          {:a 2} false
      {[:a] (partial < 3)} {:a 4} true
      {[:a] (partial < 3)} {:a 3} false)))


(let [received-packet   (fn [pkt-fn & args]
                          (->> args
                               (apply pkt-fn)
                               (pkt/encode)
                               (.-buffer)
                               (pkt/decode)))
      str->int8array    #(.from js/Uint8Array (crypt/stringToUtf8ByteArray %))
      publish!          (fn [source topic msg]
                          (put! source (received-packet pkt/publish
                                                        {:topic   topic
                                                         :payload (str->int8array msg)})))
      subscribe!        #(sut/subscription-chan %1 %2 (partial fmt/read %3))
      messages-received (fn [ch]
                          (async/close! ch)
                          (async/into [] ch))
      payloads-received #(go (map :payload (<! (messages-received %))))
      topics-received   #(go (map :topic (<! (messages-received %))))]

  (deftest subscription-chan-test0
    (testing "inactive subscribers don't block source nor active subscribers"
      (let [source       (async/chan)
            inactive-sub (subscribe! source "foo/+" fmt/raw)
            active-sub   (subscribe! source "foo/+" fmt/raw)]

        (dotimes [_ 5]
          (publish! source "foo/bar" "hello"))

        (test-async (go
                      (is (= 5 (count (<! (topics-received active-sub))))))))))

  (deftest subscription-chan-test1
    (testing "payload is nil when formatter fails"
      (let [source     (async/chan)
            custom-fmt (reify fmt/PayloadFormat
                         (read [_ buff]
                           (->> buff
                                (fmt/read fmt/string)
                                (str "read: ")))
                         (write [_ v] v))
            sub        (subscribe! source "foo/json" custom-fmt)]
        (publish! source "foo/json" "hello")

        (test-async (go
                        (is (= ["read: hello"]
                               (-> sub payloads-received <!))))))))

  (deftest subscription-chan-test2
    (let [source      (async/chan)
          foo-sub     (subscribe! source "foo/+" fmt/raw)
          not-foo-sub (subscribe! source "not-foo/#" fmt/raw)]
      (publish! source "foo/bar"      "for foo")
      (publish! source "not-foo/bar"  "for not-foo")
      (publish! source "foo/baz"      "foo foo")
      (publish! source "not-foo/bar/baz"  "for not-foo")

      (test-async (go
                      (is (= ["foo/bar" "foo/baz"]
                             (<! (topics-received foo-sub))))))
      (test-async (go
                      (is (= ["not-foo/bar" "not-foo/bar/baz"]
                             (<! (topics-received not-foo-sub))))))))

  (deftest subscription-chan-test3
    (testing "payload-formatter is applied"
      (let [source     (async/chan)
            string-sub (subscribe! source "foo/string" fmt/string)
            json-sub   (subscribe! source "foo/json" fmt/json)
            edn-sub    (subscribe! source "foo/edn" fmt/edn)]
             (publish! source "foo/string" "just a string")
             (publish! source "foo/json" "{\"a\":1}")
             (publish! source "foo/edn"  "[1 #_2 3]")

             (test-async (go
                           (is (= ["just a string"]
                                  (-> string-sub payloads-received <!)))))
             (test-async (go
                           (is (= [{"a" 1}]
                                    (-> json-sub payloads-received <!)))))
             (test-async (go
                             (is (= [[1 3]]
                                    (-> edn-sub payloads-received <!))))))))

  (deftest subscription-chan-test4
    (testing "payload is nil when formatter fails"
      (let [source   (async/chan)
            json-sub (subscribe! source "foo/json" fmt/json)]
        (publish! source "foo/json" "{\"a\":1}")
        (publish! source "foo/json"  "not valid json")
        (publish! source "foo/json"  "[1, 2, 3]")

        (test-async (go
                      (is (= [{"a" 1} nil [1,2,3]]
                             (-> json-sub payloads-received <!))))))))

  (deftest subscription-chan-test5
    (testing "message: empty \"\" yields :empty? true"
      (let [source (async/chan)
            sub    (subscribe! source "+" fmt/json)]
        (publish! source "empty" "")
        (publish! source "not-empty"  "[\"valid json\"]")
        (publish! source "not-empty"  "invalid json, but still not empty?")

        (test-async (go
                      (is (= [true false false]
                             (->> sub messages-received <! (map :empty?))))))))))


(deftest format-publish-payload
  (testing "sending null-message: yields \"\""
    (let [fut #(second
                (sut/format-publish-payload {:topic "" :payload %1} (constantly "error!")))]
      (is (= "" (fut "")))
      (is (= "" (fut nil)))))

  (testing "formatting payload: success"
    (let [fut #(sut/format-publish-payload {:topic "" :payload %1} %2)]
      (is (sub? {:payload "formatted!"}
                (second (fut "please format" (constantly "formatted!")))))))

  (testing "formatting payload: error"
    (let [fut #(sut/format-publish-payload {:topic "" :payload %1} %2)]
      (is (sub? [:payload-writing-error]
                (fut "please format" #(assert false)))))
))
