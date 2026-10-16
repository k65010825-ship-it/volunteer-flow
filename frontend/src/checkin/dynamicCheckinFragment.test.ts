import { describe, expect, it, vi } from "vitest";
import type { DynamicCheckinCode } from "../api/checkins";
import {
  buildDynamicCheckinUrl,
  consumeDynamicCheckinFragment,
} from "./dynamicCheckinFragment";

const credential: DynamicCheckinCode = {
  sessionId: "70",
  activityId: "10",
  windowEpochSecond: 1_792_137_600,
  expiresAt: "2026-10-16T04:00:45Z",
  token: "Ab_c-DeF",
};

describe("dynamic check-in fragment", () => {
  it("keeps token and window after the fragment delimiter", () => {
    const value = buildDynamicCheckinUrl("https://volunteer.example", "10", credential);
    const url = new URL(value);

    expect(url.pathname).toBe("/activities/10/check-in");
    expect(url.searchParams.get("sessionId")).toBe("70");
    expect(url.searchParams.has("token")).toBe(false);
    expect(url.searchParams.has("window")).toBe(false);
    expect(url.hash).toBe("#window=1792137600&token=Ab_c-DeF");
  });

  it("returns null but still removes malformed credentials from history", () => {
    const history = { replaceState: vi.fn() };
    expect(consumeDynamicCheckinFragment({
      hash: "#window=not-a-number&token=bad token",
      pathname: "/activities/10/check-in",
      search: "?sessionId=70",
    }, history)).toBeNull();
    expect(history.replaceState).toHaveBeenCalledWith(
      null, "", "/activities/10/check-in?sessionId=70");
  });

  it("removes the secret from browser history before returning it", () => {
    const history = { replaceState: vi.fn() };
    let cleared = false;
    history.replaceState.mockImplementation((_state, _title, url) => {
      expect(String(url)).not.toContain("token");
      expect(String(url)).not.toContain("Ab_c-DeF");
      expect(String(url)).not.toContain("window");
      cleared = true;
    });

    const input = consumeDynamicCheckinFragment({
      hash: "#window=1792137600&token=Ab_c-DeF",
      pathname: "/activities/10/check-in",
      search: "?sessionId=70",
    }, history);

    expect(cleared).toBe(true);
    expect(input).toEqual({ windowEpochSecond: 1_792_137_600, token: "Ab_c-DeF" });
  });
});
