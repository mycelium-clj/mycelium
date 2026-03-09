# Developing Workflows

A practical guide to going from a problem statement to a working, tested Mycelium workflow. This document walks through the full development process using a loan application as a running example.

---

## 1. Decompose the Problem into Steps

Start by listing the discrete operations your system must perform. Each operation becomes a cell. A cell does **one thing well** — if you find yourself describing a cell with "and", split it.

**Problem:** Process a loan application. Validate the input, assess credit risk, decide eligibility, handle approved/rejected/review outcomes, log the decision, and notify the applicant.

Break it down:

```
1. Validate the application (are required fields present and valid?)
2. Look up credit history (external data source)
3. Calculate a credit score (pure computation)
4. Classify risk level (pure computation)
5. Decide eligibility (routing logic: approve, reject, or review)
6. Auto-approve (set status, calculate interest rate)
7. Auto-reject (set status and reason)
8. Queue for review (set status and review queue)
9. Log to audit trail (cross-cutting, shared across outcomes)
10. Send notification (format and deliver)
```

Each line is a single responsibility. "Look up credit history" does not also calculate a score. "Decide eligibility" does not also approve the loan — it sets a routing key and lets the graph dispatch.

### When to Split a Cell

Split when a cell:
- Has two logical phases (fetch then transform)
- Produces output for different consumers (one key for routing, another for display)
- Could fail independently (network call vs. local computation)
- Contains a conditional that could be a graph branch

### When NOT to Split

Keep together when:
- The logic is a single expression with no meaningful intermediate state
- Splitting would create cells with trivial schemas that add noise
- The operations are tightly coupled and always run together

---

## 2. Design the Graph

Lay out how cells connect. Think in terms of edges (what follows what), branches (what decides the route), and joins (what runs in parallel).

```
[validate] ──valid──> [credit-assessment] ──success──> [eligibility]
     │                                                      │
     └──invalid──> END                           ┌──approve─┤──reject──┐──review──┐
                                                 v          v          v
                                           [auto-approve] [auto-reject] [queue-review]
                                                 │          │          │
                                                 └────┬─────┘──────────┘
                                                      v
                                                 [audit-log] ──> [send-notification] ──> END
```

Identify the graph primitives:

- **Linear sequences:** validate -> credit-assessment, audit-log -> send-notification
- **Branches:** eligibility dispatches to one of three outcome cells
- **Convergence:** all three outcomes flow into audit-log
- **Joins:** steps 2-4 (credit lookup, score, classify) are a linear sub-pipeline that could be a subworkflow

Write the structure as a workflow definition before implementing any cells:

```clojure
(def loan-workflow
  {:cells
   {:start             :loan/validate-application
    :credit-assessment :loan/credit-assessment    ;; subworkflow
    :eligibility       :loan/eligibility-decision
    :auto-approve      :loan/auto-approve
    :auto-reject       :loan/auto-reject
    :queue-review      :loan/queue-for-review
    :audit-log         :loan/audit-log
    :send-notification :loan/send-notification}

   :edges
   {:start             {:valid :credit-assessment, :invalid :end}
    :credit-assessment {:success :eligibility, :failure :end}
    :eligibility       {:approve :auto-approve, :reject :auto-reject, :review :queue-review}
    :auto-approve      :audit-log
    :auto-reject       :audit-log
    :queue-review      :audit-log
    :audit-log         :send-notification
    :send-notification :end}

   :dispatches
   {:start       [[:valid   (fn [d] (= :valid (:validation-status d)))]
                  [:invalid (fn [d] (= :invalid (:validation-status d)))]]
    :eligibility [[:approve (fn [d] (= :approve (:decision d)))]
                  [:reject  (fn [d] (= :reject (:decision d)))]
                  [:review  (fn [d] (= :review (:decision d)))]]}})
```

This definition will compile (and validate) before any cell is implemented. If you misspell an edge target or reference a nonexistent cell, compilation catches it immediately.

---

## 3. Define the Data Model

The data model is the set of keys that flow through the workflow. Each cell declares what it needs (input schema) and what it produces (output schema). Data accumulates — every cell receives all keys from every upstream cell.

Map out the keys:

| Cell | Reads | Produces |
|------|-------|----------|
| validate | `:applicant-name`, `:income`, `:loan-amount` | `:validation-status`, `:validation-errors` |
| credit-bureau-lookup | `:applicant-name` | `:credit-history` |
| calculate-score | `:credit-history` | `:credit-score` |
| classify-risk | `:credit-score` | `:risk-level` |
| eligibility-decision | `:risk-level`, `:loan-amount` | `:decision` |
| auto-approve | `:credit-score` | `:application-status`, `:interest-rate`, `:decision-reason` |
| auto-reject | `:risk-level` | `:application-status`, `:decision-reason` |
| queue-for-review | `:risk-level`, `:loan-amount` | `:application-status`, `:review-queue`, `:decision-reason` |
| audit-log | `:applicant-name` | `:audit-trail` |
| send-notification | `:application-status`, `:applicant-name` | `:notification` |

This table serves two purposes:
1. It becomes the Malli schemas in cell specs
2. It lets you verify that every cell's input keys are available from upstream outputs

With key propagation (on by default), cells only need to return new keys — input keys are automatically merged forward. This means your output schemas only declare what the cell adds, not everything downstream might need.

### Schema Design Principles

**Be specific in schemas.** Use enums for known value sets, not bare keywords:

```clojure
;; Good: the schema documents the domain
:output {:approve [:map [:decision [:= :approve]]]
         :reject  [:map [:decision [:= :reject]]]
         :review  [:map [:decision [:= :review]]]}

;; Weak: tells you nothing about valid values
:output [:map [:decision :keyword]]
```

**Use per-transition output schemas** when a cell branches. This lets the schema chain validator verify that each downstream path receives the right shape.

**Use open schemas (`:map`) for cells that receive external input** (e.g., after halt/resume), since the schema chain can't track keys injected from outside.

---

## 4. Implement Cells

Each cell is implemented independently. A cell needs only its own schema to be implemented correctly — you don't need to read any other cell's code.

### Cell Structure

Every cell follows the same pattern:

```clojure
(defmethod cell/cell-spec :loan/classify-risk [_]
  {:id      :loan/classify-risk
   :handler (fn [_resources data]
              (let [score (:credit-score data)]
                {:risk-level (cond
                               (>= score 720) :low
                               (>= score 620) :medium
                               :else          :high)}))
   :schema  {:input  [:map [:credit-score :int]]
             :output [:map [:risk-level [:enum :low :medium :high]]]}})
```

Key principles:

**One thing well.** This cell classifies risk. It doesn't also calculate the score or decide eligibility. Each cell is a single, testable transformation.

**Pure data in, data out.** The handler receives `resources` and `data`, returns new keys. No global state, no side-channel communication between cells.

**Side effects through resources.** When a cell needs a database or API, it receives it via the resources map:

```clojure
(defmethod cell/cell-spec :loan/credit-bureau-lookup [_]
  {:id       :loan/credit-bureau-lookup
   :handler  (fn [{:keys [credit-db]} data]
               (let [history (get credit-db (:applicant-name data))]
                 {:credit-history (or history {:accounts 0 :late-payments 0
                                               :bankruptcies 0 :years-of-history 0})}))
   :schema   {:input  [:map [:applicant-name :string]]
              :output [:map [:credit-history [:map
                                              [:accounts :int]
                                              [:late-payments :int]
                                              [:bankruptcies :int]
                                              [:years-of-history :int]]]]}
   :requires [:credit-db]})
```

This keeps the cell testable — pass a mock `credit-db` in tests.

**Errors are data, not exceptions.** When a cell encounters an expected failure (invalid input, resource not found), set a key on the data map and let the graph route it:

```clojure
;; The cell sets :validation-status — it does NOT throw
(if (valid? data)
  {:validation-status :valid}
  {:validation-status :invalid
   :validation-errors (collect-errors data)})
```

Reserve exceptions for truly unexpected failures. Use error groups to catch those.

---

## 5. Test Each Cell Independently

Test cells before wiring them into a workflow. Each cell is a pure function — give it input, check its output.

```clojure
(require '[mycelium.dev :as dev])

;; Basic: does the cell produce correct output?
(deftest classify-risk-test
  (let [results (dev/test-transitions :loan/classify-risk
                  {:low    {:input {:credit-score 750}}
                   :medium {:input {:credit-score 650}}
                   :high   {:input {:credit-score 500}}})]
    (is (get-in results [:low :pass?]))
    (is (= :low (get-in results [:low :output :risk-level])))
    (is (= :medium (get-in results [:medium :output :risk-level])))
    (is (= :high (get-in results [:high :output :risk-level])))))
```

### What to Test in a Cell

**Schema conformance.** `dev/test-cell` validates output against the declared schema automatically. If `:pass?` is true, the output conforms.

**Dispatch routing.** For cells with branching, verify the correct dispatch fires:

```clojure
(deftest eligibility-approve-test
  (let [result (dev/test-cell :loan/eligibility-decision
                 {:input      {:risk-level :low :loan-amount 30000}
                  :dispatches [[:approve (fn [d] (= :approve (:decision d)))]
                               [:reject  (fn [d] (= :reject (:decision d)))]
                               [:review  (fn [d] (= :review (:decision d)))]]})]
    (is (:pass? result))
    (is (= :approve (:matched-dispatch result)))))
```

**Edge cases and boundaries.** Test threshold values, empty inputs, missing optional keys:

```clojure
;; Score clamping
(deftest score-clamped-test
  (let [result-low  (dev/test-cell :loan/calculate-score
                      {:input {:credit-history {:accounts 0 :late-payments 10
                                                :bankruptcies 3 :years-of-history 0}}})
        result-high (dev/test-cell :loan/calculate-score
                      {:input {:credit-history {:accounts 10 :late-payments 0
                                                :bankruptcies 0 :years-of-history 20}}})]
    (is (= 300 (get-in result-low [:output :credit-score])))
    (is (= 850 (get-in result-high [:output :credit-score])))))
```

**Resource-dependent cells.** Pass mock resources:

```clojure
(deftest fetch-found-test
  (let [store  (atom {"Alice" {:application-status :approved}})
        result (dev/test-cell :loan/fetch-application
                 {:input     {:applicant-name "Alice"}
                  :resources {:app-store store}
                  :dispatches [[:found (fn [d] (= :found (:fetch-status d)))]
                               [:not-found (fn [d] (= :not-found (:fetch-status d)))]]})]
    (is (= :found (:matched-dispatch result)))))
```

### Cell Testing Checklist

For each cell, verify:
- Output conforms to declared schema (`:pass?` is true)
- Correct dispatch fires for each scenario
- Edge cases produce valid output (not exceptions)
- Resources are used through the resources map, not hardcoded

---

## 6. Test the Workflow

Once all cells pass individually, test the integrated workflow. Workflow tests verify that cells compose correctly — data flows through the graph, dispatches route to the right branches, and the final output has all expected keys.

### Path-Based Testing

Test each distinct path through the graph:

```clojure
;; Happy path: good credit, small loan -> auto-approve
(deftest auto-approve-path-test
  (let [result (myc/run-compiled compiled-workflow
                 {:credit-db credit-db, :app-store (atom {})}
                 {:applicant-name "Alice" :income 80000 :loan-amount 30000})]
    (is (= :approved (:application-status result)))
    (is (number? (:interest-rate result)))
    (is (some? (:notification result)))
    (is (seq (:audit-trail result)))))

;; Bad credit -> auto-reject
(deftest auto-reject-path-test
  (let [result (myc/run-compiled compiled-workflow
                 {:credit-db credit-db, :app-store (atom {})}
                 {:applicant-name "Carol" :income 40000 :loan-amount 15000})]
    (is (= :rejected (:application-status result)))
    (is (string? (:decision-reason result)))))

;; Early exit: invalid input
(deftest invalid-application-test
  (let [result (myc/run-compiled compiled-workflow
                 {:credit-db credit-db, :app-store (atom {})}
                 {:applicant-name "" :income nil :loan-amount nil})]
    (is (= :invalid (:validation-status result)))
    (is (nil? (:credit-score result)))      ;; never reached credit assessment
    (is (nil? (:notification result)))))    ;; never reached notification
```

### Trace Verification

Use the execution trace to verify the path taken:

```clojure
(deftest trace-reject-path-test
  (let [result   (myc/run-compiled compiled-workflow resources data)
        trace    (:mycelium/trace result)
        cell-ids (set (map :cell-id trace))]
    ;; Reject path should hit auto-reject, not auto-approve
    (is (contains? cell-ids :loan/auto-reject))
    (is (not (contains? cell-ids :loan/auto-approve)))
    (is (not (contains? cell-ids :loan/queue-for-review)))))
```

### Error Handling Tests

Verify that errors are caught and routed correctly:

```clojure
(deftest error-handling-test
  (let [result (myc/run-compiled compiled-workflow resources bad-data)]
    (if (myc/error? result)
      (let [{:keys [error-type message]} (myc/workflow-error result)]
        (is (#{:schema/input :handler} error-type)))
      ;; Or verify the workflow handled it gracefully via dispatch
      (is (= :invalid (:validation-status result))))))
```

### Workflow Testing Checklist

- Every path from `:start` to `:end` has at least one test
- Early exits (validation failure, not-found) verify that downstream cells didn't run
- Trace confirms the expected sequence of cells
- Error paths produce meaningful output

---

## 7. Iterate and Refine

### Use Dev Tools

```clojure
;; Enumerate all possible paths
(dev/enumerate-paths workflow-def)

;; See accumulated schema at each cell
(dev/infer-workflow-schema workflow-def)

;; Check for unreachable cells or cycles
(dev/analyze-workflow workflow-def)

;; Debug a run in the REPL
(myc/run-workflow wf resources data {:on-trace (dev/trace-logger)})
```

### Extract Subworkflows

When a group of cells forms a cohesive sub-process, extract it as a subworkflow. In the loan example, credit assessment (lookup -> score -> classify) is a natural subworkflow:

```clojure
(def credit-assessment-workflow
  {:cells      {:start         :loan/credit-bureau-lookup
                :calc-score    :loan/calculate-score
                :classify-risk :loan/classify-risk}
   :edges      {:start :calc-score, :calc-score :classify-risk, :classify-risk :end}
   :dispatches {}})

(compose/register-workflow-cell!
  :loan/credit-assessment
  credit-assessment-workflow
  {:input  [:map [:applicant-name :string]]
   :output [:map [:credit-score :int] [:risk-level [:enum :low :medium :high]]]})
```

Now the parent workflow uses `:loan/credit-assessment` as a single cell. The sub-process can be tested independently and reused in other workflows.

### Add Resilience and Timeouts

Once the core logic works, layer on operational concerns:

```clojure
;; Add timeout for external credit bureau lookup
:timeouts {:credit-assessment 10000}

;; Add resilience for flaky external APIs
:resilience {:credit-assessment {:retry {:max-attempts 3 :wait-ms 500}
                                  :circuit-breaker {:failure-rate 50
                                                    :minimum-calls 10}}}
```

These are declared in the workflow definition — cell handlers stay focused on domain logic.

### Add Edge Transforms

When integrating cells with mismatched key names — especially when reusing existing cells from different contexts — use edge transforms instead of modifying cell handlers or adding adapter cells:

```clojure
;; Cell A produces :user-name, Cell B expects :name
:transforms {:cell-a {:output {:fn     (fn [data] (assoc data :name (:user-name data)))
                                :schema {:input  [:map [:user-name :string]]
                                         :output [:map [:name :string]]}}}}
```

Transforms are validated at compile time — their schemas participate in the schema chain validation, catching mismatches before runtime.

For branching cells, apply different transforms per edge when downstream cells have different contracts:

```clojure
:transforms {:classify {:premium {:output {:fn ... :schema ...}}
                         :basic   {:output {:fn ... :schema ...}}}}
```

**When to use transforms vs. cells:** Transforms are for mechanical reshaping (key renaming, structural mapping). If the reshaping involves domain logic or needs its own tests, make it a cell.

### Add Constraints

Declare structural invariants once the graph is stable:

```clojure
:constraints [{:type :must-follow :if :eligibility :then :audit-log}
              {:type :never-together :cells [:auto-approve :auto-reject]}
              {:type :always-reachable :cell :audit-log}]
```

---

## Summary

The development process follows a consistent pattern:

1. **Decompose** — Break the problem into single-responsibility steps
2. **Graph** — Connect the steps with edges, branches, and joins
3. **Model** — Define the data keys each step reads and produces
4. **Implement** — Write each cell as a pure function with schema contracts
5. **Unit test** — Test each cell in isolation with `dev/test-cell`
6. **Integrate** — Test the full workflow path by path
7. **Refine** — Extract subworkflows, add resilience, add constraints

The manifest is the architecture. Write it first. Once it compiles, each cell can be implemented and tested independently. When all cells pass, the workflow works — the framework guarantees that data flows correctly through the graph.
