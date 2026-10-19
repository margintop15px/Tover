// Offline verification of the retained historical capture.
// node experiments/inventory-rdf/capture.mjs --verify <capture.json>
import assert from "node:assert/strict";
import { createHash } from "node:crypto";
import { readFile } from "node:fs/promises";

const TARGET = {
  projectId: "yjecngpktdufhmvxsqhh",
  workspaceId: "581d2ff7-258a-4dfe-8714-992d44698c80",
  connectionId: "a84ebd83-b93d-494f-b63e-513d958edd61",
  productId: "5792316122",
  sku: "5326517520",
  offerId: "L_Компрессор_4",
};
const READ_ONLY_POSTS = new Set([
  "/v3/product/info/list", "/v4/product/info/stocks", "/v1/analytics/stocks",
  "/v2/posting/fbo/get", "/v3/posting/fbs/get",
  "/v3/supply-order/get", "/v1/supply-order/bundle",
]);
const PERSONAL_KEYS = new Set([
  "customer", "client", "buyer", "recipient", "addressee", "customer_data", "client_data",
  "buyer_data", "recipient_data", "address", "delivery_address", "billing_address",
  "client_info", "customer_info", "buyer_info", "recipient_info", "person", "person_info",
  "contact", "contact_person", "contact_info", "customer_name", "client_name", "buyer_name", "recipient_name",
  "customer_address", "client_address", "recipient_address", "postal_address", "full_address", "shipping_address",
  "address_line", "address_line_1", "address_line_2", "street_address",
  "first_name", "last_name", "middle_name", "firstname", "lastname", "full_name",
  "email", "phone", "phone_number", "mobile_phone", "passport", "passport_number",
  "credential_ciphertext", "api_key", "apikey", "authorization", "service_role_key",
]);
const canonical = (value) => JSON.stringify(value, function (_key, item) {
  return item && typeof item === "object" && !Array.isArray(item)
    ? Object.fromEntries(Object.keys(item).sort().map((key) => [key, item[key]])) : item;
});
const sha256 = (value) => createHash("sha256").update(canonical(value)).digest("hex");
const CHECKSUM_ALGORITHM = "SHA-256 of UTF-8 ECMAScript JSON.stringify after recursive object-key sorting; array order unchanged; native ECMAScript number serialization.";
function redact(value, secrets = []) {
  if (Array.isArray(value)) return value.map((item) => redact(item, secrets));
  if (value && typeof value === "object") {
    return Object.fromEntries(Object.entries(value).filter(([key]) => !PERSONAL_KEYS.has(
      key.replace(/([a-z0-9])([A-Z])/g, "$1_$2").toLowerCase()))
      .map(([key, item]) => [key, redact(item, secrets)]));
  }
  if (typeof value === "string") {
    for (const secret of secrets.filter(Boolean)) value = value.replaceAll(secret, "[REDACTED]");
  }
  return value;
}
function verify(capture) {
  assert.equal(capture.version, 1);
  assert.deepEqual(capture.target, TARGET);
  const allowedTables = new Set(["ozon_products", "ozon_stock_snapshots", "ozon_stock_analytics",
    "ozon_posting_items", "ozon_supply_order_items", "ozon_returns", "ozon_postings", "ozon_supply_orders", "ozon_warehouses"]);
  assert(Array.isArray(capture.stored) && Array.isArray(capture.fresh));
  assert.equal(new Set(capture.stored.map((entry) => entry.table)).size, capture.stored.length);
  let count = 0;
  for (const entry of capture.stored) {
    assert(allowedTables.has(entry.table) && Array.isArray(entry.rows));
    assert.equal(entry.sha256, sha256(entry.rows));
    const ids = entry.rows.map((row) => row.id);
    assert(ids.every((id) => typeof id === "string"));
    assert.equal(new Set(ids).size, ids.length);
    assert.deepEqual(ids, [...ids].sort());
    assert(entry.rows.every((row) => row.workspace_id === TARGET.workspaceId && row.connection_id === TARGET.connectionId));
    count += ids.length;
  }
  assert(count <= 1000);
  assert.equal(capture.manifest.recordCount, count);
  for (const entry of capture.fresh) {
    assert(READ_ONLY_POSTS.has(entry.endpoint));
    assert.equal(entry.sha256, sha256(entry.response));
  }
  assert.equal(capture.manifest.sha256, sha256({ target: capture.target, stored: capture.stored, fresh: capture.fresh }));
}
function selfCheck() {
  const cleaned = redact({ customer: { name: "person" }, email: "private", name: "product",
    client_info: { name: "person" }, recipientName: "person", shippingAddress: "private",
    return_from_customer_stock_count: 4, client_delivery_date_begin: "2026-09-20", nested: { api_key: "secret" } });
  assert.deepEqual(cleaned, { name: "product", return_from_customer_stock_count: 4,
    client_delivery_date_begin: "2026-09-20", nested: {} });
  assert.equal(sha256({ b: 2, a: 1 }), sha256({ a: 1, b: 2 }));
  assert.equal(sha256({ amount: 1e-7 }), createHash("sha256").update('{"amount":1e-7}').digest("hex"));
  assert.equal(redact("contains secret", ["secret"]), "contains [REDACTED]");
  assert(!READ_ONLY_POSTS.has("/v1/product/import"));
}

async function main() {
  selfCheck();
  if (process.argv.includes("--self-check")) {
    console.log("Capture self-check passed.");
    return;
  }
  if (process.argv.includes("--verify")) {
    const path = process.argv[process.argv.indexOf("--verify") + 1];
    assert(path, "A capture path is required.");
    verify(JSON.parse(await readFile(path, "utf8")));
    console.log("Capture checksum, count, scoping and uniqueness verification passed.");
    return;
  }
  throw new Error("Read-only live capture was retired with the legacy app; use --verify or --self-check.");
}

main().catch((error) => {
  console.error(error.message.startsWith("Expected") || error.message.startsWith("Snapshot") || error.message.startsWith("Read-only")
    ? error.message : "Capture failed; no credential or response data logged.");
  process.exitCode = 1;
});
