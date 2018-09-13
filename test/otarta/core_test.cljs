(ns otarta.core-test
  (:require
   [cljs.test :refer [deftest is testing are]]
   [otarta.test-helpers :as helpers :refer [sub?]]
   [otarta.core :as sut]))


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

      ;; Hash
      "foo/#" "foo/bar"      true
      "foo/#" "foo/bar/moar" true
      "foo/#" "foo"          true ;; !!
      "+/b/c" "a/b/c"        true

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
