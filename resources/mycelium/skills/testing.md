---
name: testing
description: Isolated cell tests, trace-based workflow tests, schema iteration, CLI gate.
---
# Testing

Mycelium's claim is that schema contracts catch integration bugs early.
Testing therefore splits into cheap isolated cell tests plus trace-based
workflow tests — no mocking frameworks needed.

## Isolated Cell Tests {#cell-tests}

```clojure
(dev/test-cell :user/fetch-profile
  {:input     {:user-id "alice"}
   :resources {:db stub-db}
   :dispatches [[:found (fn [d] (:profile d))]
                [:not-found (fn [d] (:error-type d))]]
   :expected-dispatch :found})
;; => {:pass? true :matched-dispatch :found :output {...}}
```

Multiple dispatch paths: `dev/test-transitions` takes a map of
label → case and asserts each case lands on its own label. Omitting
`:dispatches` tests output only — useful for cells without branches.

## Workflow Tests Via Trace {#trace-tests}

Run the workflow and assert on `:mycelium/trace`:

```clojure
(let [result (myc/run-workflow wf {} {:x 5})
      trace  (:mycelium/trace result)]
  (is (= [:start :validate :finish] (mapv :cell trace)))
  (is (= [:ok :authorized :done] (mapv :transition trace)))
  (is (= 42 (get-in (last trace) [:data :result]))))
```

The trace is a flight recorder: cell, transition taken, data snapshot at each
step, per-member timing inside `:join-traces`. On schema failure the failing
entry carries `:error` with `:key-diff` (missing/extra keys) and
`:failed-keys` (actual value + type per bad key).

## Schema Iteration {#schema-iteration}

Write handlers first, infer schemas from real runs:

```clojure
(def inferred (dev/infer-schemas wf {} [input-1 input-2]))
(dev/apply-inferred-schemas! inferred wf)
```

Then lock down: run with `:validate :strict` (default). During development
`:validate :warn` collects all violations without halting; `:validate :off`
skips checks entirely.

## CLI: One Cell, Then The Gate {#cli-gate}

`myc test <manifest> <cell> [--input '{...}']` is `dev/test-cell` from the
shell: schema validation on both sides, the manifest's dispatch predicates
evaluated so the matched label is reported, errors tagged by phase
(`input`/`output`/`dispatch`). Without `--input` it uses a generated input
from the schema. Exit 3 on failure; `--json` for the full result map.

`myc status <manifest>` re-runs every cell with generated inputs and exits 3
unless all pass. It is the cheap pre-PR check; the kaocha suite remains the
real contract.
