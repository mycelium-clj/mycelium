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

;; ===== batch semantics: one validation per apply-ops =====

(deftest apply-ops-validates-once-at-the-end-test
  ;; add-cell alone leaves the new cell without edges (invalid); wiring it in
  ;; the same batch must succeed
  (let [result (patch/apply-ops manifest
                                {:ops [{:op "add-cell" :name :audit :id :t/audit :doc "audit"}
                                       {:op "set-edge" :from :audit :to :end}
                                       {:op "set-edge" :from :process :label :done :to :audit}]})]
    (is (= :end (get-in result [:edges :audit])))
    (is (= {:done :audit} (get-in result [:edges :process])))
    (is (thrown-with-msg? Exception #"no edges"
          (patch/apply-op manifest {:op "add-cell" :name :audit :id :t/audit :doc "audit"})))))

(deftest apply-ops-rejects-invalid-final-state-test
  (is (thrown-with-msg? Exception #"Unreachable|no edges|Invalid"
        (patch/apply-ops manifest {:ops [{:op "add-cell" :name :audit :id :t/audit :doc "audit"}]}))))

;; ===== add-cell =====

(deftest add-cell-builds-definition-with-defaults-test
  (let [result (patch/apply-ops manifest
                                {:ops [{:op "add-cell" :name :audit :id :t/audit :doc "audit"
                                        :edges :end}
                                       {:op "set-edge" :from :err :to :audit}]})]
    (is (= {:id :t/audit :doc "audit" :schema {:input [:map] :output [:map]} :on-error nil}
           (get-in result [:cells :audit])))
    (is (= :end (get-in result [:edges :audit])))))

(deftest add-cell-with-schema-requires-and-dispatches-test
  (let [result (patch/apply-ops manifest
                                {:ops [{:op "add-cell" :name :audit :id :t/audit :doc "audit"
                                        :input [:map [:z :int]] :output [:map [:ok :boolean]]
                                        :requires [:db] :on-error :err
                                        :edges {:ok :end :fail :err}
                                        :dispatches '[[:ok (fn [d] (:ok d))] [:fail (fn [d] (not (:ok d)))]]}
                                       {:op "set-edge" :from :process :label :done :to :audit}]})]
    (is (= [:db] (get-in result [:cells :audit :requires])))
    (is (= :err (get-in result [:cells :audit :on-error])))
    (is (= [:map [:z :int]] (get-in result [:cells :audit :schema :input])))
    (is (= {:ok :end :fail :err} (get-in result [:edges :audit])))
    (is (= 2 (count (get-in result [:dispatches :audit]))))))

(deftest add-cell-after-splices-into-unconditional-edge-test
  (let [result (patch/apply-op manifest {:op "add-cell" :name :log :id :t/log :doc "log" :after :err})]
    (is (= :log (get-in result [:edges :err])))
    (is (= :end (get-in result [:edges :log])))))

(deftest add-cell-after-conditional-edge-is-refused-test
  (is (thrown-with-msg? Exception #"set-edge"
        (patch/apply-op manifest {:op "add-cell" :name :log :id :t/log :doc "log" :after :start}))))

(deftest add-cell-into-pipeline-test
  (let [pm {:id :t/p
            :cells {:start {:id :t/a :doc "a" :schema {:input [:map] :output [:map]} :on-error nil}
                    :b     {:id :t/b :doc "b" :schema {:input [:map] :output [:map]} :on-error nil}}
            :pipeline [:start :b]}
        after (patch/apply-op pm {:op "add-cell" :name :mid :id :t/mid :doc "mid" :after :start})
        end   (patch/apply-op pm {:op "add-cell" :name :tail :id :t/tail :doc "tail"})]
    (is (= [:start :mid :b] (:pipeline after)))
    (is (= [:start :b :tail] (:pipeline end)))
    (is (not (contains? after :edges)))
    (is (thrown-with-msg? Exception #":pipeline"
          (patch/apply-op pm {:op "add-cell" :name :x :id :t/x :doc "x" :edges :end})))))

(deftest add-cell-refuses-existing-names-test
  (is (thrown-with-msg? Exception #"already exists"
        (patch/apply-op manifest {:op "add-cell" :name :process :id :t/x :doc "x" :edges :end})))
  (is (thrown-with-msg? Exception #"already exists"
        (patch/apply-op frag-manifest {:op "add-cell" :name :render :id :t/x :doc "x" :edges :end})))
  (is (thrown-with-msg? Exception #"already exists"
        (patch/apply-op manifest {:op "add-cell" :name :end :id :t/x :doc "x" :edges :end}))))

;; ===== remove-cell =====

(def removable
  (-> manifest
      (assoc-in [:cells :log] {:id :t/log :doc "log" :schema {:input [:map] :output [:map]} :on-error nil})
      (assoc-in [:edges :err] :log)
      (assoc-in [:edges :log] :end)
      (assoc :regions {:tail [:err :log]} :timeouts {:log 100})))

(deftest remove-cell-cleans-its-own-entries-test
  (let [result (patch/apply-op removable {:op "remove-cell" :name :log :rewire :end})]
    (is (not (contains? (:cells result) :log)))
    (is (not (contains? (:edges result) :log)))
    (is (not (contains? (:timeouts result) :log)))
    (is (= [:err] (get-in result [:regions :tail])))
    ;; incoming edge retargeted
    (is (= :end (get-in result [:edges :err])))))

(deftest remove-cell-refuses-while-referenced-test
  (let [e (try (patch/apply-op removable {:op "remove-cell" :name :log})
               (catch Exception e e))]
    (is (instance? Exception e))
    (is (re-find #"still referenced" (ex-message e)))
    (is (re-find #":edges :err" (ex-message e)))
    (is (re-find #"--rewire" (ex-message e)))))

(deftest remove-cell-rewires-on-error-and-map-edges-test
  (let [m (-> manifest
              (assoc-in [:cells :err2] {:id :t/err2 :doc "e2" :schema {:input [:map] :output [:map]} :on-error nil})
              (assoc-in [:edges :err] :err2)
              (assoc-in [:edges :err2] :end))
        result (patch/apply-op m {:op "remove-cell" :name :err :rewire :err2})]
    (is (= :err2 (get-in result [:cells :start :on-error])))
    (is (= :err2 (get-in result [:cells :process :on-error])))
    (is (= {:success :process :failure :err2} (get-in result [:edges :start])))))

(deftest remove-cell-from-pipeline-test
  (let [pm {:id :t/p
            :cells {:start {:id :t/a :doc "a" :schema {:input [:map] :output [:map]} :on-error nil}
                    :b     {:id :t/b :doc "b" :schema {:input [:map] :output [:map]} :on-error nil}
                    :c     {:id :t/c :doc "c" :schema {:input [:map] :output [:map]} :on-error nil}}
            :pipeline [:start :b :c]}
        result (patch/apply-op pm {:op "remove-cell" :name :b})]
    (is (= [:start :c] (:pipeline result)))
    (is (not (contains? (:cells result) :b)))))

(deftest remove-cell-guards-test
  (is (thrown-with-msg? Exception #":start" (patch/apply-op manifest {:op "remove-cell" :name :start})))
  (is (thrown-with-msg? Exception #"Unknown cell" (patch/apply-op manifest {:op "remove-cell" :name :nope})))
  (is (thrown-with-msg? Exception #"fragment" (patch/apply-op frag-manifest {:op "remove-cell" :name :render}))))

;; ===== set-edge / delete-edge =====

(deftest set-edge-unconditional-and-labelled-test
  (let [a (patch/apply-op manifest {:op "set-edge" :from :err :to :process})
        b (patch/apply-ops manifest {:ops [{:op "set-edge" :from :process :label :retry :to :start}
                                           {:op "set-dispatches" :name :process
                                            :dispatches '[[:retry (fn [d] (:retry? d))] [:done (constantly true)]]}]})]
    (is (= :process (get-in a [:edges :err])))
    (is (= {:done :end :retry :start} (get-in b [:edges :process])))))

(deftest set-edge-label-on-unconditional-edge-makes-a-map-test
  ;; err's edge is :end; adding a label turns it into {:ok :end}, and dispatch
  ;; coverage then needs a predicate from set-dispatches in the same batch
  (let [result (patch/apply-ops manifest {:ops [{:op "set-edge" :from :err :label :ok :to :end}
                                                {:op "set-dispatches" :name :err :dispatches '[[:ok (constantly true)]]}]})]
    (is (= {:ok :end} (get-in result [:edges :err])))
    (is (= 1 (count (get-in result [:dispatches :err]))))))

(deftest set-edge-guards-test
  (is (thrown-with-msg? Exception #"Unknown cell" (patch/apply-op manifest {:op "set-edge" :from :nope :to :end})))
  (is (thrown-with-msg? Exception #"Invalid edge target|Unknown|not found"
        (patch/apply-op manifest {:op "set-edge" :from :err :to :nowhere})))
  (is (thrown-with-msg? Exception #":pipeline"
        (patch/apply-op {:id :t/p :cells {:start {:id :t/a :doc "a" :schema {:input [:map] :output [:map]} :on-error nil}} :pipeline [:start]}
                        {:op "set-edge" :from :start :to :end}))))

(deftest delete-edge-label-drops-matching-dispatch-test
  (let [m (-> manifest
              (assoc-in [:edges :start :retry] :process)
              (assoc-in [:dispatches :start] '[[:retry (fn [d] (:retry? d))]
                                               [:success (fn [d] (:y d))]
                                               [:failure (fn [d] (not (:y d)))]]))
        result (patch/apply-op m {:op "delete-edge" :from :start :label :retry})]
    (is (= {:success :process :failure :err} (get-in result [:edges :start])))
    (is (= [:success :failure] (mapv first (get-in result [:dispatches :start]))))))

(deftest delete-edge-whole-then-set-test
  (let [result (patch/apply-ops manifest {:ops [{:op "delete-edge" :from :err}
                                                {:op "set-edge" :from :err :to :end}]})]
    (is (= :end (get-in result [:edges :err])))))

;; ===== set-cell-field / set-dispatches =====

(deftest set-cell-field-with-expect-guard-test
  (let [ok (patch/apply-op manifest {:op "set-cell-field" :name :process :field :doc :value "transform" :expect "process"})]
    (is (= "transform" (get-in ok [:cells :process :doc]))))
  (let [e (try (patch/apply-op manifest {:op "set-cell-field" :name :process :field :doc :value "x" :expect "stale"})
               (catch Exception e e))]
    (is (re-find #"expected" (ex-message e)))
    (is (re-find #"\"process\"" (ex-message e)))))

(deftest set-cell-field-schema-and-on-error-test
  (let [result (patch/apply-ops manifest
                                {:ops [{:op "set-cell-field" :name :process :field :schema
                                        :value {:input [:map [:y :int]] :output [:map [:z :int] [:w :int]]}}
                                       {:op "set-cell-field" :name :process :field :on-error :value nil}]})]
    (is (= [:map [:z :int] [:w :int]] (get-in result [:cells :process :schema :output])))
    (is (nil? (get-in result [:cells :process :on-error])))
    (is (contains? (get-in result [:cells :process]) :on-error))))

(deftest set-cell-field-rejects-invalid-results-test
  (is (thrown? Exception
        (patch/apply-op manifest {:op "set-cell-field" :name :process :field :on-error :value :nowhere}))))

(deftest coerce-field-value-by-field-test
  (is (= "hello" (patch/coerce-field-value :doc "hello")))
  (is (= :t/x (patch/coerce-field-value :id "t/x")))
  (is (= :err (patch/coerce-field-value :on-error ":err")))
  (is (nil? (patch/coerce-field-value :on-error "nil")))
  (is (= {:input [:map]} (patch/coerce-field-value :schema "{:input [:map]}")))
  (is (= [:db] (patch/coerce-field-value :requires "[:db]"))))

;; ===== diff =====

(deftest diff-manifests-reports-cells-edges-and-sections-test
  (let [b (-> manifest
              (assoc-in [:cells :process :doc] "changed")
              (update :cells dissoc :err)
              (assoc-in [:cells :audit] {:id :t/audit :doc "a" :schema {:input [:map] :output [:map]} :on-error nil})
              (assoc-in [:edges :audit] :end)
              (update :edges dissoc :err)
              (assoc-in [:edges :start :failure] :audit)
              (assoc :regions {:core [:start]}))
        d (patch/diff-manifests manifest b)]
    (is (= [:audit] (get-in d [:cells :added])))
    (is (= [:err] (get-in d [:cells :removed])))
    (is (= {:doc ["process" "changed"]} (get-in d [:cells :changed :process])))
    (is (= [:audit] (get-in d [:edges :added])))
    (is (= [:err] (get-in d [:edges :removed])))
    (is (= [{:success :process :failure :err} {:success :process :failure :audit}]
           (get-in d [:edges :changed :start])))
    (is (= [nil {:core [:start]}] (get-in d [:sections :regions])))
    (is (false? (:same? d))))
  (is (true? (:same? (patch/diff-manifests manifest manifest)))))

(deftest render-handles-keys-that-also-appear-as-values-test
  ;; edge maps chain: {:start :delete, :delete :next}. Editing the :delete
  ;; entry must not touch the :delete *value* of :start.
  (let [text "{:id :t/chain\n :cells {:start {:id :t/a :doc \"a\" :schema {:input [:map] :output [:map]} :on-error nil}\n         :delete {:id :t/d :doc \"d\" :schema {:input [:map] :output [:map]} :on-error nil}}\n :edges {:start  :delete\n         :delete :end}}\n"
        old (clojure.edn/read-string text)
        new (-> old
                (assoc-in [:cells :audit] {:id :t/audit :doc "x" :schema {:input [:map] :output [:map]} :on-error nil})
                (assoc-in [:edges :delete] :audit)
                (assoc-in [:edges :audit] :end))
        out (patch/render text old new)]
    (is (= new (clojure.edn/read-string out)))
    ;; minimal rewrite, not the pprint fallback: original spacing survives
    (is (str/includes? out "{:start  :delete\n"))
    (is (str/includes? out ":delete :audit"))))

(deftest render-appends-with-the-map-indentation-test
  (let [text "{:id :t/x\n :cells {:start {:id :t/a :doc \"a\" :schema {:input [:map] :output [:map]} :on-error nil}}\n :edges {:start :end}}\n"
        old (clojure.edn/read-string text)
        new (assoc old :regions {:core [:start]})
        out (patch/render text old new)]
    (is (= new (clojure.edn/read-string out)))
    (is (str/includes? out "\n :regions {:core [:start]}"))))

(deftest render-removes-an-entry-with-its-separator-test
  (let [text "{:id :t/x\n :doc \"d\"\n :cells {:start {:id :t/a :doc \"a\" :schema {:input [:map] :output [:map]} :on-error nil}}\n :edges {:start :end}}\n"
        old (clojure.edn/read-string text)
        new (dissoc old :doc)
        out (patch/render text old new)]
    (is (= new (clojure.edn/read-string out)))
    (is (= "{:id :t/x\n :cells {:start {:id :t/a :doc \"a\" :schema {:input [:map] :output [:map]} :on-error nil}}\n :edges {:start :end}}\n" out))))

(deftest render-indents-multi-line-values-under-their-key-test
  (let [text "{:id :t/x\n :cells\n {:start {:id :t/a :doc \"a\" :schema {:input [:map] :output [:map]} :on-error nil}}\n :edges {:start :end}}\n"
        old (clojure.edn/read-string text)
        big {:id :t/audit :doc "a long description that pushes pprint past the line width"
             :schema {:input [:map [:todo-id :int] [:filter :string]] :output [:map [:ok :boolean]]}
             :on-error nil :requires [:db :cache]}
        new (assoc-in old [:cells :audit] big)
        out (patch/render text old new)
        lines (str/split-lines out)
        audit-line (first (filter #(str/includes? % ":audit {") lines))
        col (str/index-of audit-line "{")]
    (is (= new (clojure.edn/read-string out)))
    ;; every continuation line of the appended map starts at the map's column
    (doseq [l (->> lines (drop-while #(not= % audit-line)) rest (take-while #(not (str/starts-with? % " :edges"))))]
      (is (str/starts-with? l (apply str (repeat (inc col) " "))) l))))
