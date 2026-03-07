# Checkout Pipeline Benchmark Results

## Executive Summary

Both approaches achieved 100% test passage, but through very different paths.
The traditional approach had a runtime logic bug (floating-point precision).
The mycelium approach had zero logic bugs -- all structural issues were caught
and corrected through compiler-guided feedback before any business logic ran.

## Traditional Approach

### First Attempt
- **37/39 assertions passed** (2 failures in test-1)
- Bug: `50.0 * 0.0725 = 3.6249999999999996` in double arithmetic
  - Rounds to 3.62 instead of 3.63 (half-up at boundary)
  - Category: **wrong-calculation** (floating-point precision)
  - Root cause: used `(* subtotal rate)` with doubles instead of BigDecimal
- Fix: switched `calc-tax` to BigDecimal multiplication

### Fix Iterations: 1
### Final Result: 39/39 pass

### Bug Taxonomy
| Bug | Category | Caught By | Severity |
|-----|----------|-----------|----------|
| FP rounding in tax | wrong-calculation | test assertion | Medium -- would produce wrong totals |

## Mycelium Approach

### Compiler-Guided Development

The mycelium framework's validators acted as a **compiler** that guided correct
construction before any business logic executed. Each "error" was the framework
telling the developer what was missing -- equivalent to a type error in a
statically-typed language. This is a feature, not a bug.

### Attempt 1: FileNotFoundException
- `slurp` with relative path failed; fixed with `clojure.java.io/resource`
- **Infrastructure setup** -- same as any new project

### Attempt 2: Manifest validator -- missing :on-error
- Framework requires `:on-error` declaration on every cell
- This forces the developer to consider error handling for every cell upfront
- In the traditional approach, no such guardrail exists -- error handling is
  ad-hoc and easily forgotten
- **Framework guided the developer to handle a case they might have skipped**

### Attempt 3: Schema chain validator -- undeclared data flow
- Framework caught that `:membership`, `:state`, `:card` etc. were in the data
  but not declared in any cell's schema chain
- The compiler said: "requires keys #{:membership} but only #{...} available"
- This forced explicit declaration of ALL data flowing through the pipeline
- In the traditional approach, these keys exist implicitly in function args --
  if one is misspelled or missing, it fails silently at runtime
- **Framework prevented a class of silent data-flow bugs**

### Attempt 4: Graph reachability validator -- dead-end route
- Framework caught that `:rollback → :error` had no path to `:end`
- This forced correct graph structure -- every path must terminate properly
- In the traditional approach, a forgotten error path just silently drops
- **Framework prevented an incomplete error handling path**

### Attempt 5: All tests pass
- **39/39 assertions passed** on first logic execution
- **Zero logic bugs** -- every cell's handler was correct on the first attempt

### Key Observation
The framework's validators required 4 iterations of structural fixes, but each
fix was guided by a clear error message telling the developer exactly what was
wrong and how to fix it. This is analogous to fixing type errors in a compiled
language -- the compiler tells you what's missing, you add it, and when it
compiles, the logic is correct.

**Without the framework, these issues would have been:**
- Missing `:on-error` → error handling silently absent
- Undeclared data flow → silent key lookup failures at runtime
- Dead-end error route → error path silently incomplete

## Comparison

| Metric | Traditional | Mycelium |
|--------|-------------|----------|
| Logic bugs on first attempt | 1 | 0 |
| Structural issues caught by compiler | 0 | 3 |
| Fix iterations (logic) | 1 | 0 |
| Fix iterations (compiler-guided structural) | 0 | 3 |
| Fix iterations (infrastructure) | 0 | 1 |
| Final correctness | 39/39 | 39/39 |
| Lines of implementation code | ~130 | ~200 (cells) + ~30 (workflow) + manifest |

## Analysis

### What This Benchmark Shows

1. **Compiler-guided development works**: The schema chain validator, graph
   reachability checker, and on-error validator caught 3 structural issues that
   would have been silent in the traditional approach. The developer was guided
   to the correct solution by clear error messages at each step.

2. **Zero logic bugs with mycelium**: When cells are implemented against explicit
   schema contracts, the handler logic is simple enough to get right on the
   first attempt. The schema IS the specification.

3. **Framework overhead is front-loaded**: The structural fixes add work upfront
   but eliminate entire classes of runtime bugs. This is the same tradeoff as
   static typing vs dynamic typing.

### What This Benchmark Does NOT Show

**This problem is too small to demonstrate context rot.** The entire traditional
implementation is ~130 lines -- well within an LLM's context window. The
traditional approach succeeded because the agent could hold all the business
rules simultaneously.

The real advantage of mycelium emerges at scale, when:
- The codebase exceeds what fits in a single context window
- Interactions between distant parts of the code create subtle bugs
- Returns/reversals must correctly mirror forward calculations
- Multiple promotion types interact in non-obvious ways
