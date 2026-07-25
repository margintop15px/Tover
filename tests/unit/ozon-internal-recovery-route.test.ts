import assert from "node:assert/strict";
import test from "node:test";

import { maxDuration } from "../../src/app/api/internal/integrations/ozon/recover/route";

test("internal recovery route exports a duration below the scheduler timeout", () => {
  assert.ok(maxDuration > 0);
  assert.ok(maxDuration < 120);
});
