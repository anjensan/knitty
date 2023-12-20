(import 'knitty.javaimpl.KnittyLoader)
(KnittyLoader/touch)

(ns knitty.javaimpl
  (:require [manifold.deferred :as md]
            [clojure.tools.logging :as log])
  (:import [knitty.javaimpl MDM KaDeferred]))


;; KdDeferred

(KaDeferred/setExceptionLogFn
 (fn log-ex [e] (log/error e "error in deferred handler")))

(definline create-kd []
  `(KaDeferred.))

(definline kd-await-all [ls ds]
  `(KaDeferred/awaitAll ~ls ~ds))

(definline kd-set-revokee [kd revokee]
  (list '.setRevokee (with-meta kd {:tag "knitty.javaimpl.KaDeferred"}) revokee))

(defmethod print-method KaDeferred [y ^java.io.Writer w]
  (.write w "#knitty/Deferred[")
  (let [error (md/error-value y ::none)
        value (md/success-value y ::none)]
    (cond
      (not (identical? error ::none))
      (do
        (.write w ":error ")
        (print-method (class error) w)
        (.write w " ")
        (print-method (ex-message error) w))
      (not (identical? value ::none))
      (do
        (.write w ":value ")
        (print-method value w))
      :else
      (.write w "…")))
  (.write w "]"))


;; MDM

(definline max-initd []
  `(MDM/maxid))

(definline keyword->intid [k]
  `(MDM/regkw ~k))

(definline mdm-fetch! [mdm kid]
  (list '.fetch (with-meta mdm {:tag "knitty.javaimpl.MDM"}) kid))

(definline mdm-freeze! [mdm]
  (list '.freeze (with-meta mdm {:tag "knitty.javaimpl.MDM"})))

(definline mdm-cancel! [mdm]
  (list '.cancel (with-meta mdm {:tag "knitty.javaimpl.MDM"})))

(definline mdm-get! [mdm kid]
  (list '.get (with-meta mdm {:tag "knitty.javaimpl.MDM"}) kid))

(definline fetch-result-claimed? [r]
  (list '.-claimed (with-meta r {:tag "knitty.javaimpl.MDM$Result"})))

(definline fetch-result-value [r]
  (list '.-value (with-meta r {:tag "knitty.javaimpl.MDM$Result"})))

(definline none? [x]
  `(MDM/isNone ~x))

(defn create-mdm [init]
  (MDM. init))