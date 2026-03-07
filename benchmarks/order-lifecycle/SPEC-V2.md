# Order Lifecycle Benchmark V2 -- Scaled-Up Specification

## Changes from V1

V2 adds 5 new subsystems on top of the existing order lifecycle. Each new subsystem
interacts with multiple existing subsystems, creating a web of cross-cutting concerns
that is difficult to hold in context simultaneously.

**Existing subsystems** (from V1): item expansion, promotions (COMBO75/ELEC10/BUNDLE5/
tiered/fixed/loyalty), tax, shipping, payment, fraud, inventory, loyalty, returns,
modifications.

**New subsystems** (V2):
1. Multi-currency
2. Gift wrapping
3. Store credit (third payment method)
4. Restocking fees on returns
5. Bulk pricing (quantity discounts)

## New Subsystem Specifications

### 1. Multi-currency

**Supported currencies and rates** (per 1 USD):

| Currency | Rate | Symbol |
|----------|------|--------|
| USD | 1.00 | $ |
| EUR | 0.92 | € |
| GBP | 0.79 | £ |
| CAD | 1.36 | C$ |

**Rules:**
- Order input includes `:currency` (default `"USD"`)
- ALL internal calculations happen in USD (no conversion during computation)
- Display amounts are converted at the END for the result only
- Conversion: `display_amount = round2(usd_amount * rate)`
- Result includes both `:total` (USD) and `:display-total` (converted)
- Also: `:display-subtotal`, `:display-tax`, `:display-shipping`
- Loyalty points are always based on USD amounts (no conversion)
- Shipping thresholds ($75 free shipping) evaluated in USD
- Refunds computed in USD, displayed in original order currency
- Payment processed in USD amounts

**Key interaction**: Currency conversion must happen ONLY at display time. If
conversion happens during computation, all downstream calculations (tax rates,
shipping thresholds, discount tiers) will produce wrong results.

### 2. Gift Wrapping

**Rules:**
- Items can be individually gift-wrapped: include `{:gift-wrap true}` in item spec
- Item spec format: `{"laptop" 1}` becomes `{"laptop" {:qty 1 :gift-wrap true}}`
- Non-wrapped items use the simple format: `{"shirt" 1}` (qty only)
- Wrapping costs:
  - Books/digital: $2.99 per item
  - All other categories: $4.99 per item
- Gift wrap is a **service**, taxed at a flat 8% service rate regardless of state
  - Exception: OR (Oregon) = 0% on everything including services
- Gift wrap cost is NOT included in `:discounted-subtotal` (it's a separate line)
- Gift wrap cost IS included in `:total`
- Gift wrap tax is separate from product tax
- Result includes: `:gift-wrap-total` (sum of wrap costs), `:gift-wrap-tax`

**Return interactions:**
- Defective return: gift wrap fee IS refunded (product was defective)
- Changed-mind return: gift wrap fee NOT refunded (wrapping service was consumed)
- Gift wrap refund appears as `:gift-wrap-refund` in return result

**Key interaction**: Gift wrap tax uses a different rate than product tax, and must
be computed separately. The total includes wrap cost + wrap tax + product subtotal +
product tax + shipping.

### 3. Store Credit (Third Payment Method)

**Payment waterfall** (order of application):
1. Gift card (applied first, up to balance)
2. Store credit (applied second, up to balance)
3. Credit card (remainder)

**Input**: Order includes `:store-credit-balance` (default 0)

**Result includes**:
```
:payment {:gift-card-charged X
          :store-credit-charged Y
          :credit-card-charged Z}
```

**Refund waterfall** (REVERSE order):
1. Credit card first (up to original CC charge)
2. Store credit second (up to original SC charge)
3. Gift card last (remainder)

**Return result includes**:
```
:payment {:credit-card-refunded X
          :store-credit-refunded Y
          :gift-card-refunded Z}
```

**Key interaction**: Three-way payment split makes the refund waterfall more complex.
Each refund method is capped by the original charge amount for that method.

### 4. Restocking Fees on Returns

**Rules:**
- Changed-mind returns incur a restocking fee:
  - Electronics: 15% of item's `:final-price`
  - Clothing: 10%
  - Books: 5%
  - Digital: 0% (no restocking for digital)
- Defective returns: NO restocking fee
- Fee is per-item, rounded to 2 decimal places
- Fee is deducted from the subtotal refund
- Fee is NOT taxed
- Result includes: `:restocking-fee` (total fee across all returned items)
- Adjusted: `:subtotal-refund` = sum(final_prices) - restocking_fee
- Tax refund is unchanged (full tax refund regardless of restocking fee)
- Shipping refund is unchanged
- Total refund = adjusted subtotal + tax + shipping

**Key interaction**: Restocking fee reduces the subtotal refund, which affects
the loyalty clawback calculation (clawback is based on subtotal_refund, not
original item prices). The payment refund is based on total_refund (after
restocking fee deduction).

### 5. Bulk Pricing (Quantity Discounts)

**Rules:**
- 3-4 units of same product: 5% off that product's base price per unit
- 5+ units of same product: 10% off that product's base price per unit
- Applied BEFORE all other promotions (before COMBO75, ELEC10, BUNDLE5, etc.)
- Discount is on the original `:price`, stored as the new `:current-price`
- Bulk-discounted items still count toward category promotion thresholds
  (e.g., 3 laptops at 5% off still trigger ELEC10 since 3 >= 2 electronics)

**Pipeline order** (updated for V2):
1. Expand items
2. **Bulk pricing** (NEW)
3. COMBO75
4. ELEC10
5. BUNDLE5
6. Order-level %
7. Fixed coupon
8. Loyalty redemption

**Key interaction**: Bulk pricing reduces base prices before COMBO75's $75 discount
is distributed. Since COMBO75 distributes proportionally by `:current-price`, lower
bulk-discounted prices get a smaller share of the $75 discount. Also, bulk pricing
affects the tiered discount threshold (subtotal computed from bulk-discounted prices).

## Updated Item Specification Format

To support gift wrapping, items can use two formats:

```clojure
;; Simple (no gift wrap):
{"laptop" 1}

;; With options:
{"laptop" {:qty 1 :gift-wrap true}}
```

The expand-items step must handle both formats.

## New Test Cases

### T19: Multi-currency EUR order
- 1x laptop + 1x headphones, state CA, currency EUR, bronze, card 4xxx
- Same USD calculation as T2 (ELEC10 triggers)
- Expected: same USD totals, plus display amounts in EUR
  - display-subtotal: round2(923.38 * 0.92) = 849.51
  - display-tax: round2(80.79 * 0.92) = 74.33
  - display-shipping: round2(0.0 * 0.92) = 0.0
  - display-total: round2(1004.17 * 0.92) = 923.84

### T20: Gift wrapping with tax
- 1x laptop (gift-wrap), 1x shirt, state CA, bronze, card 4xxx
- Gift wrap: 1 item @ $4.99 = $4.99
- Gift wrap tax: round2(4.99 * 0.08) = $0.40
- Product subtotal: same as if no wrapping
- Product tax: same as if no wrapping (computed on product prices, not wrap)
- Total = product subtotal + product tax + shipping + gift-wrap + gift-wrap-tax
- Expected: discounted-subtotal 926.98, tax 80.71, shipping 6.24,
  gift-wrap-total 4.99, gift-wrap-tax 0.40,
  total = 926.98 + 80.71 + 6.24 + 4.99 + 0.40 = 1019.32

### T21: Gift wrapping Oregon (no service tax)
- 1x novel (gift-wrap), state OR, gold, card 4xxx
- Gift wrap: books @ $2.99
- Gift wrap tax: OR = 0%
- Expected: discounted-subtotal 14.99, tax 0.0, shipping 0.0 (gold),
  gift-wrap-total 2.99, gift-wrap-tax 0.0, total 17.98

### T22: Three-way split payment
- 1x laptop + 1x shirt, state CA, gift-card $200, store-credit $300, card 4xxx
- Total: 1013.93 (same as T7)
- Payment: gift-card 200.0, store-credit 300.0, credit-card 513.93
- Expected: payment split in waterfall order

### T23: Return with restocking fee (changed-mind, electronics)
- Place order: 1x laptop + 1x headphones, state CA, card 4xxx (same as T2)
- Return headphones, changed-mind
- Headphones final-price from T2: 65.69 (after ELEC10 + tiered)

Wait, I need to recalculate. T2 has ELEC10 + tiered stacking.

Actually, let me use exact values from the existing tests. T2 has:
- discounted-subtotal: 923.38
- From T2, laptop and headphones after ELEC10 + tiered 5%:
  - Need per-item detail. Let me compute:
  - After ELEC10: laptop 899.99, headphones 71.99
  - Tiered 5% on 971.98: discount 48.60
  - laptop share: round2(899.99/971.98 * 48.60) = 45.00
  - headphones share: 48.60 - 45.00 = 3.60
  - laptop final: 899.99 - 45.00 = 854.99
  - headphones final: 71.99 - 3.60 = 68.39
  - subtotal: 854.99 + 68.39 = 923.38 ✓

OK so headphones final-price = 68.39

- Restocking fee: electronics 15% = round2(68.39 * 0.15) = 10.26
- Subtotal refund: 68.39 - 10.26 = 58.13
- Tax refund: headphones tax from T2... need to compute:
  - headphones tax = round2(68.39 * 0.0875) = 5.98
- Shipping refund: 0.0 (changed-mind)
- Total refund: 58.13 + 5.98 + 0.0 = 64.11
- Loyalty clawback: floor(58.13 / 923.38 * 923) = floor(58.09) = 58
- Payment refund: all to credit card (cc-charged = 1004.17)
  cc-refunded = min(64.11, 1004.17) = 64.11

### T24: Return gift-wrapped defective item (wrap refunded)
- Place order: 1x laptop (gift-wrap), state CA, card 4xxx
- Return laptop, defective
- Wrap refund: $4.99 (defective = wrap refunded)
- Wrap tax refund: $0.40
- Subtotal refund: laptop final-price (no restocking for defective)
- Tax refund: laptop tax
- Total includes wrap refund + wrap tax refund
- Expected: subtotal-refund 949.99, tax-refund 83.12, shipping-refund 0.0,
  gift-wrap-refund 4.99, gift-wrap-tax-refund 0.40,
  total-refund = 949.99 + 83.12 + 0.0 + 4.99 + 0.40 = 1038.50

### T25: Return gift-wrapped changed-mind (wrap NOT refunded)
- Place order: 1x laptop (gift-wrap), state CA, card 4xxx
- Return laptop, changed-mind
- Wrap NOT refunded (changed-mind)
- Restocking: electronics 15% of 949.99 = round2(142.50) = 142.50
- Subtotal refund: 949.99 - 142.50 = 807.49
- Tax refund: 83.12 (full tax refund regardless of restocking)
- gift-wrap-refund: 0.0
- Total: 807.49 + 83.12 = 890.61

### T26: Bulk pricing (3 laptops)
- 3x laptop, state CA, bronze, card 4xxx
- Bulk: 3 units = 5% off = round2(999.99 * 0.05) = 50.00 per unit
- Per unit after bulk: 999.99 - 50.00 = 949.99
- ELEC10: 3 >= 2 electronics, 10% off each
  - disc: round2(949.99 * 0.10) = 95.00
  - after ELEC10: 949.99 - 95.00 = 854.99 each, total 2564.97
- Tiered: 2564.97 >= 1000, 10% off
  - discount: round2(2564.97 * 0.10) = 256.50
  - distributed equally (all same price): 85.50 each
  - after tiered: 854.99 - 85.50 = 769.49 each, total 2308.47

Wait, the distribution is proportional. Since all items have the same price:
  - each share: round2(854.99/2564.97 * 256.50) = round2(85.50) = 85.50
  - sum: 85.50 * 3 = 256.50 ✓
  - after tiered: 769.49 each, subtotal 2308.47

- Tax: 769.49 * 0.0875 = 67.33 each, total 201.99

Wait: round2(769.49 * 0.0875) = round2(67.330375) = 67.33
Total tax: 67.33 * 3 = 201.99

- Shipping: west warehouse, subtotal 2308.47 >= 75 → free
- Total: 2308.47 + 201.99 + 0.0 = 2510.46
- Fraud: 2510.46 <= 5000 → approve
- Loyalty: floor(2308.47 * 1.0) = 2308

### T27: Bulk pricing + COMBO75 + ELEC10 stacking
- 3x laptop + 1x headphones + 1x novel, state CA, bronze, card 4xxx
- Bulk: laptop 3 units = 5% off → 949.99 each; headphones 1 unit = no bulk; novel 1 = no bulk
- COMBO75: laptop + headphones + novel all present → $75 off distributed
  proportionally by current price
  - laptop current: 949.99 (x3), headphones: 79.99, novel: 14.99
  - For COMBO75, it applies to the 3 product TYPES, but we have 3 laptops.
  - COMBO75 triggers when "laptop", "headphones", AND "novel" product IDs are ALL
    present. The $75 discount is distributed across items of those product types.
  - Total current price of combo items: 949.99*3 + 79.99 + 14.99 = 2944.96
  - laptop(each) share: round2(949.99/2944.96 * 75) = round2(24.19) = 24.19
    Actually: 949.99/2944.96 = 0.32262... * 75 = 24.197... → 24.20
  - Hmm this gets complex. Let me just define the expected result and verify
    computationally later.

Actually, this is getting really complex for hand computation. Let me simplify
the test cases and compute them programmatically during implementation.

### T28: Multi-currency return (EUR)
- Place EUR order (same as T19), return headphones defective
- Refund computed in USD, displayed in EUR
- Expected: USD refund amounts + display-* amounts in EUR

### T29: Store credit return waterfall
- Place order with 3-way split (same as T22), return shirt changed-mind
- Refund waterfall: credit-card first (up to 513.93), then store-credit, then gift-card
- Expected: refund split following reverse waterfall

### T30: Modification that changes bulk pricing tier
- Place order: 5x laptop (10% bulk), modify to 2x laptop (no bulk)
- Major price change: losing 10% bulk + ELEC10 (need 2+ for ELEC10, still have 2)
- Actually with 2 laptops, ELEC10 still triggers (2 >= 2)
- But bulk pricing lost (2 < 3)
- Delta: new total should be higher since bulk discount is lost

## Complete Pipeline Order (V2)

### Placement
1. Expand items (handle both simple and option formats)
2. Bulk pricing
3. COMBO75
4. ELEC10
5. BUNDLE5
6. Order-level %
7. Fixed coupon
8. Loyalty redemption
9. Compute discounted subtotal
10. Compute gift wrap costs + gift wrap tax
11. Compute per-item tax (parallel with shipping)
12. Compute shipping (parallel with tax)
13. Compute total (subtotal + tax + shipping + gift-wrap + gift-wrap-tax)
14. Fraud check
15. Reserve inventory
16. Process payment (3-way waterfall)
17. Compute loyalty points
18. Finalize result (USD + display currency)

### Returns
1. Find returned items
2. Compute restocking fee (if changed-mind)
3. Compute subtotal refund (items - restocking)
4. Compute tax refund
5. Compute shipping refund
6. Compute gift wrap refund (defective only)
7. Compute gift wrap tax refund (defective only)
8. Compute total refund
9. Compute loyalty clawback
10. Compute payment refund (3-way reverse waterfall)
11. Convert to display currency
12. Restore inventory

### Modifications
1. Recompute full pricing pipeline with new items
2. Compute delta (in USD)
3. Convert delta to display currency
