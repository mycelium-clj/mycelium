(ns mycelium.store-test
  (:require [clojure.test :refer [deftest is testing use-fixtures]]
            [mycelium.cell :as cell]
            [mycelium.core :as myc]
            [mycelium.store :as store]))

(use-fixtures :each (fn [f] (cell/clear-registry!) (f)))

;; ===== Round 1: Memory store basics =====

(deftest memory-store-save-load-test
  (testing "save-workflow! persists and load-workflow retrieves"
    (let [s (store/memory-store)
          data {:mycelium/halt true :mycelium/resume :some-state :x 1}]
      (store/save-workflow! s "sess-1" data)
      (is (= data (store/load-workflow s "sess-1"))))))

(deftest memory-store-delete-test
  (testing "delete-workflow! removes persisted state"
    (let [s (store/memory-store)]
      (store/save-workflow! s "sess-1" {:x 1})
      (store/delete-workflow! s "sess-1")
      (is (nil? (store/load-workflow s "sess-1"))))))

(deftest memory-store-list-test
  (testing "list-workflows returns all session IDs"
    (let [s (store/memory-store)]
      (store/save-workflow! s "a" {:x 1})
      (store/save-workflow! s "b" {:x 2})
      (is (= #{"a" "b"} (set (store/list-workflows s)))))))

(deftest memory-store-overwrite-test
  (testing "save-workflow! overwrites existing state"
    (let [s (store/memory-store)]
      (store/save-workflow! s "sess-1" {:x 1})
      (store/save-workflow! s "sess-1" {:x 2})
      (is (= {:x 2} (store/load-workflow s "sess-1")))
      (is (= 1 (count (store/list-workflows s)))))))

(deftest new-session-id-test
  (testing "new-session-id returns unique strings"
    (let [ids (repeatedly 100 store/new-session-id)]
      (is (= 100 (count (set ids))))
      (is (every? string? ids)))))

;; ===== Round 2: run-with-store halts =====

(deftest run-with-store-halt-persists-test
  (testing "Halted workflow state is persisted to store"
    (defmethod cell/cell-spec :store/halt-cell [_]
      {:id :store/halt-cell
       :handler (fn [_ data] (assoc data :done true :mycelium/halt {:reason :review}))
       :schema {:input [:map] :output [:map]}})

    (let [s (store/memory-store)
          compiled (myc/pre-compile
                     {:cells {:start :store/halt-cell}
                      :edges {:start :end}})
          result (store/run-with-store compiled {} {} s)]
      (is (string? (:mycelium/session-id result)) "Returns session ID")
      (is (= {:reason :review} (:mycelium/halt result)) "Returns halt context")
      (is (nil? (:mycelium/resume result)) "Resume token not exposed to caller")
      (let [stored (store/load-workflow s (:mycelium/session-id result))]
        (is (some? stored) "State persisted in store")
        (is (some? (:mycelium/resume stored)) "Stored state has resume token")
        (is (true? (:done stored)) "Stored state has cell output")))))

(deftest run-with-store-completes-no-persist-test
  (testing "Completed workflow is not persisted"
    (defmethod cell/cell-spec :store/ok-cell [_]
      {:id :store/ok-cell
       :handler (fn [_ data] (assoc data :done true))
       :schema {:input [:map] :output [:map]}})

    (let [s (store/memory-store)
          compiled (myc/pre-compile
                     {:cells {:start :store/ok-cell}
                      :edges {:start :end}})
          result (store/run-with-store compiled {} {} s)]
      (is (true? (:done result)) "Returns normal result")
      (is (nil? (:mycelium/session-id result)) "No session ID for completed workflow")
      (is (empty? (store/list-workflows s)) "Nothing persisted"))))

;; ===== Round 3: resume-with-store completes =====

(deftest resume-with-store-completes-test
  (testing "Resume from store completes and deletes state"
    (defmethod cell/cell-spec :store/r-step1 [_]
      {:id :store/r-step1
       :handler (fn [_ data] (assoc data :step1 true :mycelium/halt true))
       :schema {:input [:map] :output [:map]}})
    (defmethod cell/cell-spec :store/r-step2 [_]
      {:id :store/r-step2
       :handler (fn [_ data] (assoc data :step2 true))
       :schema {:input [:map] :output [:map]}})

    (let [s (store/memory-store)
          compiled (myc/pre-compile
                     {:cells {:start :store/r-step1, :next :store/r-step2}
                      :edges {:start :next, :next :end}})
          halted (store/run-with-store compiled {} {} s)
          session-id (:mycelium/session-id halted)
          result (store/resume-with-store compiled {} session-id s)]
      (is (true? (:step1 result)) "Data from before halt")
      (is (true? (:step2 result)) "Resumed cell ran")
      (is (nil? (:mycelium/session-id result)) "No session ID on completion")
      (is (nil? (store/load-workflow s session-id)) "State deleted after completion"))))

;; ===== Round 4: resume-with-store re-halts =====

(deftest resume-with-store-rehalts-test
  (testing "Resume that halts again updates the store"
    (defmethod cell/cell-spec :store/rh-a [_]
      {:id :store/rh-a
       :handler (fn [_ data] (assoc data :a true :mycelium/halt true))
       :schema {:input [:map] :output [:map]}})
    (defmethod cell/cell-spec :store/rh-b [_]
      {:id :store/rh-b
       :handler (fn [_ data] (assoc data :b true :mycelium/halt true))
       :schema {:input [:map] :output [:map]}})
    (defmethod cell/cell-spec :store/rh-c [_]
      {:id :store/rh-c
       :handler (fn [_ data] (assoc data :c true))
       :schema {:input [:map] :output [:map]}})

    (let [s (store/memory-store)
          compiled (myc/pre-compile
                     {:cells {:start :store/rh-a, :mid :store/rh-b, :fin :store/rh-c}
                      :pipeline [:start :mid :fin]})
          halted1 (store/run-with-store compiled {} {} s)
          sid (:mycelium/session-id halted1)
          halted2 (store/resume-with-store compiled {} sid s)]
      (is (= sid (:mycelium/session-id halted2)) "Same session ID reused")
      (is (some? (:mycelium/halt halted2)) "Re-halted")
      (let [stored (store/load-workflow s sid)]
        (is (true? (:a stored)) "First cell data present")
        (is (true? (:b stored)) "Second cell data present"))
      ;; Final resume completes
      (let [result (store/resume-with-store compiled {} sid s)]
        (is (true? (:c result)))
        (is (nil? (store/load-workflow s sid)) "Cleaned up after completion")))))

;; ===== Round 5: resume-with-store merge data =====

(deftest resume-with-store-merge-data-test
  (testing "Human input merged on resume"
    (defmethod cell/cell-spec :store/ask [_]
      {:id :store/ask
       :handler (fn [_ data] (assoc data :question "Approve?" :mycelium/halt true))
       :schema {:input [:map] :output [:map]}})
    (defmethod cell/cell-spec :store/decide [_]
      {:id :store/decide
       :handler (fn [_ data] (assoc data :result (if (:approved data) "yes" "no")))
       :schema {:input [:map] :output [:map]}})

    (let [s (store/memory-store)
          compiled (myc/pre-compile
                     {:cells {:start :store/ask, :next :store/decide}
                      :edges {:start :next, :next :end}})
          halted (store/run-with-store compiled {} {} s)
          sid (:mycelium/session-id halted)
          result (store/resume-with-store compiled {} sid s {:approved true})]
      (is (= "yes" (:result result))))))

;; ===== Round 6: Error cases =====

(deftest resume-unknown-session-throws-test
  (testing "Resuming unknown session throws"
    (defmethod cell/cell-spec :store/dummy [_]
      {:id :store/dummy
       :handler (fn [_ data] (assoc data :x true))
       :schema {:input [:map] :output [:map]}})

    (let [s (store/memory-store)
          compiled (myc/pre-compile
                     {:cells {:start :store/dummy}
                      :edges {:start :end}})]
      (is (thrown-with-msg? Exception #"not found"
            (store/resume-with-store compiled {} "nonexistent" s))))))

;; ===== Round 7: Custom store via reify =====

(deftest custom-store-protocol-test
  (testing "Custom store implementation works via protocol"
    (defmethod cell/cell-spec :store/custom-cell [_]
      {:id :store/custom-cell
       :handler (fn [_ data] (assoc data :ran true :mycelium/halt true))
       :schema {:input [:map] :output [:map]}})

    (let [state (atom {})
          custom-store (reify store/WorkflowStore
                         (save-workflow! [_ session-id data]
                           (swap! state assoc session-id data)
                           session-id)
                         (load-workflow [_ session-id]
                           (get @state session-id))
                         (delete-workflow! [_ session-id]
                           (swap! state dissoc session-id)
                           nil)
                         (list-workflows [_]
                           (keys @state)))
          compiled (myc/pre-compile
                     {:cells {:start :store/custom-cell}
                      :edges {:start :end}})
          result (store/run-with-store compiled {} {} custom-store)]
      (is (string? (:mycelium/session-id result)))
      (is (= 1 (count @state)) "Custom store received the data"))))
