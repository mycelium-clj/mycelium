(ns mycelium.cli-patch-test
  (:require [clojure.data.json :as json]
            [clojure.edn :as edn]
            [clojure.java.io :as io]
            [clojure.string :as str]
            [clojure.test :refer [deftest is use-fixtures]]
            [mycelium.cell :as cell]
            [mycelium.cli :as cli]))

(def manifest
  {:id :test/clip
   :cells {:start   {:id :t/parse :doc "parse" :schema {:input [:map [:x :int]]
                                                        :output [:map [:y :int]]}
                     :on-error :err}
           :process {:id :t/process :doc "process" :schema {:input [:map [:y :int]]
                                                            :output [:map [:z :int]]}
                     :on-error :err}
           :err     {:id :t/err :doc "errors" :schema {:input [:map] :output [:map]}
                     :on-error nil}}
   :edges {:start   {:success :process, :failure :err}
           :process {:done :end}
           :err     :end}
   :dispatches {:start   '[[:success (fn [d] (:y d))]
                           [:failure (fn [d] (not (:y d)))]]
                :process '[[:done (constantly true)]]}})

(defn- write-manifest!
  ([m] (write-manifest! m pr-str))
  ([m render]
   (let [f (java.io.File/createTempFile "myc-patch" ".edn")]
     (spit f (render m))
     (.getAbsolutePath f))))

(defn- temp-dir! []
  (let [d (java.nio.file.Files/createTempDirectory "myc-patch" (make-array java.nio.file.attribute.FileAttribute 0))]
    (.toFile d)))

(use-fixtures :each (fn [f] (cell/clear-registry!) (f)))

(deftest patch-rename-writes-file-and-reports-new-hash-test
  (let [path  (write-manifest! manifest)
        before (slurp path)
        {:keys [exit message]} (cli/run ["patch" path
                                         "--op" "rename-cell" "--from" "process" "--to" "transform"])]
    (is (zero? exit) message)
    (is (str/includes? message "patch ok"))
    (is (re-find #"[0-9a-f]{64}" message))
    ;; file actually changed and contains the new name
    (is (not= before (slurp path)))
    (is (str/includes? (slurp path) ":transform"))
    ;; written file still validates
    (is (zero? (:exit (cli/run ["validate" path]))))
    ;; reported hash is the hash of the written file
    (is (str/includes? message (str/trimr (:message (cli/run ["hash" path])))))
    ;; no temp file left behind
    (is (empty? (filter #(str/starts-with? (.getName %) ".myc-")
                        (.listFiles (.getParentFile (io/file path))))))))

(deftest patch-accepts-colon-prefixed-names-and-any-arg-order-test
  (let [path (write-manifest! manifest)
        {:keys [exit message]} (cli/run ["patch" path
                                         "--op" "rename-cell" "--to" ":transform" "--from" ":process"])]
    (is (zero? exit) message)
    (is (contains? (:cells (edn/read-string (slurp path))) :transform))))

(deftest patch-dry-run-does-not-write-test
  (let [path   (write-manifest! manifest)
        before (slurp path)
        {:keys [exit message]} (cli/run ["patch" path "--dry-run"
                                         "--op" "rename-cell" "--from" "process" "--to" "transform"])]
    (is (zero? exit) message)
    (is (str/includes? message "dry run"))
    (is (= before (slurp path)))))

(deftest patch-expect-hash-guard-test
  (let [path (write-manifest! manifest)
        good-hash (:message (cli/run ["hash" path]))
        ;; correct hash passes
        ok (cli/run ["patch" path "--expect-hash" (str/trimr good-hash)
                     "--op" "rename-cell" "--from" "process" "--to" "transform"])
        _ (spit path (pr-str manifest))
        ;; stale hash rejected, file untouched
        before (slurp path)
        stale (cli/run ["patch" path "--expect-hash" (apply str (repeat 64 "0"))
                        "--op" "rename-cell" "--from" "process" "--to" "transform"])]
    (is (zero? (:exit ok)) (:message ok))
    (is (= 1 (:exit stale)))
    (is (str/includes? (:message stale) "Stale"))
    (is (= before (slurp path)))))

(deftest patch-invalid-op-exits-1-test
  (let [path (write-manifest! manifest)
        {:keys [exit message]} (cli/run ["patch" path "--op" "explode"])]
    (is (= 1 exit))
    (is (str/includes? message "Unknown op"))))

(deftest patch-op-help-lists-ops-without-a-manifest-test
  (let [{:keys [exit message]} (cli/run ["patch" "--op" "help"])]
    (is (zero? exit))
    (is (str/includes? message "rename-cell"))
    (is (str/includes? message "--from"))
    (is (str/includes? message "--to"))))

(deftest patch-rejected-edit-leaves-file-untouched-test
  ;; renaming to an existing cell name fails validation; file must be unchanged
  (let [path   (write-manifest! manifest)
        before (slurp path)
        {:keys [exit message]} (cli/run ["patch" path
                                         "--op" "rename-cell" "--from" "process" "--to" "err"])]
    (is (= 1 exit))
    (is (str/includes? message "already exists"))
    (is (= before (slurp path)))))

(deftest patch-json-output-test
  (let [path (write-manifest! manifest)
        out  (json/read-str (:message (cli/run ["patch" path "--json" "--dry-run"
                                                "--op" "rename-cell" "--from" "process" "--to" "transform"])))]
    (is (= true (get out "ok")))
    (is (= false (get out "written")))
    (is (re-matches #"[0-9a-f]{64}" (get out "hash")))
    (is (= path (get out "path")))))

;; ===== the file is the projection: keep its shape =====

(deftest patch-keeps-pipeline-shorthand-test
  (let [pm {:id :t/p
            :cells {:start {:id :t/a :doc "a" :schema {:input [:map] :output [:map]} :on-error nil}
                    :b     {:id :t/b :doc "b" :schema {:input [:map] :output [:map]} :on-error nil}}
            :pipeline [:start :b]}
        path (write-manifest! pm)
        {:keys [exit message]} (cli/run ["patch" path "--op" "rename-cell" "--from" "b" "--to" "render"])
        after (edn/read-string (slurp path))]
    (is (zero? exit) message)
    (is (= [:start :render] (:pipeline after)))
    (is (not (contains? after :edges)))
    (is (not (contains? after :dispatches)))))

(deftest patch-keeps-fragments-and-formatting-test
  ;; the real todomvc manifest: fragments referenced by file, comments and layout
  (let [dir  (temp-dir!)
        wf   (io/file dir "workflows") frags (io/file dir "fragments")
        _    (.mkdirs wf) _ (.mkdirs frags)
        _    (io/copy (io/file "examples/todomvc/resources/fragments/fetch-render-list.edn")
                      (io/file frags "fetch-render-list.edn"))
        path (.getAbsolutePath (io/file wf "todo-delete.edn"))
        _    (spit path (str ";; delete flow\n" (slurp "examples/todomvc/resources/workflows/todo-delete.edn")))
        before (edn/read-string (slurp path))
        {:keys [exit message]} (cli/run ["patch" path "--op" "rename-cell" "--from" "delete" "--to" "remove"])
        text  (slurp path)
        after (edn/read-string text)]
    (is (zero? exit) message)
    (is (contains? after :fragments))
    (is (= (:fragments before) (:fragments after)))
    (is (= #{:start :remove} (set (keys (:cells after)))))
    (is (= {:start :remove, :remove :fetch-list} (:edges after)))
    (is (str/includes? text ";; delete flow"))
    ;; untouched cell keeps its multi-line layout
    (is (str/includes? text "  {:id       :request/parse-todo\n"))
    (is (zero? (:exit (cli/run ["validate" path]))) "written manifest validates")))

(deftest patch-refuses-fragment-internal-cell-test
  (let [dir  (temp-dir!)
        wf   (io/file dir "workflows") frags (io/file dir "fragments")
        _    (.mkdirs wf) _ (.mkdirs frags)
        _    (io/copy (io/file "examples/todomvc/resources/fragments/fetch-render-list.edn")
                      (io/file frags "fetch-render-list.edn"))
        path (.getAbsolutePath (io/file wf "todo-delete.edn"))
        _    (io/copy (io/file "examples/todomvc/resources/workflows/todo-delete.edn") (io/file path))
        before (slurp path)
        {:keys [exit message]} (cli/run ["patch" path "--op" "rename-cell" "--from" "fetch-stats" "--to" "stats"])]
    (is (= 1 exit))
    (is (str/includes? message "defined by fragment :tail"))
    (is (str/includes? message "fragments/fetch-render-list.edn"))
    (is (= before (slurp path)))))

(deftest patch-does-not-add-on-error-keys-test
  (let [m (update-in manifest [:cells :err] dissoc :on-error)
        path (write-manifest! m)
        {:keys [exit message]} (cli/run ["patch" path "--op" "rename-cell" "--from" "process" "--to" "transform"])]
    (is (zero? exit) message)
    (is (not (contains? (get-in (edn/read-string (slurp path)) [:cells :err]) :on-error)))))

(deftest patch-honors-require-for-inherit-schemas-test
  (let [m {:id :t/inherit
           :cells {:start {:id :fixture/double :doc "double" :schema :inherit :on-error nil}}
           :edges {:start :end}}
        path (write-manifest! m)
        without (cli/run ["patch" path "--op" "rename-cell" "--from" "start" "--to" "entry"])
        ;; the :each fixture clears the registry; `require` is a no-op once
        ;; loaded, so re-register the way a fresh CLI process would see it
        _       (require 'mycelium.cli-fixture-cells :reload)
        with    (cli/run ["patch" path "--require" "mycelium.cli-fixture-cells" "--dry-run"
                          "--op" "rename-cell" "--from" "start" "--to" "entry"])]
    (is (= 1 (:exit without)))
    (is (str/includes? (:message without) "not registered"))
    ;; renaming :start is refused, but only after the manifest loaded fine
    (is (= 1 (:exit with)))
    (is (str/includes? (:message with) "Cannot rename :start"))))

(deftest patch-is-print-length-safe-test
  (let [path (write-manifest! manifest)
        {:keys [exit message]} (binding [*print-length* 2 *print-level* 1]
                                 (cli/run ["patch" path "--op" "rename-cell" "--from" "process" "--to" "transform"]))]
    (is (zero? exit) message)
    (is (= (assoc-in (-> manifest
                         (update :cells (fn [cs] (-> cs (dissoc :process) (assoc :transform (:process cs)))))
                         (update :edges (fn [es] (-> es (dissoc :process) (assoc :transform (:process es)))))
                         (update :dispatches (fn [ds] (-> ds (dissoc :process) (assoc :transform (:process ds))))))
                     [:edges :start :success] :transform)
           (edn/read-string (slurp path))))))
