import assert from "node:assert/strict";
import test from "node:test";
import { extractWithOpenAI } from "../../src/lib/operation-imports/openai";

const invoiceItems = [
  ["LEN-IP3-15", 5, 45000], ["LOG-M170", 10, 1200],
  ["CAB-HDMI-2", 20, 450], ["SAM-S27R500", 3, 28500],
  ["OKL-930", 15, 680], ["EXT-5SOCK", 8, 350],
].map(([skuCode, quantity, unitPrice]) => ({ productName: skuCode, skuCode, quantity, unitPrice }));

const input = {
  fileName: "invoice.jpg", mimeType: "image/jpeg", buffer: Buffer.from("test-image"),
  ref: { categories: [], products: [], warehouses: [], suppliers: [], stores: [] },
  existingDuplicates: [],
};

function response(items = invoiceItems, status = "completed") {
  return { status, output_text: JSON.stringify({
    findings: { sourceItemCount: 6 },
    operations: [{ type: "purchase", operationDate: "2024-10-15", items }],
  }) };
}

test("image extraction preserves all six invoice lines and refuses partial/empty results", async (t) => {
  const oldKey = process.env.OPENAI_API_KEY;
  process.env.OPENAI_API_KEY = "test-only";
  t.after(() => {
    if (oldKey === undefined) delete process.env.OPENAI_API_KEY;
    else process.env.OPENAI_API_KEY = oldKey;
  });
  let payload: unknown = response();
  t.mock.method(globalThis, "fetch", async () => Response.json(payload));
  const result = await extractWithOpenAI(input);
  assert.equal(result.candidates.length, 1);
  const candidate = result.candidates[0];
  assert.equal(candidate.normalizedOperation.items?.length, 6);
  assert.deepEqual(candidate.operation.items?.map(({ skuCode, quantity, unitPrice }) => ({ productName: skuCode, skuCode, quantity, unitPrice })), invoiceItems);
  assert.equal(candidate.operation.items?.reduce((sum, item) => sum + item.quantity! * item.unitPrice!, 0), 344500);
  assert.ok(candidate.validationErrors.some(error => error.field === "items[5].productId"));

  payload = response(invoiceItems.slice(0, 1));
  await assert.rejects(extractWithOpenAI(input), /lists 6 items, but detection returned 1/);
  payload = response(invoiceItems, "incomplete");
  await assert.rejects(extractWithOpenAI(input), /did not complete/);
  payload = { status: "completed", output_text: JSON.stringify({ operations: [] }) };
  await assert.rejects(extractWithOpenAI(input), /No operations/);
  payload = { status: "completed", output: [{ type: "message", content: [{ type: "refusal", refusal: "Cannot read" }] }] };
  await assert.rejects(extractWithOpenAI(input), /did not contain JSON/);
});
