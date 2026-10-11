import MockAdapter from "axios-mock-adapter";
import { afterEach, expect, it } from "vitest";
import { apiClient } from "./http";
import { checkinErrorMessage, getAttendanceSummary } from "./checkins";
const mock = new MockAdapter(apiClient);
afterEach(() => mock.reset());
it("keeps a bodyless forbidden response safe and gives a permission message", async () => {
  mock.onGet("/api/v1/activities/10/attendance-summary").reply(403);
  const error = await getAttendanceSummary("10").catch(cause => cause);
  expect(checkinErrorMessage(error)).toContain("权限");
});
