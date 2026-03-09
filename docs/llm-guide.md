# Mycelium Implementation Guide

A concise reference for implementing workflows with Mycelium. Designed for use as LLM context — include this file when prompting an agent to build Mycelium applications.

---

## Workflow in 60 Seconds

A Mycelium workflow is a directed graph of **cells** (pure data transformations) connected by **edges** (routing). Data accumulates through the graph — each cell receives all keys from every upstream cell.

```clojure
(require '[mycelium.cell :as cell]
         '[mycelium.core :as myc])

;; 1. Define cells
(cell/defcell :app/validate
  {:input  [:map [:name :string]]
   :output [:map [:valid :boolean]]}
  (fn [_resources data]
    {:valid (not (empty? (:name data)))}))

(cell/defcell :app/greet
  {:input  [:map [:name :string]]
   :output [:map [:greeting :string]]}
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

;; With schema (recommended)
(cell/defcell :ns/cell-id
  {:input  [:map [:x :int]]
   :output [:map [:y :int]]}
  (fn [resources data]
    {:y (* 2 (:x data))}))

;; With schema + options
(cell/defcell :ns/cell-id
  {:input    [:map [:x :int]]
   :output   [:map [:y :int]]
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
5. **Schemas use [Malli](https://github.com/metosin/malli) syntax.** `[:map [:key :type]]` for maps. Types: `:string`, `:int`, `:double`, `:boolean`, `:keyword`, `:uuid`, `[:enum :a :b]`, `[:vector :type]`, etc.

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

## Development Workflow

### 1. Write the Manifest First

Define cells, edges, schemas. Compilation validates everything:

```clojure
(myc/pre-compile workflow-def) ;; Throws on invalid structure
```

### 2. Generate Stubs

```clojure
(println (myc/generate-stubs workflow-def))
;; Prints defcell forms with schemas and TODO handlers
```

Copy the output, fill in handler logic. Schemas and wiring are pre-validated.

### 3. Test Cells in Isolation

```clojure
(require '[mycelium.dev :as dev])

(dev/test-cell :app/validate {:input {:name "Alice"}})
;; => {:pass? true, :output {:valid true}, :errors [], :duration-ms 0.1}
```

### 4. Test the Workflow

```clojure
(let [result (myc/run-workflow workflow {} {:name "Alice"})]
  (is (nil? (myc/workflow-error result)))
  (is (= "Hello, Alice!" (:greeting result))))
```

### 5. Check Errors

```clojure
(when-let [err (myc/workflow-error result)]
  ;; err has :error-type, :cell-name, :cell-id, :message, :failed-keys
  (println (:message err)))
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
| Register cell | `(cell/defcell :ns/id schema-map handler-fn)` |
| Linear pipeline | `{:pipeline [:a :b :c]}` |
| Branching edges | `{:edges {:start {:label :target}}}` |
| Dispatch | `{:dispatches {:start [[:label (fn [d] pred)]]}}` |
| Parallel join | `{:joins {:name {:cells [:a :b] :strategy :parallel}}}` |
| Run workflow | `(myc/run-workflow wf resources data)` |
| Pre-compile | `(def c (myc/pre-compile wf))` then `(myc/run-compiled c res data)` |
| Check error | `(myc/workflow-error result)` |
| Test cell | `(dev/test-cell :ns/id {:input {...}})` |
| Generate stubs | `(println (myc/generate-stubs wf))` |
| Coerce types | `(myc/pre-compile wf {:coerce? true})` |
