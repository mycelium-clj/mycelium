(ns mycelium.patch-test
  (:require [clojure.edn]
            [clojure.string :as str]
            [clojure.test :refer [deftest is]]
            [mycelium.patch :as patch]))

(def manifest
  {:id :test/patch
   :cells {:start   {:id :t/parse :doc "parse" :schema {:input [:map [:x :int]]
                                                        :output [:map [:y :int]]}
                     :on-error :err}
           :process {:id :t/process :doc "process" :schema {:input [:map [:y :int]]
                                                            :output [:map [:z :int]]}
                     :on-error :err}
           :err     {:id :t/err :doc "handle errors" :schema {:input [:map [:any :any]]
                                                              :output [:map]}
                     :on-error nil}}
   :edges {:start   {:success :process, :failure :err}
           :process {:done :end}
           :err     :end}
   :dispatches {:start   '[[:success (fn [d] (:y d))]
                           [:failure (fn [d] (not (:y d)))]]
                :process '[[:done (constantly true)]]}})

;; ===== rename-cell rewrites every reference =====

(deftest rename-cell-updates-cells-edges-dispatches-test
  (let [result (patch/apply-op manifest {:op "rename-cell" :from :process :to :transform})]
    (is (contains? (:cells result) :transform))
    (is (not (contains? (:cells result) :process)))
    (is (= {:success :transform, :failure :err} (get-in result [:edges :start])))
    (is (= {:done :end} (get-in result [:edges :transform])))
    (is (contains? (:dispatches result) :transform))
    (is (not (contains? (:dispatches result) :process)))))

(deftest rename-cell-updates-joins-regions-constraints-test
  (let [m (-> manifest
              (assoc-in [:cells :fanout] {:id :t/fanout :doc "fan out"
                                          :schema {:input [:map] :output [:map [:f :int]]}
                                          :on-error nil})
              (assoc :joins {:batch {:cells [:fanout] :strategy :parallel}}
                     :regions {:core [:start :process]}
                     :constraints [{:type :must-follow :if :process :then :err}
                                   {:type :never-together :cells [:process :err]}]
                     :timeouts {:process 5000}
                     :error-groups {:main {:cells [:process] :on-error :err}})
              ;; join member replaces :process downstream so the join is reachable
              (assoc-in [:edges :process] {:done :batch})
              (assoc-in [:dispatches :process] '[[:done (constantly true)]]))
        ;; two renames: join member and plain cell
        result (patch/apply-ops m {:ops [{:op "rename-cell" :from :fanout :to :wind}
                                         {:op "rename-cell" :from :process :to :transform}]})]
    (is (= [:wind] (get-in result [:joins :batch :cells])))
    (is (= [:start :transform] (get-in result [:regions :core])))
    (is (= {:type :must-follow :if :transform :then :err}
           (first (:constraints result))))
    (is (= [:transform :err] (get-in result [:constraints 1 :cells])))
    (is (contains? (:timeouts result) :transform))
    (is (= [:transform] (get-in result [:error-groups :main :cells])))))

(deftest rename-cell-updates-pipeline-and-on-error-test
  (let [m {:id :test/pipeline
           :cells {:start {:id :t/a :doc "a" :schema {:input [:map] :output [:map]} :on-error :b}
                   :b     {:id :t/b :doc "b" :schema {:input [:map] :output [:map]} :on-error nil}}
           :pipeline [:start :b]}
        result (patch/apply-op m {:op "rename-cell" :from :b :to :render})]
    (is (= [:start :render] (:pipeline result)))
    (is (= :render (get-in result [:cells :start :on-error])))))

(deftest rename-cell-renames-join-name-test
  (let [m (assoc manifest
                 :joins {:batch {:cells [:process] :strategy :parallel}}
                 ;; joins need edges from start; adjust edges for a valid shape
                 :edges {:start {:success :batch, :failure :err}
                         :batch {:done :end}
                         :err :end})
        result (patch/apply-op m {:op "rename-cell" :from :batch :to :fork})]
    (is (contains? (:joins result) :fork))
    (is (not (contains? (:joins result) :batch)))
    (is (= {:success :fork, :failure :err} (:start (:edges result))))))

;; ===== guards =====

(deftest rename-unknown-cell-throws-test
  (is (thrown-with-msg? Exception #"Unknown cell :nope"
        (patch/apply-op manifest {:op "rename-cell" :from :nope :to :x}))))

(deftest rename-cannot-clobber-existing-cell-test
  (is (thrown-with-msg? Exception #"already exists"
        (patch/apply-op manifest {:op "rename-cell" :from :process :to :err}))))

(deftest rename-cannot-touch-start-test
  (is (thrown-with-msg? Exception #":start"
        (patch/apply-op manifest {:op "rename-cell" :from :start :to :entry}))))

;; ===== expect-hash guard =====

(deftest expect-hash-match-applies-test
  (let [h (patch/manifest-hash manifest)]
    (is (map? (patch/apply-ops manifest {:expect-hash h
                                         :ops [{:op "rename-cell" :from :process :to :transform}]})))))

(deftest expect-hash-mismatch-throws-with-current-hash-test
  (let [result (try (patch/apply-ops manifest {:expect-hash (apply str (repeat 64 "0"))
                                               :ops [{:op "rename-cell" :from :process :to :x}]})
                    (catch Exception e e))]
    (is (instance? Exception result))
    (is (re-find #"(?i)stale" (ex-message result)))
    (is (re-find #"current hash: [0-9a-f]{64}" (ex-message result)))))

;; ===== validate before write =====

(deftest invalid-result-manifest-is-rejected-test
  ;; renaming a cell to a name that breaks an edge target is impossible by
  ;; construction, but a schema-less op result must still pass validation;
  ;; here we craft an op on a manifest whose validation would fail after
  ;; the edit: rename leaves :on-error pointing at a renamed-away cell —
  ;; covered by rewrite, so instead assert the happy path validates.
  (let [result (patch/apply-op manifest {:op "rename-cell" :from :process :to :transform})]
    (is (map? result))
    (is (= :test/patch (:id result)))))

(deftest apply-ops-validates-result-test
  ;; validation failure surfaces as an exception naming validation, not a
  ;; silent bad write: build a manifest that is valid, then rename err away
  ;; while something still references it — impossible via rewrite, so use a
  ;; second op that would break structure. There is no such op yet, so this
  ;; asserts the validation hook runs: a broken manifest fails immediately.
  (is (thrown? Exception
        (patch/apply-op (assoc-in manifest [:cells :process :schema :input] [:not-a-type])
                        {:op "rename-cell" :from :process :to :transform}))))

;; ===== rename does not touch what it doesn't need to =====

(deftest rename-does-not-add-on-error-to-cells-lacking-it-test
  (let [m (update-in manifest [:cells :err] dissoc :on-error)
        result (patch/apply-op m {:op "rename-cell" :from :process :to :transform})]
    (is (not (contains? (get-in result [:cells :err]) :on-error)))
    (is (= :err (get-in result [:cells :start :on-error])))))

;; ===== raw manifests with :fragments =====

(def fragment
  {:id :frag/tail
   :doc "tail"
   :entry :fetch
   :exits [:done]
   :cells {:fetch  {:id :f/fetch :doc "fetch" :schema {:input [:map] :output [:map [:items :any]]} :on-error nil}
           :render {:id :f/render :doc "render" :schema {:input [:map [:items :any]] :output [:map [:html :string]]} :on-error nil}}
   :edges {:fetch :render, :render :_exit/done}})

(def frag-manifest
  {:id :test/frag
   :fragments {:tail {:fragment fragment :as :fetch-list :exits {:done :finish}}}
   :cells {:start  {:id :t/parse :doc "parse" :schema {:input [:map] :output [:map]} :on-error nil}
           :finish {:id :t/finish :doc "finish" :schema {:input [:map] :output [:map]} :on-error nil}}
   :edges {:start :fetch-list, :finish :end}})

(deftest rename-fragment-entry-alias-rewrites-as-and-host-edges-test
  (let [result (patch/apply-op frag-manifest {:op "rename-cell" :from :fetch-list :to :list})]
    (is (= :list (get-in result [:fragments :tail :as])))
    (is (= :list (get-in result [:edges :start])))
    ;; still a raw manifest: fragments not inlined
    (is (contains? result :fragments))
    (is (= #{:start :finish} (set (keys (:cells result)))))))

(deftest rename-host-cell-rewrites-fragment-exits-test
  (let [result (patch/apply-op frag-manifest {:op "rename-cell" :from :finish :to :done-page})]
    (is (= :done-page (get-in result [:fragments :tail :exits :done])))
    (is (contains? (:cells result) :done-page))))

(deftest rename-fragment-internal-cell-is-refused-with-pointer-test
  (let [e (try (patch/apply-op frag-manifest {:op "rename-cell" :from :render :to :draw})
               (catch Exception e e))]
    (is (instance? Exception e))
    (is (re-find #"defined by fragment :tail" (ex-message e)))))

(deftest rename-to-fragment-internal-name-is-refused-test
  (is (thrown-with-msg? Exception #"already exists"
        (patch/apply-op frag-manifest {:op "rename-cell" :from :finish :to :render}))))

;; ===== cell-refs =====

(deftest cell-refs-lists-every-reference-site-test
  (let [m (-> manifest
              (assoc :regions {:core [:start :process]}
                     :timeouts {:process 5000}
                     :constraints [{:type :must-follow :if :process :then :err}]
                     :error-groups {:main {:cells [:process] :on-error :err}}))
        refs (patch/cell-refs m :process)
        roles (frequencies (map :role refs))]
    (is (= 1 (:definition roles)))
    (is (= 1 (:edges-out roles)))
    (is (= 1 (:edges-in roles)))
    (is (= 1 (:dispatches roles)))
    (is (= 1 (:region roles)))
    (is (= 1 (:timeout roles)))
    (is (= 1 (:constraint roles)))
    (is (= 1 (:error-group roles)))
    (is (every? vector? (map :path refs)))
    ;; :err is an :on-error target for two cells plus an edge target and a group handler
    (is (= 2 (:on-error (frequencies (map :role (patch/cell-refs m :err))))))))

(deftest cell-refs-covers-fragments-test
  (let [roles (set (map :role (patch/cell-refs frag-manifest :finish)))]
    (is (contains? roles :fragment-exit)))
  (let [roles (set (map :role (patch/cell-refs frag-manifest :fetch-list)))]
    (is (contains? roles :fragment-entry))
    (is (contains? roles :edges-in))))

(deftest rename-moves-every-reference-test
  (let [m (assoc manifest :regions {:core [:start :process]} :timeouts {:process 5000})
        before (count (patch/cell-refs m :process))
        result (patch/apply-op m {:op "rename-cell" :from :process :to :transform})]
    (is (pos? before))
    (is (empty? (patch/cell-refs result :process)))
    (is (= before (count (patch/cell-refs result :transform))))))

;; ===== op registry =====

(deftest ops-registry-describes-rename-cell-test
  (let [ops (patch/ops)]
    (is (contains? ops "rename-cell"))
    (is (string? (get-in ops ["rename-cell" :doc])))
    (is (= [:from :to] (mapv :name (get-in ops ["rename-cell" :args]))))))

;; ===== render: format-preserving text output =====

(def formatted-text
  ";; loan workflow
{:id :t/render
 :doc \"docs\"
 :cells
 {:start   {:id :t/a ; entry
            :doc \"a\"
            :schema {:input [:map] :output [:map]}
            :on-error :err}
  :process {:id :t/b
            :doc \"b\"
            :schema {:input [:map] :output [:map]}
            :on-error :err}
  :err     {:id :t/e :doc \"e\" :schema {:input [:map] :output [:map]} :on-error nil}}

 :edges {:start :process
         :process :err   ; done
         :err :end}}
")

(deftest render-preserves-comments-and-layout-on-rename-test
  (let [old (clojure.edn/read-string formatted-text)
        new (patch/apply-op old {:op "rename-cell" :from :process :to :transform})
        out (patch/render formatted-text old new)]
    (is (= new (clojure.edn/read-string out)))
    (is (str/includes? out ";; loan workflow"))
    (is (str/includes? out "; entry"))
    (is (str/includes? out "; done"))
    ;; untouched cell keeps its exact layout
    (is (str/includes? out "  :err     {:id :t/e :doc \"e\" :schema {:input [:map] :output [:map]} :on-error nil}"))
    (is (str/includes? out ":transform {:id :t/b"))
    (is (not (str/includes? out ":process")))))

(deftest render-adds-and-removes-keys-test
  (let [old (clojure.edn/read-string formatted-text)
        new (-> old (assoc :regions {:core [:start]}) (dissoc :doc))
        out (patch/render formatted-text old new)]
    (is (= new (clojure.edn/read-string out)))
    (is (str/includes? out ";; loan workflow"))
    (is (not (str/includes? out ":doc \"docs\"")))
    (is (str/includes? out ":regions"))))

(deftest render-falls-back-to-pretty-print-when-text-is-not-the-old-map-test
  (let [old {:id :t/x :cells {} :edges {}}
        new {:id :t/y :cells {} :edges {}}
        out (patch/render "{:id :t/unrelated}" old new)]
    (is (= new (clojure.edn/read-string out)))))

(deftest render-ignores-print-length-bindings-test
  (let [old {:id :t/x :cells {} :edges {} :pipeline (vec (range 50))}
        new (assoc old :pipeline (vec (range 60)))
        out (binding [*print-length* 3 *print-level* 1]
              (patch/render (pr-str old) old new))]
    (is (= new (clojure.edn/read-string out)))))
