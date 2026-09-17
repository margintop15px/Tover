import assert from "node:assert/strict";
import test from "node:test";
import { getSafeNextPath } from "../../src/lib/auth-redirect";

test("auth redirects accept local paths and reject external or disguised URLs", () => {
  assert.equal(getSafeNextPath("/operations?tab=recent"), "/operations?tab=recent");
  assert.equal(getSafeNextPath("/reset-password"), "/reset-password");
  for (const path of [null, "https://example.com", "//example.com", "/\\example.com", "/\n/example.com"]) {
    assert.equal(getSafeNextPath(path), "/");
  }
});
