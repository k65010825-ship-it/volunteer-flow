import { describe, expect, it } from "vitest";
import { backNavigation, isNavigationHome } from "./backNavigation";

describe("shared back navigation", () => {
  it.each(["/", "/activities", "/admin/organizations", "/login"])(
    "hides the back control on entry %s",
    (path) => {
      expect(isNavigationHome(path)).toBe(true);
    },
  );
  it.each([
    "/profile",
    "/notifications",
    "/registrations",
    "/register",
    "/join",
    "/organizations/1/activities",
    "/organizations/1/manage",
    "/organizations/1/audit",
    "/activities/20/edit",
    "/activities/20/check-in",
    "/forbidden",
    "/missing",
  ])("shows back on subpage %s", (path) => {
    expect(isNavigationHome(path)).toBe(false);
  });
  it("uses an actual local previous entry", () => {
    expect(
      backNavigation(
        "/organizations/1/activities?page=2",
        "/activities/20/edit",
        false,
        false,
      ),
    ).toEqual({
      useHistory: true,
      destination: "/organizations/1/activities?page=2",
    });
  });
  it.each([
    undefined,
    null,
    "https://example.com",
    "//example.com",
    "/\\example.com",
    "/\nexample.com",
    "/profile",
    "/login?redirect=/profile",
    "/register",
  ])("falls back instead of leaving the app or looping for %s", (previous) => {
    expect(backNavigation(previous, "/profile", false, false)).toEqual({
      useHistory: false,
      destination: "/activities?userView=1",
    });
  });
  it("uses the administrator home for a direct admin subpage", () => {
    expect(backNavigation(undefined, "/profile", true, false).destination).toBe(
      "/admin/organizations",
    );
  });
  it("returns a directly opened public registration page to login", () => {
    expect(
      backNavigation(undefined, "/register", false, true).destination,
    ).toBe("/login");
  });
});
