# Mycelium: Implementation Plan

## Overview

Mycelium is a framework built on top of [Maestro](https://github.com/yogthos/maestro) that provides schema-enforced, hierarchically composable workflow components designed for agent-driven development. The core thesis is that LLM coding agents fail at managing large codebases for the same reason humans do — unbounded context. Mycelium solves this by structuring applications as directed graphs of pure data transformations where each node has a fixed scope, explicit contracts, and can be developed in complete isolation.

The name "mycelium" reflects the architecture: like a fungal network, the system consists of many small nodes (cells) connected by a communication substrate (the graph), forming a resilient, composable organism.

---

## Analysis of the Plan

The plan in `plan.md` lays out a compelling vision. Here is what it gets right, what needs refinement, and what's missing.

### Strengths

1. **Fixed-scope components prevent context rot.** By constraining each agent to a single node with explicit input/output schemas, the agent never needs to hold the full application in context. This directly addresses the primary failure mode of agentic coding today.

2. **The state machine graph as a contract.** Separating *what to do* (node logic) from *how to route* (graph transitions) means the architecture itself is auditable. A human can verify the high-level flow without reading implementation code.

3. **Fractal composition.** A workflow-as-node pattern means complexity scales through nesting rather than expansion. A 50-node system can be 5 workflows of 10 nodes each, where each workflow is manageable independently.

4. **Data-oriented design.** Plain maps flowing through pure functions eliminate hidden state, make debugging trivial (inspect the map at any point), and enable serialization for replay/debugging.

### Gaps to Address

1. **Maestro API alignment.** The plan uses a simplified syntax (`:handler`, `:next`) that doesn't match Maestro's actual API (`:handler`, `:dispatches` with predicate vectors, `::fsm/start`/`::fsm/end` reserved states). The implementation needs a DSL layer that bridges this gap — providing the clean mental model from the plan while compiling down to valid Maestro specs.

2. **Schema enforcement mechanics.** The plan says "use Malli" but doesn't specify *where* validation runs. Maestro's `:pre`/`:post` interceptors are the natural hook — validate input schemas in `:pre`, output schemas in `:post`. This needs to be automatic, not opt-in.

3. **Error handling strategy.** The plan mentions error states but doesn't detail how schema violations, handler exceptions, and business-logic failures map to different error paths. These are distinct concerns that need distinct handling.

4. **Resource lifecycle.** The plan mentions passing capabilities (like DB connections) in the state map, but doesn't address lifecycle management. Resources need initialization, cleanup, and scoping — a system component model (similar to Integrant or Mount) is needed.

5. **Workflow composition protocol.** The plan describes fractal nesting conceptually but doesn't define the mechanics: how does a child workflow's terminal state map back to the parent's dispatch predicates? How are resources scoped across levels?

6. **Development tooling.** The plan references Crush for workspace isolation but doesn't detail the actual developer experience: how does an agent discover its contract, run isolated tests, and validate against the schema without touching the rest of the system?

---

## Architecture

### Core Abstraction: The Cell

A **cell** is the atomic unit. It is a pure function with schema contracts:

```clojure
{:id       :auth/parse-request
 :handler  (fn [resources data] ...)
 :schema   {:input  [:map [:http-request any?]]
            :output [:map [:user-id [:string {:min 3}]] [:auth-token :string]]}
 :async?   false
 :doc      "Extracts and validates credentials from the Ring request map."}
```

Cells declare `:input` and `:output` schemas separately. The `:input` schema declares what keys the cell **reads** — the framework validates these keys exist, but the full data map passes through untouched. The `:output` schema declares what keys the cell **adds or modifies** — the framework validates these keys exist in the returned data. Keys not mentioned in either schema flow through transparently, enabling non-adjacent cells to communicate without intermediate cells needing to declare passthrough keys.

Cells may optionally declare `:async? true` to use Maestro's async handler protocol (see Async Cells below).

Cells are defined using a `defcell` macro that registers the cell in a registry and validates the schema definition at compile time.

### Core Abstraction: The Workflow

A **workflow** is a directed graph of cells, defined as data:

```clojure
{:id     :auth/user-flow
 :cells  {:start              :auth/parse-request
           :validate-session   :auth/validate-session
           :fetch-profile      :user/fetch-profile
           :render-home        :ui/render-home}
 :edges  {:start             {:success :validate-session
                               :failure :error}
           :validate-session  {:authorized   :fetch-profile
                               :unauthorized :error}
           :fetch-profile     {:found     :render-home
                               :not-found :error}
           :render-home       :end}
 :schema {:input  [:map [:http-request any?]]
          :output [:map [:http-response map?]]}}
```

The workflow DSL compiles down to a Maestro FSM spec. The `:dispatches` map in the workflow definition provides predicate functions for each edge label. Maestro evaluates these predicates against the handler's output data to determine routing — handlers compute data, the graph decides where it goes.

### Compilation: Workflow → Maestro FSM

The compilation step transforms the workflow definition into a Maestro-compatible spec:

1. Map `:start` to `::fsm/start`, `:end` to `::fsm/end`, `:error` to `::fsm/error`
2. For each cell in `:cells`, generate a Maestro state entry:
   - `:handler` wraps the cell's handler to inject schema validation
   - `:dispatches` taken from the workflow's `:dispatches` map — each predicate evaluates the handler's output data to determine routing
3. Install `:pre` interceptor for input schema validation
4. Install `:post` interceptor for output schema validation
5. Wire up the cell registry so handlers are resolved by ID

### Hierarchical Composition

A compiled workflow can itself be used as a cell in a parent workflow. This is the fractal scaling mechanism:

```clojure
;; The auth workflow is itself a cell in the platform workflow
{:id    :platform/main
 :cells {:start         :http/parse-route
          :auth          :auth/user-flow        ;; <-- this is a full workflow
          :dashboard     :dashboard/workflow     ;; <-- another workflow-as-cell
          :api           :api/workflow}
 :edges {:start     {:auth-route      :auth
                      :dashboard-route :dashboard
                      :api-route       :api}
          :auth      {:success :end, :failure :error}
          :dashboard {:success :end, :failure :error}
          :api       {:success :end, :failure :error}}}
```

When a workflow is used as a cell:
- The parent passes its current `:data` map as input to the child workflow
- The child workflow runs to completion (reaching its `::fsm/end`)
- The child's output data is merged back into the parent's `:data` map
- The child's transition signal (`:success`/`:failure`) propagates to the parent's dispatch logic

This is implemented by wrapping the child workflow's `run` call in a handler function that satisfies the cell protocol.

### Resource Management

Resources are external dependencies (DB connections, HTTP clients, caches) that cells need but should not create or manage. They follow an explicit injection pattern:

```clojure
;; At system startup
(def resources
  {:db     (jdbc/get-datasource db-spec)
   :cache  (atom {})
   :config (load-config)})

;; Passed to Maestro's run
(maestro/run compiled-fsm resources)
```

Every handler receives `resources` as its first argument. Cells declare which resources they require in their definition, enabling validation at compile time that all dependencies are satisfied:

```clojure
{:id        :user/fetch-profile
 :handler   (fn [{:keys [db]} data] ...)
 :requires  [:db]
 :schema    {...}}
```

For testing, resources are trivially mockable — pass a map with stub implementations.

### Async Cells

Cells that perform I/O (HTTP calls, database queries, file operations) can declare `:async? true`. This maps directly to Maestro's async handler protocol:

```clojure
(defcell :user/fetch-profile
  {:doc      "Queries the database for user profile data"
   :schema   {:input  [:map [:user-id :string]]
              :output [:map [:profile [:map [:name :string] [:email :string]]]]}
   :requires [:db]
   :async?   true}
  [resources data callback error-callback]
  (let [db (:db resources)]
    (try
      (let [profile (db/get-user db (:user-id data))]
        (callback (assoc data :profile profile :mycelium/transition :found)))
      (catch Exception e
        (error-callback e)))))
```

Schema validation for async cells wraps the callback: the framework intercepts the `callback` argument and validates the output data against the cell's `:output` schema before allowing the transition to proceed. If validation fails, it calls `error-callback` with a schema violation error instead. This keeps the validation guarantee uniform — sync or async, every cell's output is checked.

### Parallel Cell Execution

When multiple cells in a workflow are independent (no data dependency between them), they can run concurrently. This is expressed in the workflow DSL using a `:parallel` group:

```clojure
{:cells {:start           :http/parse-request
         :fetch-profile   :user/fetch-profile
         :fetch-prefs     :user/fetch-preferences
         :fetch-notifs    :notifications/fetch-recent
         :merge-data      :ui/merge-user-data
         :render          :ui/render-dashboard}
 :edges {:start           :parallel/user-data
         :parallel/user-data {:cells [:fetch-profile :fetch-prefs :fetch-notifs]
                              :join  :merge-data}
         :merge-data      {:success :render, :failure :error}
         :render          :end}}
```

A `:parallel` group compiles into a single Maestro state whose handler spawns async tasks for each listed cell, collects their results, merges the output data maps, and transitions to the `:join` target. Each parallel cell must be async-capable. Schema validation runs on each cell's output independently before the merge.

This leverages Maestro's async handler support — the parallel coordinator state uses `:async? true` and calls the callback only when all child cells complete (or error-callback if any fail).

### Persistence Model

The data map and Maestro's halt/resume mechanism naturally support workflow persistence. The halted FSM state is a plain Clojure map — no opaque objects, no closures, no live references. This means it can be serialized to EDN, JSON, or any store.

Design constraints to preserve serializability:
- The data map must contain only serializable values (no functions, atoms, channels, or Java objects)
- Resources are **not** part of the halted state — they are re-injected on resume
- The trace is included in the halted state, preserving the full execution history

```clojure
;; Halt a long-running workflow (e.g., waiting for user approval)
(def halted (maestro/run compiled-fsm resources {:data initial-data}))
;; halted is a plain map: {:current-state-id :await-approval :data {...} :trace [...]}

;; Persist to storage
(store/save! db workflow-id halted)

;; Later: resume after approval
(let [saved   (store/load db workflow-id)
      updated (assoc-in saved [:data :approved?] true)]
  (maestro/run compiled-fsm resources updated))
```

Implementation of the serialization layer is deferred, but the architecture is designed to never close this door. Cells should not put non-serializable values into the data map.

---

## Project Structure

```
mycelium/
├── deps.edn
├── CLAUDE.md                       ;; Agent protocol for Claude Code
├── src/
│   └── mycelium/
│       ├── core.clj                ;; Public API: defcell, defworkflow, compile, run
│       ├── cell.clj                ;; Cell registry, validation, wrapping
│       ├── workflow.clj            ;; Workflow→Maestro compilation
│       ├── schema.clj              ;; Malli integration, pre/post interceptors
│       ├── compose.clj             ;; Hierarchical workflow-as-cell composition
│       ├── manifest.clj            ;; Manifest loading, cell-brief generation
│       ├── orchestrate.clj         ;; Agent orchestration helpers, prompt generation
│       └── dev.clj                 ;; Development utilities, REPL helpers
├── test/
│   └── mycelium/
│       ├── core_test.clj
│       ├── cell_test.clj
│       ├── workflow_test.clj
│       ├── schema_test.clj
│       ├── manifest_test.clj
│       └── compose_test.clj
└── examples/
    └── user_onboarding/
        ├── workflows/
        │   └── user-onboarding.edn ;; The manifest (graph + all cell contracts)
        ├── src/
        │   └── app/
        │       └── cells/
        │           ├── auth.clj    ;; :auth/parse-request, :auth/validate-session
        │           ├── user.clj    ;; :user/fetch-profile
        │           └── ui.clj      ;; :ui/render-home
        └── test/
            └── app/
                └── cells/
                    ├── auth_test.clj
                    ├── user_test.clj
                    └── ui_test.clj
```

The key structural rule: **manifests are separate from implementations**. The `workflows/` directory contains pure-data `.edn` files that describe the architecture. The `src/` directory contains implementations. An agent working on a cell reads the manifest for its contract and its own implementation file — nothing else.

### Dependencies

```clojure
;; deps.edn
{:paths ["src" "resources"]
 :deps  {org.clojure/clojure   {:mvn/version "1.12.0"}
         ca.yogthos/maestro     {:mvn/version "RELEASE"}
         metosin/malli          {:mvn/version "0.17.0"}}
 :aliases
 {:dev  {:extra-paths ["dev" "test" "examples"]
         :extra-deps  {nrepl/nrepl {:mvn/version "1.3.0"}}}
  :test {:extra-paths ["test" "examples"]
         :extra-deps  {lambdaisland/kaocha {:mvn/version "1.91.1392"}}}}}
```

---

## Implementation Phases

### Phase 1: Cell Registry and Schema Enforcement

**Goal:** Establish the atomic unit — cells with Malli contracts that are validated automatically.

#### 1.1 Cell Definition and Registry (`cell.clj`)

Build a registry (an atom holding a map of cell-id → cell-spec) and the `defcell` macro:

```clojure
(defcell :auth/parse-request
  {:doc         "Extracts credentials from Ring request"
   :schema      {:input  [:map [:http-request any?]]
                 :output [:map [:user-id [:string {:min 3}]] [:auth-token :string]]}
   :transitions #{:success :failure}
   :requires    [:config]}
  [resources data]
  ;; implementation — must return data with :mycelium/transition set
  )
```

`defcell` should:
- Validate the schema definition at registration time (are the Malli schemas themselves valid?)
- Validate that `:transitions` is a non-empty set of keywords
- Store the cell spec in the registry
- Define a var for the handler function

#### 1.2 Schema Interceptors (`schema.clj`)

Build Maestro-compatible `:pre` and `:post` interceptor functions:

- **`:pre` interceptor:** Look up the current state's cell definition. Validate that all keys declared in the cell's `:input` schema exist in `(:data fsm-state)` and satisfy their type constraints. The full data map passes through — no keys are stripped. On failure, redirect to `::fsm/error` with a descriptive error attached.
- **`:post` interceptor:** After the handler runs, validate that all keys declared in the cell's `:output` schema exist in the returned data and satisfy their type constraints. For per-transition output schemas, the post-interceptor uses the dispatch target (already determined by Maestro) to select the correct schema. On failure, redirect to `::fsm/error`.
- **For async cells:** The post-interceptor logic wraps the callback rather than running in the `:post` hook. The framework replaces the cell's callback with a validating wrapper that checks the output schema before forwarding to Maestro's internal callback.

Schema errors produce rich diagnostics: which cell failed, which schema (input or output), the actual data, and the Malli humanized error. This gives agents immediate, actionable feedback when their implementation doesn't match the contract.

#### 1.3 Tests

- Cell with valid input/output passes through cleanly
- Cell with invalid input triggers error state with schema violation details
- Cell with valid input but invalid output triggers error state
- Cell registry rejects duplicate IDs
- Cell registry rejects invalid Malli schema definitions

---

### Phase 2: Workflow Compilation

**Goal:** Transform the human-friendly workflow DSL into a compiled Maestro FSM.

#### 2.1 DSL→Maestro Compiler (`workflow.clj`)

The compiler takes a workflow map and produces a Maestro spec:

**Input (workflow DSL):**
```clojure
{:cells {:start :auth/parse-request
         :validate :auth/validate-session}
 :edges {:start    {:success :validate, :failure :error}
         :validate {:ok :end, :fail :error}}}
```

**Output (Maestro spec):**
```clojure
{:fsm {::fsm/start {:handler  <wrapped-auth/parse-request>
                     :dispatches [[::validate (fn [d] (:user-id d))]
                                  [::fsm/error (fn [d] (not (:user-id d)))]]}
       ::validate  {:handler  <wrapped-auth/validate-session>
                    :dispatches [[::fsm/end   (fn [d] (:session-valid d))]
                                 [::fsm/error (fn [d] (not (:session-valid d)))]]}}
 :opts {:pre  <schema-pre-interceptor>
        :post <schema-post-interceptor>}}
```

> **Note:** Dispatch predicates are defined at the workflow level in the `:dispatches` key, not generated from cell transition signals. Handlers compute data; the graph routes.

Key design decisions:
- Handlers return enriched data — routing is determined by `:dispatches` predicates at the workflow level
- Internal state IDs are namespaced to avoid collisions during composition
- `:end` maps to `::fsm/end`, `:error` maps to `::fsm/error`, `:start` maps to `::fsm/start`

#### 2.2 Workflow Validation

At compile time, validate:
- All cells referenced in `:cells` exist in the registry
- All edge targets reference valid cell names (or `:end`/`:error`)
- The graph is connected (every cell is reachable from `:start`)
- All cell transitions are covered by edges (no missing branches)
- Schema chain compatibility (see below)

#### 2.3 Schema Chain Validation

This is a critical safety mechanism. When cell A connects to cell B via an edge, the compiler verifies that A's output provides what B's input requires. Since the data map uses passthrough semantics (keys not in a cell's schema flow through untouched), the check is:

> For every **required** key in cell B's `:input` schema, that key must appear in either:
> (a) cell A's `:output` schema, or
> (b) the accumulated outputs of all cells that can reach B through any path from `:start`

Input keys marked `{:optional true}` are excluded from the required set — the chain validator will not fail a compile if an optional key is never produced upstream.

This is a static reachability analysis over the graph. The compiler walks every path from `:start` to each cell, accumulates the output keys along the way, and checks that the cell's input requirements are satisfied.

When a chain breaks, the error message is specific and actionable:

```
Schema chain error: :user/fetch-profile requires key :user-id in its input,
but no upstream cell on any path from :start produces it.

Paths analyzed:
  :start → :validate-session → :fetch-profile
    Available keys at :fetch-profile: #{:http-request :session-valid}
    Missing: #{:user-id}

Suggestion: Either add :user-id to :auth/validate-session's output schema,
or add a cell between :validate-session and :fetch-profile that produces it.
```

This catches contract mismatches at manifest validation time, before any agent writes a line of implementation code. It also catches regressions: if someone changes a cell's output schema and removes a key that a downstream cell needs, the compiler rejects the manifest with a clear explanation of what broke and where.

#### 2.4 Tests

- Simple linear workflow (A → B → C → end) compiles and runs
- Branching workflow compiles with correct dispatch predicates
- Looping workflow compiles (cycle detection warns but allows)
- Missing cell reference in edges fails at compile time
- Unreachable cell fails at compile time
- Schema chain validation catches missing keys with clear error messages
- Schema chain validation traces through multiple paths correctly
- Modifying a cell's output schema to remove a key used downstream produces a chain error

---

### Phase 3: Hierarchical Composition

**Goal:** Enable workflows to be used as cells in parent workflows (fractal scaling).

#### 3.1 Workflow-as-Cell Adapter (`compose.clj`)

A function that wraps a compiled workflow as a cell:

```clojure
(defn workflow->cell
  "Wraps a workflow so it can be used as a cell in a parent workflow."
  [workflow-id]
  {:id      workflow-id
   :handler (fn [resources data]
              (let [child-fsm    (compile-workflow (get-workflow workflow-id))
                    result       (maestro/run child-fsm resources {:data data})
                    transition   (if (:mycelium/error result) :failure :success)]
                (-> result
                    (assoc :mycelium/transition transition))))
   :schema  (get-workflow-schema workflow-id)})
```

The adapter:
- Runs the child workflow to completion
- Maps the child's terminal state to a transition signal for the parent
- Preserves the child's trace in the parent's data for debugging
- Scopes resource access (child only sees resources it declares)

#### 3.2 Namespace Isolation

When composing, internal state IDs must not collide. Each child workflow's states are prefixed with its workflow ID during compilation:

- Child state `:validate` in workflow `:auth/flow` becomes `:auth/flow::validate`
- Traces preserve the full path for debugging: `[:platform/main :auth/flow :auth/flow::validate]`

#### 3.3 Parallel Groups

Implement the `:parallel` edge type that compiles into a fork/join pattern:

```clojure
:edges {:start :parallel/user-data
        :parallel/user-data {:cells [:fetch-profile :fetch-prefs :fetch-notifs]
                             :join  :merge-data}}
```

The compiler generates a single async Maestro state for the parallel group. Its handler:
1. Spawns each listed cell as a `future` (or uses `core.async`)
2. Each cell runs with the same input data map and resources
3. Each cell's output is validated against its schema independently
4. Results are merged (later cell outputs override earlier ones for conflicting keys)
5. On success, calls the Maestro callback with the merged data
6. On any cell failure, calls error-callback with details on which cell failed

This reuses the async cell infrastructure — the parallel coordinator is just a synthetic async cell.

#### 3.4 Tests

- Child workflow runs within parent workflow
- Child failure propagates as parent transition
- Resources are correctly scoped to child
- Traces show full hierarchical path
- Three-level nesting works (grandchild → child → parent)
- Parallel group runs cells concurrently and merges results
- Parallel group propagates failure from any child cell
- Schema validation runs on each parallel cell's output independently

---

### Phase 4: Manifest System and Agent Tooling

**Goal:** Build the contract-first infrastructure that makes cells assignable to subagents.

#### 4.1 Manifest Loader (`manifest.clj`)

Load and validate `.edn` workflow manifests:

```clojure
(load-manifest "workflows/user-onboarding.edn")
;; => {:id :user-onboarding, :cells {...}, :edges {...}}
```

The loader:
- Reads the `.edn` file
- Validates the manifest structure (are all referenced cells defined? do edges connect?)
- Validates all Malli schemas within the manifest (are they well-formed?)
- Checks schema chain compatibility (does cell A's output overlap with cell B's input?)
- Returns the validated manifest or throws with diagnostics

This is the conductor's primary tool — it validates the architecture before any implementation begins.

#### 4.2 Cell Brief Generator (`manifest.clj`)

Extract a self-contained brief for a single cell, suitable as a subagent prompt:

```clojure
(cell-brief manifest :auth/parse-request)
;; => {:id          :auth/parse-request
;;     :doc         "Extract and validate credentials from the HTTP request"
;;     :file        "src/app/cells/auth.clj"
;;     :test-file   "test/app/cells/auth_test.clj"
;;     :schema      {:input [...] :output [...]}
;;     :requires    []
;;     :transitions #{:success :failure}
;;     :examples    {:input  {...}    ;; auto-generated from Malli schema
;;                   :output {...}}
;;     :prompt      "..."            ;; full agent prompt string (see Agent Assignment Protocol)
;;     }
```

The `:prompt` field is a ready-to-use string that can be passed directly to `Task(prompt=...)`. It contains everything the agent needs: contract, examples, file paths, test commands, and rules.

Malli generators produce the example data, giving agents concrete values to work with rather than abstract schema descriptions.

#### 4.3 Isolated Cell Testing Harness (`dev.clj`)

A test helper that runs a single cell in isolation with full schema validation:

```clojure
(test-cell :auth/parse-request
  {:resources {:config test-config}
   :input     {:http-request mock-request}
   :expect    {:transition :success
               :output     {:user-id "123" :auth-token string?}}})
```

This:
- Validates the input against the cell's input schema (catches bad test data)
- Runs the handler
- Validates the output against the cell's output schema
- Checks the transition signal is in the allowed set
- On failure, reports with full Malli humanized errors and the exact data that failed
- Returns a structured result map `{:pass? true/false :errors [...] :output {...} :duration-ms ...}`

The harness is the agent's primary feedback loop. It replaces "run the whole app and see what happens" with "test this one function against its contract."

#### 4.4 Workflow Visualization (`dev.clj`)

Generate a DOT graph from a manifest for visual inspection:

```clojure
(workflow->dot manifest)
;; => "digraph { start -> validate_session [label=\"success\"]; ... }"
```

This lets the conductor (or a human) review the architecture visually.

#### 4.5 Scaffold Generator (`dev.clj`)

Generate skeleton implementation and test files from a manifest:

```clojure
(scaffold-cell! manifest :auth/parse-request)
;; Creates src/app/cells/auth.clj with:
;;   (defcell :auth/parse-request
;;     {:doc "Extract and validate credentials..."
;;      :schema {:input [...] :output [...]}
;;      :requires []
;;      :transitions #{:success :failure}}
;;     [resources data]
;;     ;; TODO: implement
;;     (assoc data :mycelium/transition :success))
;;
;; Creates test/app/cells/auth_test.clj with:
;;   (deftest parse-request-test
;;     (testing "success case"
;;       (let [result (dev/test-cell :auth/parse-request
;;                      {:input {:http-request {...}}
;;                       :resources {}})]
;;         (is (:pass? result)))))
```

The scaffold gives the agent a starting point with the correct structure, schema, and a failing test. The agent's job is to fill in the `TODO` and make the test pass. This eliminates boilerplate errors (wrong namespace, wrong schema format, missing transition) that waste agent context on mechanical issues.

#### 4.6 Status Dashboard (`dev.clj`)

Report implementation status across all cells in a workflow:

```clojure
(workflow-status manifest)
;; => {:total 4
;;     :implemented 2
;;     :passing 1
;;     :failing 1
;;     :pending 2
;;     :cells [{:id :auth/parse-request :status :passing}
;;             {:id :auth/validate-session :status :failing
;;              :error "Output missing key :session-valid"}
;;             {:id :user/fetch-profile :status :pending}
;;             {:id :ui/render-home :status :pending}]}
```

The conductor uses this to track progress, identify which cells need reassignment, and know when to run integration tests.

#### 4.7 Hot-Reload Exploration

Explore using Maestro's halt/resume mechanism for live cell replacement during development:

```clojure
;; In the REPL: reload a cell's implementation without restarting the workflow
(mycelium.dev/reload-cell! :auth/parse-request)
;; Re-evaluates the defcell form, updates the registry, recompiles the workflow
```

The idea: when a cell's implementation changes, recompile the workflow and use halt/resume to swap the cell mid-execution. This would let an agent iterate on a cell within a running system, getting immediate feedback against real data flowing through the graph.

This is exploratory — it depends on whether Maestro's compiled FSM can be safely reconstructed with a new handler while preserving the halted state. If it works, it dramatically tightens the agent's development loop. If it proves too fragile, the fallback (restart the workflow from scratch with test data) is still fast enough for isolated cell development.

---

### Phase 5: Example Application

**Goal:** Prove the architecture with a concrete user-onboarding workflow.

Build the example from the plan: HTTP request → auth → fetch profile → render response. This serves as both a proof-of-concept and a template for future projects.

#### 5.1 Define Schemas and Workflow Graph

The human defines the graph and schemas. This is the "blueprint" phase — no implementation logic, just structure and contracts.

#### 5.2 Implement Cells in Isolation

Each cell is implemented independently using the dev tooling:
- Agent reads `(inspect-cell :cell-id)` to understand the contract
- Agent writes the handler
- Agent runs `(test-cell :cell-id ...)` to verify
- Agent commits when tests pass

#### 5.3 Integration Test

Run the full workflow end-to-end with mock resources. Verify the trace shows correct state progression and the final output satisfies the workflow's output schema.

---

### Phase 6: Agent Orchestration (`orchestrate.clj`)

**Goal:** Provide a programmatic interface for conductor agents to manage cell implementation workflows.

This namespace bridges Mycelium's contract system with the mechanics of agent orchestration. It doesn't couple to any specific agent framework — instead it produces the data structures (prompts, status reports, reassignment briefs) that a conductor needs.

#### 6.1 Batch Cell Brief Generation

```clojure
(orchestrate/cell-briefs manifest)
;; => {:auth/parse-request    {:prompt "..." :file "..." :test-file "..."}
;;     :auth/validate-session {:prompt "..." :file "..." :test-file "..."}
;;     :user/fetch-profile    {:prompt "..." :file "..." :test-file "..."}
;;     :ui/render-home        {:prompt "..." :file "..." :test-file "..."}}
```

Returns a map of cell-id to brief, ready to be used as subagent prompts. The conductor iterates this map and spawns one worker per entry.

#### 6.2 Reassignment Brief

When a cell fails, generate a targeted brief that includes the failure context:

```clojure
(orchestrate/reassignment-brief manifest :auth/validate-session
  {:error   "Output missing key :session-valid"
   :input   {:user-id "123" :auth-token "tok_abc"}
   :output  {:user-id "123" :mycelium/transition :authorized}})
;; => {:prompt "... Your previous implementation failed. The output was missing
;;      key :session-valid. Given input {:user-id \"123\" ...}, your handler
;;      returned {...} which does not satisfy the output schema. Fix the handler
;;      to include :session-valid in the output. ..."
;;     :file "..." :test-file "..."}
```

This gives the replacement agent the exact failure, the input that caused it, and what the output should have contained. No guesswork, no re-reading the whole codebase.

#### 6.3 Orchestration Plan

Generate a full execution plan from a manifest — the conductor's playbook:

```clojure
(orchestrate/plan manifest)
;; => {:scaffold  [:auth/parse-request :auth/validate-session
;;                 :user/fetch-profile :ui/render-home]
;;     :parallel  [[:auth/parse-request :auth/validate-session
;;                  :user/fetch-profile :ui/render-home]]
;;     :sequential []
;;     :integration-test {:command "(mycelium.core/run-workflow ...)"
;;                        :expected-trace [:start :validate-session
;;                                         :fetch-profile :render-home :end]}}
```

The plan identifies which cells can be built in parallel (all of them, in the common case where cells are independent) and provides the integration test command. A conductor agent can follow this plan mechanically.

#### 6.4 Progress Report

Structured report suitable for a conductor agent's context:

```clojure
(orchestrate/progress manifest)
;; => "Workflow: :user-onboarding
;;     Status: 2/4 cells passing
;;
;;     [PASS] :auth/parse-request
;;     [FAIL] :auth/validate-session — Output missing key :session-valid
;;     [    ] :user/fetch-profile — Not yet implemented
;;     [    ] :ui/render-home — Not yet implemented
;;
;;     Next actions:
;;     - Reassign :auth/validate-session (use orchestrate/reassignment-brief)
;;     - Assign :user/fetch-profile and :ui/render-home (use orchestrate/cell-briefs)"
```

This is text, not data, because it's destined for an agent's prompt. The conductor reads this, understands the state, and takes the suggested next actions.

#### 6.5 Tests

- `cell-briefs` generates valid briefs for all cells in a manifest
- `reassignment-brief` includes error context and failing data in the prompt
- `plan` correctly identifies parallel vs sequential cell groups
- `progress` reflects the current implementation status accurately

---

## Agent-Driven Development

The architecture must be agent-friendly by default, not as an afterthought. This means every design decision — file layout, contract format, testing harness, error reporting — should be evaluated through the lens of: "Can a subagent with no prior context pick this up and do useful work?"

### The Problem with Current Agent Workflows

When Claude Code (or any LLM coding tool) is given a task like "add 2FA to the auth flow," it typically needs to:
1. Read the entire codebase to understand the architecture
2. Figure out where changes need to happen
3. Hold all of that context while writing new code
4. Hope that its changes don't break something it forgot about

This fails at scale because the agent's context fills up with code it doesn't need, the relationships between files become too numerous to track, and the agent starts making mistakes (context rot). The solution is not a smarter agent — it's a structure that makes each task small enough that any agent can succeed.

### The Conductor / Worker Model

Mycelium enables a two-tier agent model that maps directly to how Claude Code uses subagents:

**The Conductor** is the top-level agent (or human) that owns the workflow graph. Its responsibilities:
- Define or modify the workflow graph (which cells exist, how they connect)
- Define cell contracts (input/output schemas, transition signals, resource requirements)
- Delegate cell implementation to Worker agents
- Run integration tests after cells are implemented
- The conductor never implements cell logic — it only works with the graph and contracts

**Workers** are subagents, each assigned to exactly one cell. A worker:
- Reads only its cell's contract (not the full workflow, not other cells)
- Implements the handler to satisfy the contract
- Tests in isolation using the cell test harness
- Has no knowledge of or access to other cells

This maps naturally to Claude Code's `Task` tool — the conductor spawns one subagent per cell, each with a focused prompt and minimal context.

### Contract-First Development

The critical enabler is that **contracts exist before implementations**. A workflow manifest is a pure-data artifact that fully describes what each cell must do, without any implementation code:

```clojure
;; workflows/user-onboarding.edn
{:id :user-onboarding
 :doc "Handles new user registration and profile setup"

 :cells
 {:start
  {:id       :auth/parse-request
   :doc      "Extract and validate credentials from the HTTP request"
   :schema   {:input  [:map
                        [:http-request [:map
                          [:headers map?]
                          [:body map?]]]]
              :output [:map
                        [:user-id [:string {:min 3}]]
                        [:auth-token :string]]}
   :requires []
   :transitions #{:success :failure}}

  :validate-session
  {:id       :auth/validate-session
   :doc      "Check credentials against the session store"
   :schema   {:input  [:map
                        [:user-id :string]
                        [:auth-token :string]]
              :output [:map
                        [:user-id :string]
                        [:session-valid :boolean]]}
   :requires [:db]
   :transitions #{:authorized :unauthorized}}

  ;; ... more cells
  }

 :edges
 {:start            {:success :validate-session
                     :failure :error}
  :validate-session {:authorized   :fetch-profile
                     :unauthorized :error}
  :fetch-profile    {:found     :render-home
                     :not-found :error}
  :render-home      :end}}
```

This manifest is:
- **Complete** — it contains everything an agent needs to know about any cell
- **Self-contained** — no need to chase imports or read other files
- **Machine-readable** — the framework can extract a single cell's contract programmatically
- **Diffable** — changes to the architecture show up as clean data diffs
- **Versionable** — the manifest is the spec, so it can be put under version control and reviewed

### File Layout for Agent Isolation

The project structure is designed so that each agent touches the minimum number of files:

```
project/
├── workflows/
│   └── user-onboarding.edn      ;; The manifest (conductor reads this)
├── src/
│   └── app/
│       └── cells/
│           ├── auth.clj          ;; :auth/parse-request, :auth/validate-session
│           ├── user.clj          ;; :user/fetch-profile
│           └── ui.clj            ;; :ui/render-home
└── test/
    └── app/
        └── cells/
            ├── auth_test.clj
            ├── user_test.clj
            └── ui_test.clj
```

**The conductor** reads: `workflows/user-onboarding.edn` (one file)
**A cell worker** reads: its cell's contract (extracted from the manifest) + its implementation file + its test file (2-3 files total)

No agent ever needs to read the full `src/` tree. The file structure enforces this — cells are in separate files, grouped by domain, with no cross-imports between cell files.

### The Agent Assignment Protocol

When the conductor delegates a cell to a worker, it provides a **cell brief** — a self-contained prompt generated from the manifest. Here's the exact protocol:

#### Step 1: Conductor extracts the cell brief

```clojure
(mycelium.dev/cell-brief :auth/parse-request)
```

This produces a string like:

```
## Cell: :auth/parse-request
Namespace: app.cells.auth
File: src/app/cells/auth.clj
Test: test/app/cells/auth_test.clj

## Purpose
Extract and validate credentials from the HTTP request.

## Contract

Input schema:
  [:map
    [:http-request [:map [:headers map?] [:body map?]]]]

Output schema:
  [:map
    [:user-id [:string {:min 3}]]
    [:auth-token :string]]

Required resources: none

Transition signals: :success, :failure
  Return (assoc data :mycelium/transition :success) on success.
  Return (assoc data :mycelium/transition :failure) on failure.

## Example Data (auto-generated from schema)

Example input:
  {:http-request {:headers {"content-type" "application/json"}
                  :body {"username" "alice" "password" "s3cret"}}}

Example output:
  {:user-id "alice123"
   :auth-token "tok_abc123"
   :mycelium/transition :success}

## Testing

Run in REPL:
  (require '[mycelium.dev :as dev])
  (dev/test-cell :auth/parse-request
    {:input {:http-request {:headers {} :body {"username" "alice" "password" "s3cret"}}}
     :expect {:transition :success}})

Run tests:
  clj -M:test -m kaocha.runner --focus app.cells.auth-test

## Rules
- Your handler signature is: (fn [resources data] -> data)
- You MUST return the data map with :mycelium/transition set to one of: :success, :failure
- You MUST NOT require or call any other cell's namespace
- You MUST NOT create database connections, HTTP clients, or any resources — use only what is in `resources`
- You MUST NOT modify any file other than your implementation and test files
- The output data MUST pass the output schema validation
```

#### Step 2: Conductor spawns the worker

In Claude Code, this looks like:

```
Task(subagent_type="general-purpose", prompt="""
You are implementing a single cell in a Mycelium workflow.

{cell_brief}

Read the implementation file, write the handler, write tests, and verify
they pass. Do not touch any other files.
""")
```

Multiple workers can be spawned in parallel since cells are isolated. The conductor can launch all cell implementations simultaneously:

```
# These run concurrently — no dependencies between cells
Task("implement auth/parse-request", prompt=cell_brief_1)
Task("implement auth/validate-session", prompt=cell_brief_2)
Task("implement user/fetch-profile", prompt=cell_brief_3)
Task("implement ui/render-home", prompt=cell_brief_4)
```

#### Step 3: Worker implements and tests

The worker:
1. Reads the implementation file (creates it if it doesn't exist)
2. Writes the `defcell` with the handler logic
3. Writes tests using the cell test harness
4. Runs tests via REPL or test runner
5. Iterates until tests pass and schema validation succeeds
6. Reports completion

The worker never needs to understand the workflow graph, other cells, or the overall application architecture. It just satisfies a contract.

#### Step 4: Conductor verifies integration

After all workers report completion, the conductor:
1. Compiles the workflow from the manifest
2. Runs the integration test with mock resources
3. Verifies the trace shows correct state progression
4. If any cell fails integration, inspects the trace to identify which cell produced bad data, and reassigns just that cell

### Schema Validation as Agent Guardrails

Malli schemas serve double duty — they're both runtime validation and agent guardrails:

1. **Input schemas prevent overcoupling.** If a cell's input schema says `[:map [:user-id :string]]`, the agent implementing it literally cannot access `:http-request` even if it's in the data map. The pre-interceptor strips keys not in the input schema before passing data to the handler. This prevents agents from accidentally depending on data they shouldn't know about.

2. **Output schemas catch drift.** If an agent returns `{:userid "123"}` instead of `{:user-id "123"}`, the post-interceptor catches it immediately with a clear error: "Output validation failed for :auth/parse-request — missing key :user-id". The agent gets immediate, actionable feedback.

3. **Dispatch coverage validation.** Every edge label must have a corresponding dispatch predicate, and every dispatch predicate must match an edge. This is checked at compile time.

This means an agent can't produce code that "works on my machine" but breaks in integration. The schemas enforce the contract at the boundary, every time.

### Workflow-Level Agent: The Conductor's Own Scope

The conductor agent also has a bounded scope. It works with:
- The workflow manifest (a data structure small enough to fit in any context)
- The cell briefs (summaries, not implementations)
- Integration test results (traces and schema errors)

The conductor never reads cell implementation code. Its job is purely structural:
- "Do all cells exist?"
- "Does the graph connect properly?"
- "Do the schemas chain correctly (output of A is compatible with input of B)?"
- "Does the integration test pass?"

If the conductor needs to modify the architecture (add a new cell, change routing), it only edits the manifest. The affected cells get new contracts, and the conductor reassigns just those cells to workers.

### Handling Agent Failure

When a worker agent fails (produces code that doesn't satisfy the contract), the recovery path is clean:

1. **Schema failure at test time.** The worker sees the Malli error, understands what's wrong, and fixes it. No other agent is affected.

2. **Schema failure at integration time.** The conductor sees which cell failed (from the Maestro trace), extracts the failing input data, and creates a new worker prompt: "Your implementation of :auth/validate-session failed with this input: `{...}`. The output was `{...}` which doesn't match the output schema. Fix it."

3. **Persistent failure.** If a cell repeatedly fails, the conductor can inspect the contract for ambiguity and either clarify the schema or split the cell into simpler sub-cells. The graph is just data — restructuring is cheap.

4. **Context rot prevention.** If a worker's context gets too large (too many iterations), the conductor kills it and spawns a fresh worker. All the context the new worker needs is in the cell brief — nothing is lost.

### Scaling to Large Applications

For a large application (50+ cells), the conductor itself might become overwhelmed. The fractal composition model solves this:

```
Platform Conductor
├── Auth Workflow Conductor (owns 5 cells)
│   ├── Worker: parse-request
│   ├── Worker: validate-session
│   ├── Worker: check-2fa
│   ├── Worker: refresh-token
│   └── Worker: logout
├── Dashboard Workflow Conductor (owns 8 cells)
│   ├── Worker: fetch-metrics
│   ├── Worker: aggregate-data
│   └── ...
└── API Workflow Conductor (owns 12 cells)
    └── ...
```

Each sub-conductor manages its own workflow manifest and cell assignments. The platform conductor only sees the sub-workflows as opaque cells with schemas. The hierarchy mirrors the composition hierarchy — agents manage what they can see, and what they can see is always bounded.

### CLAUDE.md Integration

For projects using Claude Code, the `CLAUDE.md` file should encode the agent protocol:

```markdown
# Agent Protocol

This project uses Mycelium's cell-based architecture.

## For cell implementation tasks:
1. Read only your assigned cell's contract (provided in the prompt)
2. Implement in the specified file, test in the specified test file
3. Do not read or modify other cell files
4. Verify with: (mycelium.dev/test-cell :cell-id {:input ... :resources ...})

## For workflow modification tasks:
1. Edit only the workflow manifest .edn file
2. Run (mycelium.dev/validate-workflow :workflow-id) to check graph validity
3. Run (mycelium.dev/check-schema-chain :workflow-id) to verify schema compatibility
4. Do not modify cell implementations — reassign cells if contracts change

## File ownership:
- workflows/*.edn — conductor only
- src/app/cells/*.clj — assigned worker only
- test/app/cells/*_test.clj — assigned worker only
```

This gives Claude Code explicit rules about scope, preventing the "read everything and hope for the best" pattern that causes context rot.

---

## Design Principles

1. **Data is the API.** Cells communicate exclusively through the data map. No side channels, no shared atoms, no global state. The data map is the single source of truth.

2. **Schemas are not optional.** Every cell must declare input and output schemas. Validation runs automatically on every transition. This is the "trap" that prevents agents from producing incorrect code that happens to compile.

3. **Routing is decoupled from handlers.** Handlers compute and return data. Dispatch predicates at the workflow level examine the data to determine which edge to take. There is no implicit routing based on exception types or return value shapes.

4. **Resources are injected, never acquired.** Cells declare dependencies; the system provides them. This makes testing trivial and prevents cells from reaching outside their boundaries.

5. **The graph is auditable.** The workflow definition is pure data. It can be serialized, versioned, diffed, and visualized. A human can approve the architecture by reading a map.

6. **Composition over expansion.** When a workflow gets too large, split it into sub-workflows and compose them. The solution to complexity is always more structure, never bigger components.

7. **Contracts before code.** The manifest exists before any implementation. Agents implement against contracts, not against other code. This eliminates the need for an agent to "understand the codebase" — it only needs to understand its contract.

8. **Agent scope is bounded by design.** No agent — conductor or worker — ever needs to hold the full system in context. The conductor sees the graph; workers see their cell. If an agent's scope feels too large, the architecture needs more decomposition, not a smarter agent.

---

## Implementation Order

| Step | What | Depends On | Deliverable |
|------|------|------------|-------------|
| 1 | Project skeleton, deps.edn, CLAUDE.md | — | Building project with `clj` |
| 2 | Cell registry + `defcell` (sync + async) | Step 1 | `mycelium.cell` namespace with tests |
| 3 | Schema interceptors (passthrough model) | Steps 1, 2 | `mycelium.schema` with pre/post validation, async callback wrapping |
| 4 | Workflow compiler + schema chain validation | Steps 2, 3 | `mycelium.workflow` compiles DSL → Maestro with clear chain errors |
| 5 | Integration: compile + run | Steps 3, 4 | End-to-end workflow execution with schema enforcement |
| 6 | Hierarchical composition + parallel groups | Step 5 | `mycelium.compose` with nested workflows and fork/join |
| 7 | Manifest system + cell briefs | Steps 2, 4 | `mycelium.manifest` loads .edn, generates agent prompts |
| 8 | Cell test harness + scaffold + hot-reload | Steps 2, 7 | `mycelium.dev` with test-cell, scaffold, status, reload exploration |
| 9 | Agent orchestration | Steps 7, 8 | `mycelium.orchestrate` with briefs, reassignment, progress reports |
| 10 | Public API (`core.clj`) | Steps 2–9 | Unified namespace re-exporting key functions |
| 11 | Example: manifest-first workflow | Step 10 | User-onboarding built entirely via agent protocol |

Step 11 is the proof: define a manifest, generate cell briefs, implement each cell as if it were assigned to a subagent (in isolation, using only the brief), run integration. If it works, the architecture delivers on its promise.

---

## Example: Claude Code Conductor Session

To make the agent workflow concrete, here is what a full conductor session looks like in practice. The human asks Claude Code to build a feature. Claude Code acts as the conductor.

### Human Request

> "Build the user onboarding workflow from the manifest."

### Conductor Actions

```
1. Read workflows/user-onboarding.edn
2. Run (mycelium.dev/validate-manifest manifest) — confirm graph is valid
3. Run (mycelium.dev/scaffold-all! manifest) — generate skeleton files for all cells
4. For each cell, generate the brief:
     brief-1 = (mycelium.dev/cell-brief manifest :auth/parse-request)
     brief-2 = (mycelium.dev/cell-brief manifest :auth/validate-session)
     brief-3 = (mycelium.dev/cell-brief manifest :user/fetch-profile)
     brief-4 = (mycelium.dev/cell-brief manifest :ui/render-home)
5. Spawn workers IN PARALLEL:
     Task(prompt=brief-1)  — implements :auth/parse-request
     Task(prompt=brief-2)  — implements :auth/validate-session
     Task(prompt=brief-3)  — implements :user/fetch-profile
     Task(prompt=brief-4)  — implements :ui/render-home
6. Wait for all workers to complete
7. Run (mycelium.dev/workflow-status manifest) — check all cells pass
8. If any cells fail:
     - Extract the error and failing input from the status report
     - Spawn a new worker with the error context appended to the brief
     - Repeat until all cells pass
9. Run integration test:
     (let [fsm (mycelium.core/compile-manifest manifest)
           result (maestro/run fsm mock-resources {:data mock-input})]
       (assert (= :success (:mycelium/transition result))))
10. Report completion to human
```

Key properties of this flow:
- **Steps 5 is parallel.** All four cells are implemented concurrently. No agent blocks another.
- **The conductor never reads cell code.** It only reads the manifest and status reports.
- **Failure recovery is targeted.** If cell 2 fails, only cell 2 gets a new worker. Cells 1, 3, 4 are untouched.
- **The human approves the manifest (step 1), not the implementation.** The schemas guarantee that implementations satisfy the contract.

### Adding a Feature Later

> "Add 2FA to the auth flow."

```
1. Read workflows/user-onboarding.edn
2. Edit the manifest:
   - Add new cell :auth/check-2fa with schema contract
   - Update edges: :validate-session → :check-2fa → :fetch-profile
3. Run (mycelium.dev/validate-manifest manifest) — confirm new graph is valid
4. Run (mycelium.dev/scaffold-cell! manifest :auth/check-2fa)
5. Spawn ONE worker: Task(prompt=(cell-brief manifest :auth/check-2fa))
6. Wait for worker, verify, run integration test
```

Only one new cell is implemented. The existing cells are untouched. The conductor edits one file (the manifest) and assigns one worker. This is fractal scaling in action — adding complexity doesn't increase the scope of any individual agent's task.

---

## Design Decisions

Resolved questions that shaped the architecture:

1. **Async cells: validate in the callback.** Cells can declare `:async? true` to use Maestro's async handler protocol `(fn [resources data callback error-callback])`. Schema validation wraps the callback — the framework intercepts it and validates the output before forwarding. This keeps the validation guarantee uniform across sync and async cells without requiring the agent to manually call validation.

2. **Schema evolution: compatibility check with clear errors.** The workflow compiler performs schema chain validation at compile time. When cell A connects to cell B, the compiler verifies that A's accumulated output keys satisfy B's input requirements. Breaking changes produce actionable error messages that identify the exact path, the missing key, and which cell needs to change. This catches contract mismatches before any agent writes implementation code.

3. **Hot reloading: explore in Phase 4.** Using Maestro's halt/resume to swap cell implementations in a running workflow. Prototype in Phase 4 (dev tooling). If it works, it tightens the agent's feedback loop dramatically. If too fragile, fall back to restart-with-test-data.

4. **Parallel execution: async fork/join groups.** Independent cells run concurrently via `:parallel` edge groups, implemented as a synthetic async Maestro state that spawns futures for each cell and merges results. This reuses the async cell infrastructure — the parallel coordinator is itself an async cell.

5. **Persistence: design for it, defer implementation.** The data map must remain serializable (no functions, atoms, or live objects). Resources are re-injected on resume, not serialized. The halted state is a plain map that can be stored anywhere. The serialization layer itself is deferred, but the constraint is enforced now.

6. **Schema passthrough model.** Cells declare `:input` (what they read) and `:output` (what they add/modify). The full data map passes through untouched — schemas validate that required keys exist but don't strip extra keys. This allows non-adjacent cells to communicate through the data map without intermediate cells needing to declare passthrough keys, while still catching missing-key errors.

7. **`mycelium.orchestrate` namespace.** Ships with the framework. Provides `cell-briefs`, `reassignment-brief`, `plan`, and `progress` — the data structures a conductor agent needs to manage cell implementation. Agnostic to any specific agent framework; produces prompts and reports as text/data that any orchestrator can consume.
