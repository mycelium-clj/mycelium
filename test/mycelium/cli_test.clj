(ns mycelium.cli-test
  (:require [clojure.string :as str]
            [clojure.test :refer [deftest is use-fixtures]]
            [mycelium.cell :as cell]
            [mycelium.cli :as cli]))

(def test-manifest
  {:id :test/cli
   :doc "A test workflow"
   :cells {:start   {:id :test/parse
                     :doc "Parse input"
                     :schema {:input [:map [:x :int]]
                              :output [:map [:y :int]]}
                     :on-error nil}
           :process {:id :test/process
                     :doc "Process data"
                     :schema {:input [:map [:y :int]]
                              :output [:map [:z :int]]}
                     :requires [:db]
                     :on-error nil}}
   :edges {:start   {:success :process, :failure :error}
           :process {:done :end}}
   :dispatches {:start   '[[:success (fn [data] (:y data))]
                           [:failure (fn [data] (not (:y data)))]]
                :process '[[:done (constantly true)]]}})

(defn- write-manifest! [m]
  (let [f (java.io.File/createTempFile "myc-test" ".edn")]
    (spit f (pr-str m))
    (.getAbsolutePath f)))

(def valid-path (delay (write-manifest! test-manifest)))

(use-fixtures :each (fn [f] (cell/clear-registry!) (f)))

;; ===== dispatch: validate =====

(deftest validate-ok-test
  (let [{:keys [exit message]} (cli/run ["validate" @valid-path])]
    (is (zero? exit))
    (is (str/includes? message "ok"))))

(deftest validate-structural-failure-exits-1-test
  (let [path (write-manifest! (assoc-in test-manifest [:edges :start] :nonexistent))
        {:keys [exit message]} (cli/run ["validate" path])]
    (is (= 1 exit))
    (is (str/includes? message "Invalid edge target"))))

(deftest validate-parse-failure-exits-1-test
  (let [f (java.io.File/createTempFile "myc-bad" ".edn")]
    (spit f "{:id :broken :cells")
    (let [{:keys [exit message]} (cli/run ["validate" (.getAbsolutePath f)])]
      (is (= 1 exit))
      (is (str/includes? message "Read error")))))

;; ===== dispatch: hash =====

(deftest hash-is-stable-sha-256-of-canonical-edn-test
  (let [{:keys [exit message]} (cli/run ["hash" @valid-path])]
    (is (zero? exit))
    (is (re-matches #"[0-9a-f]{64}" (str/trimr message)))))

(deftest hash-is-key-order-insensitive-test
  (let [h1 (cli/run ["hash" @valid-path])
        reordered (write-manifest!
                   (into (sorted-map-by (fn [a b] (compare (str b) (str a)))) test-manifest))
        h2 (cli/run ["hash" reordered])]
    (is (= (str/trimr (:message h1)) (str/trimr (:message h2))))))

;; ===== dispatch: status =====

(deftest status-pending-cells-exit-3-test
  (let [{:keys [exit message]} (cli/run ["status" @valid-path])]
    (is (= 3 exit))
    (is (str/includes? message "0/2 passing"))
    (is (str/includes? message "pending"))
    (is (str/includes? message ":start (:test/parse)"))))

(deftest status-json-flag-test
  (let [{:keys [exit message]} (cli/run ["status" @valid-path "--json"])]
    (is (= 3 exit))
    (is (str/includes? message "\"total\": 2"))
    ;; unnamespaced keywords must not render as "/name"
    (is (str/includes? message "\"status\""))
    (is (not (str/includes? message "\"/passing\"")))
    (is (not (str/includes? message "\"/pending\"")))))

;; ===== dispatch: brief / briefs =====

(deftest brief-unknown-cell-exits-1-test
  (let [{:keys [exit message]} (cli/run ["brief" @valid-path :nope])]
    (is (= 1 exit))
    (is (str/includes? message "Cell :nope not found in manifest"))))

(deftest briefs-lists-all-cells-test
  (let [{:keys [exit message]} (cli/run ["briefs" @valid-path])]
    (is (zero? exit))
    (is (str/includes? message "## Cell: :test/parse"))
    (is (str/includes? message "## Cell: :test/process"))))

;; ===== dispatch: region =====

(deftest region-brief-known-and-unknown-test
  (let [m (assoc test-manifest :regions {:core [:start :process]})
        path (write-manifest! m)]
    (let [{:keys [exit message]} (cli/run ["region" path :core])]
      (is (zero? exit))
      (is (str/includes? message "## Region: core")))
    (let [{:keys [exit message]} (cli/run ["region" path :nope])]
      (is (= 1 exit))
      (is (str/includes? message "Unknown region :nope")))))

;; ===== dispatch: plan =====

(deftest plan-prints-scaffold-test
  (let [{:keys [exit message]} (cli/run ["plan" @valid-path])]
    (is (zero? exit))
    (is (str/includes? message "cells: :start, :process"))
    (is (str/includes? message "all cells are independent"))))

;; ===== dispatch: paths =====

(deftest paths-enumerates-all-test
  (let [{:keys [exit message]} (cli/run ["paths" @valid-path])]
    (is (zero? exit))
    (is (str/includes? message ":start --success-> :process --done-> :end"))
    (is (str/includes? message ":start --failure-> :error"))
    (is (str/includes? message "2 paths"))))

;; ===== usage errors =====

(deftest unknown-command-exits-64-with-usage-test
  (let [{:keys [exit message]} (cli/run ["frobnicate" @valid-path])]
    (is (= 64 exit))
    (is (str/includes? message "Unknown command: frobnicate"))
    (is (str/includes? message "Usage: myc <command>"))
    (is (str/includes? message "validate"))))

(deftest missing-path-exits-64-test
  (let [{:keys [exit message]} (cli/run ["brief" @valid-path])]
    (is (= 64 exit))
    (is (str/includes? message "Usage: myc brief <path> <cell-name>"))))

(deftest missing-file-exits-66-test
  (let [{:keys [exit message]} (cli/run ["validate" "/tmp/does-not-exist-myc.edn"])]
    (is (= 66 exit))
    (is (str/includes? message "No such file"))))
