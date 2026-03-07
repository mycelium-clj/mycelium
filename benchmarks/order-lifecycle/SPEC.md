# Order Lifecycle Benchmark -- Specification

## Goal

Create a system complex enough that an LLM cannot hold all interacting business
rules in context simultaneously. The traditional approach should produce logic
bugs that the mycelium approach avoids through isolated cell development.

## Complexity Drivers

The system has three workflows that share data contracts:
1. **Order Placement** (16 cells) -- pricing, promotions, tax, shipping, payment
2. **Return Processing** (10 cells) -- must REVERSE order calculations exactly
3. **Order Modification** (6 cells) -- must compute DELTA correctly

The killer interactions:
- Returns must proportionally reverse per-item discounts from 3 stacking promo types
- Per-item tax depends on category x state (exemption matrix)
- Order-level discounts must be distributed proportionally and tracked per-item
- Split payment (gift card + credit) must be reversed in opposite order
- Loyalty points earned depend on post-discount total; returns claw back proportionally
- Modifications must recompute pricing and charge/refund the delta

## Product Catalog

| ID | Name | Category | Price | Weight | Warehouse |
|----|------|----------|-------|--------|-----------|
| laptop | Laptop | electronics | $999.99 | 5.0 | west |
| shirt | T-Shirt | clothing | $29.99 | 0.5 | east |
| novel | Novel | books | $14.99 | 0.8 | east |
| headphones | Headphones | electronics | $79.99 | 0.3 | west |
| ebook | E-Book | digital | $9.99 | 0.0 | digital |

## Tax Rules (per-item, based on category x state)

| State | Base Rate | Electronics | Clothing | Books | Digital |
|-------|-----------|-------------|----------|-------|---------|
| CA | 7.25% | +1.5% surcharge (=8.75% total) | normal | normal | normal |
| NY | 8.875% | normal | EXEMPT (< $110/item) | EXEMPT | normal |
| OR | 0% | all exempt | all exempt | all exempt | all exempt |
| TX | 6.25% | normal | normal | normal | EXEMPT |

Per-item tax: round(item_discounted_price x applicable_rate, 2) using HALF_UP.
Order tax = sum of per-item taxes (no further rounding).

## Promotions (applied in order, all stackable)

### 1. ELEC10 -- Electronics quantity discount
- Trigger: 2+ electronics items in order
- Effect: 10% off each electronics item's current price
- Per-item discount: round(item_price x 0.10, 2)

### 2. BUNDLE5 -- Cross-category bundle
- Trigger: 1+ electronics AND 1+ books in same order
- Effect: 5% off each item in both categories (at their current price after ELEC10)
- Per-item discount: round(item_price x 0.05, 2)

### 3. Order-level percentage discount
- Sources: coupon percentage (SAVE10=10%, SAVE20=20%) OR tiered (>=500 -> 5%, >=1000 -> 10%)
- Rule: highest of coupon% and tiered% wins (mutually exclusive)
- Applied to running subtotal AFTER category promos
- Per-item distribution: proportional to each item's current price, rounded, remainder-adjusted

### 4. Fixed coupon
- FLAT15: $15 off order subtotal, applied after percentage discount
- Distributed proportionally to items (same as order-level %)

### 5. Loyalty redemption
- Rate: 100 points = $5.00
- Applied last, after all other discounts
- Cannot exceed current subtotal
- Distributed proportionally to items

## Discount Tracking

Each item stores its final_price after all discounts. This is critical for returns.
The order stores per-item records:
```
{:product-id "laptop"
 :original-price 999.99
 :final-price 812.24    ;; after all discounts
 :tax-amount 71.07      ;; per-item tax, rounded
 :warehouse "west"}
```

## Shipping (per warehouse group)

- Group items by warehouse
- Per group: $5.99 base + $0.50/lb (total weight of group)
- Free if group's post-discount subtotal >= $75
- Digital items: no shipping (warehouse = "digital")
- Gold/Platinum membership: free all shipping
- Per-group cost rounded to 2 decimals
- Order shipping = sum of group costs

## Payment

### Single payment
- Credit card: first char '4' = approve, '5' = decline

### Split payment (gift card + credit card)
- Gift card applied first, up to its balance
- Remainder charged to credit card
- Order stores: {:gift-card-charged X, :credit-card-charged Y}

## Loyalty Program

### Earning
- earn_base = final_discounted_subtotal (after all discounts including loyalty redemption)
- points = floor(earn_base x tier_multiplier)
- Tier multipliers: bronze=1.0, silver=1.5, gold=2.0

### Tiers (based on lifetime_spend)
- $0 - $499: bronze
- $500 - $1999: silver
- $2000+: gold

### Redemption
- 100 points = $5.00
- Cannot redeem more than current subtotal
- Redeemed value subtracted from earn_base

## Fraud Check

- total > $5000 -> reject
- total > $2000 -> review (halt workflow for manual approval)
- otherwise -> approve

## Return Processing

### Input
- original_order: the full order record (with per-item final prices, tax, etc.)
- returned_items: list of {product-id, quantity, reason}
- reason: "defective" or "changed-mind"

### Refund Calculation (per returned item)
- Item refund = item's final_price from original order (already includes all discounts)
- Tax refund = item's tax_amount from original order
- Subtotal refund = sum of item refunds
- Tax refund total = sum of item tax refunds

### Shipping Refund
- "defective": proportional share of original warehouse shipping cost
  - share = item_final_price / warehouse_group_subtotal x warehouse_shipping_cost
  - round to 2 decimals
- "changed-mind": $0

### Loyalty Clawback
- clawback = floor(subtotal_refund / original_discounted_subtotal x original_points_earned)

### Payment Refund (reverse order of charge)
- Total refund = subtotal_refund + tax_refund + shipping_refund
- Credit card first, up to original credit card charge
- Remainder to gift card
- Order stores: {:credit-card-refunded X, :gift-card-refunded Y}

## Order Modification

### Input
- original_order: the full order record
- changes: list of {product-id, new-quantity}

### Process
1. Recompute entire pricing pipeline with new quantities
2. Calculate delta = new_total - original_total
3. If delta > 0: charge additional to credit card
4. If delta < 0: refund difference (credit card first, then gift card)
5. Recalculate loyalty points earned (delta)
6. Update inventory reservations

## Test Cases

### Order Placement

#### T1: Single item baseline
- 1x laptop, state CA, no promos, bronze, credit card 4xxx
- Expected: subtotal $949.99 (5% tiered), tax $83.12, ship $0, total $1033.11

#### T2: ELEC10 triggers
- 1x laptop + 1x headphones, state CA, bronze, credit card 4xxx
- Expected: ELEC10 10% off each, then tiered 5%

#### T3: BUNDLE5 triggers
- 1x headphones + 1x novel, state CA, bronze, credit card 4xxx
- Expected: BUNDLE5 5% off both (no ELEC10, only 1 electronics)

#### T4: ELEC10 + BUNDLE5 + tiered (complex stacking)
- 1x laptop + 1x headphones + 1x novel, state CA, bronze, credit card 4xxx
- Expected: all three promos stack

#### T5: NY tax exemptions
- 1x shirt + 1x novel, state NY, coupon SAVE10, bronze, credit card 4xxx
- Expected: clothing exempt, books exempt, tax = $0

#### T6: Loyalty redemption + Gold
- 1x laptop, state OR, 500 points to redeem, gold membership, credit card 4xxx
- Expected: tiered 5%, redeem $25, earn at 2x multiplier, free ship

#### T7: Split payment
- 1x laptop + 1x shirt, state CA, gift card $200 balance, credit card 4xxx
- Expected: gift card $200 + credit card remainder

#### T8: Multi-warehouse shipping
- 1x laptop + 1x shirt + 1x novel + 1x ebook, state TX, bronze, credit card 4xxx
- Expected: west shipment (laptop), east shipment (shirt+novel), no digital ship

#### T9: Fraud reject
- 10x laptop, state TX, bronze, credit card 4xxx
- Expected: error, order rejected (total > $5000)

#### T10: Payment decline + rollback
- 1x laptop, state CA, bronze, credit card 5xxx
- Expected: error, inventory rolled back

### Return Processing

#### T11: Full return of simple order (defective)
- Return all items from T1-like order, reason defective
- Expected: full refund of item + tax, proportional shipping refund

#### T12: Partial return from complex order (changed mind)
- Return headphones from T4-like order, reason changed-mind
- Expected: proportional discount reversal, no shipping refund, loyalty clawback

#### T13: Return from split payment order
- Return shirt from T7-like order
- Expected: refund to credit card first (reverse of charge order)

### Order Modification

#### T14: Increase quantity (charge more)
- Modify T1-like order: change laptop qty from 1 to 2
- Expected: recompute with ELEC10 now triggered, charge delta

#### T15: Decrease quantity changes discount tier
- Modify T4-like order: remove novel
- Expected: BUNDLE5 no longer triggers, recompute, refund delta
