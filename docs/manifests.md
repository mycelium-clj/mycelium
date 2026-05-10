# Working with Manifests

Manifests are EDN files that define complete workflows as pure data. They serve as the single source of truth for workflow structure, cell schemas, and routing logic.

## Manifest Structure

```clojure
{:id            :workflow-name              ;; required
 :doc           "Description"               ;; optional
 :input-schema  [:map [:http-request [:map]]] ;; optional, validates workflow input
 :pipeline      [:start :step2 :step3]      ;; optional, shorthand for linear flows
 :fragments     {...}                       ;; optional, see workflow-fragments.md
 :cells         {...}                       ;; required
 :edges         {...}                       ;; required (unless :pipeline used)
 :dispatches    {...}                       ;; optional, required only for conditional edges
 :joins         {...}                       ;; optional
 :interceptors  [...]                       ;; optional
}
```

## Cell Definitions in Manifests

Each cell in the `:cells` map must have:

```clojure
{:cell-name
 {:id       :namespace/cell-id      ;; cell registry ID
  :doc      "What this cell does"   ;; optional
  :schema   {:input  [:map ...]     ;; Malli input schema
             :output [:map ...]     ;; or {:transition-label [:map ...], ...}
            }                       ;; or :inherit — see below
  :on-error :error-cell             ;; required: target cell keyword or nil
  :requires [:db]}}                 ;; optional: resource dependencies
```

### Schema Inheritance

If a cell is already registered with a schema in the cell registry, you can use `:schema :inherit` instead of duplicating it:

```clojure
:start
{:id       :request/parse-todo
 :schema   :inherit           ;; pulls schema from cell registry
 :on-error nil}
```

This looks up the cell's `:schema` from the registry via `cell/get-cell` and resolves it before validation. If the cell isn't registered or has no schema, validation throws with a clear error.

This is useful when cells define their own schemas via `defmethod cell-spec` and you don't want to repeat them in the manifest.

### The `:on-error` Declaration

Every cell must declare `:on-error`:
- `:on-error :render-error` — declares that errors route to `:render-error`
- `:on-error nil` — explicitly declares "no error handling needed"

The target must be a valid cell name in the manifest. This is a **declaration** that the manifest author has considered error handling — it does not auto-generate edges.

## Loading Manifests

```clojure
(require '[mycelium.manifest :as manifest])

;; Load from file path — expands fragments, validates
(def m (manifest/load-manifest "path/to/workflow.edn"))

;; Validate an in-memory manifest
(manifest/validate-manifest manifest-map)

;; With options
(manifest/validate-manifest manifest-map {:strict? false}) ;; allow missing :on-error
```

`load-manifest` automatically:
1. Parses the EDN file
2. Expands `:fragments` if present
3. Validates the manifest structure

## Converting to Workflow

```clojure
(def workflow-def (manifest/manifest->workflow m))
```

This:
- Registers stub handlers for cells not yet implemented
- Applies manifest metadata (schema, requires) to already-registered cells
- Returns a workflow definition map suitable for `pre-compile` or `run-workflow`

For production use, pass the result to `myc/pre-compile` to avoid recompilation per request:
```clojure
(def compiled (myc/pre-compile workflow-def opts))
(myc/run-compiled compiled resources initial-data)
```

## Input Schema Validation

Add `:input-schema` to validate data before any cell runs:

```clojure
{:id :dashboard
 :input-schema [:map [:http-request [:map [:cookies map?]]]]
 :cells {...}}
```

When `run-workflow` receives data that doesn't match:
```clojure
{:mycelium/input-error {:schema [:map [:http-request [:map [:cookies map?]]]]
                        :errors [{:path [:http-request :cookies], ...}]
                        :data   {:http-request {:headers {}}}}}
```

## Dispatch Predicates in EDN

Predicates are `(fn ...)` forms evaluated by Maestro's SCI (Small Clojure Interpreter):

```clojure
:dispatches
{:start [[:success (fn [data] (:auth-token data))]
         [:failure (fn [data] (:error-type data))]]}
```

Available in predicates: core Clojure functions. No `require` or namespace access.

## Pipeline Shorthand

For simple linear workflows with no branching, use `:pipeline` instead of `:edges` and `:dispatches`:

```clojure
{:id :todo-add
 :pipeline [:start :create :fetch-list :render]
 :cells {:start      {:id :request/parse-todo  :schema {...} :on-error nil}
         :create     {:id :todo/create         :schema {...} :on-error nil}
         :fetch-list {:id :todo/list           :schema {...} :on-error nil}
         :render     {:id :ui/render-list      :schema {...} :on-error nil}}}
```

This expands to:
- `:edges` — each cell flows to the next: `{:start :create, :create :fetch-list, :fetch-list :render, :render :end}`
- `:dispatches` — empty `{}`

**Constraints**: `:pipeline` is mutually exclusive with `:edges`, `:dispatches`, `:fragments`, and `:joins`. Use it only for pure linear workflows.

## Optional Dispatches

`:dispatches` is only required when you have conditional (map) edges. If all edges are unconditional keywords (e.g., `:start :next-cell`), you can omit `:dispatches` entirely:

```clojure
{:id :simple
 :cells {:start {:id :my/cell :schema {...} :on-error nil}}
 :edges {:start :end}}
;; no :dispatches needed
```

## Ring Middleware

Mycelium provides `mycelium.middleware` to bridge Ring HTTP requests to workflow execution:

```clojure
(require '[mycelium.middleware :as mw])

;; Create a Ring handler from a compiled workflow
(def handler
  (mw/workflow-handler compiled-workflow
    {:resources {:db db}           ;; or (fn [request] {:db ...})
     :input-fn  (fn [req] {:http-request req})  ;; default
     :output-fn mw/html-response}))             ;; default

;; html-response extracts :html from result, returns {:status 200 :body html}
(mw/html-response {:html "<h1>Hi</h1>"})
;; => {:status 200, :headers {"Content-Type" "text/html; charset=utf-8"}, :body "<h1>Hi</h1>"}
```

Common pattern with Reitit routes:

```clojure
(defn- wf-handler [compiled db]
  (mw/workflow-handler compiled {:resources {:db db}}))

(defn routes [db]
  [["/"     {:get  {:handler (wf-handler compiled-page db)}}]
   ["/add"  {:post {:handler (wf-handler compiled-add db)}}]])
```

`workflow-handler` also handles input validation errors — if the workflow returns `:mycelium/input-error`, a 400 response is returned automatically.

## Cell Briefs (for LLM Agents)

Generate a self-contained prompt for implementing a single cell:

```clojure
(manifest/cell-brief m :validate-session)
;; => {:id :auth/validate-session
;;     :doc "Check credentials against the session store"
;;     :schema {:input [...], :output {...}}
;;     :requires [:db]
;;     :examples {:input {...}, :output {...}}
;;     :prompt "## Cell: :auth/validate-session\n..."}
```

The `:prompt` string contains everything an LLM needs to implement the cell: schema, examples, dispatch rules, and coding constraints.

## Manifest Validation Rules

`validate-manifest` checks:
- `:id` is present
- `:cells` is present and non-empty
- `:edges` is present (or `:pipeline` which expands to `:edges`)
- Every cell has valid `:id`, `:schema` (with `:input` and `:output`, or `:inherit`), and `:on-error`
- `:schema :inherit` cells have a registered cell with a schema in the registry
- `:on-error` targets exist in `:cells`
- All edge targets point to valid cells, joins, or terminal states (`:end`, `:error`, `:halt`)
- Every non-join-member cell has an edge entry
- Dispatch coverage: every edge label has a predicate, every predicate has an edge
- All cells are reachable from `:start`
- `:input-schema` is well-formed Malli (if present)
- Join members have no edge entries
- Join names don't collide with cell names

## Example: Complete Manifest

```clojure
{:id :user-profile
 :doc "Authenticate, fetch user by ID, render profile page"
 :input-schema [:map [:http-request [:map]]]

 :fragments
 {:auth {:ref   "fragments/cookie-auth.edn"
         :as    :start
         :exits {:success :fetch-profile-by-id
                 :failure :render-error}}}

 :cells
 {:fetch-profile-by-id
  {:id       :user/fetch-profile-by-id
   :schema   {:input  [:map [:http-request [:map [:path-params [:map [:id :string]]]]]]
              :output [:per-transition
                       {:found     [:map [:profile map?]]
                        :not-found [:map [:error-type :keyword]
                                         [:error-message :string]]}]}
   :on-error :render-error
   :requires [:db]}

  :render-profile
  {:id       :ui/render-user-profile
   :schema   {:input  [:map [:profile [:map [:id :string] [:name :string] [:email :string]]]]
              :output [:map [:html :string]]}
   :on-error :render-error
   :requires []}

  :render-error
  {:id       :ui/render-error
   :schema   {:input  [:map]
              :output [:map [:html :string] [:error-status :int]]}
   :on-error nil
   :requires []}}

 :edges
 {:fetch-profile-by-id {:found :render-profile, :not-found :render-error}
  :render-profile      {:done :end}
  :render-error        {:done :end}}

 :dispatches
 {:fetch-profile-by-id [[:found     (fn [data] (:profile data))]
                         [:not-found (fn [data] (:error-type data))]]
  :render-profile      [[:done (fn [_] true)]]
  :render-error        [[:done (fn [_] true)]]}}
```

## Typical Workflow Loader Pattern

Use `pre-compile` at load time so requests pay zero compilation cost:

```clojure
(ns app.workflows.dashboard
  (:require [mycelium.manifest :as manifest]
            [mycelium.core :as myc]
            [clojure.java.io :as io]
            [app.middleware :as mw]
            [app.cells.auth]       ;; load cell registrations
            [app.cells.user]
            [app.cells.ui]))

(def manifest-data
  (manifest/load-manifest
   (str (io/resource "workflows/dashboard.edn"))))

(def compiled-workflow
  (myc/pre-compile
   (manifest/manifest->workflow manifest-data)
   mw/workflow-opts))

(defn run-dashboard [db request]
  (myc/run-compiled
   compiled-workflow
   {:db db}
   {:http-request {:query-params (or (:query-params request) {})
                   :cookies      (or (:cookies request) {})}}))
```

`pre-compile` performs workflow validation, FSM compilation, and Malli schema pre-compilation once. `run-compiled` only validates the input schema (if present) and runs the pre-built FSM.
