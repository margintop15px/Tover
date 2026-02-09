# Inventory Management System - Implementation Plan

## 1. Requirements Analysis

### 1.1 What the requirements describe

The requirements define a **warehouse inventory management system** (WMS-lite) for small/medium businesses that track physical goods across warehouses. The system covers:

- **Master data**: Products, Warehouses, Suppliers, Categories, Stores
- **Operations**: 8 distinct transaction types that affect inventory and/or financial balances
- **Reports**: 4 configurable report types with filtering and column customization
- **Calculated fields**: Weighted-average cost price, supplier debt

### 1.2 Relationship to the existing system

The current Tover codebase is a **marketplace analytics platform** focused on:
- Importing external marketplace data (orders, payments) via CSV
- Displaying KPI dashboards (GMV, units sold, critical stock)
- Point-in-time inventory snapshots

The new requirements represent a **pivot to a first-party inventory management system** where users directly record warehouse operations rather than importing external data. Key differences:

| Aspect | Current System | New Requirements |
|--------|---------------|-----------------|
| Data source | CSV imports from marketplaces | Direct user input via forms |
| Inventory model | Point-in-time snapshots | Running balance updated by operations |
| Products | SKU strings (no catalog) | Full product catalog with metadata |
| Warehouses | None | First-class entity with types |
| Suppliers | None | First-class entity with debt tracking |
| Operations | Implicit (orders/payments) | 8 explicit operation types |
| Cost calculation | Static (imported unit_cost) | Weighted average, auto-calculated |

**Decision needed**: Do we deprecate the existing orders/payments/inventory_snapshots tables or keep them alongside the new system? **Recommendation**: Keep them for now (no migration needed), build the new system as new tables and pages. The old dashboard can coexist until a deliberate deprecation decision is made.

### 1.3 Key design decisions and open questions

#### D1: Cost price on returns and write-offs
The requirements specify cost recalculation only on Purchase operations. **Recommendation**: Returns, write-offs, and sales should use the current weighted-average cost (no recalculation) - they consume inventory at its current cost. Only Purchases trigger the weighted-average recalculation formula.

#### D2: What happens when inventory goes to zero?
When all units of a product on a warehouse are sold/written off, we should **preserve the last known unit_cost** on the balance row (quantity=0, unit_cost=last value). This way, if a return or new purchase arrives, the cost context isn't lost.

#### D3: Production cost calculation
The requirements state: "себестоимость всех исходных артикулов, участвующих в одной данной операции, должна автоматически присваиваться конечному артикулу". This means:
- Sum of (quantity * unit_cost) for all source materials = total input cost
- This total becomes the cost basis for the output product
- New output unit_cost = (existing_qty * existing_cost + total_input_cost) / (existing_qty + output_qty)

#### D4: Defect operation product naming
The requirements describe creating a copy of the product with "." prefix. This means:
- Auto-create a new product record (if it doesn't exist) named ".{original_name}" with same SKU prefix
- The defect product is tracked separately in inventory
- Future enhancement: user-configurable defect naming pattern in settings

#### D5: Warehouse purpose restrictions
When a warehouse has a purpose set, some operations should be restricted:
- **Storage** (`storage`): All operations allowed
- **Sales** (`sales`): Purchase(in), Sale(out), Return(in), Transfer(in/out), Defect(out)
- **Production** (`production`): Production(in/out), Transfer(in/out), Defect(out)

This needs user validation. For MVP, we can implement it as **warnings** rather than hard blocks, and make them configurable later.

#### D6: Historical inventory queries ("at a given date")
The "Inventory Balances" report needs to show stock at any historical date. Two approaches:
- **Option A**: Replay all operations from the beginning up to target date. Simple but slow at scale.
- **Option B**: Maintain periodic snapshots + compute delta from last snapshot.

**Recommendation**: Start with Option A (compute from operations). Add periodic snapshot optimization in a later phase if performance becomes an issue. For most SMBs with <100k operations, this will be fast enough with proper indexing.

#### D7: Negative inventory
Should the system allow negative inventory (selling more than available)? The requirements don't explicitly forbid it but imply physical goods. **Recommendation**: Allow negative quantities (warn but don't block). Some businesses operate with backorders. Show negative balances prominently in reports.

#### D8: Currency
The requirements mention "рубли" (rubles) but don't discuss multi-currency. **Recommendation**: Store all monetary values as plain numeric. Add a workspace-level currency setting. Multi-currency conversion is a future feature.

#### D9: Initial data entry
Users need a way to enter initial inventory balances when starting to use the system. **Recommendation**: Create a special "Initial Balance" setup flow or treat the initial data entry as a batch of Purchase operations with a special flag.

---

## 2. Database Schema

### 2.1 New tables

```sql
-- Migration: 003_inventory_system.sql

-- Product categories (optional grouping)
CREATE TABLE public.categories (
  id UUID PRIMARY KEY DEFAULT gen_random_uuid(),
  workspace_id UUID NOT NULL REFERENCES public.organizations(id) ON DELETE CASCADE,
  name TEXT NOT NULL,
  created_at TIMESTAMPTZ NOT NULL DEFAULT now(),
  updated_at TIMESTAMPTZ NOT NULL DEFAULT now(),
  UNIQUE(workspace_id, name)
);

-- Stores (optional product assignment)
CREATE TABLE public.stores (
  id UUID PRIMARY KEY DEFAULT gen_random_uuid(),
  workspace_id UUID NOT NULL REFERENCES public.organizations(id) ON DELETE CASCADE,
  name TEXT NOT NULL,
  created_at TIMESTAMPTZ NOT NULL DEFAULT now(),
  updated_at TIMESTAMPTZ NOT NULL DEFAULT now(),
  UNIQUE(workspace_id, name)
);

-- Warehouses
CREATE TABLE public.warehouses (
  id UUID PRIMARY KEY DEFAULT gen_random_uuid(),
  workspace_id UUID NOT NULL REFERENCES public.organizations(id) ON DELETE CASCADE,
  name TEXT NOT NULL,
  description TEXT,
  purpose TEXT CHECK (purpose IS NULL OR purpose IN ('storage', 'sales', 'production')),
  is_default_defect BOOLEAN NOT NULL DEFAULT false,
  created_at TIMESTAMPTZ NOT NULL DEFAULT now(),
  updated_at TIMESTAMPTZ NOT NULL DEFAULT now(),
  UNIQUE(workspace_id, name)
);

-- Products (master catalog)
CREATE TABLE public.products (
  id UUID PRIMARY KEY DEFAULT gen_random_uuid(),
  workspace_id UUID NOT NULL REFERENCES public.organizations(id) ON DELETE CASCADE,
  name TEXT NOT NULL,
  sku_code TEXT,
  category_id UUID REFERENCES public.categories(id) ON DELETE SET NULL,
  store_id UUID REFERENCES public.stores(id) ON DELETE SET NULL,
  is_defect_copy BOOLEAN NOT NULL DEFAULT false,
  original_product_id UUID REFERENCES public.products(id) ON DELETE SET NULL,
  created_at TIMESTAMPTZ NOT NULL DEFAULT now(),
  updated_at TIMESTAMPTZ NOT NULL DEFAULT now(),
  UNIQUE(workspace_id, sku_code)
);

-- Suppliers
CREATE TABLE public.suppliers (
  id UUID PRIMARY KEY DEFAULT gen_random_uuid(),
  workspace_id UUID NOT NULL REFERENCES public.organizations(id) ON DELETE CASCADE,
  name TEXT NOT NULL,
  address TEXT,
  contact_info TEXT,
  created_at TIMESTAMPTZ NOT NULL DEFAULT now(),
  updated_at TIMESTAMPTZ NOT NULL DEFAULT now(),
  UNIQUE(workspace_id, name)
);

-- Product balances (current inventory per product per warehouse)
CREATE TABLE public.product_balances (
  id UUID PRIMARY KEY DEFAULT gen_random_uuid(),
  workspace_id UUID NOT NULL REFERENCES public.organizations(id) ON DELETE CASCADE,
  product_id UUID NOT NULL REFERENCES public.products(id) ON DELETE CASCADE,
  warehouse_id UUID NOT NULL REFERENCES public.warehouses(id) ON DELETE CASCADE,
  quantity NUMERIC(14,3) NOT NULL DEFAULT 0,
  unit_cost NUMERIC(14,4) NOT NULL DEFAULT 0,
  created_at TIMESTAMPTZ NOT NULL DEFAULT now(),
  updated_at TIMESTAMPTZ NOT NULL DEFAULT now(),
  UNIQUE(workspace_id, product_id, warehouse_id)
);

-- Operations (all types in one table, discriminated by type)
CREATE TABLE public.operations (
  id UUID PRIMARY KEY DEFAULT gen_random_uuid(),
  workspace_id UUID NOT NULL REFERENCES public.organizations(id) ON DELETE CASCADE,
  type TEXT NOT NULL CHECK (type IN (
    'purchase', 'sale', 'return', 'write_off',
    'transfer', 'production', 'defect', 'payment'
  )),
  operation_date DATE NOT NULL,
  comment TEXT,
  supplier_id UUID REFERENCES public.suppliers(id) ON DELETE RESTRICT,
  payment_amount NUMERIC(14,2),
  created_at TIMESTAMPTZ NOT NULL DEFAULT now(),
  updated_at TIMESTAMPTZ NOT NULL DEFAULT now()
);

-- Operation items (product movements within an operation)
CREATE TABLE public.operation_items (
  id UUID PRIMARY KEY DEFAULT gen_random_uuid(),
  operation_id UUID NOT NULL REFERENCES public.operations(id) ON DELETE CASCADE,
  product_id UUID NOT NULL REFERENCES public.products(id) ON DELETE RESTRICT,
  warehouse_id UUID NOT NULL REFERENCES public.warehouses(id) ON DELETE RESTRICT,
  quantity NUMERIC(14,3) NOT NULL CHECK (quantity > 0),
  unit_price NUMERIC(14,4),
  direction TEXT NOT NULL CHECK (direction IN ('in', 'out')),
  store_id UUID REFERENCES public.stores(id) ON DELETE SET NULL,
  created_at TIMESTAMPTZ NOT NULL DEFAULT now()
);
```

### 2.2 Indexes

```sql
CREATE INDEX idx_products_workspace ON public.products(workspace_id);
CREATE INDEX idx_products_sku ON public.products(workspace_id, sku_code);
CREATE INDEX idx_products_category ON public.products(category_id) WHERE category_id IS NOT NULL;

CREATE INDEX idx_warehouses_workspace ON public.warehouses(workspace_id);

CREATE INDEX idx_suppliers_workspace ON public.suppliers(workspace_id);

CREATE INDEX idx_product_balances_workspace ON public.product_balances(workspace_id);
CREATE INDEX idx_product_balances_product ON public.product_balances(product_id);
CREATE INDEX idx_product_balances_warehouse ON public.product_balances(warehouse_id);

CREATE INDEX idx_operations_workspace_date ON public.operations(workspace_id, operation_date DESC);
CREATE INDEX idx_operations_type ON public.operations(workspace_id, type);
CREATE INDEX idx_operations_supplier ON public.operations(supplier_id) WHERE supplier_id IS NOT NULL;

CREATE INDEX idx_operation_items_operation ON public.operation_items(operation_id);
CREATE INDEX idx_operation_items_product ON public.operation_items(product_id);
CREATE INDEX idx_operation_items_warehouse ON public.operation_items(warehouse_id);
```

### 2.3 RLS policies

Follow the same pattern as existing tables:
- All new tables with `workspace_id`: member can SELECT, admin/owner can INSERT/UPDATE/DELETE
- `operation_items`: access via parent `operations` table workspace check
- Enable RLS on all new tables

### 2.4 Triggers

- `set_updated_at` trigger on all new tables (reuse existing function)
- Auto-create "Брак" (Defect) warehouse when a new organization is created (extend `bootstrap_new_user`)

### 2.5 Entity relationship diagram

```
organizations (workspace)
  |
  +-- categories
  +-- stores
  +-- warehouses
  +-- suppliers
  +-- products -----> categories (optional)
  |             \---> stores (optional)
  |             \---> products (self-ref for defect copies)
  +-- product_balances --> products
  |                   \--> warehouses
  +-- operations ---------> suppliers (optional)
      |
      +-- operation_items --> products
                          \--> warehouses
                          \--> stores (optional, for production)
```

---

## 3. Operation Business Logic

Each operation type has specific validation rules and side effects on `product_balances`.

### 3.1 Purchase (Закупка)

**Validation:**
- Required: product_id, warehouse_id, quantity, supplier_id, unit_price (per unit)
- supplier_id must be set on the operation
- Creates 1 operation_item with direction='in'

**Side effects:**
1. Upsert `product_balances` for (product_id, warehouse_id):
   - new_qty = old_qty + purchase_qty
   - new_cost = (old_qty * old_cost + purchase_qty * unit_price) / new_qty
2. Supplier debt increases by (quantity * unit_price)

### 3.2 Sale (Продажа)

**Validation:**
- Required: product_id, warehouse_id, quantity
- Creates 1 operation_item with direction='out'

**Side effects:**
1. Decrease `product_balances.quantity` by sale quantity
2. No cost recalculation (uses current unit_cost)

### 3.3 Return (Возврат)

**Validation:**
- Required: product_id, warehouse_id, quantity
- Creates 1 operation_item with direction='in'

**Side effects:**
1. Increase `product_balances.quantity` by return quantity
2. No cost recalculation (returned goods carry current unit_cost)

### 3.4 Write-off (Списание)

**Validation:**
- Required: product_id, warehouse_id, quantity
- Creates 1 operation_item with direction='out'

**Side effects:**
1. Decrease `product_balances.quantity` by write-off quantity

### 3.5 Transfer (Перемещение)

**Validation:**
- Required: product_id, source_warehouse_id, destination_warehouse_id, quantity
- source and destination must be different warehouses
- Creates 2 operation_items: direction='out' from source, direction='in' to destination

**Side effects:**
1. Decrease source warehouse balance by quantity
2. Increase destination warehouse balance by quantity (same unit_cost)
3. Total inventory unchanged

### 3.6 Production (Производство)

**Validation:**
- Required: 1+ source items (product_id, warehouse_id, quantity each), 1 output item (product_id, warehouse_id, quantity, store_id)
- All source and output products must already exist in the catalog
- Creates N operation_items direction='out' (sources) + 1 operation_item direction='in' (output)

**Side effects:**
1. Decrease balance for each source product
2. Calculate total_input_cost = SUM(source_qty * source_unit_cost) for each source
3. Upsert output product balance:
   - new_qty = old_qty + output_qty
   - new_cost = (old_qty * old_cost + total_input_cost) / new_qty
4. Optionally update output product's store_id

### 3.7 Defect (Брак)

**Validation:**
- Required: product_id, source_warehouse_id, quantity
- Destination warehouse defaults to the workspace's "Defect" warehouse (if exists), otherwise user must choose
- Creates 2 operation_items: direction='out' from source (original product), direction='in' to defect warehouse (defect product copy)

**Side effects:**
1. Find or create defect product copy (name = ".{original_name}", same sku_code prefix, is_defect_copy=true, original_product_id=source)
2. Decrease source product balance on source warehouse
3. Increase defect product balance on defect warehouse (same unit_cost)

### 3.8 Payment (Оплата)

**Validation:**
- Required: supplier_id, payment_amount
- No operation_items (this operation doesn't move products)

**Side effects:**
1. Supplier debt decreases by payment_amount

---

## 4. Reports

### 4.1 Inventory Balances (Остатки товара)

**Query approach**: For current date, read directly from `product_balances`. For historical date, compute by replaying operations.

**Default columns:**
| Product (Name/SKU) | Warehouse 1 | Warehouse 2 | ... | Total |
|---------------------|-------------|-------------|-----|-------|

**Features:**
- Toggle between units (штуки) and cost (рубли) display mode
- Column visibility: user can show/hide optional columns (category, store)
- Filters: by product name/SKU, by category, by store, by warehouse; hide zeros, show only negatives
- Date selector for historical view

**API**: `GET /api/reports/inventory-balances?date=YYYY-MM-DD&mode=units|cost&category_id=...&warehouse_id=...`

### 4.2 Product Movement (Товародвижение)

**Default layout:**
| Product/Warehouse | Purchase In | Sale Out | Return In | Write-off Out | Transfer In | Transfer Out | Net |
|-------------------|------------|----------|-----------|--------------|------------|-------------|-----|

**Features:**
- Date range filter (required)
- Group by: product or warehouse (first column)
- Show individual operation types or aggregated Inbound/Outbound
- Filter by operation type, product, warehouse
- All columns filterable

**API**: `GET /api/reports/product-movement?from=...&to=...&group_by=product|warehouse`

### 4.3 Supplier Debt (Задолженность по Поставщикам)

**Default layout:**
| Supplier | Purchased (period) | Paid (period) | Current Debt | Debt Type |
|----------|-------------------|---------------|-------------|-----------|

**Features:**
- Date selector for "current debt as of date"
- Period selector for purchased/paid columns
- Debt type indicator: creditor (поставщик поставил больше) vs debitor (заплатили больше)
- Filter by debt type
- Drill-down: click supplier to see all purchases and payments for the period

**Calculation:**
- debt = SUM(purchase amounts) - SUM(payment amounts) across all time up to selected date
- Period columns show activity within selected range only

**API**: `GET /api/reports/supplier-debt?as_of=YYYY-MM-DD&from=...&to=...`

### 4.4 Operations Log (Операции)

**Layout:**
| Date | Type | Product | Warehouse | Quantity | Supplier | Amount | Comment |
|------|------|---------|-----------|----------|----------|--------|---------|

**Features:**
- Date range filter
- Filter by operation type, product, warehouse, supplier
- Sortable by date (default: newest first)
- Pagination

**API**: `GET /api/reports/operations?from=...&to=...&type=...&product_id=...&limit=50&offset=0`

---

## 5. Implementation Phases

### Phase 1: Database & Reference Data (Foundation)

**Goal**: Schema in place, CRUD for all master data entities, basic UI pages.

**Tasks:**
1. Write migration `003_inventory_system.sql`:
   - All tables from Section 2.1
   - Indexes from Section 2.2
   - RLS policies (Section 2.3)
   - `updated_at` triggers
   - Extend `bootstrap_new_user` to auto-create "Брак" warehouse
2. TypeScript types for new entities in `src/types/database.ts`
3. API routes (CRUD):
   - `GET/POST /api/warehouses` + `PATCH/DELETE /api/warehouses/[id]`
   - `GET/POST /api/categories` + `PATCH/DELETE /api/categories/[id]`
   - `GET/POST /api/stores` + `PATCH/DELETE /api/stores/[id]`
   - `GET/POST /api/suppliers` + `PATCH/DELETE /api/suppliers/[id]`
   - `GET/POST /api/products` + `PATCH/DELETE /api/products/[id]`
4. UI pages:
   - `/warehouses` - list + create/edit forms
   - `/categories` - list + create/edit forms
   - `/stores` - list + create/edit forms
   - `/suppliers` - list + create/edit forms
   - `/products` - list + create/edit forms (with category/store dropdowns)
5. Navigation: Add sidebar/menu for new sections
6. i18n: Add Russian and English translations for all new strings

**Estimated scope**: ~25-30 files changed/created

### Phase 2: Operations Engine (Core Logic)

**Goal**: Users can record all 8 operation types, balances update automatically.

**Tasks:**
1. Server-side operation processing logic (`src/lib/operations/`):
   - `process-purchase.ts` - with weighted average cost calculation
   - `process-sale.ts`
   - `process-return.ts`
   - `process-write-off.ts`
   - `process-transfer.ts`
   - `process-production.ts` - with multi-source cost rollup
   - `process-defect.ts` - with auto-create defect product copy
   - `process-payment.ts` - with supplier debt update
   - `validate-operation.ts` - shared validation (dates, required fields per type)
   - `update-balances.ts` - shared balance update logic
2. API route: `POST /api/operations` with type-discriminated request body
3. API route: `GET /api/operations` for listing (used by Operations report too)
4. API route: `GET /api/operations/[id]` for detail view
5. UI: `/operations/new` - operation creation form:
   - Type selector (tabs or dropdown)
   - Dynamic form fields based on operation type
   - All attributes selected from dropdowns (products, warehouses, suppliers)
   - Quantity and price inputs
   - Date picker and optional comment
6. UI: `/operations` - operations list page
7. Initial balance entry flow: special UI for first-time setup

**Estimated scope**: ~20-25 files

### Phase 3: Reports (Analytics)

**Goal**: All 4 report types functional with basic filtering.

**Tasks:**
1. Report API endpoints (Section 4):
   - `GET /api/reports/inventory-balances`
   - `GET /api/reports/product-movement`
   - `GET /api/reports/supplier-debt`
   - (Operations report reuses `GET /api/operations` with filters)
2. Historical inventory calculation function:
   - Compute balances at any past date by replaying operations
3. Supplier debt calculation function:
   - Aggregate purchases and payments up to target date
4. UI pages:
   - `/reports/inventory` - with units/cost toggle, column visibility, filters
   - `/reports/movement` - with date range, grouping options, filters
   - `/reports/supplier-debt` - with date selector, debt type filter, drill-down
   - `/reports/operations` - filterable operations list
5. Shared report components:
   - Column visibility selector
   - Filter bar (multi-select for products, warehouses, etc.)
   - Date range picker (reuse existing component)
   - Export buttons (future: CSV/Excel export)

**Estimated scope**: ~15-20 files

### Phase 4: Polish & Advanced Features

**Goal**: Production-ready quality, advanced features from requirements.

**Tasks:**
1. Warehouse purpose-based operation restrictions (warn/block)
2. Defect naming customization in workspace settings
3. Customizable report column ordering (drag-and-drop)
4. Performance optimization for historical inventory queries (snapshot caching)
5. Validation: prevent deleting warehouses/products that have active balances or operations
6. Bulk initial balance import (CSV upload for starting inventory)
7. Future-proof: document AI-powered data extraction extension points for product name/SKU recognition from uploaded files

**Estimated scope**: ~10-15 files

---

## 6. File Structure (New)

```
src/
  app/
    warehouses/
      page.tsx                    # Warehouses list
    categories/
      page.tsx                    # Categories list
    stores/
      page.tsx                    # Stores list
    suppliers/
      page.tsx                    # Suppliers list
    products/
      page.tsx                    # Products catalog
    operations/
      page.tsx                    # Operations list
      new/
        page.tsx                  # Create operation form
    reports/
      inventory/
        page.tsx                  # Inventory balances report
      movement/
        page.tsx                  # Product movement report
      supplier-debt/
        page.tsx                  # Supplier debt report
      operations/
        page.tsx                  # Operations report
    api/
      warehouses/
        route.ts                  # GET (list), POST (create)
        [id]/
          route.ts                # GET, PATCH, DELETE
      categories/
        route.ts
        [id]/
          route.ts
      stores/
        route.ts
        [id]/
          route.ts
      suppliers/
        route.ts
        [id]/
          route.ts
      products/
        route.ts
        [id]/
          route.ts
      operations/
        route.ts                  # GET (list), POST (create)
        [id]/
          route.ts                # GET detail
      reports/
        inventory-balances/
          route.ts
        product-movement/
          route.ts
        supplier-debt/
          route.ts
  lib/
    operations/
      process-purchase.ts
      process-sale.ts
      process-return.ts
      process-write-off.ts
      process-transfer.ts
      process-production.ts
      process-defect.ts
      process-payment.ts
      validate-operation.ts
      update-balances.ts
      index.ts                    # Re-export + dispatch by type
  components/
    OperationForm.tsx             # Dynamic operation creation form
    ReportTable.tsx               # Configurable report table
    ColumnSelector.tsx            # Show/hide columns
    FilterBar.tsx                 # Multi-filter component
    EntityForm.tsx                # Shared CRUD form for master data
  types/
    inventory.ts                  # New entity TypeScript interfaces
supabase/
  migrations/
    003_inventory_system.sql      # All new tables, indexes, RLS, triggers
```

---

## 7. Data Flow Examples

### 7.1 Purchase operation flow

```
User fills form: Product="Widget A", Warehouse="Main", Qty=100, Supplier="Acme", Price=50.00
  |
  v
POST /api/operations
  { type: "purchase", operation_date: "2026-02-10", supplier_id: "...",
    items: [{ product_id: "...", warehouse_id: "...", quantity: 100, unit_price: 50.00 }] }
  |
  v
Server validation (validate-operation.ts)
  - Check product exists, warehouse exists, supplier exists
  - Check quantity > 0, unit_price > 0
  |
  v
Insert operation + operation_items rows
  |
  v
Update product_balances (update-balances.ts):
  - Current: qty=200, unit_cost=45.00
  - New: qty=200+100=300, unit_cost=(200*45 + 100*50)/300 = 46.67
  |
  v
Return operation ID + updated balance
```

### 7.2 Production operation flow

```
User fills form:
  Sources: [Widget A x10, Widget B x5] from Warehouse "Production"
  Output: Widget C x20 to Warehouse "Production", Store "Online Shop"
  |
  v
POST /api/operations
  { type: "production", operation_date: "2026-02-10",
    items: [
      { product_id: "A", warehouse_id: "prod", quantity: 10, direction: "out" },
      { product_id: "B", warehouse_id: "prod", quantity: 5, direction: "out" },
      { product_id: "C", warehouse_id: "prod", quantity: 20, direction: "in", store_id: "..." }
    ] }
  |
  v
Validation: all products exist, warehouse exists, quantities > 0
  |
  v
Calculate production cost:
  - Widget A: 10 units * 46.67 cost = 466.70
  - Widget B: 5 units * 30.00 cost = 150.00
  - Total input cost = 616.70
  |
  v
Update balances:
  - Widget A on "Production": qty -= 10
  - Widget B on "Production": qty -= 5
  - Widget C on "Production": new_cost = (old_qty*old_cost + 616.70) / (old_qty + 20)
  |
  v
Optionally update Widget C store_id
```

---

## 8. Technical Considerations

### 8.1 Transaction safety
All operation processing MUST run inside a database transaction. If any step fails (balance update, item insertion), the entire operation rolls back. Use Supabase's `rpc` or a service-role client with explicit transactions.

### 8.2 Concurrency
Two users recording operations on the same product simultaneously could cause race conditions on `product_balances`. Use `SELECT ... FOR UPDATE` on the balance row within the transaction to prevent stale reads.

### 8.3 Audit trail
Operations are never deleted or modified after creation. If a correction is needed, a new compensating operation is created. This preserves a full audit trail. The `operations` table should eventually have a `deleted_at` soft-delete column if cancellation is needed.

### 8.4 Performance targets
- Product catalog: support up to 50,000 products per workspace
- Operations: support up to 500,000 operations per workspace
- Reports: inventory balance query < 500ms for current date, < 2s for historical
- Product balance updates: < 100ms per operation

### 8.5 Future: AI-powered data extraction
The requirements mention future ability to extract product names and SKU codes from uploaded Excel/PDF files. Design the product creation API to accept data from any source (manual input today, AI extraction tomorrow). The `imports` table infrastructure already supports tracking external data loads.

---

## 9. Implementation Order Summary

```
Phase 1 (Foundation)     Phase 2 (Operations)    Phase 3 (Reports)      Phase 4 (Polish)
===================      ====================    =================      ================
Migration 003            Operation processors    Report APIs            Warehouse restrictions
TypeScript types         Validation logic        Historical calc        Custom defect naming
Warehouse CRUD           Balance updater         Supplier debt calc     Column drag-and-drop
Category CRUD            POST /api/operations    Report UI pages        Perf optimization
Store CRUD               Operation form UI       Filter components      Cascade protections
Supplier CRUD            Operations list UI      Column selector        Bulk CSV import
Product CRUD             Initial balance flow    Date range filters     AI extraction prep
Reference data UI
Navigation + i18n
```

Each phase is independently deployable and provides value. Phase 1 must complete before Phase 2. Phase 3 depends on Phase 2 (needs operations data). Phase 4 can be done incrementally.
