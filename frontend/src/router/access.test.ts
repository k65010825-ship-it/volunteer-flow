import { describe, expect, it } from "vitest";
import { loginDestination, workspaceHome } from "./access";
import type { CurrentUser } from "../api/types";
const user: CurrentUser = {
  id: "1",
  username: "student",
  realName: "同学",
  studentNumber: "20260001",
  contact: "contact",
  platformRole: "USER",
};
describe("workspace navigation", () => {
  it("uses the server-provided platform identity", () => {
    expect(workspaceHome(user)).toBe("/activities");
    expect(workspaceHome({ ...user, platformRole: "PLATFORM_ADMIN" })).toBe(
      "/admin/organizations",
    );
  });
  it("preserves local deep links", () => {
    expect(loginDestination("/activities/123?from=notification", user)).toBe(
      "/activities/123?from=notification",
    );
  });
  it.each([
    "https://example.com",
    "//example.com",
    "/\\example.com",
    "/\nexample.com",
    undefined,
    ["/admin"],
  ])("rejects nonlocal or malformed redirect %s", (value) => {
    expect(loginDestination(value, user)).toBe("/activities");
  });
});
