(ns otarta.core-test
  (:require-macros
   [cljs.core.async.macros :refer [go go-loop]])
  (:require
   [cljs.core.async :as async :refer [<! >! take! put! chan]]
   [cljs.test :as test :refer [deftest is testing are]]
   [goog.crypt :as crypt]
   [huon.log :as log :refer [debug info warn error]]
   [otarta.core :as sut]
   [otarta.format :as fmt :refer [PayloadFormat]]
   [otarta.packet :as pkt]
   [otarta.util :refer-macros [err-> err->>]]
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
      "ws://user:password@host/path"  {:password "password"}))

  (testing "contains :root-topic when fragment provided"
    (are [broker-url root-topic] (= root-topic (:root-topic (sut/parse-broker-url broker-url)))
      "ws://user@host/path#foo"                       "foo"
      "ws://user:some-pass@host/path#some/root/topic" "some/root/topic"
      "ws://user:some-pass@host/path#"                nil))

  (testing "assigns :default-topic-root to :root-topic when none in broker-url"
    (are [broker-url default expected] (= expected
                                          (-> broker-url
                                              (sut/parse-broker-url {:default-root-topic default})
                                              :root-topic))
      "ws://user@host/path"                           "default" "default"
      "ws://user:some-pass@host/path#some/root/topic" "default" "some/root/topic"
      "ws://user:some-pass@host/path#"                nil       nil
      "ws://user:some-pass@host/path#"                "default" "default")))


(deftest client-test
  (testing "config"
    (testing "client-id"
      (testing "when none provided has default prefix, correct chars and length [MQTT-3.1.3-5]"
        (is (re-find #"otarta[a-zA-Z0-9]{17}$"
                     (-> "ws://localhost" (sut/client) :config :client-id))))
      (testing "when prefix provided has correct chars and length"
        (is (re-find #"origin[a-zA-Z0-9]{17}$"
                     (-> "ws://localhost"
                         (sut/client {:client-id-prefix "origin"})
                         :config
                         :client-id))))
      (testing "when client-id provided it's used as-is"
        (is (= "custom-client"
             (-> "ws://localhost"
                 (sut/client {:client-id "custom-client"})
                 :config
                 :client-id)))))))


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


(defn str->uint8array [s]
  (js/Uint8Array. (crypt/stringToUtf8ByteArray s)))


(defn create-client [{source :source root-topic :root-topic}]
  {:stream (atom {:source source}) :config {:root-topic root-topic}})

(let [received-packet   (fn [pkt-fn & args]
                          (->> args
                               (apply pkt-fn)
                               (pkt/encode)
                               (.-buffer)
                               (pkt/decode)))
      publish!          (fn [source topic msg]
                          (put! source (received-packet pkt/publish
                                                        {:empty?  (= msg "")
                                                         :topic   topic
                                                         :payload (str->uint8array msg)})))
      subscribe!        #(-> %3
                             (err->> (fmt/read)
                                     (sut/subscription-chan %1 %2))
                             second)
      messages-received (fn [ch]
                          (async/close! ch)
                          (async/into [] ch))
      payloads-received #(go (map :payload (<! (messages-received %))))
      topics-received   #(go (map :topic (<! (messages-received %))))]

  (deftest subscription-chan-test0
    (testing "inactive subscribers don't block source nor active subscribers"
      (let [source       (async/chan)
            client       (create-client {:source source})
            inactive-sub (subscribe! client "foo/+" :raw)
            active-sub   (subscribe! client "foo/+" :raw)]

        (dotimes [_ 5]
          (publish! source "foo/bar" "hello"))

        (test-async (go
                      (is (= 5 (count (<! (topics-received active-sub))))))))))

  (deftest subscription-chan-test2
    (testing "receive messages according to topic-filter"
      (let [source      (async/chan)
            client      (create-client {:source source})
            foo-sub     (subscribe! client "foo/+" :raw)
            not-foo-sub (subscribe! client "not-foo/#" :raw)]
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

    (testing "root-topic of client are not part of the received topics"
      (let [source  (async/chan)
            client  (create-client {:source source :root-topic "root"})
            foo-sub (subscribe! client "root/foo/+" :raw)]
        (publish! source "root/foo/bar" "for foo")
        (publish! source "root/foo/baz" "for foo")

        (test-async (go
                      (is (= ["foo/bar" "foo/baz"]
                             (<! (topics-received foo-sub)))))))))

  (deftest subscription-chan-test2b
    (testing "similar subs both receive messages"
      (let [source            (async/chan)
            client            (create-client {:source source})
            foo1-sub          (subscribe! client "foo/+" :raw)
            foo2-sub          (subscribe! client "foo/+" :raw)
            not-listening-sub (subscribe! client "foo/+" :raw)]
        (publish! source "foo/bar"      "for foo")
        (publish! source "foo/baz"      "foo foo")

        (test-async (go
                      (is (= ["foo/bar" "foo/baz"]
                             (<! (topics-received foo1-sub))))))
        (test-async (go
                      (is (= ["foo/bar" "foo/baz"]
                             (<! (topics-received foo2-sub)))))))))


  (deftest subscription-chan-test3
    (testing "payload-formatter is applied"
      (let [source      (async/chan)
            client      (create-client {:source source})
            string-sub  (subscribe! client "foo/string" :string)
            json-sub    (subscribe! client "foo/json" :json)
            edn-sub     (subscribe! client "foo/edn" :edn)
            transit-sub (subscribe! client "foo/transit" :transit)]
        (publish! source "foo/string" "just a string")
        (publish! source "foo/json" "{\"a\":1}")
        (publish! source "foo/edn"  "[1 #_2 3]")
        (publish! source "foo/transit" "[\"^ \",\"~:a\",1]")

        (test-async (go
                      (is (= ["just a string"]
                             (-> string-sub payloads-received <!)))))
        (test-async (go
                      (is (= [{"a" 1}]
                             (-> json-sub payloads-received <!)))))
        (test-async (go
                      (is (= [[1 3]]
                             (-> edn-sub payloads-received <!)))))
        (test-async (go
                      (is (= [{:a 1}]
                             (-> transit-sub payloads-received <!))))))))

  (deftest subscription-chan-test4
    (testing "messages with payloads that fail the formatter are not received"
      (let [source (async/chan)
            client (create-client {:source source})
            sub    (subscribe! client "foo/json" :json)]
        (publish! source "foo/json" "invalid json")
        (publish! source "foo/json" "[\"valid json\"]")

        (test-async (go
                      (is (= 1
                             (count (-> sub payloads-received <!)))))))))


  (deftest subscription-chan-test5
    (testing "message: empty \"\" yields :empty? true"
      (let [source (async/chan)
            client (create-client {:source source})
            sub    (subscribe! client "+" :json)]
        (publish! source "empty" "")
        (publish! source "not-empty"  "[\"valid json\"]")

        (test-async (go
                      (is (= [true false]
                             (->> sub messages-received <! (map :empty?))))))))))
