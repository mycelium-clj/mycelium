# Mycelium Implementation Guide

A concise reference for implementing workflows with Mycelium. Designed for use as LLM context — include this file when prompting an agent to build Mycelium applications.

---

## Workflow in 60 Seconds

A Mycelium workflow is a directed graph of **cells** (pure data transformations) connected by **edges** (routing). Data accumulates through the graph — each cell receives all keys from every upstream cell.

```clojure
(require '[mycelium.cell :as cell]
         '[mycelium.core :as myc])

;; 1. Define cells (lite schema syntax — {key type})
(cell/defcell :app/validate
  {:input  {:name :string}
   :output {:valid :boolean}}
  (fn [_resources data]
    {:valid (not (empty? (:name data)))}))

(cell/defcell :app/greet
  {:input  {:name :string}
   :output {:greeting :string}}
  (fn [_resources data]
    {:greeting (str "Hello, " (:name data) "!")}))

;; 2. Define workflow
(def workflow
  {:cells {:start :app/validate
           :greet :app/greet}
   :edges {:start :greet
           :greet :end}})

;; 3. Run it
(myc/run-workflow workflow {} {:name "Alice"})
;; => {:name "Alice", :valid true, :greeting "Hello, Alice!", :mycelium/trace [...]}
```

---

## Cell Definition

Use `cell/defcell` to register cells. The ID is specified once (no duplication).

```clojure
;; Minimal — no schema
(cell/defcell :ns/cell-id
  (fn [resources data]
    {:result-key "value"}))

;; With schema (recommended — lite syntax)
(cell/defcell :ns/cell-id
  {:input  {:x :int}
   :output {:y :int}}
  (fn [resources data]
    {:y (* 2 (:x data))}))

;; With schema + options
(cell/defcell :ns/cell-id
  {:input    {:x :int}
   :output   {:y :int}
   :doc      "Doubles the input"
   :requires [:db]}
  (fn [{:keys [db]} data]
    {:y (* 2 (:x data))}))
```

### Cell Rules

1. **Handler signature:** `(fn [resources data] -> data-map)`
2. **Return only new keys.** Key propagation is on by default — input keys are merged automatically. Don't `(assoc data ...)`, just return `{:new-key value}`.
3. **Resources for infrastructure.** Database connections, HTTP clients, config go in `resources`. Data map is for workflow state only.
4. **Errors are data.** Set a key (`:status :failed`) and let dispatch predicates route it. Don't throw for expected failures.
5. **Schemas use [Malli](https://github.com/metosin/malli) syntax** or **lite syntax** (see below).

### Schema Syntax

Two equivalent ways to write schemas:

```clojure
;; Malli vector syntax (full power)
{:input  [:map [:subtotal :double] [:state :string]]
 :output [:map [:tax :double]]}

;; Lite syntax (simpler, recommended for most cases)
{:input  {:subtotal :double, :state :string}
 :output {:tax :double}}
```

Lite syntax auto-converts `{:key :type}` to `[:map [:key :type]]`. It works in `defcell`, `set-cell-schema!`, and manifest schemas. Nested maps are supported:

```clojure
{:input {:address {:street :string, :city :string}}}
;; becomes [:map [:address [:map [:street :string] [:city :string]]]]
```

Use full Malli syntax when you need: enums (`[:enum :a :b]`), unions (`[:or :string :int]`), optional fields, or other advanced features. Both syntaxes can be mixed freely.

Available types: `:string`, `:int`, `:double`, `:boolean`, `:keyword`, `:uuid`, `[:enum :a :b]`, `[:vector :type]`, `[:map-of :key-type :val-type]`, etc.

---

## Workflow Patterns

### Linear Pipeline

```clojure
{:cells    {:start :app/a, :step2 :app/b, :step3 :app/c}
 :pipeline [:start :step2 :step3]}
;; Expands to: :edges {:start :step2, :step2 :step3, :step3 :end}
```

### Branching

```clojure
{:cells {:start :app/check
         :ok    :app/process
         :err   :app/error}
 :edges {:start {:pass :ok, :fail :err}
         :ok :end, :err :end}
 :dispatches {:start [[:pass (fn [d] (:valid d))]
                       [:fail (fn [d] (not (:valid d)))]]}}
```

Handlers compute data. Dispatch predicates choose the route. Separate concerns.

### Per-Transition Output Schema

When a cell branches, declare output per edge:

```clojure
(cell/defcell :app/check
  {:input  [:map [:value :int]]
   :output {:pass [:map [:status [:= :ok]]]
            :fail [:map [:status [:= :error]] [:reason :string]]}}
  (fn [_ data]
    (if (pos? (:value data))
      {:status :ok}
      {:status :error :reason "must be positive"})))
```

### Parallel Join

```clojure
{:cells {:start :app/validate
         :tax   :app/calc-tax        ;; join member — no edges entry
         :ship  :app/calc-shipping   ;; join member — no edges entry
         :total :app/compute-total}
 :joins {:fees {:cells [:tax :ship] :strategy :parallel}}
 :edges {:start :fees
         :fees  {:done :total, :failure :end}
         :total :end}}
```

Join members have **no entries in `:edges`**. Each gets the same input snapshot. Output keys must not overlap.

### Default Fallback

```clojure
:edges {:start {:success :ok, :default :fallback}}
:dispatches {:start [[:success (fn [d] (:valid d))]]}
;; :default auto-generates (constantly true) as last predicate
```

---

## Development Workflow (3 Phases)

Mycelium supports an iterative development workflow. You don't need to get everything right on the first try.

### Phase 1: Structure (compile-time feedback)

Write the manifest with cells, edges, and schemas. Pre-compilation catches structural errors immediately:

```clojure
(myc/pre-compile workflow-def) ;; Throws on: missing edges, unreachable cells, bad schemas
```

Generate cell stubs from the workflow structure:

```clojure
(println (myc/generate-stubs workflow-def))
;; Prints defcell forms with schemas and TODO handlers
```

### Phase 2: Logic (run with warnings, iterate)

Write handler implementations. Run with `:validate :warn` to see all problems at once without halting:

```clojure
(let [result (myc/run-workflow workflow {} test-data {:validate :warn})]
  ;; Workflow runs to completion even if schemas don't match
  (println (:mycelium/warnings result)))
;; => [{:cell-id :app/tax, :phase :output,
;;      :message "Schema output validation failed at :app/tax
;;        Missing key(s): #{:tax}
;;        Extra key(s): #{:tax-amount}",
;;      :key-diff {:missing #{:tax}, :extra #{:tax-amount}}}]
```

Schema errors now include **key-diff suggestions** — if you misname a key, the error tells you exactly what to rename.

You can also skip validation entirely during early development:

```clojure
(myc/run-workflow workflow {} test-data {:validate :off})
```

**Or infer schemas from test runs** — write handlers first, let Mycelium figure out the schemas:

```clojure
(require '[mycelium.dev :as dev])

;; Run with test data, observe actual shapes
(def inferred (dev/infer-schemas workflow-def {} test-inputs))
;; => {:start {:input [:map [:x :int]], :output [:map [:x :int] [:result :int]]}
;;     :fmt   {:input [:map [:x :int] [:result :int]], :output [:map ...]}}

;; Apply inferred schemas to cell registry
(dev/apply-inferred-schemas! inferred workflow-def)
```

### Phase 3: Contract (strict mode)

Once logic is correct, lock down schemas. Run with `:validate :strict` (the default):

```clojure
(let [result (myc/run-workflow workflow {} data)]
  ;; Schema violations halt execution with precise error
  (when-let [err (myc/workflow-error result)]
    (println (:message err))     ;; human-readable with key-diff suggestions
    (println (:key-diff err))    ;; {:missing #{...} :extra #{...}}
    (println (:failed-keys err)) ;; per-key details with types
    ))
```

### Testing Cells in Isolation

```clojure
(dev/test-cell :app/validate {:input {:name "Alice"}})
;; => {:pass? true, :output {:valid true}, :errors [], :duration-ms 0.1}
```

---

## Common Mistakes

### 1. Returning the full data map

```clojure
;; WRONG — don't thread the full map
(fn [_ data] (assoc data :y (* 2 (:x data))))

;; RIGHT — return only new keys (key propagation merges input)
(fn [_ data] {:y (* 2 (:x data))})
```

Both work, but the second is idiomatic. Key propagation is on by default.

### 2. Wrong items format

If the spec says items are `[{"laptop" 1} {"shirt" 2}]`, keep that format. Mycelium doesn't impose any format on your data — it flows whatever you put in. Match the domain spec exactly.

### 3. Returning nil from a cell

Cell handlers must return a map! Returning `nil` causes downstream failures. If a cell has nothing to add, return `{}`.

---

## Quick Reference

| Concept | Syntax |
|---------|--------|
| Register cell | `(cell/defcell :ns/id {:input {:x :int} :output {:y :int}} handler-fn)` |
| Lite schema | `{:input {:x :int}}` → `{:input [:map [:x :int]]}` |
| Linear pipeline | `{:pipeline [:a :b :c]}` |
| Branching edges | `{:edges {:start {:label :target}}}` |
| Dispatch | `{:dispatches {:start [[:label (fn [d] pred)]]}}` |
| Parallel join | `{:joins {:name {:cells [:a :b] :strategy :parallel}}}` |
| Run workflow | `(myc/run-workflow wf resources data)` |
| Run with warnings | `(myc/run-workflow wf res data {:validate :warn})` |
| Run without validation | `(myc/run-workflow wf res data {:validate :off})` |
| Pre-compile | `(def c (myc/pre-compile wf))` then `(myc/run-compiled c res data)` |
| Check error | `(myc/workflow-error result)` — includes `:key-diff` suggestions |
| Infer schemas | `(dev/infer-schemas wf res [test-data ...])` |
| Apply inferred | `(dev/apply-inferred-schemas! inferred wf)` |
| Test cell | `(dev/test-cell :ns/id {:input {...}})` |
| Generate stubs | `(println (myc/generate-stubs wf))` |
| Coerce types | `(myc/pre-compile wf {:coerce? true})` |
