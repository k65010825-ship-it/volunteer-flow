import type { CurrentUser } from "../api/types";

export function workspaceHome(user: CurrentUser | null) {
  return user?.platformRole === "PLATFORM_ADMIN"
    ? "/admin/organizations"
    : "/activities";
}

/** Login return links must stay inside this application. */
export function loginDestination(redirect: unknown, user: CurrentUser | null) {
  if (
    typeof redirect === "string" &&
    /^\/(?!\/)/.test(redirect) &&
    !/[\\\u0000-\u001f]/.test(redirect)
  )
    return redirect;
  return workspaceHome(user);
}
