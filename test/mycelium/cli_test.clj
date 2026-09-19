(ns mycelium.cli-test
  (:require [clojure.data.json :as json]
            [clojure.string :as str]
            [clojure.test :refer [deftest is use-fixtures]]
            [mycelium.cell :as cell]
            [mycelium.cli :as cli]
            [mycelium.manifest :as manifest]))

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
    (is (str/includes? message "Invalid manifest"))
    (is (str/includes? message "Invalid edge target"))))

(deftest validate-parse-failure-exits-1-test
  (let [f (java.io.File/createTempFile "myc-bad" ".edn")]
    (spit f "{:id :broken :cells")
    (let [{:keys [exit message]} (cli/run ["validate" (.getAbsolutePath f)])]
      (is (= 1 exit))
      (is (str/includes? message "Read error")))))

(deftest validate-is-strict-by-default-test
  ;; matches load-manifest's default: a cell without :on-error fails
  (let [path (write-manifest! (update-in test-manifest [:cells :process] dissoc :on-error))
        strict  (cli/run ["validate" path])
        lenient (cli/run ["validate" path "--lenient"])]
    (is (= 1 (:exit strict)))
    (is (str/includes? (:message strict) "missing :on-error"))
    (is (zero? (:exit lenient)) (:message lenient))))

(deftest other-commands-stay-lenient-test
  (let [path (write-manifest! (update-in test-manifest [:cells :process] dissoc :on-error))]
    (is (zero? (:exit (cli/run ["paths" path]))))
    (is (zero? (:exit (cli/run ["brief" path "start"]))))))

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

(deftest hash-is-over-raw-file-not-expanded-form-test
  ;; a :pipeline manifest and its expanded :edges twin are different files
  (let [pm {:id :t/p
            :cells {:start {:id :t/a :doc "a" :schema {:input [:map] :output [:map]} :on-error nil}}
            :pipeline [:start]}
        em (-> pm (dissoc :pipeline) (assoc :edges {:start :end} :dispatches {}))]
    (is (not= (:message (cli/run ["hash" (write-manifest! pm)]))
              (:message (cli/run ["hash" (write-manifest! em)]))))))

;; ===== dispatch: status =====

(deftest status-pending-cells-exit-3-test
  (let [{:keys [exit message]} (cli/run ["status" @valid-path])]
    (is (= 3 exit))
    (is (str/includes? message "0/2 passing"))
    (is (str/includes? message "pending"))
    (is (str/includes? message ":start (:test/parse)"))))

(deftest status-json-flag-test
  (let [{:keys [exit message]} (cli/run ["status" @valid-path "--json"])
        parsed (json/read-str message)]
    (is (= 3 exit))
    (is (= false (get parsed "ok")))
    (is (= 3 (get parsed "exit")))
    (is (= 2 (get parsed "total")))
    ;; keywords render as plain strings, namespaced ones keep the namespace
    (is (= "pending" (get-in parsed ["cells" 0 "status"])))
    (is (= "test/parse" (get-in parsed ["cells" 0 "id"])))))

;; ===== --json envelope =====

(deftest json-envelope-on-success-and-failure-test
  (let [ok  (json/read-str (:message (cli/run ["validate" @valid-path "--json"])))
        bad (json/read-str (:message (cli/run ["validate" (write-manifest! (assoc-in test-manifest [:edges :start] :nope)) "--json"])))
        h   (json/read-str (:message (cli/run ["hash" @valid-path "--json"])))]
    (is (= true (get ok "ok")))
    (is (= "test/cli" (get ok "id")))
    (is (= false (get bad "ok")))
    (is (= 1 (get bad "exit")))
    (is (str/includes? (get bad "error") "Invalid edge target"))
    (is (re-matches #"[0-9a-f]{64}" (get h "hash")))))

(deftest json-encodes-non-json-values-safely-test
  ;; control characters, sets, and namespaced keywords must produce valid JSON
  (let [enc (var-get #'cli/->json)
        out (enc {:a "x\u0001y" :b #{1} :c :ns/kw :d '(fn [d] (:y d)) 5 :five})
        parsed (json/read-str out)]
    (is (= "x\u0001y" (get parsed "a")))
    (is (= [1] (get parsed "b")))
    (is (= "ns/kw" (get parsed "c")))
    (is (string? (get parsed "d")))
    (is (contains? parsed "5"))))

;; ===== dispatch: brief / briefs =====

(deftest brief-unknown-cell-exits-1-test
  (let [{:keys [exit message]} (cli/run ["brief" @valid-path "nope"])]
    (is (= 1 exit))
    (is (str/includes? message "Cell :nope not found in manifest"))))

(deftest brief-accepts-colon-prefixed-cell-name-test
  ;; status/paths print :start — copying that back must work
  (let [{:keys [exit message]} (cli/run ["brief" @valid-path ":start"])]
    (is (zero? exit) message)
    (is (str/includes? message "## Cell: :test/parse"))))

(deftest briefs-lists-all-cells-test
  (let [{:keys [exit message]} (cli/run ["briefs" @valid-path])]
    (is (zero? exit))
    (is (str/includes? message "## Cell: :test/parse"))
    (is (str/includes? message "## Cell: :test/process"))))

;; ===== dispatch: region =====

(deftest region-brief-known-and-unknown-test
  (let [m (assoc test-manifest :regions {:core [:start :process]})
        path (write-manifest! m)]
    (let [{:keys [exit message]} (cli/run ["region" path ":core"])]
      (is (zero? exit))
      (is (str/includes? message "## Region: core")))
    (let [{:keys [exit message]} (cli/run ["region" path "nope"])]
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

;; ===== flag parsing =====

(deftest valued-flags-do-not-leak-into-positionals-test
  ;; --require's value must not be mistaken for the path or the cell
  (let [before (cli/run ["status" "--require" "clojure.set" @valid-path])
        middle (cli/run ["brief" @valid-path "--require" "clojure.set" "start"])]
    (is (= 3 (:exit before)) (:message before))
    (is (zero? (:exit middle)) (:message middle))
    (is (str/includes? (:message middle) "## Cell: :test/parse"))))

(deftest require-missing-namespace-exits-1-test
  (let [{:keys [exit message]} (cli/run ["status" @valid-path "--require" "no.such.ns"])]
    (is (= 1 exit))
    (is (str/includes? message "Could not require no.such.ns"))))

(deftest valued-flag-without-value-is-usage-error-test
  (let [{:keys [exit message]} (cli/run ["status" @valid-path "--require"])]
    (is (= 64 exit))
    (is (str/includes? message "--require"))))

;; ===== usage errors =====

(deftest unknown-command-exits-64-with-usage-test
  (let [{:keys [exit message]} (cli/run ["frobnicate" @valid-path])]
    (is (= 64 exit))
    (is (str/includes? message "Unknown command: frobnicate"))
    (is (str/includes? message "Usage: myc <command>"))
    (is (str/includes? message "validate"))))

(deftest missing-cell-arg-exits-64-test
  (let [{:keys [exit message]} (cli/run ["brief" @valid-path])]
    (is (= 64 exit))
    (is (str/includes? message "Usage: myc brief <path> <cell-name>"))))

(deftest missing-path-exits-64-test
  (let [{:keys [exit message]} (cli/run ["validate"])]
    (is (= 64 exit))
    (is (str/includes? message "Usage: myc validate <manifest-path>"))))

(deftest missing-file-exits-66-test
  (let [{:keys [exit message]} (cli/run ["validate" "/tmp/does-not-exist-myc.edn"])]
    (is (= 66 exit))
    (is (str/includes? message "No such file"))))

;; ===== fragment resolution relative to the manifest =====

(deftest manifest-dir-is-absolute-even-for-bare-filenames-test
  (let [dir (manifest/manifest-dir "todo-delete.edn")]
    (is (some? dir))
    (is (.isAbsolute (java.io.File. ^String dir)))))

;; ===== dispatch: test (one cell in isolation) =====

(def fixture-manifest
  {:id :test/fixture
   :cells {:start  {:id :fixture/double :doc "double x" :schema {:input [:map [:x :int]] :output [:map [:y :int]]} :on-error nil}
           :broken {:id :fixture/broken :doc "returns a string" :schema {:input [:map [:x :int]] :output [:map [:y :int]]} :on-error nil}}
   :edges {:start {:big :broken, :small :end}
           :broken :end}
   :dispatches {:start '[[:big (fn [d] (> (:y d) 10))]
                         [:small (fn [d] (<= (:y d) 10))]]}})

(defn- load-fixture-cells! []
  ;; the :each fixture clears the registry and `require` is a no-op once loaded
  (require 'mycelium.cli-fixture-cells :reload))

(deftest test-command-runs-one-cell-with-given-input-test
  (load-fixture-cells!)
  (let [path (write-manifest! fixture-manifest)
        {:keys [exit message]} (cli/run ["test" path "start" "--input" "{:x 21}"
                                         "--require" "mycelium.cli-fixture-cells"])]
    (is (zero? exit) message)
    (is (str/includes? message "Result: PASS"))
    (is (str/includes? message "Output: {:y 42}"))
    ;; dispatch forms from the manifest are evaluated to report routing
    (is (str/includes? message "Matched dispatch: :big"))))

(deftest test-command-generates-input-when-omitted-test
  (load-fixture-cells!)
  (let [path (write-manifest! fixture-manifest)
        {:keys [exit message]} (cli/run ["test" path "start" "--require" "mycelium.cli-fixture-cells"])]
    (is (zero? exit) message)
    (is (str/includes? message "Input: {:x "))))

(deftest test-command-reports-schema-failure-exit-3-test
  (load-fixture-cells!)
  (let [path (write-manifest! fixture-manifest)
        {:keys [exit message]} (cli/run ["test" path "broken" "--input" "{:x 1}"
                                         "--require" "mycelium.cli-fixture-cells"])]
    (is (= 3 exit))
    (is (str/includes? message "Result: FAIL"))
    (is (str/includes? message "[output]"))))

(deftest test-command-json-carries-result-test
  (load-fixture-cells!)
  (let [path (write-manifest! fixture-manifest)
        out  (json/read-str (:message (cli/run ["test" path "start" "--input" "{:x 1}" "--json"
                                                "--require" "mycelium.cli-fixture-cells"])))]
    (is (= true (get out "ok")))
    (is (= true (get out "pass?")))
    (is (= {"y" 2} (get out "output")))
    (is (= "small" (get out "matched-dispatch")))))

(deftest test-command-unregistered-cell-hints-require-test
  (let [path (write-manifest! fixture-manifest)
        {:keys [exit message]} (cli/run ["test" path "start"])]
    (is (= 1 exit))
    (is (str/includes? message "--require"))))

;; ===== dispatch: refs =====

(deftest refs-command-lists-sites-test
  (let [{:keys [exit message]} (cli/run ["refs" @valid-path ":process"])]
    (is (zero? exit) message)
    (is (str/starts-with? message ":process — 4 references"))
    (is (str/includes? message "edges-in"))
    (is (str/includes? message ":edges :start :success")))
  (let [{:keys [exit message]} (cli/run ["refs" @valid-path "nope"])]
    (is (= 1 exit))
    (is (str/includes? message "not found"))))
