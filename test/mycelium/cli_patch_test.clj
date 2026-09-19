(ns mycelium.cli-patch-test
  (:require [clojure.string :as str]
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
  [m]
  (let [f (java.io.File/createTempFile "myc-patch" ".edn")]
    (spit f (pr-str m))
    (.getAbsolutePath f)))

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
    (is (zero? (:exit (cli/run ["validate" path]))))))

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

(deftest patch-rejected-edit-leaves-file-untouched-test
  ;; renaming to an existing cell name fails validation; file must be unchanged
  (let [path   (write-manifest! manifest)
        before (slurp path)
        {:keys [exit message]} (cli/run ["patch" path
                                         "--op" "rename-cell" "--from" "process" "--to" "err"])]
    (is (= 1 exit))
    (is (str/includes? message "already exists"))
    (is (= before (slurp path)))))
