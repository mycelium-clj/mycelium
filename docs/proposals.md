# Feature Proposals

Evaluated ideas inspired by Harel statecharts, ranked by combined value and feasibility. History states (#1 from the original ranking) has been implemented as halt/resume — see the [quick reference](quick-reference.md#halt--resume-human-in-the-loop).

## ~~1. Default Transitions~~ (Implemented)

**Value: Medium-High | Feasibility: High** — **DONE**

Currently if no dispatch predicate matches, Maestro throws — catastrophic for agent-generated routing logic. A `:default` edge label that auto-generates a catch-all `(constantly true)` as the last dispatch predicate would be ~10 lines in `compile-edges`. Every complex workflow benefits from `{:success :next, :default :error-handler}` as a safety net.

### Proposed API

```clojure
{:edges {:start {:success :process, :default :error-handler}
         :process {:done :end, :default :error-handler}
         :error-handler :end}
 :dispatches {:start [[:success (fn [d] (:valid d))]]
              ;; :default auto-generates (constantly true) as last predicate
              }}
```

### Implementation

- In `compile-edges` (workflow.clj), detect `:default` edge labels
- Auto-append `[:default (constantly true)]` as the final dispatch predicate for cells that have a `:default` edge
- Validate that `:default` is never the *only* edge (would mean unconditional — just use a bare keyword edge instead)
- Schema chain validation: the `:default` path inherits only the cell's input keys (no output guarantees since the handler may have partially failed)

### Scope

~30-50 lines across `workflow.clj` + tests.

---

## ~~2. Temporal Logic / Global Constraints~~ (Implemented)

**Value: High | Feasibility: Medium** — **DONE**

"If a file is flagged as missing metadata, it must pass through a tagging cell before `:end`" — the kind of invariant that catches agent mistakes at compile time rather than runtime. `dev/enumerate-paths` already walks all paths; a constraint checker would layer on top.

### Proposed Constraint Language

```clojure
{:constraints [{:type :must-follow
                :if   :flag-missing     ;; if this cell runs...
                :then :apply-tags}      ;; ...this cell must appear later on the same path

               {:type :must-precede
                :cell :validate         ;; this cell...
                :before :process}       ;; ...must appear before this on every path containing it

               {:type :never-together
                :cells [:manual-review :auto-approve]}  ;; mutually exclusive paths

               {:type :always-reachable
                :cell :error-handler}]} ;; must be reachable from every non-terminal cell
```

### Implementation

1. Enumerate all paths using existing `dev/enumerate-paths`
2. For each constraint type, check the relevant property across all paths
3. Report violations at compile time with the specific path that violates
4. Add constraints to manifest validation

### Constraint Types

| Type | Meaning |
|------|---------|
| `:must-follow` | If cell A appears on a path, cell B must appear later on that path |
| `:must-precede` | Cell A must appear before cell B on every path containing B |
| `:never-together` | Cells A and B never appear on the same path |
| `:always-reachable` | Cell must be reachable from every non-terminal cell |

### Scope

~150-250 lines in a new `constraints.clj` or added to `dev.clj`. The key design risk is making the language expressive enough to be useful but simple enough that agents can generate constraints, not just satisfy them.

---

## ~~3. Graph-Level Timeout Transitions~~ (Implemented)

**Value: High | Feasibility: High** — **DONE**

Harel emphasizes time bounds as a property of the state itself, not the internal activity. Currently an agent writing an async cell must bake timeout logic into the handler. Moving timeouts to the edge definition lets the LLM write pure domain logic while the framework handles failure routing.

The pattern already exists — `invoke-cell-handler` in `workflow.clj` wraps async cells with promise-based timeout logic inside joins (30s default). This just needs to be lifted to the general handler invocation path.

### Proposed API

```clojure
{:cells {:fetch-tags :mp3/parse-id3
         :render     :ui/render-tags
         :fallback   :ui/show-error}
 :edges {:fetch-tags {:done :render, :timeout :fallback}
         :render     :end
         :fallback   :end}
 :dispatches {:fetch-tags [[:done (constantly true)]]}
 :timeouts {:fetch-tags 5000}}  ;; ms
```

When `:fetch-tags` exceeds 5000ms, the framework injects `:mycelium/timeout true` into data and routes to the `:timeout` edge target. The handler stays pure:

```clojure
(fn [resources data]
  (assoc data :tags (parse-id3 (:path data))))
```

### Implementation

1. Add optional `:timeouts` map to workflow definition (cell-name -> ms)
2. In `compile-workflow`, wrap handlers of timed cells with promise-race (reuse join timeout pattern)
3. On timeout, inject `:mycelium/timeout true` into data; dispatch predicates route naturally
4. Schema chain validation: timeout edges need valid targets
5. Validate timeout values are positive integers

### Interaction with Resilience Policies

This is distinct from resilience4j `:timeout` policies (which wrap the handler with a resilience4j `TimeLimiter`). Graph-level timeouts are about **routing** — they redirect to an alternative cell. Resilience timeouts are about **error handling** — they produce `:mycelium/resilience-error`. Both can coexist: resilience timeout catches within the cell, graph timeout routes around it.

### Scope

~100-150 lines. Needs schema chain awareness and validation updates.

---

## ~~4. Zooming / Depth for LLM Context Management~~ (Implemented)

**Value: Medium | Feasibility: High** — **DONE**

The existing `cell-brief` and `orchestrate` module scope context per-cell well. What's missing is a "region brief" — context for a subgraph cluster rather than a single cell. For example: "here are the 3 cells in the metadata-fixing region, their schemas, and how they connect to each other."

### Proposed API

```clojure
;; Workflow definition adds optional :regions
{:regions {:auth       [:parse-request :validate-session]
           :data-fetch [:fetch-profile :fetch-orders]
           :rendering  [:render-summary :render-error]}}

;; Generate a brief for a region
(orch/region-brief manifest :auth)
;; => {:cells [{:id :auth/parse, :schema {...}}, {:id :auth/validate, :schema {...}}]
;;     :internal-edges {:parse-request {:success :validate-session}}
;;     :entry-points [:parse-request]
;;     :exit-points [{:cell :validate-session, :transitions {:authorized :_exit, :unauthorized :_exit}}]
;;     :prompt "## Region: auth\n..."}
```

### Implementation

- Add `:regions` to workflow definition (optional, informational grouping)
- `orch/region-brief` generates a scoped brief showing cells, their schemas, internal edges, and boundary connections
- No runtime behavior change — purely a tooling/orchestration enhancement
- Validate that region cells exist and regions don't overlap

### Scope

~100-150 lines in `orchestrate.clj` + tests. This is an incremental addition to existing tooling.

---

## ~~5. Overlapping States (Shared Error Handling)~~ (Implemented)

**Value: Medium | Feasibility: Medium** — **DONE**

The gap: "if ANY cell in this set fails, route to this handler" without wiring `:on-error` individually. Between fragments, interceptors, and the existing `:on-error` manifest field, most use cases are covered. This proposal targets the remaining gap.

### Proposed API

```clojure
{:error-groups {:data-pipeline {:cells [:fetch :transform :validate]
                                 :on-error :pipeline-error-handler}
                :auth          {:cells [:parse :check-session]
                                 :on-error :auth-error-handler}}}
```

### Implementation

- At compile time, for each cell in an error group, inject a catch-all error transition to the group's error handler
- This is syntactic sugar over per-cell `:on-error` — it expands to individual error edges
- Validate that error handler cells exist and groups don't overlap
- Schema chain: error handler inputs should be satisfied by any cell in the group (union of possible states)

### Why It's Lower Priority

- Fragments already provide reusable subgraph extraction with shared error routing
- Workflow-level interceptors apply cross-cutting logic by scope
- The `:on-error` manifest field handles individual cells
- The remaining unaddressed cases are rare enough that explicit wiring is acceptable

### Scope

~80-120 lines. Mostly compile-time expansion in `workflow.clj`.

---

## 6. Edge Actions (Instantaneous Transitions)

**Value: Low | Feasibility: High**

Per-edge data transformations that run between cells without being full cells themselves.

### Why It's Not Recommended

Interceptors already fill this role. Workflow-level interceptors with `:pre`/`:post` can transform data at cell boundaries with scope matching. The only gap is per-edge (not per-cell) actions, but this conflicts with Mycelium's core philosophy: every data transformation should be explicit and schema-validated. A 3-line cell to strip whitespace is better than an inline edge action that bypasses the contract system.

**Recommendation**: Document the interceptor pattern as the solution. Don't add edge actions.

---

## Implementation Priority

| Priority | Feature | Effort | Dependencies |
|----------|---------|--------|--------------|
| Done | Default transitions | Small | None |
| Done | Global constraints | Medium | `enumerate-paths` (exists) |
| Done | Graph-level timeouts | Medium | None (reuses join timeout pattern) |
| Done | Region briefs | Small | None |
| Done | Error groups | Small | None |
| Skip | Edge actions | - | Covered by interceptors |

**Recommended sequence**: Default transitions first (quick win, pairs well with halt/resume for safety), then global constraints (highest architectural value), then graph-level timeouts.
