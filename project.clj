(defproject com.github.anjensan/knitty "0.7.0-alpha2-SNAPSHOT"
  :description "Knitty is a library for a DAG-based processing of asynchronous data"
  :url "https://github.com/anjensan/knitty"
  :license {:name "MIT License" :url "http://opensource.org/licenses/MIT"}

  :dependencies [[org.clojure/clojure "1.12.0"]
                 [manifold/manifold "0.4.3"]
                 [org.hdrhistogram/HdrHistogram "2.2.2"]
                 [macroz/tangle "0.2.2"]]

  :plugins [[lein-aot-filter "0.1.0"]
            [lein-shell "0.5.0"]]

  :aot-include [#"knitty\.javaimpl\..*"]
  :java-source-paths ["src-java"]
  :javac-options ["--release" "17"]
  :jvm-opts ["-server" "-XX:-OmitStackTraceInFastThrow" "-Xmx4g" "-Xss16m"]

  :source-paths ["src"]
  :global-vars {*warn-on-reflection* true}

  :aliases {"bump-version" ["change" "version" "leiningen.release/bump-version"]}

  :release-tasks [["vcs" "assert-committed"]
                  ["change" "version" "leiningen.release/bump-version" "release"]
                  ["vcs" "commit"]
                  ["vcs" "tag" "v" "--no-sign"]
                  ["deploy"]]

  :repositories [["releases" {:url "https://clojars.org/repo"
                              :username :env
                              :sign-releases false
                              :password :env}]]

  :profiles {:dev {:dependencies [[criterium/criterium "0.4.6"]
                                  [prismatic/plumbing "0.6.0"]
                                  [clj-kondo/clj-kondo "2025.04.07"]]
                   :injections [(require 'knitty.test-util)]}
             :prof {:jvm-opts ["-Djdk.attach.allowAttachSelf" "-XX:+UnlockDiagnosticVMOptions" "-XX:+DebugNonSafepoints"]
                    :dependencies [[com.clojure-goes-fast/clj-async-profiler "1.6.2"]]}}

  :prep-tasks [["javac"]
               ["compile"]
               ["aot-filter"]
               ["shell" "find" "target/classes" "-type" "d" "-empty" "-delete"]]

  :test-selectors {:default #(not (some #{:benchmark :stress} (cons (:tag %) (keys %))))
                   :benchmark :benchmark
                   :stress #(or (:stress %) (= :stress (:tag %)))
                   :all (constantly true)})
