/**
 * Seed script: "Sweet Crumbs Bakery" — realistic inventory dataset.
 *
 * Authenticates as the E2E user, then calls the REST API on localhost:3000
 * to create entities and operations. Idempotent: safe to re-run (409 = skip).
 *
 * Prerequisites:
 *   - Dev server running: npm run dev
 *   - .env.local with Supabase credentials
 *   - E2E_EMAIL and E2E_PASSWORD env vars set
 *
 * Run: npm run seed
 */

import { createClient } from "@supabase/supabase-js";

const BASE_URL = "http://localhost:3000";

// ─── Auth ────────────────────────────────────────────────────────────────────

async function buildCookieHeader(): Promise<string> {
  const url = process.env.NEXT_PUBLIC_SUPABASE_URL;
  const anonKey = process.env.NEXT_PUBLIC_SUPABASE_ANON_KEY;
  const email = process.env.E2E_EMAIL;
  const password = process.env.E2E_PASSWORD;

  if (!url || !anonKey) {
    throw new Error("Missing NEXT_PUBLIC_SUPABASE_URL or NEXT_PUBLIC_SUPABASE_ANON_KEY");
  }
  if (!email || !password) {
    throw new Error("Missing E2E_EMAIL or E2E_PASSWORD env vars");
  }

  const supabase = createClient(url, anonKey);
  const { data, error } = await supabase.auth.signInWithPassword({ email, password });
  if (error || !data.session) {
    throw new Error(`Auth failed: ${error?.message ?? "no session"}`);
  }

  // @supabase/ssr stores the session as:
  //   sb-<projectRef>-auth-token = base64-<base64url(JSON.stringify(session))>
  const projectRef = new URL(url).hostname.split(".")[0];
  const cookieName = `sb-${projectRef}-auth-token`;

  const sessionJson = JSON.stringify(data.session);
  const base64 = Buffer.from(sessionJson).toString("base64url");
  const cookieValue = `base64-${base64}`;

  return `${cookieName}=${cookieValue}`;
}

// ─── API helper ──────────────────────────────────────────────────────────────

let cookie = "";

interface ApiResult<T = Record<string, unknown>> {
  status: number;
  data: T;
}

async function api<T = Record<string, unknown>>(
  method: string,
  path: string,
  body?: unknown,
): Promise<ApiResult<T>> {
  const res = await fetch(`${BASE_URL}${path}`, {
    method,
    headers: {
      "Content-Type": "application/json",
      Cookie: cookie,
    },
    body: body ? JSON.stringify(body) : undefined,
  });
  const data = await res.json() as T;
  return { status: res.status, data };
}

// ─── Idempotent entity creators ──────────────────────────────────────────────

interface Named { id: string; name: string }
interface Paginated<T> { items: T[] }

async function findByName<T extends Named>(path: string, name: string): Promise<T | null> {
  const { data } = await api<Paginated<T>>("GET", `${path}?limit=200`);
  return data.items?.find((i) => i.name === name) ?? null;
}

async function createOrFind<T extends Named>(
  path: string,
  body: Record<string, unknown>,
  label: string,
): Promise<T> {
  const { status, data } = await api<T>("POST", path, body);
  if (status === 201) {
    console.log(`  + Created ${label}: ${body.name}`);
    return data;
  }
  if (status === 409) {
    const existing = await findByName<T>(path, body.name as string);
    if (!existing) throw new Error(`409 but could not find existing ${label}: ${body.name}`);
    console.log(`  ~ Exists ${label}: ${body.name}`);
    return existing;
  }
  throw new Error(`Failed to create ${label} "${body.name}": ${status} ${JSON.stringify(data)}`);
}

async function createOperation(body: Record<string, unknown>, label: string): Promise<string> {
  const { status, data } = await api<{ id?: string; errors?: unknown[] }>(
    "POST",
    "/api/operations",
    body,
  );
  if (status === 201 && data.id) {
    console.log(`  + Op: ${label}`);
    return data.id;
  }
  throw new Error(`Failed to create operation "${label}": ${status} ${JSON.stringify(data)}`);
}

// ─── Seed data ───────────────────────────────────────────────────────────────

async function seed() {
  console.log("\n🍞 Sweet Crumbs Bakery — Seed Script\n");

  // 1. Auth
  console.log("Authenticating…");
  cookie = await buildCookieHeader();
  console.log("Authenticated.\n");

  // 2. Categories
  console.log("Categories:");
  const cats = {
    raw: await createOrFind<Named>("/api/categories", { name: "Raw Ingredients" }, "category"),
    packaging: await createOrFind<Named>("/api/categories", { name: "Packaging" }, "category"),
    bread: await createOrFind<Named>("/api/categories", { name: "Bread" }, "category"),
    pastries: await createOrFind<Named>("/api/categories", { name: "Pastries" }, "category"),
    cakes: await createOrFind<Named>("/api/categories", { name: "Cakes" }, "category"),
  };

  // 3. Stores
  console.log("\nStores:");
  const stores = {
    shop: await createOrFind<Named>("/api/stores", { name: "Main Street Shop" }, "store"),
    market: await createOrFind<Named>("/api/stores", { name: "Farmers Market" }, "store"),
    online: await createOrFind<Named>("/api/stores", { name: "Online Orders" }, "store"),
  };

  // 4. Warehouses
  console.log("\nWarehouses:");
  const wh = {
    storage: await createOrFind<Named>("/api/warehouses", {
      name: "Main Storage",
      purpose: "storage",
      description: "Central ingredient storage",
    }, "warehouse"),
    production: await createOrFind<Named>("/api/warehouses", {
      name: "Production Kitchen",
      purpose: "production",
      description: "Where baking happens",
    }, "warehouse"),
    shopDisplay: await createOrFind<Named>("/api/warehouses", {
      name: "Shop Display",
      purpose: "sales",
      description: "Main street retail display",
    }, "warehouse"),
    marketStall: await createOrFind<Named>("/api/warehouses", {
      name: "Market Stall",
      purpose: "sales",
      description: "Farmers market booth",
    }, "warehouse"),
  };

  // Fetch defect warehouse (auto-created with first warehouse)
  const { data: whList } = await api<Paginated<{ id: string; name: string; isDefaultDefect: boolean }>>(
    "GET",
    "/api/warehouses?limit=50",
  );
  const defectWh = whList.items.find((w) => w.isDefaultDefect);
  if (!defectWh) throw new Error("Default defect warehouse not found");
  console.log(`  ~ Defect warehouse: ${defectWh.name} (${defectWh.id})`);

  // 5. Suppliers
  console.log("\nSuppliers:");
  const suppliers = {
    grain: await createOrFind<Named>("/api/suppliers", {
      name: "GrainMaster Mills",
      address: "42 Wheat Lane, Milltown",
      contactInfo: "grain@master.com",
    }, "supplier"),
    dairy: await createOrFind<Named>("/api/suppliers", {
      name: "Fresh Valley Dairy",
      address: "7 Pasture Road, Dairyville",
      contactInfo: "info@freshvalley.com",
    }, "supplier"),
    fruit: await createOrFind<Named>("/api/suppliers", {
      name: "Orchard Best Fruits",
      address: "15 Apple Orchard Dr",
      contactInfo: "sales@orchardbest.com",
    }, "supplier"),
    pack: await createOrFind<Named>("/api/suppliers", {
      name: "PackRight Supplies",
      address: "100 Box Street, Packton",
      contactInfo: "orders@packright.com",
    }, "supplier"),
  };

  // 6. Products
  console.log("\nProducts:");

  // Raw Ingredients
  const flour = await createOrFind<Named>("/api/products", {
    name: "Wheat Flour (25 kg)",
    skuCode: "RAW-001",
    categoryId: cats.raw.id,
  }, "product");
  const sugar = await createOrFind<Named>("/api/products", {
    name: "White Sugar (10 kg)",
    skuCode: "RAW-002",
    categoryId: cats.raw.id,
  }, "product");
  const butter = await createOrFind<Named>("/api/products", {
    name: "Unsalted Butter (5 kg)",
    skuCode: "RAW-003",
    categoryId: cats.raw.id,
  }, "product");
  const eggs = await createOrFind<Named>("/api/products", {
    name: "Fresh Eggs (dozen)",
    skuCode: "RAW-004",
    categoryId: cats.raw.id,
  }, "product");
  const yeast = await createOrFind<Named>("/api/products", {
    name: "Active Dry Yeast (500g)",
    skuCode: "RAW-005",
    categoryId: cats.raw.id,
  }, "product");
  const cream = await createOrFind<Named>("/api/products", {
    name: "Heavy Cream (2L)",
    skuCode: "RAW-006",
    categoryId: cats.raw.id,
  }, "product");
  const apples = await createOrFind<Named>("/api/products", {
    name: "Baking Apples (kg)",
    skuCode: "RAW-007",
    categoryId: cats.raw.id,
  }, "product");

  // Packaging
  const cakeBox = await createOrFind<Named>("/api/products", {
    name: "Small Cake Box",
    skuCode: "PKG-001",
    categoryId: cats.packaging.id,
  }, "product");
  const breadBag = await createOrFind<Named>("/api/products", {
    name: "Bread Paper Bag",
    skuCode: "PKG-002",
    categoryId: cats.packaging.id,
  }, "product");

  // Bread
  const sourdough = await createOrFind<Named>("/api/products", {
    name: "Sourdough Loaf",
    skuCode: "BRD-001",
    categoryId: cats.bread.id,
    storeId: stores.shop.id,
  }, "product");
  const baguette = await createOrFind<Named>("/api/products", {
    name: "French Baguette",
    skuCode: "BRD-002",
    categoryId: cats.bread.id,
    storeId: stores.shop.id,
  }, "product");
  const ryeBread = await createOrFind<Named>("/api/products", {
    name: "Rye Bread",
    skuCode: "BRD-003",
    categoryId: cats.bread.id,
    storeId: stores.market.id,
  }, "product");

  // Pastries
  const croissant = await createOrFind<Named>("/api/products", {
    name: "Butter Croissant",
    skuCode: "PST-001",
    categoryId: cats.pastries.id,
    storeId: stores.shop.id,
  }, "product");
  const cinnamonRoll = await createOrFind<Named>("/api/products", {
    name: "Cinnamon Roll",
    skuCode: "PST-002",
    categoryId: cats.pastries.id,
    storeId: stores.shop.id,
  }, "product");
  const applePie = await createOrFind<Named>("/api/products", {
    name: "Apple Pie Slice",
    skuCode: "PST-003",
    categoryId: cats.pastries.id,
    storeId: stores.market.id,
  }, "product");

  // Cakes
  const chocCake = await createOrFind<Named>("/api/products", {
    name: "Chocolate Layer Cake",
    skuCode: "CAK-001",
    categoryId: cats.cakes.id,
    storeId: stores.shop.id,
  }, "product");
  const cheesecake = await createOrFind<Named>("/api/products", {
    name: "Classic Cheesecake",
    skuCode: "CAK-002",
    categoryId: cats.cakes.id,
    storeId: stores.online.id,
  }, "product");
  const carrotCake = await createOrFind<Named>("/api/products", {
    name: "Carrot Cake",
    skuCode: "CAK-003",
    categoryId: cats.cakes.id,
    storeId: stores.shop.id,
  }, "product");

  // ─── 7. Operations (28, chronological) ──────────────────────────────────────

  console.log("\nOperations:");

  // #1 Jan 6 — Purchase: Flour + Yeast from GrainMaster
  await createOperation({
    type: "purchase",
    operationDate: "2025-01-06",
    comment: "Weekly flour and yeast restock",
    supplierId: suppliers.grain.id,
    items: [
      { productId: flour.id, warehouseId: wh.storage.id, quantity: 4, unitPrice: 18.50 },
      { productId: yeast.id, warehouseId: wh.storage.id, quantity: 6, unitPrice: 4.25 },
    ],
  }, "#1 Purchase flour+yeast from GrainMaster");

  // #2 Jan 6 — Purchase: Dairy from Fresh Valley
  await createOperation({
    type: "purchase",
    operationDate: "2025-01-06",
    comment: "Dairy delivery — butter, eggs, cream",
    supplierId: suppliers.dairy.id,
    items: [
      { productId: butter.id, warehouseId: wh.storage.id, quantity: 5, unitPrice: 22.80 },
      { productId: eggs.id, warehouseId: wh.storage.id, quantity: 10, unitPrice: 5.45 },
      { productId: cream.id, warehouseId: wh.storage.id, quantity: 8, unitPrice: 6.90 },
    ],
  }, "#2 Purchase dairy from Fresh Valley");

  // #3 Jan 6 — Purchase: Apples from Orchard Best
  await createOperation({
    type: "purchase",
    operationDate: "2025-01-06",
    comment: "Baking apples for pies",
    supplierId: suppliers.fruit.id,
    items: [
      { productId: apples.id, warehouseId: wh.storage.id, quantity: 15, unitPrice: 3.20 },
    ],
  }, "#3 Purchase apples from Orchard Best");

  // #4 Jan 6 — Purchase: Packaging from PackRight
  await createOperation({
    type: "purchase",
    operationDate: "2025-01-06",
    comment: "Packaging restock",
    supplierId: suppliers.pack.id,
    items: [
      { productId: cakeBox.id, warehouseId: wh.storage.id, quantity: 50, unitPrice: 1.20 },
      { productId: breadBag.id, warehouseId: wh.storage.id, quantity: 100, unitPrice: 0.35 },
    ],
  }, "#4 Purchase packaging from PackRight");

  // #5 Jan 7 — Production: Sourdough (flour+yeast → 20 loaves)
  await createOperation({
    type: "production",
    operationDate: "2025-01-07",
    comment: "Morning sourdough batch",
    items: [
      { productId: flour.id, warehouseId: wh.storage.id, quantity: 1, direction: "out" },
      { productId: yeast.id, warehouseId: wh.storage.id, quantity: 1, direction: "out" },
      { productId: sourdough.id, warehouseId: wh.production.id, quantity: 20, direction: "in" },
    ],
  }, "#5 Produce 20 sourdough loaves");

  // #6 Jan 7 — Production: Baguettes (flour+yeast → 30)
  await createOperation({
    type: "production",
    operationDate: "2025-01-07",
    comment: "Morning baguette batch",
    items: [
      { productId: flour.id, warehouseId: wh.storage.id, quantity: 1, direction: "out" },
      { productId: yeast.id, warehouseId: wh.storage.id, quantity: 1, direction: "out" },
      { productId: baguette.id, warehouseId: wh.production.id, quantity: 30, direction: "in" },
    ],
  }, "#6 Produce 30 baguettes");

  // #7 Jan 7 — Production: Croissants (flour+butter+eggs → 40)
  await createOperation({
    type: "production",
    operationDate: "2025-01-07",
    comment: "Croissant laminating batch",
    items: [
      { productId: flour.id, warehouseId: wh.storage.id, quantity: 1, direction: "out" },
      { productId: butter.id, warehouseId: wh.storage.id, quantity: 2, direction: "out" },
      { productId: eggs.id, warehouseId: wh.storage.id, quantity: 2, direction: "out" },
      { productId: croissant.id, warehouseId: wh.production.id, quantity: 40, direction: "in" },
    ],
  }, "#7 Produce 40 croissants");

  // #8 Jan 7 — Transfer: Sourdough Production → Shop (15)
  await createOperation({
    type: "transfer",
    operationDate: "2025-01-07",
    comment: "Restock shop with sourdough",
    productId: sourdough.id,
    sourceWarehouseId: wh.production.id,
    destinationWarehouseId: wh.shopDisplay.id,
    quantity: 15,
  }, "#8 Transfer 15 sourdough → Shop");

  // #9 Jan 7 — Transfer: Baguettes Production → Shop (20)
  await createOperation({
    type: "transfer",
    operationDate: "2025-01-07",
    comment: "Restock shop with baguettes",
    productId: baguette.id,
    sourceWarehouseId: wh.production.id,
    destinationWarehouseId: wh.shopDisplay.id,
    quantity: 20,
  }, "#9 Transfer 20 baguettes → Shop");

  // #10 Jan 7 — Transfer: Croissants Production → Shop (30)
  await createOperation({
    type: "transfer",
    operationDate: "2025-01-07",
    comment: "Restock shop with croissants",
    productId: croissant.id,
    sourceWarehouseId: wh.production.id,
    destinationWarehouseId: wh.shopDisplay.id,
    quantity: 30,
  }, "#10 Transfer 30 croissants → Shop");

  // #11 Jan 7 — Sale: Morning shop sales
  await createOperation({
    type: "sale",
    operationDate: "2025-01-07",
    comment: "Morning rush — bread and croissants",
    items: [
      { productId: sourdough.id, warehouseId: wh.shopDisplay.id, quantity: 5, unitPrice: 7.50 },
      { productId: baguette.id, warehouseId: wh.shopDisplay.id, quantity: 8, unitPrice: 4.00 },
      { productId: croissant.id, warehouseId: wh.shopDisplay.id, quantity: 12, unitPrice: 3.50 },
    ],
  }, "#11 Morning shop sales");

  // #12 Jan 7 — Sale: Afternoon shop sales
  await createOperation({
    type: "sale",
    operationDate: "2025-01-07",
    comment: "Afternoon sales",
    items: [
      { productId: sourdough.id, warehouseId: wh.shopDisplay.id, quantity: 4, unitPrice: 7.50 },
      { productId: baguette.id, warehouseId: wh.shopDisplay.id, quantity: 5, unitPrice: 4.00 },
      { productId: croissant.id, warehouseId: wh.shopDisplay.id, quantity: 8, unitPrice: 3.50 },
    ],
  }, "#12 Afternoon shop sales");

  // #13 Jan 8 — Production: Apple pie slices (flour+butter+sugar+apples → 24)
  await createOperation({
    type: "production",
    operationDate: "2025-01-08",
    comment: "Apple pie batch for market",
    items: [
      { productId: flour.id, warehouseId: wh.storage.id, quantity: 1, direction: "out" },
      { productId: butter.id, warehouseId: wh.storage.id, quantity: 1, direction: "out" },
      { productId: sugar.id, warehouseId: wh.storage.id, quantity: 1, direction: "out" },
      { productId: apples.id, warehouseId: wh.storage.id, quantity: 5, direction: "out" },
      { productId: applePie.id, warehouseId: wh.production.id, quantity: 24, direction: "in" },
    ],
  }, "#13 Produce 24 apple pie slices");

  // #14 Jan 8 — Production: Chocolate layer cakes (5)
  await createOperation({
    type: "production",
    operationDate: "2025-01-08",
    comment: "Chocolate cake production",
    items: [
      { productId: flour.id, warehouseId: wh.storage.id, quantity: 1, direction: "out" },
      { productId: sugar.id, warehouseId: wh.storage.id, quantity: 1, direction: "out" },
      { productId: eggs.id, warehouseId: wh.storage.id, quantity: 2, direction: "out" },
      { productId: butter.id, warehouseId: wh.storage.id, quantity: 1, direction: "out" },
      { productId: cream.id, warehouseId: wh.storage.id, quantity: 2, direction: "out" },
      { productId: chocCake.id, warehouseId: wh.production.id, quantity: 5, direction: "in" },
    ],
  }, "#14 Produce 5 chocolate layer cakes");

  // #15 Jan 8 — Production: Classic cheesecakes (4)
  await createOperation({
    type: "production",
    operationDate: "2025-01-08",
    comment: "Cheesecake production",
    items: [
      { productId: eggs.id, warehouseId: wh.storage.id, quantity: 2, direction: "out" },
      { productId: cream.id, warehouseId: wh.storage.id, quantity: 3, direction: "out" },
      { productId: sugar.id, warehouseId: wh.storage.id, quantity: 1, direction: "out" },
      { productId: cheesecake.id, warehouseId: wh.production.id, quantity: 4, direction: "in" },
    ],
  }, "#15 Produce 4 classic cheesecakes");

  // #16 Jan 9 — Transfer: Apple pies Production → Market (20)
  await createOperation({
    type: "transfer",
    operationDate: "2025-01-09",
    comment: "Stock market stall with pies",
    productId: applePie.id,
    sourceWarehouseId: wh.production.id,
    destinationWarehouseId: wh.marketStall.id,
    quantity: 20,
  }, "#16 Transfer 20 apple pies → Market");

  // #17 Jan 9 — Sale: Online cheesecake orders (3)
  await createOperation({
    type: "sale",
    operationDate: "2025-01-09",
    comment: "Online orders — cheesecakes for delivery",
    items: [
      { productId: cheesecake.id, warehouseId: wh.production.id, quantity: 3, unitPrice: 35.00 },
    ],
  }, "#17 Online sale: 3 cheesecakes");

  // #18 Jan 9 — Defect: Stale croissants in shop (3)
  await createOperation({
    type: "defect",
    operationDate: "2025-01-09",
    comment: "Stale croissants — moved to defect",
    productId: croissant.id,
    sourceWarehouseId: wh.shopDisplay.id,
    quantity: 3,
  }, "#18 Defect: 3 stale croissants");

  // #19 Jan 10 — Sale: Saturday farmers market (16 pie slices)
  await createOperation({
    type: "sale",
    operationDate: "2025-01-10",
    comment: "Saturday market — great pie sales!",
    items: [
      { productId: applePie.id, warehouseId: wh.marketStall.id, quantity: 16, unitPrice: 5.50 },
    ],
  }, "#19 Market sale: 16 apple pie slices");

  // #20 Jan 10 — Return: Customer returns cheesecake (1)
  await createOperation({
    type: "return",
    operationDate: "2025-01-10",
    comment: "Customer return — wrong flavor ordered",
    items: [
      { productId: cheesecake.id, warehouseId: wh.production.id, quantity: 1 },
    ],
  }, "#20 Return: 1 cheesecake");

  // #21 Jan 13 — Payment: Partial payment to GrainMaster ($95)
  await createOperation({
    type: "payment",
    operationDate: "2025-01-13",
    comment: "Partial payment — first installment",
    supplierId: suppliers.grain.id,
    paymentAmount: 95.00,
  }, "#21 Payment: $95 to GrainMaster");

  // #22 Jan 13 — Payment: Full payment to Fresh Valley ($257.10)
  await createOperation({
    type: "payment",
    operationDate: "2025-01-13",
    comment: "Full payment for dairy delivery",
    supplierId: suppliers.dairy.id,
    paymentAmount: 257.10,
  }, "#22 Payment: $257.10 to Fresh Valley");

  // #23 Jan 13 — Purchase: Second week flour restock
  await createOperation({
    type: "purchase",
    operationDate: "2025-01-13",
    comment: "Second week flour restock",
    supplierId: suppliers.grain.id,
    items: [
      { productId: flour.id, warehouseId: wh.storage.id, quantity: 3, unitPrice: 18.50 },
      { productId: sugar.id, warehouseId: wh.storage.id, quantity: 2, unitPrice: 8.75 },
    ],
  }, "#23 Purchase: flour+sugar restock from GrainMaster");

  // #24 Jan 14 — Production: Rye bread (15 loaves)
  await createOperation({
    type: "production",
    operationDate: "2025-01-14",
    comment: "Rye bread batch",
    items: [
      { productId: flour.id, warehouseId: wh.storage.id, quantity: 1, direction: "out" },
      { productId: yeast.id, warehouseId: wh.storage.id, quantity: 1, direction: "out" },
      { productId: ryeBread.id, warehouseId: wh.production.id, quantity: 15, direction: "in" },
    ],
  }, "#24 Produce 15 rye bread loaves");

  // #25 Jan 14 — Production: Cinnamon rolls (25)
  await createOperation({
    type: "production",
    operationDate: "2025-01-14",
    comment: "Cinnamon roll batch",
    items: [
      { productId: flour.id, warehouseId: wh.storage.id, quantity: 1, direction: "out" },
      { productId: butter.id, warehouseId: wh.storage.id, quantity: 1, direction: "out" },
      { productId: sugar.id, warehouseId: wh.storage.id, quantity: 1, direction: "out" },
      { productId: eggs.id, warehouseId: wh.storage.id, quantity: 1, direction: "out" },
      { productId: cinnamonRoll.id, warehouseId: wh.production.id, quantity: 25, direction: "in" },
    ],
  }, "#25 Produce 25 cinnamon rolls");

  // #26 Jan 14 — Transfer: Rye bread Production → Market (10)
  await createOperation({
    type: "transfer",
    operationDate: "2025-01-14",
    comment: "Stock market stall with rye bread",
    productId: ryeBread.id,
    sourceWarehouseId: wh.production.id,
    destinationWarehouseId: wh.marketStall.id,
    quantity: 10,
  }, "#26 Transfer 10 rye bread → Market");

  // #27 Jan 15 — Write-off: Expired eggs (3 dozen)
  await createOperation({
    type: "write_off",
    operationDate: "2025-01-15",
    comment: "Expired eggs discarded",
    items: [
      { productId: eggs.id, warehouseId: wh.storage.id, quantity: 3 },
    ],
  }, "#27 Write-off: 3 dozen expired eggs");

  // #28 Jan 15 — Sale: Mid-week sales (cinnamon rolls + sourdough)
  await createOperation({
    type: "sale",
    operationDate: "2025-01-15",
    comment: "Mid-week sales — cinnamon rolls flying off shelves",
    items: [
      { productId: cinnamonRoll.id, warehouseId: wh.production.id, quantity: 10, unitPrice: 4.50 },
      { productId: sourdough.id, warehouseId: wh.production.id, quantity: 3, unitPrice: 7.50 },
    ],
  }, "#28 Mid-week sales: cinnamon rolls + sourdough");

  // ─── Summary ─────────────────────────────────────────────────────────────────

  console.log("\n────────────────────────────────────");
  console.log("Seed complete!");
  console.log("  Categories:  5");
  console.log("  Stores:      3");
  console.log("  Warehouses:  4 (+1 defect)");
  console.log("  Suppliers:   4");
  console.log("  Products:    18");
  console.log("  Operations:  28");
  console.log("────────────────────────────────────\n");
}

seed().catch((err) => {
  console.error("\nSeed failed:", err);
  process.exit(1);
});
