import MockAdapter from "axios-mock-adapter";
import { afterEach, describe, expect, it } from "vitest";
import { apiClient } from "./http";
import {
  checkinErrorMessage,
  getAttendanceSummary,
  getDynamicCheckinCode,
  openCheckinSession,
  submitDynamicCheckin,
} from "./checkins";
const mock = new MockAdapter(apiClient);
afterEach(() => mock.reset());
it("keeps a bodyless forbidden response safe and gives a permission message", async () => {
  mock.onGet("/api/v1/activities/10/attendance-summary").reply(403);
  const error = await getAttendanceSummary("10").catch(cause => cause);
  expect(checkinErrorMessage(error)).toContain("权限");
});

describe("dynamic check-in API contracts", () => {
  it("opens management sessions in dynamic QR mode", async () => {
    mock.onPost("/api/v1/activities/10/check-in-sessions").reply(config => {
      expect(JSON.parse(config.data)).toEqual({ durationMinutes: 15, sessionType: "DYNAMIC_QR" });
      return [201, { data: { sessionId: "70", activityId: "10", sessionType: "DYNAMIC_QR" } }];
    });

    await openCheckinSession("10", 15);
  });

  it("uses exact issuance and submission endpoints without normalizing the token", async () => {
    const credential = {
      sessionId: "70",
      activityId: "10",
      windowEpochSecond: 1_792_137_600,
      expiresAt: "2026-10-16T04:00:45Z",
      token: "Ab_c-DeF",
    };
    mock.onGet("/api/v1/check-in-sessions/70/dynamic-code")
      .reply(200, { data: credential });
    mock.onPost("/api/v1/check-in-sessions/70/dynamic-check-ins").reply(config => {
      expect(JSON.parse(config.data)).toEqual({
        windowEpochSecond: 1_792_137_600,
        token: "Ab_c-DeF",
      });
      return [201, { data: { recordId: "401" } }];
    });

    expect(await getDynamicCheckinCode("70")).toEqual(credential);
    await submitDynamicCheckin("70", {
      windowEpochSecond: credential.windowEpochSecond,
      token: credential.token,
    });
  });
});
