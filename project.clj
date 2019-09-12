(defproject spootnik/maniflow "0.1.0"
  :description "Additional utilies on top of manifold"
  :url "https://github.com/pyr/maniflow"
  :license {:name "MIT/ISC License"
            :url  "https://github.com/pyr/maniflow/tree/master/LICENSE"}
  :dependencies [[org.clojure/clojure "1.10.1"]
                 [spootnik/commons    "0.3.1"]
                 [manifold            "0.1.8"]]
  :deploy-repositories [["snapshots" :clojars] ["releases" :clojars]]
  :pendantic? :abort)
