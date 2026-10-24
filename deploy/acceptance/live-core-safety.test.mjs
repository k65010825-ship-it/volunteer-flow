import test from "node:test";
import assert from "node:assert/strict";
import { verifyAcceptanceIdentity } from "./live-core-safety.mjs";
const runId = "1987806a-0b3c-4c31-a721-847bd38bf317";
const valid = {
  database: "vf_acceptance_1987806a0b3c4c31a721847bd38bf317",
  maintenanceDisabled: true,
  consumerDisabled: true,
};
test("accepts only the precise isolated database with disabled background workers", () => {
  assert.doesNotThrow(() => verifyAcceptanceIdentity(runId, valid));
});
test("rejects the original database before any credential or business write", () => {
  assert.throws(() =>
    verifyAcceptanceIdentity(runId, { ...valid, database: "volunteer_flow" }),
  );
});
test("rejects a different acceptance run", () => {
  assert.throws(() =>
    verifyAcceptanceIdentity(runId, {
      ...valid,
      database: "vf_acceptance_00000000000000000000000000000000",
    }),
  );
});
test("rejects enabled workers and missing isolation metadata", () => {
  for (const identity of [
    null,
    {},
    { ...valid, maintenanceDisabled: false },
    { ...valid, consumerDisabled: false },
  ]) {
    assert.throws(() => verifyAcceptanceIdentity(runId, identity));
  }
});
test("rejects malformed run identifiers", () => {
  assert.throws(() => verifyAcceptanceIdentity("bad-id", valid));
});
