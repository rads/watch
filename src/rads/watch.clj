(ns rads.watch
  (:require [babashka.cli :as cli]
            [babashka.process :refer [sh]]
            [pod.babashka.fswatcher :as fw]
            [clojure.core.async :refer [<!] :as async]
            [clojure.string :as str]
            [taoensso.timbre :as log]))

(defn print-help [_]
  (println (str/trim "
Usage: bb -x rads.watch/watch")))

(declare print-commands)

(defn- start-builder [build-events build-fn]
  (async/go-loop [i 1]
    (let [event (<! build-events)]
      (log/info [:start-build i event])
      (build-fn)
      (log/info [:end-build i event])
      (recur (inc i)))))

(defn- build-event? [{:keys [type path] :as _watch-event}]
  (and (not (#{:chmod} type))
       (not (str/ends-with? path "~"))))

(defn start-watchers [build-events]
  (loop []
    (when-let [line (read-line)]
      (fw/watch line #(async/put! build-events %) {:recursive true})
      (recur))))

(defn watch [opts]
  (prn opts)
  (if (:help opts)
    (print-help nil)
    (do
      (let [build-fn #(sh (:utility opts) {:out :inherit :err :inherit})
            build-xf (filter build-event?)
            build-events (async/chan (async/sliding-buffer 1) build-xf)]
        (start-builder build-events build-fn)
        (start-watchers build-events))
      (deref (promise)))))

(def commands
  [{:cmds ["commands"] :fn #(print-commands %)}
   {:cmds ["help"] :fn print-help}
   {:cmds [] :fn #(watch (assoc (:opts %) :utility (:args %)))}])

(defn print-commands [_]
  (println (str/join " " (keep #(first (:cmds %)) commands)))
  nil)

(defn set-logging-config! [{:keys [debug]}]
  (log/merge-config! {:min-level (if debug :debug :warn)}))

(defn -main [& _]
  (set-logging-config! (cli/parse-opts *command-line-args*))
  (cli/dispatch commands *command-line-args* {}))