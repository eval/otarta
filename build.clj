(require '[cljs.build.api :as api]
         '[clojure.string :as string]
         '[figwheel-sidecar.repl-api :as figwheel]
         '[figwheel-sidecar.components.nrepl-server :as figwheel.nrepl])

(def source-dir "src")

(def compiler-config
  {:main          'otarta.core
   :output-to     "target/app.js"
   :source-map    "target/app.js.map"
   :output-dir    "target/out"
   :target        :nodejs
   :optimizations :simple
   :npm-deps      {:websocket "1.0.26"}
   :install-deps  true})

(def dev-config
  (merge compiler-config
         {:optimizations :none
          :source-map    true}))

(def nrepl-options
  {:nrepl-port       7890
   :nrepl-middleware ["cider.nrepl/cider-middleware"
                      "cider.piggieback/wrap-cljs-repl"]})

(def ensure-nrepl-port! #(spit ".nrepl-port" (:nrepl-port nrepl-options)))

(def figwheel-options
  {:figwheel-options nrepl-options
   :all-builds       [{:id           "dev"
                       :figwheel     true
                       :source-paths [source-dir]
                       :compiler     dev-config}]})

;;; Tasks --------------------------------------------------------------------------------

(defmulti task first)

(defmethod task :default [_]
  (task ["repl"]))

(defmethod task "compile" [_]
  (api/build source-dir compiler-config))

(defmethod task "compile-watch" [_]
  (api/watch source-dir compiler-config))

(defmethod task "repl" [_]
  (ensure-nrepl-port!)
  (figwheel.nrepl/start-nrepl-server nrepl-options nil)
  (println "Started nREPL server on port:" (:nrepl-port nrepl-options)))

(defmethod task "figwheel" [_]
  (ensure-nrepl-port!)
  (figwheel/start-figwheel! figwheel-options))

(task *command-line-args*)
