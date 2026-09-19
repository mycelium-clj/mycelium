---
name: patterns
description: Branching, joins, halt/resume, fragments, constraints — when to reach for each.
---
# Workflow Patterns

Composable shapes for common flow problems. All are plain manifest data —
compose them freely.

## Branching {#branching}

Handlers compute data; dispatch predicates decide the route. Keep decision
keys in the output schema so the contract covers routing inputs:

```clojure
:edges      {:check {:pass :process, :fail :error}}
:dispatches {:check [[:pass (fn [d] (pos? (:value d)))]
                     [:fail (fn [d] (not (pos? (:value d))))]]}
```

Use `:default` as a catch-all edge label for agent-generated routing safety
nets. `:default` must never be the only edge.

## Parallel Fan-Out/Fan-In {#joins}

```clojure
:joins {:fees {:cells [:calc-tax :calc-shipping :calc-gift] :strategy :parallel}}
:edges {:start :fees
        :fees  {:done :total :failure :error}}
```

Members have NO `:edges` entries. Each member gets the same input snapshot;
outputs merge. Member output keys must be disjoint — or provide `:merge-fn`.
Join default dispatches route to `:failure` when any member threw
(`:mycelium/join-error`).

## Human-In-The-Loop {#halt-resume}

A cell returns `:mycelium/halt {:reason :needs-approval ...}`; execution
pauses with all data and trace preserved. Resume later, possibly in another
process:

```clojure
(def halted   (store/run-with-store compiled res data store))
(def resumed  (store/resume-with-store compiled res
                (:mycelium/session-id halted) store {:approved true}))
```

## Reusable Subgraphs {#fragments}

`:fragments` merges a fragment manifest's cells/edges/dispatches into the
host — shared flows like cookie-auth parsing extracted once. Workflow-level
`:interceptors` add `:pre`/`:post` transformations by scope. Nest whole
workflows as cells with `mycelium.compose/register-workflow-cell!`; child
traces surface via `:mycelium/child-trace`.

## Compile-Time Invariants {#constraints}

```clojure
:constraints [{:type :must-follow :if :flag-missing :then :apply-tags}
              {:type :never-together :cells [:manual-review :auto-approve]}]
```

Four types: `:must-follow`, `:must-precede`, `:never-together`,
`:always-reachable`. Checked against every enumerated path at validation
time — violations name the offending path. Prefer a constraint over a
downstream runtime check whenever the invariant is knowable from the graph.
