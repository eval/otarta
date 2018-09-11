(ns otarta.core-test
  (:require
   [cljs.test :refer [deftest is testing are]]
   [otarta.test-helpers :as helpers :refer [sub?]]
   [otarta.core :as sut]))

(deftest parse-broker-url-test
  (testing "contains :ws-url"
    (are [broker-url ws-url] (sub? {:ws-url ws-url} (sut/parse-broker-url broker-url))
      "ws://host/path"      "ws://host/path"
      "ws://user@host/path" "ws://host/path")))
