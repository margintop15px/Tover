import assert from "node:assert/strict";
import test from "node:test";
import { readJsonResponse } from "../../src/lib/api-response";

test("reads accept JSON objects but reject HTTP and malformed response failures", async () => {
  assert.deepEqual(await readJsonResponse(Response.json({ items: [] })), { items: [] });
  await assert.rejects(readJsonResponse(Response.json({ error: "private diagnostic" }, { status: 503 })), { message: "Request failed (HTTP 503)" });
  for (const body of ["<html>gateway</html>", "null", "[]", '"text"']) {
    await assert.rejects(readJsonResponse(new Response(body)));
  }
});
