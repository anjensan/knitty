(ns knitty.traceviz
  "Provides functions for visualizing yarn execution traces."
  (:require [knitty.trace :as t]
            [clojure.datafy :refer [datafy]]
            [clojure.java.browse :as browse]
            [clojure.java.io :as io]
            [clojure.java.shell :as shell]
            [clojure.pprint :as pp]
            [clojure.string :as str]
            [tangle.core :as tgl]
            [clojure.math :as math]))


(def ^:dynamic *options*
  "Default options for trace visualization.

  - :format       - Output format (:auto, :raw, :edn, :dot, :xdot, :svg, :png, etc.)
  - :dpi          - Rendering DPI.
  - :width        - Maximum width (in characters) for printed node labels.
  - :lines        - Maximum number of lines printed for each label.
  - :pp-max-len   - Maximum number of items to print in sequences.
  - :pp-max-level - Maximum depth for pretty-printing nested collections.
  - :clusters     - Boolean indicating whether to group nodes in clusters.
  - :concentrate  - Boolean to merge/concentrate graph edges.
  - :hspace       - Horizontal spacing for graph layout.
  - :vspace       - Vertical spacing for graph layout.
  - :show-unused  - Boolean indicating whether to show unused nodes.
  "
  {:format :auto
   :dpi 120
   :width 150
   :lines 32
   :pp-max-len 20
   :pp-max-level 6
   :clusters true
   :concentrate false
   :hspace 0.5
   :vspace 1.5
   :show-unused false})


(defn- graphviz-escape [s]
  (if s
    (str/escape
     s
     {\< "&lt;", \> "&gt;", \& "&amp;"})
    ""))


(defn- short-string [n s]
  (graphviz-escape
   (if (> (count s) n)
     (str (subs s 0 (- n 1)) "…")
     s)))


(defn- limit-lines [n ss]
  (concat
   (take n ss)
   (when (> (count ss) n) ["…"])))


(defn- short-string-multiline [s]
  (binding  [*print-length* (:lines *options*)
             *print-level* (:pp-max-level *options*)
             pp/*print-lines* (inc (:lines *options*))
             pp/*print-right-margin* (:width *options*)]
    (->>
     (with-out-str (pp/pprint s))
     (str/split-lines)
     (limit-lines (:lines *options*))
     (map #(short-string (:width *options*) %))
     (map graphviz-escape)
     (str/join "<BR ALIGN=\"LEFT\"/>")
     )))


(defn- safe-minus [a b]
  (when (and a b (not (zero? a)) (not (zero? b)))
    (- a b)))


(defn p3-round [x]
  (let [p (Math/ceil (Math/log10 x))
        b (Math/pow 10.0 (- p 3.0))]
    (-> x (/ b) (Math/round) (* b) (float))))


(defn- nice-time [t]
  (when t
    (let [ms (p3-round (* t 1e-6))
          s (format "%.5f" ms)
          [_ s] (re-matches #"(.*\..+?)0*" s)    ;; strip trailing 0s
          [_ s] (re-matches #"(.*?)(?:\.0)?" s)  ;; strip ".0"
          ]
      (str s "㎳"))))


(defn- render-tracegraph-dot [g]
  (let [show-unused (:show-unused *options* false)
        gtime (:time g)]
  (tgl/graph->dot

   ;; nodes
   (for [[k v] (sort-by #(some-> % second :start-at -) (:nodes g))
         :when (or show-unused (not= (:type v) :lazy-unused))]
     (assoc v :id k))

   ;; edges
   (sort-by
    (fn [[_ c]] (some-> c :time -))
    (for [[[a b] c] (:links g)
          :when (or show-unused
                    (and (not= (:type c) :maybe)
                         (:used c)))]
      [(str (:yankid-dst c) "$" b)
       (str (:yankid-src c) "$" a)
       c]))

   ;; tangle.core options
   {:directed? true

    :node->id
    (fn [{:keys [id yankid]}]
      (str yankid "$" id))

    :graph {:dpi (:dpi *options*)
            :rankdir :TB
            :ranksep (:vspace *options*)
            :nodesep (:hspace *options*)
            :concentrate (:concentrate *options*)
            :mclimit 4
            :newrank true}

    :node->cluster
    (when (:clusters *options*)
      (fn [{:keys [yankid]}]
        (str yankid)))

    :cluster->descriptor
    (fn [c]
      (let [x (-> (Long/valueOf ^String c))
            sg (-> g :clusters (get x))]
        {:label (str
                 (nice-time (or (:shift sg) 0))
                 " ⊕ "
                 "Δ" (nice-time (:time sg))
                 " ⟹ "
                 (nice-time (safe-minus (:done-at sg) (:base-at g))))
         :style "dashed"
         :color "gray"
         :labelloc "b"
         :labeljust "r"
         :fontsize  10}))

    :node->descriptor
    (fn [{:keys [type deferred id value thread error
                 start-at deps-time func-time finish-at]}]
      {:shape :rect
       :color "silver"
       :style  (cond
                 (= :lazy-unused type) "dotted,filled"
                 (= :input type)       "dotted,filled"
                 (= :yanked type)      "bold,filled"
                 deferred              "rounded,filled"
                 :else                 "solid,filled")
       :fillcolor (cond
                    error                   "lightpink"
                    (= :lazy-unused type)   "lightcyan"
                    (= :leaked type)        "violet"
                    (= :input type)         "skyblue"
                    (= :changed-input type) "lightpink"
                    (= :yanked type)        "lightgreen"
                    (= :knot type)          "lightgray"
                    deferred                "navajowhite"
                    :else                   "lemonchiffon")
       :label [:font
               {:face "monospace" :point-size 7, :color "darkslategray"}
               (cond-> [:table {:border 0}]
                 true
                 (conj
                  [:tr [:td {:colspan 2}
                        [:font {:face "monospace bold"
                                :point-size 10
                                :color "black"}
                         (graphviz-escape (str id))]]])

                 (not (#{:lazy-unused :leaked :knot} type))
                 (conj

                  [:tr [:td {:colspan 2, :align "text"}
                        (if error
                          [:font {:face "monospace bold"
                                  :point-size 7
                                  :color "blue"}
                           "exception: " (-> error class (.getName))
                           [:br {:align "left"}]

                           (when-let [m (ex-message error)]
                             [:font "message: " (short-string-multiline m)
                              [:br {:align "left"}]])

                           (when-let [ed (ex-data error)]
                             [:font
                              "ex-data:"
                              [:br {:align "left"}]
                              (short-string-multiline (datafy ed))])]

                          [:font {:point-size 8
                                  :face "monospace",
                                  :color "blue"}
                           (short-string-multiline (datafy value))])


                        [:br {:align :left}]]])

                 (#{:lazy-unused} type)
                 (conj [:tr [:td {:colspan 2} "unused"]])

                 (#{:leaked} type)
                 (conj [:tr [:td {:colspan 2} "leaked"]])

                 (#{:yanked :interim :leaked} type)
                 (conj
                  [:tr [:td {:align :left}
                        (if deferred
                          "thread*"
                          "thread")]
                   [:td {:align :right} (short-string 40 thread)]]
                  [:tr [:td {:align :left} "time"]
                   [:td {:align :right}
                    (nice-time (safe-minus start-at (:base-at g)))
                    " ⊕ "
                    "Δ" (or (nice-time deps-time) "…")
                    " ⊎ "
                    "Δ" (or (nice-time func-time) "…")
                    (when finish-at " ⟹ ")
                    (when finish-at
                      [:font {:color "black"}
                       (nice-time (safe-minus finish-at (:base-at g)))])]]))]})

    :edge->descriptor
    (fn [_ _ {:keys [source type used cause timex]}]
      {:label     (or
                   (when (and timex (pos? timex) (not= type :ref))
                     (str "+" (nice-time timex)))
                   "")
       :fontsize  8
       :dir       "both"
       :color     (if cause "black" "dimgrey")
       :arrowsize "0.7"
       :arrowtail (cond
                    (#{:ref} type)                  "none"
                    (#{:sync} source)               "normal"
                    (#{:defer :input-defer} source) "empty"
                    (#{:input} source)              "vee"
                    (#{:changed-input} type)        "none"
                    :else                           "normal")
       :arrowhead (cond
                    (= :ref type)            "none"
                    (= :sync type)           "none"
                    (= :defer type)          "dot"
                    (= :maybe type)          "odot"
                    (= :route type)          "invempty"
                    (= :lazy type)           "diamond"
                    (= :changed-input type)  "none"
                    :else                    "none")
       :constraint (not (#{:maybe :changed-input} type))
       :weight (if (and gtime timex)
                 (int (- 1 (math/log (/ timex gtime))))
                 1)

       :style (cond
                (not used)              "dotted"
                (= type :maybe)         "dashed"
                cause                   "bold"
                (= type :changed-input) "dotted"
                :else                   "solid")})})))


(defn- fix-xdot-escapes [d]
  ;; slow workaround for https://gitlab.com/graphviz/graphviz/-/issues/165
  (str/replace d "\\" "⧵"))


(defn- maybe-parse-traces
  [x]
  (or
   (when (and (map? x)
              (= :knitty/parsed-trace (:type x)))
     x)
   (some-> x
           (t/find-traces)
           (->> (map t/parse-trace))
           (t/merge-parsed-traces))))


(defn render-trace
  "Renders the provided trace data into a visual representation.

  Supports multiple output formats based on the :format option in *options*:
  - :raw   - the parsed trace data as is.
  - :edn   - returns an EDN-formatted string of the parsed trace.
  - :dot   - returns the trace graph in Graphviz dot format.
  - :xdot  - returns the dot format with xdot-specific escapes fixed.
  - :svg, :png, etc. - returns an image rendered from the trace graph.

  Additional options can be provided to override *options*.
  "
  [yank-result-or-trace & {:as options}]
  (binding [*options* (into *options* options)]
    (when-let [g (maybe-parse-traces yank-result-or-trace)]
      (case (:format *options*)
        :raw g
        :edn (pr-str g)
        :dot (render-tracegraph-dot g)
        :xdot (fix-xdot-escapes (render-tracegraph-dot g))
        :svg (tgl/dot->svg (render-tracegraph-dot g))
        :png (tgl/dot->image (render-tracegraph-dot g) "png")
        (tgl/dot->image (render-tracegraph-dot g) (name (:format *options*)))))))


(def xdot-available
  "A delayed flag indicating whether xdot is available on the system."
  (delay
   (zero? (:exit (shell/sh "python" "-m" "xdot" "--help")))))


(def graphviz-available
  "A delayed flag indicating whether Graphviz (dot) is available on the system."
  (delay
   (zero? (:exit (shell/sh "dot" "-V")))))


(defn- open-rendered-trace [yank-result-or-trace options open-f]
  (let [t (apply render-trace yank-result-or-trace (mapcat identity options))
        f (java.io.File/createTempFile
           "knitty-"
           (str "." (name (:format options))))]
    (when-not t
      (throw (ex-info "trace not found" {::yank-result yank-result-or-trace})))
    (io/copy t f)
    (future (open-f (str f)))))


(defn view-trace
  "Opens a visual representation of the trace data using an external viewer.

  Based on the specified :format, the function works as follows:
  - If the format is :xdot (or :auto with xdot available), it renders the trace in xdot format and opens it using xdot.
  - If an image format (e.g. :svg, :png, etc.) is specified it renders the trace and opens it using the default browser.
  - If the format is :edn or :raw, it renders the trace in EDN format and opens it with the default viewer.
  "
  [yank-result-or-trace & {:as options}]
  (let [options (merge *options* options)
        f (:format options)
        auto (= :auto f)]
    (cond

      (or
       (= :xdot f)
       (and auto
            (force xdot-available)
            (force graphviz-available)))
      (open-rendered-trace yank-result-or-trace
                           (assoc options :format :xdot)
                           #(shell/sh "xdot" %))

      (or
       (#{:svg :svgz :png :pdf :webp :json} f)
       (and auto (force graphviz-available)))
      (open-rendered-trace yank-result-or-trace options browse/browse-url)

      (#{:auto :edn :raw} f)
      (open-rendered-trace yank-result-or-trace (assoc options :format :edn)
                           browse/browse-url)

      :else
      (throw (ex-info (str "unavailable traceviz format " f)
                      {:format f
                       :graphviz-installed (force graphviz-available)
                       :xdot-installed (force xdot-available)})))))
