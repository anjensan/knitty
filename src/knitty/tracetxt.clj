(ns knitty.tracetxt
  "Provides functions to render and print textual representations of knitty traces."
  (:require [knitty.trace :as t]))


(def ^:private inst-format
  (java.text.SimpleDateFormat.
   "yyyy-MM-dd HH:mm:ss.SSS"))


(defn- format-inst [t]
  (.format ^java.text.SimpleDateFormat inst-format t))


(def ^:private format-dep-kind
  {:sync   ""
   :defer  "[defer]"
   :lazy   "[lazy]"
   :maybe  "[maybe]"
   :ref    "[ref]"
   :case   "[case]"})


(defn- render-tracegraph-txt [ts]
  (doseq [{:keys [at yarns tracelog] :as t} (reverse ts)]
    (let [{:keys [nodes]} (t/parse-trace t)]
      (println "at" (format-inst at)
               "yank" yarns)
      (doseq [{:keys [yarn event value]} (reverse tracelog)
              :let [n (nodes yarn)]]
        (case event
          ::t/trace-start
          (do
            (println "yanked"
                     yarn
                     (if-let [c (:caller n)]
                       (str "by " c)
                       ""))
            (doseq [[d k] (:all-deps n)]
              (println " "
                       (if (when-let [x (nodes d)] (not= (:type x) :input))
                         "call" "read")
                       d
                       (format-dep-kind k k))))
          ::t/trace-route-by
          (println "route" yarn "by" value)
          ::t/trace-call
          (if-let [e (:error n)]
            (println "fail" yarn ":" (ex-message e))
            (println "exec" yarn))
          ::t/trace-finish
          (println "done" yarn (if (:deferred n) "[defer]" ""))
          :no-match)))
    (println)))


(defn print-trace
  "Prints a textual representation of the provided trace into *out*."
  [yank-result-or-trace]
  (when-let [yank-result-or-trace (t/find-traces yank-result-or-trace)]
    (render-tracegraph-txt yank-result-or-trace)))
