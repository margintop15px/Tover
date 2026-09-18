import assert from "node:assert/strict";
import test from "node:test";
import { buildSupplyTransferCandidates } from "../../src/lib/ozon/sync";
import { validateOzonCandidateOperation } from "../../src/lib/ozon/candidates";

const order = { workspace_id: "workspace", connection_id: "connection", ozon_supply_order_id: "order" };
const item = {
  external_id: "supply:bundle:sku",
  supply_state: "COMPLETED",
  completed_at_ozon: "2026-09-18T10:00:00Z",
  quantity: "2",
  local_product_id: null,
  local_destination_warehouse_id: null,
  ozon_storage_warehouse_id: "ozon-warehouse",
  storage_warehouse_name: "Ozon warehouse",
  sku: "sku",
};

test("completed supplies with Ozon destination evidence remain reviewable without local mappings", () => {
  for (const destination of [
    { ozon_storage_warehouse_id: "ozon-warehouse", storage_warehouse_name: null },
    { ozon_storage_warehouse_id: null, storage_warehouse_name: "Ozon warehouse" },
  ]) {
    const [candidate] = buildSupplyTransferCandidates(order, [{ ...item, ...destination }]);
    assert.equal(candidate.status, "needs_mapping");
    assert.equal(candidate.operation_type, "transfer");
    assert.equal(candidate.external_event_id, "supply:order:supply:bundle:sku");
    assert.equal(candidate.normalized_operation.items?.[1].warehouseId, null);
    assert.equal(candidate.normalized_operation.items?.[1].ozonWarehouseId, destination.ozon_storage_warehouse_id);
    const errors = validateOzonCandidateOperation(candidate.normalized_operation);
    assert.ok(errors.some((error) => error.field === "items[1].warehouseId"));
    assert.ok(errors.some((error) => error.field === "items[0].warehouseId"));
    assert.ok(errors.some((error) => error.field === "items[0].productId"));
  }
});

test("supplies still require completion, a date, positive quantity, and destination evidence", () => {
  for (const invalid of [
    { supply_state: "CANCELLED" },
    { supply_state: "IN_TRANSIT" },
    { completed_at_ozon: null },
    { quantity: "0" },
    { ozon_storage_warehouse_id: null, storage_warehouse_name: null },
  ]) {
    assert.deepEqual(buildSupplyTransferCandidates(order, [{ ...item, ...invalid }]), []);
  }
  const [mapped] = buildSupplyTransferCandidates(order, [{ ...item, local_destination_warehouse_id: "local-warehouse" }]);
  assert.equal(mapped.normalized_operation.items?.[1].warehouseId, "local-warehouse");
  assert.equal(mapped.status, "needs_mapping");
});

test("supply validation clears only after both product and warehouse mappings are complete", () => {
  const [candidate] = buildSupplyTransferCandidates(order, [item]);
  const operation = candidate.normalized_operation;
  const mapped = {
    ...operation,
    items: operation.items!.map((line) => ({
      ...line,
      productId: "local-product",
      warehouseId: line.direction === "out" ? "source-warehouse" : "destination-warehouse",
    })),
  };
  assert.deepEqual(validateOzonCandidateOperation(mapped), []);
  assert.ok(validateOzonCandidateOperation({
    ...mapped,
    items: mapped.items.map((line) => ({ ...line, warehouseId: "same-warehouse" })),
  }).some((error) => error.message === "Transfer source and destination warehouses must differ"));
});
