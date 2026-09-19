# Manifest Syntax

A manifest is an EDN map describing a workflow as pure data: cells, edges,
dispatches, and optional structure. Validate with `myc validate <path>`.

## Top-Level Keys {#top-level}

- `:id` (required) — namespaced workflow keyword, e.g. `:shop/checkout`
- `:doc` — one-line description
- `:cells` (required) — `{cell-name {:id :ns/cell-id :doc ... :schema {...} :requires [...] :on-error target-or-nil}}`
- `:edges` (required) — `{cell {label target-or-map-of-labels}}` or bare
  keyword for unconditional edges
- `:dispatches` — `{cell [[:label (fn [data] ...)] ...]}`; dispatch predicate
  forms are evaluated by SCI at load time. Checked in order, first match wins.
- `:joins` — parallel/sequential fan-in groups
- `:regions` — named cell clusters for LLM context scoping
- `:constraints` — compile-time path invariants
- `:timeouts` — `{cell ms}`; on expiry routes via the `:timeout` edge
- `:error-groups` — shared error routing for sets of cells
- `:pipeline` — linear shorthand; mutually exclusive with `:edges`,
  `:dispatches`, `:joins`, `:fragments`
- `:input-schema` — workflow-level input validation
- `:fragments` — reusable subgraph includes

## Edges And Dispatches {#edges-dispatches}

```clojure
:edges {:validate {:authorized :fetch-profile
                   :unauthorized :error}
        :fetch-profile :end}
:dispatches {:validate [[:authorized (fn [d] (:session-valid d))]
                        [:unauthorized (fn [d] (not (:session-valid d)))]]}
```

Every edge label needs a dispatch predicate and vice versa. `:default` as an
edge label is a catch-all — no predicate required. `:end`, `:error`, `:halt`
are terminals.

## Schemas {#schemas}

Cell schemas live in the manifest (source of truth), in lite or Malli syntax:

```clojure
:schema {:input  {:user-id :string}          ; lite
         :output [:map [:total :double]]}    ; malli
```

Branching cells declare output per transition with the mandatory wrapper:

```clojure
:output [:per-transition
         {:found [:map [:profile :map]]
          :not-found [:map [:error-message :string]]}]
```

Optional keys: `[:b {:optional true} [:maybe :string]]` — excluded from
upstream availability checks.

## Structural Validation Catches {#validation-catches}

`myc validate` fails on: missing `:id`/`:cells`/`:edges`, unknown edge
targets, unreachable cells, dispatch/edge label mismatch, invalid Malli
schemas, missing `:on-error` (strict mode is the default), join output-key
conflicts without `:merge-fn`, region overlaps, unknown constraint cells.

Hash the manifest with `myc hash <path>` before patching; pass the hash to
`myc patch --expect-hash` so a stale edit fails instead of writing.
