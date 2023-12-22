(import 'knitty.javaimpl.KnittyLoader)
(KnittyLoader/touch)

(ns knitty.javaimpl
  (:require [manifold.deferred :as md]
            [clojure.tools.logging :as log])
  (:import [knitty.javaimpl MDM KDeferred KDeferredAwaiter]))


;; KdDeferred

(KDeferred/setExceptionLogFn
 (fn log-ex [e] (log/error e "error in deferred handler")))

(defmacro create-kd
  ([] `(KDeferred.))
  ([token] `(KDeferred. ~token)))

(defmacro kd-await-coll [ls ds]
  `(KDeferredAwaiter/awaitColl ~ls ~ds))

(defmacro kd-await [ls & ds]
  `(KDeferredAwaiter/await ~ls ~@ds))

(definline kd-set-revokee [kd revokee]
  `(let [^KDeferred x# ~kd] (.setRevokee x# ~revokee)))

(definline kd-claim [kd token]
  `(let [^KDeferred x# ~kd] (.claim x# ~token)))

(definline kd-chain-from [kd d token]
  `(let [^KDeferred x# ~kd] (.chainFrom x# ~d ~token)))

(definline kd-unwrap [kd]
  `(let [^KDeferred x# ~kd] (.unwrap x#)))


(defmethod print-method KDeferred [y ^java.io.Writer w]
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

(definline mdm-fetch! [mdm kw kid]
  (list '.fetch (with-meta mdm {:tag "knitty.javaimpl.MDM"}) kw kid))

(definline mdm-freeze! [mdm]
  (list '.freeze (with-meta mdm {:tag "knitty.javaimpl.MDM"})))

(definline mdm-cancel! [mdm token]
  (list '.cancel (with-meta mdm {:tag "knitty.javaimpl.MDM"}) token))

(definline mdm-get! [mdm kw kid]
  (list '.get (with-meta mdm {:tag "knitty.javaimpl.MDM"}) kw kid))

(defn create-mdm [init]
  (MDM. init))
