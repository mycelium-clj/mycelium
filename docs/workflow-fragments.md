# Workflow Fragments

Fragments are reusable sub-workflows that can be embedded into multiple host manifests, eliminating duplication of common patterns like authentication flows.

## Problem

Without fragments, every workflow that needs authentication duplicates the same 3-4 cells, edges, and dispatches. Changing the auth flow means updating 6+ manifest files.

## Fragment Structure

A fragment is an EDN file declaring its own cells, edges, dispatches, entry point, and named exits:

```clojure
;; resources/fragments/cookie-auth.edn
{:id    :cookie-auth
 :doc   "Cookie-based auth: extract token → validate session → fetch profile"

 :entry :extract-session        ;; which cell is the entry point
 :exits [:success :failure]     ;; named exits the host must wire

 :cells
 {:extract-session
  {:id       :auth/extract-cookie-session
   :schema   {:input  [:map [:http-request [:map]]]
              :output [:per-transition
                       {:success [:map [:auth-token :string]]
                        :failure [:map [:error-type :keyword]
                                       [:error-message :string]]}]}
   :on-error :_exit/failure     ;; resolves to host's :failure exit target
   :requires []}

  :validate-session
  {:id       :auth/validate-session
   :schema   {:input  [:map [:auth-token :string]]
              :output [:per-transition
                       {:authorized   [:map [:session-valid :boolean] [:user-id :string]]
                        :unauthorized [:map [:session-valid :boolean]
                                            [:error-type :keyword]
                                            [:error-message :string]]}]}
   :on-error :_exit/failure
   :requires [:db]}

  :fetch-profile
  {:id       :user/fetch-profile
   :schema   {:input  [:map [:user-id :string] [:session-valid :boolean]]
              :output [:per-transition
                       {:found     [:map [:profile map?]]
                        :not-found [:map [:error-type :keyword]
                                         [:error-message :string]]}]}
   :on-error :_exit/failure
   :requires [:db]}}

 :edges
 {:extract-session  {:success :validate-session
                     :failure :_exit/failure}
  :validate-session {:authorized   :fetch-profile
                     :unauthorized :_exit/failure}
  :fetch-profile    {:found     :_exit/success
                     :not-found :_exit/failure}}

 :dispatches
 {:extract-session  [[:success (fn [data] (:auth-token data))]
                     [:failure (fn [data] (:error-type data))]]
  :validate-session [[:authorized   (fn [data] (:session-valid data))]
                     [:unauthorized (fn [data] (not (:session-valid data)))]]
  :fetch-profile    [[:found     (fn [data] (:profile data))]
                     [:not-found (fn [data] (:error-type data))]]}}
```

## Exit References

Fragment edges use `:_exit/name` keywords to reference named exits:
- `:_exit/success` → resolves to the host's `:success` exit target
- `:_exit/failure` → resolves to the host's `:failure` exit target

Cell `:on-error` fields can also use `:_exit/*` references. They are resolved during expansion.

## Using Fragments in Manifests

Host manifests reference fragments via the `:fragments` key:

```clojure
;; resources/workflows/dashboard.edn
{:id :dashboard
 :input-schema [:map [:http-request [:map]]]

 :fragments
 {:auth {:ref   "fragments/cookie-auth.edn"  ;; load from classpath resource
         :as    :start                         ;; entry cell becomes :start
         :exits {:success :render-dashboard    ;; :_exit/success → :render-dashboard
                 :failure :render-error}}}     ;; :_exit/failure → :render-error

 :cells
 {:render-dashboard
  {:id       :ui/render-dashboard
   :schema   {:input  [:map [:profile [:map [:name :string] [:email :string]]]]
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
 {:render-dashboard {:done :end}
  :render-error     {:done :end}}

 :dispatches
 {:render-dashboard [[:done (fn [_] true)]]
  :render-error     [[:done (fn [_] true)]]}}
```

## Fragment Mapping Fields

| Field | Required | Description |
|-------|----------|-------------|
| `:ref` | one of `:ref`/`:fragment` | Classpath resource path to fragment EDN file |
| `:fragment` | one of `:ref`/`:fragment` | Inline fragment data (instead of loading from file) |
| `:as` | no | Rename the fragment's entry cell (commonly `:start`) |
| `:exits` | yes | Map of fragment exit names to host cell targets |

## Expansion Process

Fragment expansion happens at manifest load time (`load-manifest` calls `expand-fragments` before `validate-manifest`):

1. Load fragment EDN (from `:ref` path or inline `:fragment`)
2. Validate fragment structure (entry exists, exits declared, edges consistent)
3. Rename entry cell to `:as` value if provided
4. Replace `:_exit/*` references in edges with host exit targets
5. Resolve `:_exit/*` in cell `:on-error` fields
6. Check for cell name collisions between fragment and host
7. Merge fragment cells, edges, dispatches into host manifest

## Programmatic API

```clojure
(require '[mycelium.fragment :as fragment])

;; Load fragment from classpath
(fragment/load-fragment "fragments/cookie-auth.edn")

;; Validate a fragment
(fragment/validate-fragment fragment-data)

;; Expand a single fragment
(fragment/expand-fragment fragment-data
  {:as :start, :exits {:success :next-cell, :failure :error-cell}}
  existing-host-cells)
;; => {:cells {...} :edges {...} :dispatches {...}}

;; Expand all fragments in a manifest
(fragment/expand-all-fragments manifest)
```

## Validation

Fragment validation checks:
- `:entry` exists and is a valid cell name in the fragment
- `:exits` is non-empty
- All edge targets are either fragment cell names or `:_exit/name` for declared exits
- All fragment exits are wired in the host mapping
- No cell name collisions between fragment and host cells

## Tips

- Fragment cells' `:on-error` should use `:_exit/failure` to route errors to the host's error handler
- The `:as` rename only applies to the entry cell — other fragment cells keep their original names
- Multiple fragments can be used in the same manifest as long as cell names don't collide
- Fragments cannot contain `:joins` (keep join workflows as standalone manifests)
- Fragment dispatch predicates in EDN use `(fn ...)` forms, same as manifests
- `:dispatches` can be omitted from fragments (and host manifests) when all edges are unconditional keywords
- Fragment cells support `:schema :inherit` to pull schemas from the cell registry instead of duplicating them
