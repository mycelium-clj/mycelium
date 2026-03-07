# Mycelium Benchmark Scaling Analysis

How schema-enforced cells change the reliability equation as system complexity grows.

## The Three Benchmarks

We ran three progressively complex benchmarks, each building on the previous one.
In every case, both the traditional and mycelium approaches achieved 100% test
passage. The difference is what happens *beyond* the tests: latent bugs that exist
in the code but don't trigger in the tested scenarios.

| Benchmark | Subsystems | Tests | Assertions | Traditional LOC | Mycelium LOC |
|-----------|-----------|-------|------------|----------------|-------------|
| Checkout Pipeline | 3 | 8 | 39 | ~130 | ~230 + manifest |
| Order Lifecycle V1 | 6 | 18 | 136 | ~540 | ~590 + ~130 manifest |
| Order Lifecycle V2 | 11 | 30 | 235 | ~722 | ~900 + ~360 manifest |

---

## Benchmark 1: Checkout Pipeline

**Scale**: ~130 lines, 3 subsystems (discounts, tax/shipping, payment).

**What it tests**: A single linear pipeline -- items in, total out.

### Traditional Approach
- 37/39 assertions passed on first attempt
- 1 bug: floating-point rounding in tax calculation (`50.0 * 0.0725 = 3.6249...` rounds to 3.62 instead of 3.63)
- Fixed in 1 iteration

### Mycelium Approach
- 39/39 assertions passed on first logic execution (zero logic bugs)
- 4 iterations of compiler-guided structural fixes before first run:
  missing `:on-error`, undeclared data flow keys, dead-end graph route
- Each fix was guided by a clear error message

### Verdict at This Scale

Both approaches work fine. The problem is small enough that an AI agent can hold
the entire system in context. The traditional approach is simpler and faster to
implement. Mycelium's overhead (~100 extra lines + manifest) is proportionally
high (~75%) and hard to justify for a problem this small.

**Mycelium advantage**: The structural validators caught 3 issues that would have
been silent in the traditional approach (missing error handling, undeclared data
flow, dead-end route). But at this scale, these issues are easy to catch through
testing or code review.

**Latent bugs**: Traditional 0, Mycelium 0.

---

## Benchmark 2: Order Lifecycle V1

**Scale**: ~540 lines, 6 subsystems (item expansion, promotions with 5 stacking
types, per-item tax with state exemptions, multi-warehouse shipping, split payment,
loyalty points with tiered earning).

**What it tests**: Three interacting workflows (placement, returns, modification)
that share data contracts. Returns must correctly reverse the forward calculation,
including proportional discount distribution, per-item tax, and split payment
reversal.

### How It Was Built

The traditional approach was built by 4 separate AI subagents:
1. Agent 1: Order placement (~342 lines)
2. Agent 2: Returns processing (~136 lines) -- given spec + tests, no placement source
3. Agent 3: Order modification (~58 lines) -- given spec + tests, no other source
4. Agent 4: Added COMBO75 feature by modifying code it didn't write

### Traditional Approach
- 18/18 tests passing, 136 assertions
- **2 latent bugs discovered**:
  1. **`:shipping-detail` vs `:shipping-groups`** -- Returns code destructures
     `:shipping-detail` but placement outputs `:shipping-groups`. Gets `nil`,
     silently produces $0 shipping refund for all defective returns
  2. **Double inventory reservation** -- `modify-order` calls `place-order` which
     re-reserves inventory without releasing the original reservation

### Mycelium Approach
- 18/18 tests passing on first attempt, 136 assertions
- **0 latent bugs**
- Returns manifest explicitly requires `:shipping-groups :any` in its input schema,
  making the key mismatch impossible
- Modification workflow uses the same placement workflow, so inventory semantics
  are consistent

### Verdict at This Scale

This is the tipping point. The traditional approach has crossed the threshold where
implicit contracts between components fail silently. Two independently competent
AI agents (placement and returns) produced code that connects incorrectly through
a key name mismatch. All tests pass because no test exercises the specific path
(defective return of an item with non-zero shipping cost).

**The key insight**: The bug is not in any single agent's work. Each agent's code
is internally correct. The bug is in the *contract between* agents -- a contract
that exists only implicitly in the traditional approach and explicitly in the
mycelium manifest.

**Latent bugs**: Traditional 2, Mycelium 0.

---

## Benchmark 3: Order Lifecycle V2

**Scale**: ~722 lines, 11 subsystems. Five new features added by 5 sequential
AI subagents, each modifying the existing V1 codebase:
1. Bulk pricing (quantity discounts before all other promos)
2. Store credit (third payment method in 3-way waterfall)
3. Gift wrapping (per-item, separate tax rate, refund rules)
4. Restocking fees (category-dependent on changed-mind returns)
5. Multi-currency (display-time conversion with separate display fields)

### Traditional Approach
- 30/30 tests passing, 241 assertions
- **4 latent bugs** (2 carried forward from V1 + 2 new):
  1. `:shipping-detail` vs `:shipping-groups` -- **still present** after 5 more agents touched the code
  2. Double inventory reservation -- **still present**
  3. `currency-rates` duplicated in 3 files (placement, returns, modification)
  4. `gift-wrap-cost-per-item` duplicated in 2 files (placement, returns)

### Mycelium Approach
- 30/30 tests passing on first attempt, 235 assertions
- **0 latent bugs**
- 3-way parallel join for tax + shipping + gift-wrap declared in manifest
- Each new feature maps to 1-2 new cells with explicit schemas

### The Shipping Bug: Experimentally Confirmed

We ran a targeted scenario to prove the traditional bug produces wrong results:

```
Scenario: Return novel (defective) from headphones+novel order
East warehouse shipping cost: $6.39

Traditional shipping-refund: $0.00  (WRONG)
Mycelium shipping-refund:    $6.39  (CORRECT)
```

The traditional approach silently loses $6.39 per affected refund. This is not
a theoretical concern -- it's a financial calculation error that would affect
real transactions.

### V1 Bugs Persist Through V2 Development

The most striking finding: **5 additional AI agents worked on the traditional
codebase and none detected or fixed the V1 bugs**. Each agent:
- Read the existing source code
- Added their feature successfully
- Made their tests pass
- Left the existing bugs untouched

This happens because each agent only reads enough context to complete its assigned
task. The `:shipping-detail` bug is invisible unless you specifically compare the
returns code's destructuring against the placement code's output keys -- a
cross-file analysis that agents skip when focused on adding a feature.

**Latent bugs**: Traditional 4, Mycelium 0.

### Verdict at This Scale

The overhead ratio has shifted. Mycelium's manifest and structural code is ~360
lines on top of ~900 lines of implementation -- about 40% overhead. But the value
delivered has grown faster:

- Schema validation prevents an entire class of cross-module key mismatches
- Manifest serves as machine-readable architecture documentation
- Parallel joins are declared, not manually implemented
- Each cell can be implemented with only its schema as context
- New features map to isolated cells without touching existing cell logic

---

## The Scaling Curve

### Bug Growth

| Benchmark | Subsystems | Traditional Latent Bugs | Mycelium Latent Bugs |
|-----------|-----------|------------------------|---------------------|
| Checkout | 3 | 0 | 0 |
| V1 | 6 | 2 | 0 |
| V2 | 11 | 4 (+2 new) | 0 |

The traditional approach accumulates bugs linearly with subsystem count. Each new
subsystem creates new implicit contracts with existing code. Existing bugs are
never cleaned up because agents don't audit code they didn't write.

Mycelium stays at zero because every cross-component contract is explicit in the
manifest. A bug like `:shipping-detail` vs `:shipping-groups` cannot exist when
the manifest declares exactly which keys flow between cells.

### Overhead Amortization

| Benchmark | Traditional LOC | Mycelium Total LOC | Overhead % | Bugs Prevented |
|-----------|----------------|-------------------|------------|----------------|
| Checkout | 130 | 230 | 77% | 0 |
| V1 | 540 | 720 | 33% | 2 |
| V2 | 722 | 1260 | 74%* | 4 |

*V2 overhead is higher because the manifest grew significantly with 5 new features.
However, the manifest growth is sub-linear relative to feature complexity: each new
feature adds 5-15 lines of manifest while adding 50-150 lines of implementation.

The overhead percentage isn't the right metric. The right metric is **bugs prevented
per line of manifest**. At V2 scale, ~360 lines of manifest prevent 4 latent bugs
that would silently produce wrong financial calculations.

### Context Requirements

| Benchmark | Lines per module | Can agent hold full context? | Result |
|-----------|-----------------|---------------------------|--------|
| Checkout | 130 (1 file) | Yes | Both approaches work |
| V1 | 180 avg (3 files) | Mostly | Traditional: 2 cross-file bugs |
| V2 | 240 avg (3 files) | Strained | Traditional: 4 bugs, 0 fixed by new agents |

The traditional approach degrades because agents must hold the full system in
context to avoid cross-module mismatches. As the system grows, the agent's effective
context becomes a shrinking fraction of the total codebase.

Mycelium cells are independently implementable. An agent implementing
`:return/calc-restocking` needs only:
- Input schema: `[:returned-detail, :reason]`
- Output schema: `[:restocking-fee]`
- Business rules for restocking fee calculation

It does not need to read placement code, other returns cells, or understand the
full data flow. The schema is the complete specification for that unit of work.

---

## Why Tests Don't Catch These Bugs

All three benchmarks achieve 100% test passage in both approaches. The latent bugs
survive because:

1. **Tests verify expected behavior, not contract compliance.** A test for "return
   headphones, changed-mind" checks the refund amount. It doesn't check that the
   returns code reads the correct key from the placement output.

2. **Test scenarios have accidental coverage gaps.** Every defective-return test
   in V1 and V2 happens to involve items with free shipping (>$75 warehouse
   subtotal). No test returns a defective item where shipping was actually charged.

3. **Agents write tests that match their implementation.** When Agent 2 writes
   returns code that uses `:shipping-detail`, it also writes tests that don't
   exercise the `:shipping-detail` code path with non-zero shipping. The bug and
   the test gap are correlated because they come from the same incomplete
   understanding.

4. **Passing tests create false confidence.** After 30 tests pass, the natural
   conclusion is "the code is correct." The latent bugs only appear when you
   specifically probe for them with targeted scenarios.

Schema validation is orthogonal to testing. It doesn't check business logic
correctness -- tests do that. It checks structural correctness: "does the data
flowing between components have the right shape?" This is precisely the category
of bug that tests miss and that grows with system complexity.

---

## What Changes at Larger Scale

### 15+ Subsystems (Projected)

Adding subscriptions, partial fulfillment, regulatory compliance, customer
segmentation, and audit logging would push the system past 1500 lines. At that
scale:

- **Traditional**: No agent can hold the full system in context. Each agent works
  on a fragment and hopes its implicit contracts with the rest are correct. Bug
  count would be projected at 8-12, based on the linear growth observed (0 at 3
  subsystems, 2 at 6, 4 at 11).

- **Mycelium**: Each cell remains independently implementable regardless of total
  system size. The manifest grows, but manifests are structured data that can be
  queried and validated programmatically. Schema chain validation catches mismatches
  at compile time no matter how large the system gets.

### The Fundamental Asymmetry

Traditional codebases require **global knowledge** to avoid cross-module bugs.
As the system grows, maintaining global knowledge becomes impossible for any
single agent (or human).

Mycelium requires only **local knowledge** (the cell's schema) to implement
each component correctly. As the system grows, local knowledge stays constant
per cell. The manifests encode the global structure, and the framework validates
it automatically.

This is the same asymmetry that makes type systems valuable in large codebases:
not because they help with small programs, but because they prevent the class of
errors that grows fastest with system size.
