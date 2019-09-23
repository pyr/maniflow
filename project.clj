(let [cfg   (clojure.edn/read-string (slurp "deps.edn"))
      deps  (for [[k {:keys [mvn/version exclusions]}] (:deps cfg)]
              [k version :exclusions exclusions])
      paths (:paths cfg)]

  (defproject spootnik/maniflow "0.1.8-SNAPSHOT"
    :description "Additional utilies on top of manifold"
    :url "https://github.com/pyr/maniflow"
    :license {:name "MIT/ISC License"
              :url  "https://github.com/pyr/maniflow/tree/master/LICENSE"}
    :codox {:source-uri "https://github.com/pyr/maniflow/blob/{version}/{filepath}#L{line}"
            :metadata   {:doc/format :markdown}
            :doc-files  ["README.md"]}
    :aliases {"kaocha" ["with-profile" "+dev" "run" "-m" "kaocha.runner"]}
    :dependencies ~deps
    :source-paths ~paths
    :deploy-repositories [["snapshots" :clojars] ["releases" :clojars]]
    :profiles {:dev {:dependencies [[lambdaisland/kaocha "0.0-529"]]
                     :plugins      [[lein-codox "0.10.7"]]}}
    :pendantic? :abort))
