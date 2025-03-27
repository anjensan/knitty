(ns knitty.manifold.fake-md
  (:refer-clojure :exclude [future future-call loop])
  (:require
   [clojure.test :refer [testing]]
   [clojure.tools.logging :as log]
   [clojure.tools.logging.test :as log-test]
   [knitty.deferred :as kd]
   [manifold.deferred :as md])
  (:import
   [clojure.lang IDeref]
   [java.util.concurrent CompletionStage]))

(defn chain [x & fs]
  (kd/chain* x fs))

(defn catch
  ([x ef] (kd/bind-err x ef))
  ([x ex ef] (kd/bind-err x ex ef)))

(defn chain' [x & fs]
  (kd/chain* x fs))

(defn catch'
  ([x ef] (kd/bind-err x ef))
  ([x ex ef] (kd/bind-err x ex ef)))

(defn finally [x f]
  (kd/bind-fnl x f))

(defn ->deferred [x]
  (kd/wrap* x))

(defn connect [x y]
  (kd/connect x y))

(defn alt [& xs]
  (apply kd/alt xs))

(defn zip [& xs]
  (apply kd/zip xs))

(defn on-realized
  [x on-success on-error]
  (kd/on x on-success on-error))

(defn claim! [d]
  (kd/claim! d))

(defn add-listener!
  [d ls]
  (md/add-listener! d ls))

(defn cancel-listener! [d ls]
  (md/cancel-listener! d ls))

(defn listener [xf ef]
  (md/listener xf ef))

(defn error-deferred [x]
  (kd/wrap-err x))

(defn success-deferred [x]
  (kd/wrap-val x))

(defn deferred []
  (kd/create))

(defn error!
  ([d x] (kd/error! d x))
  ([d x t] (kd/error! d x t)))

(defn success!
  ([d x] (kd/success! d x))
  ([d x t] (kd/success! d x t)))

(defn deferred? [d]
  (kd/deferred? d))


(def ^:dynamic macroses-mode :knitty)

(defmacro future [& body]
  `(case macroses-mode
     :knitty   (kd/future (kd/wrap-val (do ~@body)))
     :manifold (md/future ~@body)))

(defmacro loop [& rs]
  `(case macroses-mode
     :knitty (kd/loop ~@rs)
     :manifold (md/loop ~@rs)))

(defmacro recur [& rs]
  `(case macroses-mode
     :knitty (kd/recur ~@rs)
     :manifold (md/recur ~@rs)))

;; == modes

(def mode-rebinds (atom {:knitty {}}))

(defmacro reg-mode [name & {:as binds}]
  `(swap! mode-rebinds assoc ~name ~binds))

(defn with-modes-fixture []
  (fn [t]
    (doseq [[k bm] @mode-rebinds]
        (testing (str "mode" k)
          (with-redefs-fn bm t))
      )))

(def manifold-redefs
  {#'chain            #'md/chain
   #'catch            #'md/catch
   #'chain'           #'md/chain'
   #'catch'           #'md/catch'
   #'finally          #'md/finally
   #'->deferred       #'md/->deferred
   #'connect          #'md/connect
   #'alt              #'md/alt
   #'zip              #'md/zip
   #'on-realized      #'md/on-realized
   #'claim!           #'md/claim!
   #'add-listener!    #'md/add-listener!
   #'cancel-listener! #'md/cancel-listener!
   #'listener         #'md/listener
   #'error-deferred   #'md/error-deferred
   #'success-deferred #'md/success-deferred
   #'deferred         #'md/deferred
   #'error!           #'md/error!
   #'success!         #'md/success!
   #'deferred?        #'md/deferred?
   #'macroses-mode    :manifold})

(reg-mode
 :manifold->knitty
 #'success-deferred #'md/success-deferred
 #'error-deferred   #'md/error-deferred
 #'deferred         #'md/deferred)

(reg-mode
 :manifold
 manifold-redefs)

(reg-mode
 :knitty->manifold
 (->
  manifold-redefs
  (dissoc #'success-deferred
          #'error-deferred
          #'deferred
          #'macroses-mode)))

;; == quirks

(defn fixup-promesa-print-method-test []
  ;; fixture for 'promesa-print-method-test
  (fn [t]
    (let [mm ^clojure.lang.MultiFn print-method
          m (-> print-method methods (get CompletionStage))]
      (try
        (doto mm
          (.removeMethod CompletionStage)
          (prefer-method CompletionStage IDeref))
        (t)
        (finally
          (when m (.addMethod mm CompletionStage m)))))))


(defn fixup-count-logged-errors []
  (fn [t]
    (log-test/with-log
      (with-redefs [log-test/*stateful-log* log-test/*stateful-log*
                    log/*logger-factory*    log/*logger-factory*]
        (t)
        (System/gc)
        (Thread/sleep 50)))))
