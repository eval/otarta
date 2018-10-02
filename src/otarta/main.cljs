(ns otarta.main
  (:require-macros
   [cljs.core.async.macros :refer [go go-loop]])
  (:require
   [cljs.core.async :as async]
   [goog.crypt :as crypt]
   [huon.log :as log :refer [debug info warn error]]
   [otarta.core :as mqtt]
   websocket))


(set! js/WebSocket (.-w3cwebsocket websocket))


(def client (atom nil))


(defn handle-sub [broker-url topic-filter]
  (info :handle-sub :broker-url broker-url :topic-filter topic-filter)
  (go
    (reset! client (mqtt/client broker-url))

    (let [[err {sub-ch :ch}] (<! (mqtt/subscribe @client topic-filter {:format :string}))]
      (if err
        (do (error err) (println (str "Could not subscribe: " err)))
        (go-loop []
          ;; TODO dist. between empty?/verbose when printing?
          (when-let [{:keys [payload empty?] :as m} (<! sub-ch)]
            (info :message-received m)
            (println payload)
            (recur)))))))


(defn handle-pub [broker-url topic msg]
  (info :handle-pub :broker-url :topic topic :msg msg)
  (go
    (reset! client (mqtt/client broker-url))

    (let [[err _] (<! (mqtt/publish @client topic msg {:format :string}))]
      (when err
        (error err)
        (println (str "Could not publish: " err)))
      (mqtt/disconnect @client))))


(defn -main [& args]
  (let [argsv (vec args)]
    (when (= (last argsv) "-d")
      (log/set-root-level! :debug)
      (log/set-level! "otarta.octet-spec" :error)
      (log/enable!))

    (info :-main :args argsv)

    (condp = (first argsv)
      "pub" (apply handle-pub (subvec argsv 1 4))
      "sub" (apply handle-sub (subvec argsv 1 3))
      (println "Usage:\nsub: otarta.main sub <broker-url> <topic-filter> [-d]\npub: otarta.main pub <broker-url> <topic> <msg> [-d]\n"))))

(set! *main-cli-fn* -main)

(comment
  (def client (mqtt/client "ws://localhost:9001#otarta/main"))

  (def sub1 (atom nil))

  (go
    (let [[err {sub-ch :ch}] (<! (mqtt/subscribe client "a/#" {:format :json}))]
      (when sub-ch
        (println "subbed!")
        (swap! sub1
               (fn [current-sub]
                 (when current-sub
                   (async/close! current-sub))
                 sub-ch)))))

  (go-loop []
    (when-let [m (<! @sub1)]
      (prn m)
      (recur)))

  (mqtt/publish client "a/b" "transit!" {:format :transit :retain? true})

  (mqtt/disconnect client)
)
