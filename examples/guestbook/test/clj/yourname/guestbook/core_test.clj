(ns yourname.guestbook.core-test
  (:require [clojure.test :refer [deftest testing is]]
            [mycelium.core :as myc]
            [yourname.guestbook.workflows.home :as home]))

(deftest home-workflow-test
  (testing "home workflow renders greeting with provided name"
    (let [result (myc/run-compiled
                   home/compiled {}
                   {:http-request {:query-params {"name" "Clojure"}}})]
      (is (string? (:html result)))
      (is (re-find #"Clojure" (:html result))))))

(deftest home-workflow-default-name-test
  (testing "home workflow uses default name when none provided"
    (let [result (myc/run-compiled
                   home/compiled {}
                   {:http-request {:query-params {}}})]
      (is (string? (:html result)))
      (is (re-find #"World" (:html result))))))
