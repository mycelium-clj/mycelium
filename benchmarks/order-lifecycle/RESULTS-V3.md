# Order Lifecycle V3 Benchmark Results

## Overview

V3 scales from 8 to 15 subsystems by adding 7 new cross-cutting features. Both
implementations use the same specification (SPEC-V3.md) and identical test
expectations across 52 tests with 383 assertions.

**New V3 Features:**
1. **Subscription pricing** — 15% off base price, excludes COMBO75 eligibility
2. **Bundle products** — composite items (gaming-bundle), opaque for promotions, component-level inventory
3. **Tiered shipping** — weight-based tiers + hazmat $3/electronics + oversized $5 if >4lb
4. **Warranty add-ons** — per-category cost ($49.99 electronics, $9.99 clothing, $59.99 bundle), 8% service tax
5. **Auto-upgrade loyalty tier** — bronze→silver at $500, silver→gold at $2000 lifetime spend
6. **County-level tax** — surcharges with exemption overrides (Buffalo clothing, Austin digital)
7. **Partial fulfillment** — split into fulfilled/backordered, shipping only for fulfilled items

---

## Traditional Approach

### Result: 52 tests, 383 assertions, 17 failures.

All 17 failures trace to a **single root cause**: the V1 `:shipping-detail` vs
`:shipping-groups` key mismatch bug. Returns code at line 188 destructures
`:shipping-detail`, but placement outputs `:shipping-groups`. This causes every
defective return's shipping refund to be `$0.00`.

**Affected tests:**
| Test | Expected shipping-refund | Actual | Cascade |
|------|-------------------------|--------|---------|
| T11  | $8.00 | $0.00 | total-refund $1033.11 vs $1041.11, payment refund wrong |
| T17  | $3.37 | $0.00 | total-refund $65.82 vs $69.19, payment refund wrong |
| T24  | $8.00 | $0.00 | total-refund $1038.50 vs $1046.50 |
| T28  | $3.37 | $0.00 | total-refund $74.37 vs $77.74, display-total wrong |
| T42  | $8.00 | $0.00 | total-refund $1087.10 vs $1095.10 |
| T47  | $3.44 | $0.00 | total-refund $74.37 vs $77.81 |
| T49  | $8.00 | $0.00 | total-refund $937.53 vs $945.53 |
| T52  | $8.00 | $0.00 | total-refund $1032.09 vs $1040.09 |

Each shipping-refund failure cascades into 1-2 additional assertion failures
(total-refund, payment-refund, display amounts), producing 17 total failures
from 8 distinct test cases.

### Latent Bugs (Not Caught by Tests)

**1. `:shipping-detail` vs `:shipping-groups` (V1 bug, STILL PRESENT — now CAUGHT)**
This bug existed silently in V1 and V2 because the tested scenarios didn't
exercise shipping refund paths that would reveal it. V3's tiered shipping
makes non-zero shipping much more common, exposing the bug in 8 of 52 tests.

**2. Double inventory reservation on modification (V1 bug, STILL PRESENT)**
`modify-order` calls `place-order` which re-reserves inventory without releasing
the original reservation. Still not caught by tests.

**3. `currency-rates` duplicated in 3 files**
`placement.clj`, `returns.clj`, and `modification.clj` each define their own
`currency-rates` constant.

**4. `warranty-cost` duplicated in 2 files**
`placement.clj` and `returns.clj` each define their own `warranty-cost` map.

**5. `gift-wrap-cost-per-item` duplicated in 2 files**
Same as V2 — placement and returns each define their own function.

Final size: ~920 lines across 3 source files.

---

## Mycelium Approach

### Result: 52 tests, 383 assertions, 0 failures, 0 errors — first attempt.

Structure:
- 2 workflow manifests: `placement.edn` (18 cells), `returns.edn` (10 cells)
- 2 cell implementation files: `placement_cells.clj` (~600 lines), `returns_cells.clj` (~370 lines)
- 1 workflow wrapper: `workflow.clj` (~176 lines)
- Total: ~1146 lines implementation + ~440 lines manifest

### Why the Shipping Bug Cannot Exist in Mycelium

The returns manifest explicitly declares `:shipping-groups` in the first cell's
input schema:

```clojure
;; returns.edn - :start cell
:schema
{:input  [:map
          [:items-detail [:vector :any]]
          [:shipping-groups :any]        ;; ← enforced by schema
          [:payment :any]
          ...]}
```

The workflow wrapper (`workflow.clj`) passes `:shipping-groups` from the
placement result. If the key name didn't match, schema validation would fail
at workflow execution time — not silently return nil.

### 4-Way Parallel Join

V3 adds warranty computation to the parallel fee calculation:

```clojure
:joins {:fees {:cells    [:calc-tax :calc-ship :calc-gift-wrap :calc-warranty]
               :strategy :parallel}}
```

All four cells receive the same snapshot and produce non-overlapping output keys.
Adding warranty required adding one keyword to the vector.

---

## Head-to-Head Comparison

| Metric | Traditional (V3) | Mycelium (V3) |
|--------|-----------------|---------------|
| Tests passing | 35/52 | 52/52 |
| Assertions passing | 366/383 | 383/383 |
| Source lines | ~920 | ~1146 |
| Manifest lines | 0 | ~440 |
| Latent bugs | 5 (2 from V1 + 3 new) | 0 |
| V1 bugs fixed | 0/2 | N/A (prevented by schema) |
| Shipping refund bug | **Now causes test failures** | Impossible by construction |
| Duplicate constants | 3 files × rates, 2 files × warranty | Module-scoped |

### Key V3 Findings

**1. The V1 shipping bug finally surfaces after 3 rounds of development.**
In V1, the `:shipping-detail` bug was latent — the tested scenarios had $0
shipping or used changed-mind returns (which always return $0 shipping). In V2,
the bug remained latent because the V2 flat-rate shipping was often free
(gold/platinum or subtotal ≥ $75). In V3, tiered shipping with surcharges means
most orders have non-zero shipping costs, and defective returns now fail visibly.

This demonstrates the **time-bomb nature of latent bugs** in traditional
codebases: a bug introduced in V1 explodes in V3 because V3's features create
new paths through the code that V1 and V2 tests never exercised.

**2. One root cause → 17 test failures (cascade amplification).**
A single wrong key name (`shipping-detail` vs `shipping-groups`) cascades
through shipping refund → total refund → payment refund → display amounts,
producing 17 assertion failures across 8 test cases. In a real system, this
would mean every defective return silently under-refunds the customer's shipping.

**3. All 35 passing tests pass identically.**
The traditional approach correctly implements all 7 new V3 features for order
placement. Subscription pricing, bundles, county tax, tiered shipping, warranty,
auto-upgrade, and partial fulfillment all produce correct results. The failures
are exclusively in returns — the module boundary where key names must match
without schema enforcement.

**4. Complexity growth is sub-linear for mycelium.**
V2→V3 added 7 features. Manifest growth: ~80 lines (from ~360 to ~440).
Implementation growth: ~246 lines (from ~900 to ~1146). Each new cell adds
~5-10 lines of manifest and ~30-50 lines of implementation. The manifest serves
as both documentation and runtime contract.

**5. The traditional approach demonstrates competent implementation.**
All 7 V3 features were implemented correctly in the traditional code:
- Subscription pricing properly excludes COMBO75 (T32)
- Bundles correctly don't trigger ELEC10/BUNDLE5/COMBO75 (T33, T34)
- County tax overrides work correctly (T35-T37)
- Tiered shipping with hazmat/oversized computes correctly (T38-T40)
- Warranty with correct changed-mind 50% refund (T43)
- Auto-upgrade triggers correctly with silver multiplier (T44)
- Partial fulfillment correctly splits fulfilled/backordered (T46, T47)

The failures are not from incompetence — they're from the structural impossibility
of tracking key names across module boundaries without a contract system.

---

## Bug Severity Analysis

| Bug | Type | V1 | V2 | V3 | Impact |
|-----|------|----|----|----|----|
| `:shipping-detail` mismatch | Key name error | Latent | Latent | **17 test failures** | Wrong refund amounts |
| Double inventory on modify | Logic error | Latent | Latent | Latent | Wrong inventory counts |
| Triplicated `currency-rates` | Maintenance risk | — | Present | Present | Rate drift |
| Duplicated `gift-wrap-cost` | Maintenance risk | — | Present | Present | Cost drift |
| Duplicated `warranty-cost` | Maintenance risk | — | — | Present | Cost drift |

---

## Scaling Trajectory

| Metric | V1 (3 subsystems) | V2 (8 subsystems) | V3 (15 subsystems) |
|--------|---|---|---|
| Latent bugs | 2 | 4 | 5 (but 1 now surfaced) |
| Test failures | 0 | 0 | **17** |
| Traditional lines | ~540 | ~722 | ~920 |
| Mycelium lines | ~720 | ~1260 | ~1586 |
| Interaction matrix | ~6 cells | ~24 cells | **48 cells** |
| Time-to-surface for V1 bugs | ∞ | ∞ | V3 (finally caught) |

The traditional approach's bug from V1 took **three rounds of development** to
surface. During that time, the codebase grew from 540 to 920 lines, 12 more
features were added, and the bug was invisible to every test suite, every code
review, and every AI agent that worked on the code. The mycelium approach
prevented this class of bug entirely through schema-enforced contracts at cell
boundaries.
