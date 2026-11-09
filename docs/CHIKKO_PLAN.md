# Chikko: Planning and Architecture

Android POS and costing app for a home-based Filipino food business. Single owner, single phone. Kotlin, Jetpack Compose (Material 3). Currency: PHP (₱). Offline-first with Room as source of truth and two-way Google Sheets sync.

> Planning document only. No app code is included.

## 0. Confirmed decisions

| Topic | Decision |
|---|---|
| Unit costing | Weighted average per inventory item. No FIFO. |
| Devices | One phone. Sync rules assume one writer at a time for each record. |
| Tax | Non-VAT. Prices are final. No VAT fields. |
| Periods | Monthly periods are closed manually. Closed periods are locked. Reopen is explicit and logged. |
| Money | `Long` centavos. Unit costs use `Long` micro-centavos (1/1000 centavo) for precision. |
| Quantities | `Long` thousandths (for example, 0.150 kg = 150). |
| IDs | UUIDv4 strings. Never reused, never renumbered. |
| Timestamps | UTC epoch millis. Displayed in Asia/Manila. |

**Assumptions to confirm (see section 9):** expense allocation targets, void/delete behavior in closed periods, negative-stock policy.

---

## 1. Feature breakdown

### 1.1 MVP (phase 1)

| Module | MVP scope |
|---|---|
| POS | Category grid, item tiles, cart, qty +/-, line and sale discounts (% or ₱), item notes, variant and add-on selection, payment recording (Cash, E-wallet GCash/Maya, Bank transfer), single payment per sale. |
| Order history | List by date, filter by status and payment type, sale detail, void, refund (full and partial), edit, delete. |
| Menu manager | Categories and items, CRUD, variants and add-ons, base price, active/inactive. Starts empty with clearly labeled placeholder examples (for example "EXAMPLE: Chicken Adobo Rice Meal"), with a one-tap "remove examples" action. |
| Inventory | Ingredients and packaging: CRUD, unit, on-hand stock, weighted-average cost, low-stock threshold, stock in (purchase), stock out, wastage, manual count adjustment, movement history. |
| Recipes | Recipe lines link ingredients and packaging to a menu item or variant, with qty per serving and yield %. |
| Packaging costing | Packaging is a recipe line type. Included in food cost (total variable cost). |
| Expenses | CRUD, category, amount, period, recurring (manual copy in MVP), allocation method per expense, allocation to menu items. |
| Costing view | Cost per serving, margin %, suggested price for each menu item. Recalculates on change. |
| Reports | Daily, weekly, monthly, custom range. Gross sales, discounts, refunds, voids, food/packaging cost, overhead, gross profit, net profit, best sellers (by qty and by revenue). |
| Sync | Outbox queue, WorkManager push and pull, Google Sign-In, Receipts/Sales tab and master-data tabs, conflict log, sync status screen. |

### 1.2 Later phases

| Phase | Items |
|---|---|
| 2 | Split payments per sale. Recurring expense templates. Expense allocation to ingredient unit cost (landed cost). CSV export and import. Charts. Stock-out blocking option. Low-stock notifications. |
| 3 | FIFO costing option (only if needed). VAT-inclusive mode and VAT-exclusive reporting. Barcode scanning. Item photos. Printed-receipt support (explicitly excluded now). |
| 4 | Second device (would require a multi-writer model with per-device change IDs and a sync server or stronger merge). Sales forecasting. |

---

## 2. Architecture

### 2.1 Pattern and layers

MVVM with unidirectional data flow. Compose screens observe `StateFlow` from ViewModels. ViewModels call use cases. Use cases operate on repositories. Repositories are the only layer that touches Room, outbox, or network.

```
UI (Compose screens, one ViewModel per screen)
  -> Use cases (domain: PlaceSale, VoidSale, RefundSale, EditSale, DeleteSale,
                 RecordStockMovement, RecalculateCosts, ClosePeriod, ...)
    -> Repositories (Room DAOs + Outbox writer, in one transaction)
      -> Room (local source of truth, ledger tables)
      -> SyncEngine (WorkManager jobs) -> Google Sheets REST client
```

Rules:
- Every write goes through one use case and one Room transaction. That transaction writes domain rows, ledger entries, audit log, and outbox rows together.
- UI never computes money. It displays values from the ledger or from the costing projection.
- Domain logic is plain Kotlin with no Android imports, so it can be unit-tested on the JVM.

### 2.2 Libraries

| Concern | Choice | Justification |
|---|---|---|
| UI | Jetpack Compose, Material 3 | Requested. |
| Navigation | Navigation Compose (type-safe routes) | Standard, simple back stack. |
| State | ViewModel, StateFlow, Kotlin coroutines | Standard MVVM. |
| Database | Room (SQLite) | Requested. Migrations are explicit. |
| Background sync | WorkManager | Requested. Survives reboots and offline periods. Unique work names per sync lane. |
| Serialization | kotlinx.serialization | Outbox payloads, sheet row mapping, backup JSON. |
| Network | OkHttp + kotlinx.serialization against Sheets v4 REST | Smaller footprint than the full Google API client. Fewer transitive-dependency conflicts. Full control over batch calls and retry. |
| Auth | Google Identity authorization (Google Sign-In for Android) with the `spreadsheets` scope | Gives an access token for the Sheets REST calls. |
| DI | Manual `AppContainer` (constructor injection) | Hilt and Dagger need annotation processing, which is a build risk on CodeAssist's in-process compiler. Manual DI is enough for one user. |
| Date and time | java.time (desugared as needed for min SDK) | Standard. |
| Testing | JUnit 4 or 5, Turbine (Flow), Room in-memory tests, Compose UI tests (later) | Costing and reversal logic must be tested without a device. |

**Early spike (before committing):** confirm that Room's annotation processor and kotlinx.serialization plugin run in CodeAssist's build. If Room cannot run in-process, the fallback is raw SQLite behind the same repository interfaces. That is a major change, so verify it first.

### 2.3 Package layout

```
dev.chikko
  app/            AppContainer, navigation, theme
  core/           money, quantity, ids, clock, result types
  domain/         models, use cases, costing engine, ledger rules
  data/
    db/           Room database, entities, DAOs, migrations
    repo/         repositories (transaction boundary)
    outbox/       outbox writer and reader
    sync/         SyncEngine, WorkManager workers, conflict resolver
    sheets/       Sheets REST client, row mappers, schema definitions
    auth/         Google sign-in and token provider
  ui/
    pos/ orders/ menu/ inventory/ recipes/ expenses/ costing/ reports/ sync/ settings/
```

---

## 3. Database schema (Room)

### 3.1 Conventions

- Every mutable table has: `id TEXT PK`, `rev INTEGER` (incremented on every app write), `createdAt`, `updatedAt`, `deletedAt NULLABLE` (soft delete for master data only).
- Ledger tables are append-only. Triggers or DAO rules block UPDATE and DELETE on them.
- Sales and their lines are never hard-deleted. Deletion is a tombstone plus reversal entries.
- Snapshots are copied values, not foreign keys to current prices or costs.

### 3.2 Master data

| Entity | Key columns | Notes |
|---|---|---|
| `category` | id, name, sortOrder, active | Unlimited categories. |
| `menu_item` | id, categoryId FK, name, basePricePhp (centavos), active, notes | Unlimited items. |
| `menu_variant` | id, menuItemId FK, name, priceDeltaCentavos, active | Size, flavor, etc. |
| `add_on` | id, menuItemId FK NULLABLE, name, priceCentavos, active | Global or item-specific add-ons. |
| `inventory_item` | id, name, kind (INGREDIENT / PACKAGING), unit (kg, g, L, ml, pc, pack), lowStockQty, active | Holds the projection `onHandQty` and `avgUnitCost` (recomputed from ledger). |
| `recipe_version` | id, menuItemId FK, variantId FK NULLABLE, effectiveFrom, note | Recipes are versioned so edits do not rewrite history. |
| `recipe_line` | id, recipeVersionId FK, inventoryItemId FK, qtyPerServing (thousandths), yieldPct, kind (INGREDIENT / PACKAGING) | Packaging is included in food cost. |
| `expense` | id, name, category, amountCentavos, periodMonth (YYYY-MM), allocationMethod, targetScope, notes | See section 4.6. |
| `expense_allocation` | id, expenseId FK, menuItemId FK NULLABLE, percentOrAmount, method | Only used for MANUAL allocation. |
| `planned_volume` | periodMonth PK, plannedServings | Denominator for overhead rates. Defaults to prior month actual. |
| `period` | id, month (YYYY-MM) UNIQUE, status (OPEN / CLOSED), closedAt, reopenedAt, reopenReason | Locks ledger writes to closed months. |

### 3.3 Inventory ledger

| Entity | Key columns | Notes |
|---|---|---|
| `stock_movement` | id, inventoryItemId FK, type, qtyDelta (signed thousandths), unitCostMicro (micro-centavos), valueCentavos (signed), sourceType, sourceId, reversesId NULLABLE, reason, createdAt, periodMonth | Types: PURCHASE, SALE_CONSUMPTION, SALE_REVERSAL, REFUND_RESTOCK, WASTAGE, COUNT_ADJUST, EDIT_ADJUST, DELETE_REVERSAL, COST_ADJUST. |
| `cost_lot` (purchase) | id, inventoryItemId FK, qtyIn, unitCostMicro, purchasedAt, stockMovementId FK | Source of truth for the weighted-average calculation. |

On-hand quantity is `SUM(qtyDelta)`. Average cost is recomputed on each PURCHASE (section 4.2). Projections are cached and rebuilt from the ledger on demand.

### 3.4 Sales ledger

| Entity | Key columns | Notes |
|---|---|---|
| `sale` | id, receiptNo UNIQUE, createdAt, periodMonth, status (OPEN / COMPLETED / PARTIALLY_REFUNDED / REFUNDED / VOIDED / DELETED), subtotalCentavos, discountCentavos, totalCentavos, note, rev | `status` is a projection. The source of truth is the ledger and events. |
| `sale_line` | id, saleId FK, menuItemId (nullable, snapshot), itemNameSnapshot, variantNameSnapshot, qty (thousandths), unitPriceCentavos, lineDiscountCentavos, addOnsJson, note, unitFoodCostMicro, unitPackagingCostMicro, unitOverheadMicro, costSnapshotAt | Snapshots are frozen at sale time. |
| `payment` | id, saleId FK, type (CASH / EWALLET_GCASH / EWALLET_MAYA / BANK_TRANSFER), amountCentavos, referenceNo NULLABLE, recordedAt | Recorded only. No payment processing. |
| `refund` | id, saleId FK, amountCentavos, restockedPackaging (bool), reason, createdAt, periodMonth | |
| `refund_line` | id, refundId FK, saleLineId FK, qty | |
| `ledger_entry` | id, periodMonth, saleId NULLABLE, saleLineId NULLABLE, account (REVENUE, DISCOUNT, REFUND, FOOD_COST, PACKAGING_COST, OVERHEAD), amountCentavos (signed), reversesId NULLABLE, sourceEventId, createdAt | Profit is derived from this table. Revenue is positive. Costs are stored as positive debits, and their signs are set in the view. |
| `sale_event` | id, saleId FK, type (CREATED, VOIDED, REFUNDED, EDITED, DELETED, REOPEN_ADJUST), payloadJson, reason, createdAt, deviceId | Human-readable history. Feeds the Receipts tab status. |

### 3.5 Sync and audit

| Entity | Key columns | Notes |
|---|---|---|
| `outbox` | id, entityType, entityId, op (UPSERT / DELETE / APPEND), payloadJson, baseRev, createdAt, attempts, nextAttemptAt, status (PENDING / SENT / FAILED / CONFLICT), lastError | Written in the same transaction as the change. |
| `sheet_binding` | tabName PK, sheetId, headerHash, lastPullAt, lastPulledRev | One row per tab. |
| `sync_row_state` | tabName, entityId, lastSyncedRev, lastSyncedHash, lastSyncedFieldsJson, sheetRowIndex | Base copy used for three-way field merge. |
| `sync_conflict` | id, tabName, entityId, field, appValue, sheetValue, resolution (APP / SHEET / PENDING), createdAt | Shown on the Sync Issues screen. |
| `audit_log` | id, actor (OWNER / SHEET / SYSTEM), action, entityType, entityId, beforeJson, afterJson, createdAt | Covers settings, period closes, reopens, and sheet edits that were rejected. |

### 3.6 Relationships (summary)

```
category 1─* menu_item 1─* menu_variant
menu_item 1─* add_on (optional)
menu_item 1─* recipe_version 1─* recipe_line *─1 inventory_item
inventory_item 1─* stock_movement
inventory_item 1─* cost_lot
expense 1─* expense_allocation *─0..1 menu_item
sale 1─* sale_line 1─* ledger_entry
sale 1─* payment
sale 1─* refund 1─* refund_line *─1 sale_line
sale 1─* sale_event
period 1─* (stock_movement, ledger_entry, sale) by periodMonth
```

---

## 4. Costing logic

### 4.1 Notation

- `q` = quantity in base unit (kg, pc, and so on).
- `c` = unit cost in PHP per base unit.
- `Q_on` = on-hand quantity before the movement.
- `C_avg` = weighted-average unit cost before the movement.
- Monetary values are in PHP. Storage is centavos or micro-centavos.

### 4.2 Inventory cost (weighted average)

On PURCHASE of `q_in` at unit cost `c_in`:

```
Q_pos   = max(Q_on, 0)
C_new   = (Q_pos * C_avg + q_in * c_in) / (Q_pos + q_in)
```

- If `Q_on <= 0`, then `C_new = c_in`. Negative stock does not drag the average.
- Stock consumption, wastage, and count decreases do not change `C_avg`. They take `C_avg` as their snapshot.
- Count adjustments that increase stock (found stock) use `C_avg` as cost. If `C_avg` is unknown, the user must enter a unit cost.

Stock value = `Q_on * C_avg` (informational; the ledger holds the exact value).

### 4.3 Recipe cost per serving

For a menu item (or variant) with recipe lines `i`:

```
IngredientCost_serv = Σ_i [ qtyPerServing_i / (yieldPct_i / 100) * C_avg_i ]   (kind = INGREDIENT)
PackagingCost_serv  = Σ_j [ qtyPerServing_j * C_avg_j ]                         (kind = PACKAGING)
FoodCost_serv       = IngredientCost_serv + PackagingCost_serv                  (total variable cost)
```

- `yieldPct` covers trimming and cooking loss. Example: 80% yield means 1 kg of purchased product gives 0.8 kg usable, so the cost per usable kg is higher.
- Add-ons and variants add their own recipe lines. A variant's recipe is merged with its base item's recipe at costing time.

### 4.4 Overhead allocation (per expense)

Overhead pool for a month `m`:

```
Pool_m = Σ_e amount_e   for expenses e with periodMonth = m
```

Expected volume (denominator) for month `m`:

```
Vol_m = plannedServings_m  (defaults to prior closed month actual, editable)
```

Allocation methods. For a menu item `i` with price `p_i`, ingredient cost `FC_i`, and expected units `u_i`:

| Method | Overhead per serving of item i |
|---|---|
| EQUAL_PER_SERVING | `OH_i = Pool_m / Vol_m` |
| BY_REVENUE | `OH_i = Pool_m * p_i / Σ_j(p_j * u_j)` |
| BY_INGREDIENT_COST | `OH_i = Pool_m * FC_i / Σ_j(FC_j * u_j)` |
| BY_UNIT_COUNT | `OH_i = Pool_m * (u_i / Vol_m) / u_i = Pool_m / Vol_m`, so equal per serving unless the user sets per-item weights |
| MANUAL | `OH_i = amount_manual_i / u_i`, where the user sets the amount per item |

Each expense has one method. A month's overhead per serving is the sum of its expenses' per-serving amounts. Pool totals are kept exactly, and rounding differences go to the last item.

### 4.5 Sale snapshot

At the moment a sale line is created, snapshot:

```
unitFoodCost_snap      = IngredientCost_serv (ingredients only, from current C_avg)
unitPackagingCost_snap = PackagingCost_serv (packaging only, reported on its own line)
Total variable cost    = unitFoodCost_snap + unitPackagingCost_snap = FoodCost_serv (section 4.3)
unitOverhead_snap      = OH_i for the sale's month
```

Costs for line `L` are `qty_L * unit*_snap`.

### 4.6 Expense allocation targets and recalculation

| Target scope | Effect |
|---|---|
| MENU_ITEMS (default) | Adds per-serving overhead to the costing view and to each sale's overhead snapshot. |
| INVENTORY_ITEMS (landed cost, later phase) | Adds the expense to a purchase's unit cost. Changes `C_avg` and therefore recipe costs. |

Recalculation rule: when an expense, planned volume, menu price, or method changes:
1. Recompute `OH_i` for the month.
2. For each affected sale in an OPEN month, post an `OVERHEAD` ledger entry for `(newOH - snapshotOH) * qty`, and update `unitOverhead_snap`. Log this as an EDIT_ADJUST event.
3. For a CLOSED month, do not restate. Post the difference as a current-month entry with `reason = PRIOR_PERIOD_ADJUSTMENT`.

This keeps the rule consistent: a closed period is never changed, and open-period snapshots update automatically.

### 4.7 Profit definitions

```
Gross sales       = Σ ledger REVENUE (line price * qty)
Discounts         = Σ ledger DISCOUNT
Refunds           = Σ ledger REFUND
Net sales         = Gross sales - Discounts - Refunds
Food cost         = Σ FOOD_COST (ingredient part)
Packaging cost    = Σ PACKAGING_COST
Gross profit      = Net sales - Food cost - Packaging cost
Overhead          = Σ OVERHEAD
Net profit        = Gross profit - Overhead
Margin %          = Net profit / Net sales
```

Voids are represented as reversals, so they net out in the sums above.

### 4.8 Worked example

Placeholder item: **EXAMPLE: Chicken Adobo Rice Meal**, ₱90 per serving.

**Inventory (weighted average before the sale):**

| Item | Kind | Purchase | Stock after purchase | C_avg |
|---|---|---|---|---|
| Chicken thigh | Ingredient | 5 kg at ₱210/kg = ₱1,050 | 5.000 kg | ₱210.00/kg |
| Rice | Ingredient | 10 kg at ₱60/kg = ₱600 | 10.000 kg | ₱60.00/kg |
| Rice box | Packaging | 100 pc at ₱6.00 = ₱600 | 100 pc | ₱6.00/pc |
| Plastic bag | Packaging | 200 pc at ₱1.50 = ₱300 | 200 pc | ₱1.50/pc |

**Recipe per serving:** chicken 0.15 kg (yield 100%), rice 0.20 kg (yield 100%), rice box 1 pc, plastic bag 1 pc.

**Cost per serving:**

```
Ingredient = 0.15 * 210 + 0.20 * 60 = 31.50 + 12.00 = ₱43.50
Packaging  = 1 * 6.00 + 1 * 1.50    = ₱7.50
Food cost  = 43.50 + 7.50           = ₱51.00 (total variable cost)
```

**Overhead:** March expenses are rent ₱12,000, utilities ₱3,000, and gas ₱1,500, for a pool of ₱16,500. Method is EQUAL_PER_SERVING. Planned servings are 1,100.

```
OH per serving = 16,500 / 1,100 = ₱15.00
```

**Margin check:** price ₱90, cost ₱51 + ₱15 = ₱66, so margin is ₱24 (26.7% of price). Suggested price for a 35% target margin on full cost: `66 / (1 - 0.35) = ₱101.54`, rounded up to ₱102.

**Sale (March 12, receipt R-000123):** 2 servings at ₱90 each. 10% line discount. Paid by GCash for ₱162.

| Line | Amount |
|---|---|
| Gross revenue | 2 × 90 = ₱180.00 |
| Discount (10%) | −₱18.00 |
| Net revenue | ₱162.00 |

**Ledger entries posted by the sale:**

| # | Account / type | Movement | Amount (₱) |
|---|---|---|---|
| 1 | REVENUE | sale line | +180.00 |
| 2 | DISCOUNT | line discount | −18.00 |
| 3 | stock: chicken | SALE_CONSUMPTION, −0.300 kg at 210.00 | −63.00 (value) |
| 4 | stock: rice | SALE_CONSUMPTION, −0.400 kg at 60.00 | −24.00 (value) |
| 5 | FOOD_COST | ingredients: 63.00 + 24.00 | 87.00 |
| 6 | stock: rice box | SALE_CONSUMPTION, −2 pc at 6.00 | −12.00 (value) |
| 7 | stock: plastic bag | SALE_CONSUMPTION, −2 pc at 1.50 | −3.00 (value) |
| 8 | PACKAGING_COST | 12.00 + 3.00 | 15.00 |
| 9 | OVERHEAD | 2 × 15.00 | 30.00 |
| 10 | payment | GCash | +162.00 (cash-basis record) |

**Sale profit:**

```
Net sales     = 180 - 18 = 162.00
Food cost     = 87.00
Packaging     = 15.00
Gross profit  = 162 - 87 - 15 = 60.00
Overhead      = 30.00
Net profit    = 60 - 30 = 30.00
```

**Void of R-000123 (later the same week):**

Reversal entries reference the original entries via `reversesId`. Original entries are not edited.

| # | Reversal of | Amount (₱) |
|---|---|---|
| R1 | 1 (REVENUE) | −180.00 |
| R2 | 2 (DISCOUNT) | +18.00 |
| R3 | 3 (chicken) | +0.300 kg restored at snapshot cost 210.00 = +63.00 value |
| R4 | 4 (rice) | +0.400 kg restored at snapshot cost 60.00 = +24.00 value |
| R5 | 5 (FOOD_COST) | −87.00 |
| R6 | 6 (rice box) | +2 pc at 6.00 = +12.00 value |
| R7 | 7 (plastic bag) | +2 pc at 1.50 = +3.00 value |
| R8 | 8 (PACKAGING_COST) | −15.00 |
| R9 | 9 (OVERHEAD) | −30.00 |
| R10 | 10 (payment) | −162.00 (recorded payment reversed) |

**Results after the void:**
- Net sales, gross profit, overhead, and net profit for this sale are all ₱0.00.
- Stock is restored to its pre-sale quantity. Stock is restored at snapshot cost, so stock value is exact even if `C_avg` moved after the sale.
- Sale status becomes VOIDED. A `sale_event` is written, and the Receipts tab row is updated.

---

## 5. Transaction reversal rules

General principles:
1. Never update a ledger row. Post a new row that references the old one with `reversesId`.
2. Each reversal uses the snapshot values from the original sale, not current prices or costs.
3. Every operation is one Room transaction, and it writes the outbox rows in that same transaction.
4. Operations are blocked when the sale's period is CLOSED, unless the period is reopened (section 5.6).

### 5.1 Sale (create)

1. Validate: cart is not empty, payment total equals sale total, and the period is OPEN.
2. Allocate receipt number (`R-` + zero-padded sequence). The number is assigned in the transaction and never reused, even if the sale is voided.
3. Snapshot each line's costs (section 4.5).
4. Post ledger entries 1-10 from the worked example, plus stock movements.
5. Write `sale_event` CREATED, and the outbox rows for `sale`, `sale_line`, `payment`, and the Receipts row.

If stock would go negative, the sale still posts (see section 7.3). A warning is shown.

### 5.2 Void

Void reverses the entire sale and sets status to VOIDED. It is for a sale that never happened, or was entered incorrectly.

- Reverse all revenue, discount, stock, cost, overhead, and payment entries.
- Restock at snapshot cost. Refunded or already-reversed lines are excluded.
- Requires a reason. Receipt number is kept.
- Allowed only once. A voided sale cannot be refunded, edited, or voided again.

### 5.3 Refund (full or partial)

Refund returns money to the customer for some or all lines. The sale is kept.

- Input: lines and quantities to refund, and whether packaging is restocked.
- Revenue reversal = `refundQty / saleQty * netLineAmount`, rounded to centavos. The final refund of a line uses the remainder so totals match exactly.
- Food cost is not reversed by default. The food was made, so it is treated as spoilage. Packaging is restocked only if the user checks "restock packaging" (for unopened, unused packaging).
- Overhead is not reversed (it was incurred regardless).
- Status becomes PARTIALLY_REFUNDED or REFUNDED. A `refund` and `refund_line` row are written.
- A refund cannot exceed the remaining refundable qty or amount.

### 5.4 Edit

Edits can change qty, variant, add-ons, line discount, sale discount, notes, or payment method.

Algorithm: compute a diff between the old and new lines, then post the difference.

| Change | Result |
|---|---|
| Unchanged line | No entries. Keeps original snapshots. |
| Qty decreased | Reverse the difference at the line's snapshot. |
| Qty increased | Consume the difference at current costs. Snapshot the new unit costs on that delta, and record the new costs with the delta. |
| Line removed | Full reversal of that line's entries. |
| Line added | New consumption at current costs. |
| Variant or add-on changed | Treat as removed plus added. |
| Discount changed | Post a DISCOUNT adjustment entry. Revenue is unchanged. |
| Payment method changed | Update the payment row. Post no money entries. Log the change in `sale_event`. |

Important: edits never reprice unchanged lines. Changing an item's price later does not change an old sale. Editing a sale to add a line uses today's costs for that line only.

An edit creates EDIT_ADJUST entries and a `sale_event` EDITED with before and after JSON.

### 5.5 Delete

Delete removes the sale from reports and the Receipts tab view. It does not erase history.

Rules:
1. Delete on an OPEN period = void behavior, plus status DELETED. The row stays with reason "deleted by owner", and the ledger shows the reversals.
2. Delete on a CLOSED period is blocked. The owner must reopen the period first (section 5.6).
3. A deleted sale cannot be edited or restored. If you need it back, create a new sale.

Why not a hard delete? Hard deletes break the ledger and the sheet's receipt history. Tombstones keep the audit trail.

### 5.6 Closed periods and reopen

- Closing a month locks every ledger entry and sale in that month. Costs and reports for that month are frozen.
- Reopen requires a reason and writes an audit entry. Reopened months are flagged in the Reports UI.
- Changes made to a closed month during a reopen (void, refund, edit, delete) post their reversals with `periodMonth` set to the original month, so the month's totals update when it is re-closed. Overhead changes from later expenses still go to the current month (rule 4.6.3).
- Re-closing a month requires confirming the totals again.

### 5.7 Recipe and price changes

- Recipes are versioned. Editing a recipe creates a new `recipe_version` with `effectiveFrom = now`. Old sales keep their snapshots, so they are not affected.
- Menu price changes apply to new sales only. Old sale lines keep their `unitPriceCentavos`.
- Changing an inventory item's unit cost is handled by the purchase flow only. Past consumption keeps its snapshots.

---

## 6. Sync design and conflict rules

### 6.1 Principles

- The app's Room database is the source of truth. The sheet is a mirror that can also be edited.
- Every local write is queued in `outbox` in the same transaction. Sync is asynchronous and idempotent.
- Pushes and pulls run in WorkManager, with unique work names, a network constraint, and exponential backoff.
- Ledger tables (stock_movement, ledger_entry, sale_event, refund) are append-only in Sheets too. Sheet edits to them are reverted.

### 6.2 Google Sign-In and Sheets API setup

1. In Google Cloud Console, create a project for Chikko.
2. Enable the Google Sheets API for that project.
3. Configure the OAuth consent screen (app name, support email, your Google account as a test user). The scope needed is `https://www.googleapis.com/auth/spreadsheets`.
4. Create an OAuth client ID of type **Android**. Enter the package name and the SHA-1 fingerprint of the signing key used for the APK. A debug key and a release key have different fingerprints, so register both.
5. In the app, sign in with Google and request the spreadsheets scope through the Google Identity authorization flow. Store only the account identifier. Access tokens are refreshed on demand and never written to logs or to the database.
6. Create the spreadsheet (or have the app create it) and save its ID in `sheet_binding`.
7. Check current Google documentation for the exact Android sign-in API before implementation, since these APIs change.

Risk: an OAuth app left in Testing status may require re-authorization periodically. Check Google's current policy for refresh-token expiry before relying on long-term offline sync.

### 6.3 Sheet structure

Spreadsheet: `Chikko Records` (single file). The first tab is `README` (read-only, explains rules and what to edit).

| Tab | Purpose | Columns (in order) |
|---|---|---|
| `_meta` | Schema version, last sync info, device ID | key, value |
| `Categories` | Master data | id, name, sortOrder, active, rev, updated_at, updated_by, deleted |
| `MenuItems` | Master data | id, categoryId, name, basePricePhp, active, notes, rev, updated_at, updated_by, deleted |
| `Variants` | Master data | id, menuItemId, name, priceDeltaPhp, active, rev, updated_at, updated_by, deleted |
| `AddOns` | Master data | id, menuItemId, name, pricePhp, active, rev, updated_at, updated_by, deleted |
| `InventoryItems` | Master data plus projection | id, name, kind, unit, lowStockQty, **onHandQty (computed)**, **avgUnitCostPhp (computed)**, active, rev, updated_at, updated_by, deleted |
| `Recipes` | Recipe lines (current version) | id, menuItemId, variantId, inventoryItemId, kind, qtyPerServing, yieldPct, effectiveFrom, rev, updated_at, updated_by, deleted |
| `Expenses` | Master data | id, name, category, amountPhp, periodMonth, allocationMethod, targetScope, notes, rev, updated_at, updated_by, deleted |
| `PlannedVolume` | Denominator per month | periodMonth, plannedServings, rev, updated_at, updated_by |
| `StockMovements` | Append-only ledger (read-only in Sheets) | id, createdAt, inventoryItemId, type, qtyDelta, unitCostPhp, valuePhp, sourceId, reversesId, reason |
| `Receipts` | One row per sale (record keeping) | receiptNo, saleId, dateTime, status, paymentType, paymentRef, subtotalPhp, discountPhp, refundPhp, totalPhp, note, voidReason, lastEventAt, rev, updated_at, updated_by |
| `ReceiptItems` | One row per line item | saleLineId, saleId, receiptNo, itemName, variantName, addOns, qty, unitPricePhp, lineDiscountPhp, lineTotalPhp, status, lineNote |
| `Payments` | Append-only | id, saleId, receiptNo, type, amountPhp, referenceNo, recordedAt |
| `Refunds` | Append-only | id, saleId, receiptNo, amountPhp, restockedPackaging, reason, createdAt |
| `Periods` | Close and reopen history | month, status, closedAt, reopenedAt, reopenReason |
| `SyncIssues` | App-written log of rejected or conflicting edits | at, tab, entityId, field, appValue, sheetValue, resolution |
| `Audit` | App-written audit log | at, actor, action, entityType, entityId, details |

Notes:
- The `id` column is always the first column and is UUID text.
- Columns marked **computed** are overwritten by the app on every push.
- `rev` is the app's revision number for that row. `updated_by` is `APP` or `SHEET`.
- `Receipts` is the human-readable "receipt book". `ReceiptItems` holds the line details. Both are keyed by `saleId`. Receipt rows are never deleted. Voids change `status` and add a `voidReason`.
- Deleted master-data rows keep `deleted = TRUE` and are greyed out (no hard deletes in Sheets).

### 6.4 Change detection and push

- **Push (app to sheet):** the SyncEngine reads outbox rows in order (`createdAt`, then `id`), groups them into batches per tab, and applies them with `values.batchUpdate` using row IDs to find each row. If an ID is missing, the row is appended.
- **Pull (sheet to app):** on each pull, read every tab with `values.batchGet`, then compare each row's content hash to `sync_row_state.lastSyncedHash`. Rows with changed hashes are candidates for a sheet edit.
- Only changed rows are processed. Unchanged rows cost no writes.
- Each pull and push writes a `_meta` heartbeat.

### 6.5 Conflict rules

Single-writer model: the app holds the truth for money, stock, and the ledger. The sheet can edit master data and some notes.

| Situation | Rule |
|---|---|
| Sheet edits a computed or ledger column (for example `onHandQty`, `totalPhp`, `StockMovements`) | Reject. Revert the cell on the next push. Write a `SyncIssues` row. |
| Sheet edits a master-data field and the app did not change the row since last sync | Accept the sheet edit after validation. Apply to the app and bump `rev`. |
| App changed the row and the sheet changed it too, on different fields | Three-way merge using `lastSyncedFieldsJson` as the base. Merge both changes. No conflict. |
| Same field changed on both sides | Keep the app value (the app is the owner's working copy), write `SyncIssues` with both values, and show the item on the Sync Issues screen with "Use sheet value" as an option. |
| Sheet edit fails validation (negative price, unknown unit, bad enum) | Reject, revert the cell, and write `SyncIssues`. |
| Sheet edit to a sale's status, payment, or line amounts | Reject. Sales are changed only in the app. Status cells are reverted. |
| Sheet row deleted by the user | Treat as "restore from app": re-append the row on the next push. Deleting a master-data row must be done by setting `deleted = TRUE` in Sheets, which is honored. |
| Sheet row sheet ID changed or duplicated | Duplicate IDs: keep the first occurrence, reject the rest, and log. |
| Row inserted in Sheets with no ID (for example a new menu item typed by hand) | Accept only for master-data tabs. Generate a UUID, write it back, and create the record. Invalid rows are flagged, not imported. |
| Offline for days, then online | Outbox replays in order. Each outbox op has `baseRev`. If the remote `rev` is newer and the field is in conflict, apply the conflict rules above. |

Money fields in the app are validated against the ledger on every push, so a sheet value can never change a total without a matching app event.

### 6.6 Deletion handling

| Entity | App delete | Sheet representation | Sheet delete |
|---|---|---|---|
| Master data (category, item, variant, add-on, inventory, recipe, expense) | Soft delete (`deletedAt`), `deleted = TRUE` | Row kept, greyed | `deleted = TRUE` is honored. Removing the row is "restore". |
| Sale | Void or delete (section 5) | Status updated in Receipts. Row kept | Not honored. Reverted, logged. |
| Ledger rows | Never deleted | Rows kept | Reverted, logged. |
| Receipt-related rows | Not deleted | Kept | Reverted, logged. |

### 6.7 Protection against bad manual edits

- **Protected ranges** on computed and ledger columns, and on `id`, `rev`, `updated_by`. Owner sees a warning when editing them. This is a guardrail, not a security boundary, because the owner has full access to their own sheet.
- **Data validation rules** on enum and numeric columns (for example, `kind` must be INGREDIENT or PACKAGING). These are a dropdown or a number check.
- **Schema hash** in `_meta`. If a header is renamed or reordered, the app refuses to sync and shows a "Schema changed" banner, instead of mapping columns incorrectly.
- **Validation on pull** runs the same validators the app uses for local input.
- **Safe mode switch** in Settings: pause sync, pull-only, or push-only. Useful when fixing a broken sheet.
- **Backup:** the app writes a JSON backup to local storage before any large pull, and exports a CSV snapshot on each period close.

### 6.8 Queueing and retry

- Each outbox op is idempotent: it targets a row ID and carries the full row state at the time of the write, not a delta. Replaying an op is safe.
- Failures use exponential backoff (for example, starting at 30 seconds, capped at 1 hour). Quota (HTTP 429) and server (5xx) errors retry. Client errors (4xx other than 429) go to FAILED and surface in the Sync screen.
- Batch sizes are kept small to stay within Sheets per-minute quotas. Check the current limits in Google's documentation before tuning.

---

## 7. Edge cases

### 7.1 Editing a sale after ingredient costs changed
Unchanged lines keep their original snapshot costs. Only new or increased quantities use current `C_avg`. Reports for old sales do not move.

### 7.2 Deleting a sale from a closed period
Blocked. Reopen the period with a reason. The reversal posts to the original period, so that month's totals are restated, and the reopen is recorded. Re-closing requires confirming the totals.

### 7.3 Negative stock
Sales are allowed to push stock below zero. The item shows a red badge, and the user sees a warning in POS. Costs still use `C_avg`, because a negative balance does not change the average. A later purchase resets the average only for the positive part (see 4.2). A setting can block sales when stock would go negative (later phase).

### 7.4 Partial refunds
Revenue is pro-rated to centavos, with the last refund on a line absorbing the rounding remainder. Food cost stays by default. Packaging is restocked only when checked. Refunded quantities cannot exceed the sold quantity.

### 7.5 Recipe changes after sales exist
A new recipe version applies only to future sales. Old sales keep their snapshots. Editing an old recipe in place is not allowed in the UI. The app always creates a new version.

### 7.6 Price change after sales exist
Old lines keep the price they were sold at. Price changes apply to new sales only.

### 7.7 Expense change after sales exist
Open-month sales update their overhead snapshot (4.6). Closed-month sales are not restated. The change is posted to the current month as a prior-period adjustment.

### 7.8 Stock count adjustments
A count adjustment creates a COUNT_ADJUST movement for `counted - onHand`. Cost is `C_avg` (or the user's entered cost for found stock). It never rewrites history.

### 7.9 Wastage
Wastage creates a movement at `C_avg` and is reported separately. It is not included in food cost per serving.

### 7.10 Time zone and day boundaries
Reports bucket by Asia/Manila local date. A sale at 11:55 PM stays in that day even if synced later. Stored times are UTC.

### 7.11 Concurrency inside the app
All writes use a single Room transaction. A `Mutex` serializes sale numbering and period close.

### 7.12 Receipt number gaps
Voided and deleted sales keep their numbers, so gaps are expected. The app never reuses a number.

### 7.13 Sheet rows deleted or reordered
Row lookups use the `id` column, not row numbers. Reordering rows is harmless. Deleting a row is handled as in section 6.5.

### 7.14 Clock changes on the device
Stored timestamps are UTC. Reports bucket by date. A sale time that jumps backwards does not break ordering, because ordering uses `createdAt` plus a monotonic sequence number.

### 7.15 Empty menu on first run
Menu, inventory, and expenses start empty. Placeholder examples are tagged "EXAMPLE:" and can be removed in one step.

---

## 8. Screen list and navigation

### 8.1 Screens

| # | Screen | Purpose |
|---|---|---|
| 1 | Onboarding and sign-in | Google sign-in, sheet connection, empty-state guidance. |
| 2 | POS (home) | Category tabs, item grid, cart panel, checkout. |
| 3 | Item options sheet | Variant, add-ons, qty, notes, line discount. |
| 4 | Checkout | Sale discount, payment type, amount, reference no., confirm. |
| 5 | Orders list | Date filter, status filter, search by receipt no. |
| 6 | Sale detail | Lines, payments, refunds, events. Actions: void, refund, edit, delete. |
| 7 | Edit sale | Same structure as POS, pre-filled. Shows the diff before save. |
| 8 | Refund dialog | Select lines and qty, restock packaging toggle, reason. |
| 9 | Menu: categories list | Add, edit, reorder, activate, delete. |
| 10 | Menu: items list | Filter by category, active/inactive. |
| 11 | Menu item editor | Name, category, price, notes, active. |
| 12 | Variants and add-ons editor | Per item. |
| 13 | Inventory list | Ingredients and packaging tabs, low-stock filter. |
| 14 | Inventory item editor | Name, kind, unit, low-stock threshold. |
| 15 | Stock movement dialog | Stock in (purchase), stock out, wastage, count adjustment. |
| 16 | Stock movement history | Per item ledger view. |
| 17 | Recipe editor | Lines for an item or variant, qty, yield, packaging. |
| 18 | Costing overview | Cost per serving, margin %, suggested price per item. |
| 19 | Expenses list | By month, total pool, category. |
| 20 | Expense editor | Amount, category, month, allocation method, target scope, manual splits. |
| 21 | Planned volume | Expected servings per month (denominator). |
| 22 | Reports | Daily, weekly, monthly, custom range. Summary cards and breakdowns. |
| 23 | Best sellers | Top items by qty and revenue. |
| 24 | Period close | Close and reopen months. Shows totals before confirmation. |
| 25 | Sync status | Last sync, outbox count, failed items, sign-out. |
| 26 | Sync issues | Rejected and conflicting edits, resolve with app or sheet value. |
| 27 | Settings | Business name, currency (fixed PHP), receipt numbering prefix, safe mode, backup and export. |

### 8.2 Navigation flow

Bottom navigation with 5 destinations: **Sell** (POS), **Orders**, **Menu**, **Stock** (inventory and recipes), **Money** (costing, expenses, reports). A **More** entry in the top bar opens Sync, Settings, Period close, and Planned volume.

```
First launch
  -> Onboarding -> Sign-in -> Sheet setup -> Sell (POS)

Sell (POS)
  -> Item options sheet -> back to cart
  -> Checkout -> Receipt confirmation -> Sell (cart cleared)

Orders
  -> Sale detail -> Edit sale -> Save -> Sale detail
                 -> Refund dialog -> Sale detail
                 -> Void (confirm) -> Sale detail
                 -> Delete (confirm, blocked if closed) -> Orders

Menu
  -> Categories -> Items -> Item editor -> Variants and add-ons

Stock
  -> Inventory list -> Item editor
                   -> Movement dialog
                   -> Movement history
  -> Recipes -> Recipe editor (per item) -> Costing overview

Money
  -> Costing overview -> Item costing detail
  -> Expenses -> Expense editor
  -> Reports -> Best sellers
  -> Period close

More
  -> Planned volume, Sync status -> Sync issues, Settings
```

Key navigation rules:
- POS cart persists across tab changes until checkout or manual clear.
- Destructive actions (void, delete, period reopen) always show a confirm step with the reason field.
- Back from any edit screen with unsaved changes shows a discard prompt.

---

## 9. Risks, open questions, and roadmap

### 9.1 Risks

| # | Risk | Likelihood | Impact | Mitigation |
|---|---|---|---|---|
| R1 | Room or kotlinx.serialization annotation processing fails in CodeAssist's in-process build. | Medium | High | Early spike. Fallback: raw SQLite behind the same repository interfaces. |
| R2 | Sheets API quota or latency causes slow sync. | Medium | Medium | Batching, outbox coalescing (last write wins per row), backoff. |
| R3 | Owner edits a computed or ledger cell in Sheets. | High | Low | Protected ranges, validation, revert and log. |
| R4 | OAuth Testing-mode token expiry interrupts sync. | Medium | Medium | Check current policy. Re-auth prompt in the Sync screen. Offline queue keeps data safe. |
| R5 | Data loss on uninstall (local DB is the source). | Medium | High | Sheets mirror, JSON backup, CSV export on period close. |
| R6 | Weighted-average errors from manual stock counts. | Medium | Medium | Count adjustments are logged. Show the average and the count history. |
| R7 | Overhead rates swing with a small planned volume. | Medium | Medium | Show the denominator. Default it to prior month actual. Warn when the volume changes more than 20%. |
| R8 | Rounding drift across many small sales. | Low | Low | Store centavos. Remainder rules for refunds and overhead. Reconciliation check on period close. |
| R9 | Sale numbering collides across devices (future). | Low now | High later | Prefix per device in phase 4. |
| R10 | Non-technical user confused by reversals. | Medium | Medium | Show "voided" and "refunded" clearly. Plain-language event log. |

### 9.2 Open questions (answer before building the affected step)

1. **Expense to ingredient cost:** in MVP, do expenses go only to menu-item overhead, or do you also want some (for example, delivery fees on ingredient purchases) added to ingredient unit cost? The plan assumes MVP = menu-item overhead only.
2. **Deleting a sale in a closed month:** the plan blocks it until reopen, then posts the reversal into the original month. Is that right, or should the reversal go into the current month instead (so the closed report never changes)?
3. **Negative stock:** allow sales (current plan) or block them?
4. **Payment split:** does one sale ever use two payment types? MVP assumes one.
5. **Discount scope:** are discounts on a line, a sale, or both? Plan supports both.
6. **Receipt numbering:** prefix and start number preference (for example, "R-" and 000001).
7. **Sheet ownership:** should the app create the spreadsheet in your Drive, or will you create it and share the ID?
8. **Planned volume default:** prior month actual, or a fixed number you type in?

### 9.3 Build roadmap

| Step | Deliverable | Exit criterion |
|---|---|---|
| 0 | Spikes: Room and serialization in CodeAssist build. Sheets REST call with a test token. | Both spikes compile and run. |
| 1 | Project skeleton: manual DI, navigation shell, Material 3 theme, money and quantity types, ID and clock utilities. | App launches, unit tests for money and quantity pass. |
| 2 | Database v1: master-data entities, inventory and ledger tables, period, audit, outbox. Triggers blocking ledger UPDATE and DELETE. | Migration tests and DAO tests pass. |
| 3 | Menu manager: categories, items, variants, add-ons, placeholders with "EXAMPLE:" labels. | CRUD works offline. Soft delete works. |
| 4 | Inventory: items, weighted-average purchase, stock out, wastage, count adjustment, movement history. | Unit tests cover section 4.2 cases, including negative stock. |
| 5 | Recipes and costing engine: recipe versions, recipe cost, overhead allocation methods, costing overview, suggested price. | Worked example in section 4.8 reproduces exactly in a test. |
| 6 | Expenses and planned volume: CRUD, allocation methods, recalculation rule 4.6. | Changing an expense updates open-month snapshots and posts adjustments. Closed months are unchanged. |
| 7 | POS: grid, cart, options, discounts, notes, payment recording, sale posting (section 5.1). | Sale in test reproduces ledger entries 1-10. |
| 8 | Order history: detail, void, refund, edit, delete with reversal rules. | Worked-example void nets to zero. Edit and refund tests pass. |
| 9 | Reports: daily, weekly, monthly, custom range, best sellers, gross and net profit. | Report totals match the ledger sums in section 4.7. |
| 10 | Period close and reopen. | Closed months reject writes. Reopen logged. |
| 11 | Sync v1 (push): outbox, WorkManager, Google Sign-In, sheet creation, master-data and Receipts push. | A sale appears in Receipts within one sync cycle. Offline queue replays. |
| 12 | Sync v2 (pull and conflicts): row hashes, three-way merge, validation, SyncIssues, protected ranges and schema hash. | Each row of the conflict table in 6.5 has a test. |
| 13 | Hardening: backup and export, sync status screen, error states, empty states, accessibility pass, UI review with screenshots. | Owner can run a full day offline, then sync cleanly. |
| 14 | Release prep: signing config (SHA-1 registered), versioning, install and smoke test. | Signed build installed. Sync works with the release key. |

Each step ends with a runnable build and tests. Steps 4, 5, and 8 carry the most risk, so give them the most test coverage.
