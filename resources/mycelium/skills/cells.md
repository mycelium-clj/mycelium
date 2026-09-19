---
name: cells
description: Handler contract: signature, schemas, params, isolation testing.
---
# Cell Authoring

A cell is a pure data transformation behind a schema contract. It knows
nothing about other cells — data arrives from upstream, results flow to
whatever the graph routes to next.

## Handler Contract {#handler-contract}

```clojure
(defmethod cell/cell-spec :math/double [_]
  {:id      :math/double
   :handler (fn [resources data]
              {:result (* 2 (:x data))})
   :schema  {:input  {:x :int}
             :output {:result :int}}})
```

- Signature: `(fn [resources data] -> new-keys-map)`
- Return ONLY new/changed keys — key propagation merges them over input.
  Returning the full map works but is not idiomatic.
- Never return nil; return `{}` if the cell adds nothing.
- Infrastructure (db, http, config) comes from `resources`, declared via
  `:requires [:db]`. Data map carries workflow state only.
- Expected failures are keys (`:status :failed`), not exceptions — dispatch
  predicates route on them.
- Async cells take `(fn [resources data callback error-callback])` and set
  `:async? true`.

## Schemas {#schemas}

Two syntaxes, mixable: lite `{:x :int}` and Malli `[:map [:x :int]]`. Nested
maps supported in lite syntax. Use Malli for enums, unions, optionals.

Branching cells wrap per-transition outputs explicitly:

```clojure
:output [:per-transition
         {:pass [:map [:status [:= :ok]]]
          :fail [:map [:status [:= :error]] [:reason :string]]}]
```

A bare map output is ALWAYS lite-map syntax, never per-transition.

## Params And Reuse {#params-reuse}

One cell-id can appear at multiple workflow positions with different
configuration:

```clojure
:cells {:x3 {:id :math/multiply :params {:factor 3}}
        :x5 {:id :math/multiply :params {:factor 5}}}
```

Params arrive as `:mycelium/params` in data and are cleaned up after the step.

## Isolation Testing {#isolation-testing}

`(dev/test-cell :ns/id {:input {...} :resources {...}})` runs one cell with
full schema validation and returns `{:pass? :errors :output :duration-ms}`.
Add `:dispatches` + `:expected-dispatch` to verify routing. A cell that
can't be tested with a plain data map and a stub resource map is misdesigned.
