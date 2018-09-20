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
   [otarta.util :refer-macros [while-> while-not-> err2-> err->]]
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


(defn str->int8array [s]
  (.from js/Uint8Array (crypt/stringToUtf8ByteArray s)))


(let [received-packet   (fn [pkt-fn & args]
                          (->> args
                               (apply pkt-fn)
                               (pkt/encode)
                               (.-buffer)
                               (pkt/decode)))
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


(deftest gen-formatter-test
  (testing "unknown format"
    (is (= [:unkown-format nil]
           (sut/generate-payload-formatter :read :foo))))

  (testing "custom format"
    (let [fmt  (reify fmt/PayloadFormat
                 (read [_ _] "READ")
                 (write [_ _] "WRITTEN"))
          [_ rfut] (sut/generate-payload-formatter :read fmt)
          [_ wfut] (sut/generate-payload-formatter :write fmt)]
      (is (sub? [nil {:payload "READ"}]
                (rfut {:payload []})))
      (is (sub? [nil {:payload "WRITTEN"}]
                (wfut {:payload ""})))))


  (testing "bypasses requested format for :empty?"
    (let [[_ rfut] (sut/generate-payload-formatter :read :json)
          [_ wfut] (sut/generate-payload-formatter :write :json)]
      (is (sub? [nil {:payload ""}]
                (rfut {:empty?  true
                       :payload (.from js/Uint8Array #js [1 2 3 4])})))
      (is (.equals goog.object (js/Uint8Array.)
                   (:payload (second (wfut {:empty?  true
                                            :payload nil})))))))

  (testing "yields :error when formatter fails"
    (let [[_ read-json] (sut/generate-payload-formatter :read :json)
          [_ write-edn] (sut/generate-payload-formatter :write :edn)]
      (is (sub? [:format-error]
                (read-json {:payload (str->int8array "all but json")})))
      (is (sub? [:format-error]
                (write-edn {:payload #"no edn"})))
)))

#_(deftest construct-reader-test
  (let [[_ reader] (sut/construct-reader :json)]
    (is (= 1 (reader {:empty? true
                      :payload (.from js/Uint8Array #js [123 34 97 34 58 49 125])})))))

#_(deftest construct-formatter-test
  (let [[_ reader] (sut/construct-formatter :json :read)
        [_ writer] (sut/construct-formatter :json :write)
        [err _]    (sut/construct-formatter :bla :write)]
    (is (= err :bla))
    (is (= {:empty? false :payload {"a" 1}}
           (reader {:empty?  false
                    :payload (.from js/Uint8Array  #js [123,34,97,34,58,49,125])})))
    (is (= 1 (writer {:empty?  false
                      :payload {:a 1}})))))

#_(deftest construct-formatter2-test
  (let [reader1 (partial fmt/read (sut/construct-formatter2 :json :read))
        reader (sut/construct-formatter2 :json :read)
        #_#_[_ writer] (sut/construct-formatter :json :write)
        err (sut/construct-formatter2 :bla :write)]
    (is (nil? err))
    #_(is (= reader "bla"))
    (is (= {:empty? false :payload {"a" 1}}
           (reader {:empty?  false
                    :payload (.from js/Uint8Array  #js [123,34,97,34,58,49,125])})))
    #_(is (= 1 (writer {:empty?  false
                      :payload {:a 1}})))))

#_(deftest while-not-thread-first-test
  (let [foo (fn [a] [nil (inc a)])]
    (testing "it works"
      (is (= 2 (while-> 1 odd?
                          inc
                          inc)))
      (is (= 1 (while-> 1 even?
                          inc
                          inc)))
      (is (= 2 (while-not-> even?
                 1
                 inc
                 inc)))
      (is (= {:n 2 :error "OMG"}
             (while-not-> :error
               {}
               (assoc :n 1)
               (update :n inc)
               (as-> $ (update $ :m (fnil + 0) 10 (:n $)) )
               (assoc :error "OMG")
               (assoc :never :added))))
      #_(is (= 1 (-> 1 (#(inc %)))))
      (is (= [nil 3]
             (err2-> 1
                     foo
                     (#(vector nil (inc %)))))))))



#_(println (macroexpand '(err2-> 1)))
