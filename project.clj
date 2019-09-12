(defproject spootnik/maniflow "0.1.2"
  :description "Additional utilies on top of manifold"
  :url "https://github.com/pyr/maniflow"
  :license {:name "MIT/ISC License"
            :url  "https://github.com/pyr/maniflow/tree/master/LICENSE"}
  :plugins [[lein-codox "0.10.7"]]
  :codox {:source-uri "https://github.com/pyr/maniflow/blob/{version}/{filepath}#L{line}"
          :metadata {:doc/format :markdown}
          :doc-files ["README.md"]}
  :aliases {"kaocha" ["with-profile" "+dev" "run" "-m" "kaocha.runner"]}
  :dependencies [[org.clojure/clojure "1.10.1"]
                 [spootnik/commons    "0.3.2"]
                 [manifold            "0.1.8"]]
  :deploy-repositories [["snapshots" :clojars] ["releases" :clojars]]
  :profiles {:dev {:dependencies [[lambdaisland/kaocha        "0.0-529"]]}}
  :pendantic? :abort)
