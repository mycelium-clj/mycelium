# Checkout Pipeline Benchmark

## Thesis

Mycelium's core claim: by explicitly controlling operational context via schema-enforced cells with fixed scope, agentic coding produces more reliable results than traditional approaches where context grows unbounded.

This benchmark tests that claim by implementing the same checkout pipeline two ways:
1. **Traditional** -- a single Clojure namespace with composing functions
2. **Mycelium** -- a workflow manifest with isolated cells

## Why This Problem

An e-commerce checkout pipeline is a perfect stress-test because:

- **Long dependency chains**: a mistake in discount calculation silently corrupts tax, shipping, and payment amounts several steps later
- **Combinatorial branching**: membership tiers x coupon types x stock status x payment methods create many interacting paths
- **Cross-step data contracts**: shipping needs post-discount total + item weights; tax needs post-discount total + destination; payment needs final total after tax + shipping -- missing a field is a silent bug
- **Compensating transactions**: if payment fails after inventory is reserved, rollback is required -- agents forget state under context pressure
- **Precise numerical acceptance criteria**: every test case has an exact dollar amount, no ambiguity about correctness

## Product Catalog

| Product | Price  | Weight |
|---------|--------|--------|
| Widget  | $25.00 | 1.0 lb |
| Gadget  | $150.00| 3.0 lb |
| Gizmo   | $75.00 | 2.0 lb |

## Discount Rules

### Percentage Discounts (only the highest one applies)
- Coupon codes: SAVE10 = 10%, SAVE20 = 20% (min $100)
- Membership: Gold = 5%, Platinum = 10%
- Tiered: cart >= $200 -> 5%, >= $500 -> 10%, >= $1000 -> 15%

### Fixed Discounts (stack with each other and with the winning percentage)
- Coupon codes: FLAT15 = $15 off

### Order of Operations
1. Calculate cart subtotal from catalog prices
2. Collect all applicable percentage discounts, pick highest
3. Apply percentage discount to subtotal
4. Apply fixed discounts (subtract from result)
5. Floor discounted subtotal at $0

## Coupon Codes

| Code     | Type       | Value | Min Order | Notes          |
|----------|------------|-------|-----------|----------------|
| SAVE10   | percentage | 10%   | none      |                |
| SAVE20   | percentage | 20%   | $100      |                |
| FLAT15   | fixed      | $15   | none      |                |
| BIGSPEND | percentage | 10%   | $50       | test threshold |

## Membership Tiers

| Tier     | Discount | Free Shipping       | Priority |
|----------|----------|---------------------|----------|
| (none)   | 0%       | no                  | no       |
| bronze   | 0%       | no                  | no       |
| silver   | 0%       | if subtotal > $75   | no       |
| gold     | 5%       | always              | no       |
| platinum | 10%      | always              | yes      |

## Tax Rates (by state)

| State | Rate   |
|-------|--------|
| CA    | 7.25%  |
| NY    | 8.875% |
| OR    | 0%     |
| TX    | 6.25%  |

Tax is applied to the discounted subtotal. Rounding: half-up to 2 decimal places.

## Shipping

- Base fee: $5.99
- Per-pound: $0.50/lb
- Free if discounted subtotal >= $100
- Free if membership grants it (silver: subtotal > $75; gold/platinum: always)
- Weight is calculated from ALL items regardless of discounts

## Payment

- Cards starting with "4" -> approved (returns transaction ID)
- Cards starting with "5" -> declined
- On decline: rollback inventory reservation, return error

## Final Total

`total = discounted_subtotal + tax + shipping`

## Acceptance Test Cases

### Test 1: Basic flow
- Cart: 2x Widget ($25)
- No coupon, no membership, state: CA, card: 4111...
- Subtotal: $50.00
- Discounts: none
- Tax: $50.00 x 7.25% = $3.63
- Shipping: 2lb x $0.50 + $5.99 = $6.99 (subtotal $50 < $100)
- **Expected total: $60.62**

### Test 2: Coupon + membership free shipping
- Cart: 1x Gadget ($150) + 1x Widget ($25)
- Coupon: SAVE10 (10%), membership: silver, state: NY, card: 4111...
- Subtotal: $175.00
- Percentage discounts: SAVE10=10%. Highest=10%.
- $175.00 x 0.90 = $157.50
- Tax: $157.50 x 8.875% = $13.98
- Shipping: free (silver + subtotal $157.50 > $75)
- **Expected total: $171.48**

### Test 3: Gold + fixed coupon + no tax (OR)
- Cart: 3x Widget ($25) + 2x Gadget ($150)
- Coupon: FLAT15 ($15 fixed), membership: gold, state: OR, card: 4111...
- Subtotal: $375.00
- Percentage: Gold=5%, Tiered ($375 >= $200)=5%. Highest=5%.
- $375.00 x 0.95 = $356.25
- Fixed: $356.25 - $15.00 = $341.25
- Tax: $0.00 (Oregon)
- Shipping: free (gold membership)
- **Expected total: $341.25**

### Test 4: Platinum + tiered
- Cart: 10x Widget ($25)
- No coupon, membership: platinum, state: TX, card: 4111...
- Subtotal: $250.00
- Percentage: Platinum=10%, Tiered ($250 >= $200)=5%. Highest=10%.
- $250.00 x 0.90 = $225.00
- Tax: $225.00 x 6.25% = $14.06
- Shipping: free (platinum)
- **Expected total: $239.06**
- **Priority fulfillment: true**

### Test 5: Coupon minimum threshold rejected
- Cart: 1x Widget ($25)
- Coupon: BIGSPEND (10%, min $50) -- rejected (cart $25 < $50)
- No membership, state: CA, card: 4111...
- Subtotal: $25.00
- Discounts: none (coupon rejected)
- Tax: $25.00 x 7.25% = $1.81
- Shipping: 1lb x $0.50 + $5.99 = $6.49 (subtotal $25 < $100)
- **Expected total: $33.30**
- **Coupon warning: "Coupon BIGSPEND requires minimum order of $50.00"**

### Test 6: Payment failure + rollback
- Cart: 2x Widget ($25)
- No coupon, no membership, state: CA, card: 5111...
- Processing identical to test 1 through inventory reservation
- Payment declined -> inventory rolled back
- **Expected: error with reason "Payment declined"**
- **Inventory restored to original levels**

### Test 7: Empty cart
- Cart: []
- **Expected: validation error "Cart is empty"**

### Test 8: Complex stacking (SAVE10 vs Gold, with membership shipping)
- Cart: 2x Widget ($25) + 2x Gadget ($150)
- Coupon: SAVE10 (10%), membership: gold, state: NY, card: 4111...
- Subtotal: $350.00
- Percentage: SAVE10=10%, Gold=5%, Tiered ($350 >= $200)=5%. Highest=10%.
- $350.00 x 0.90 = $315.00
- Fixed: none
- Tax: $315.00 x 8.875% = $27.96
- Shipping: free (gold membership)
- **Expected total: $342.96**

## Experimental Protocol

### Round A: Traditional Approach
Implement the checkout as a single Clojure namespace with composing functions.
No mycelium. Attempt to get all 8 test cases passing in one implementation pass.
Document: bugs on first attempt, fix iterations, types of errors.

### Round B: Mycelium Approach
Define the workflow as a manifest. Implement each cell independently with schema
contracts. Each cell is implemented with only its schema visible, not the full
pipeline context. Document: bugs on first attempt, fix iterations, types of errors.

### Metrics
1. Tests passing on first attempt (before any fixes)
2. Number of fix iterations to reach green
3. Bug taxonomy: wrong-calculation, missing-key, wrong-branch, stale-state, ordering-error
4. Final correctness
5. Approximate token usage (conversation length as proxy)

## Hypothesis

The traditional approach will suffer from:
- Ordering errors (applying discounts in wrong sequence)
- Missing data propagation (forgetting to pass a key downstream)
- Incorrect free-shipping logic (checking pre-discount vs post-discount subtotal)
- Payment rollback bugs (forgetting to restore inventory state)

The mycelium approach will avoid these because each cell's schema makes
the contract explicit, and the agent never sees the full pipeline context.
