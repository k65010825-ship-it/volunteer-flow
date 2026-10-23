/** Entry pages do not need a back control; all functional subpages do. */
export function isNavigationHome(path: string) {
  return [
    "/",
    "/activities",
    "/admin",
    "/admin/organizations",
    "/login",
  ].includes(path);
}

export function backNavigation(
  previous: unknown,
  current: string,
  administrator: boolean,
  publicPage: boolean,
) {
  const fallback = publicPage
    ? "/login"
    : administrator
      ? "/admin/organizations"
      : "/activities?userView=1";
  if (
    typeof previous !== "string" ||
    !/^\/(?!\/)/.test(previous) ||
    /[\\\u0000-\u001f]/.test(previous)
  ) {
    return { useHistory: false, destination: fallback };
  }
  const previousPath = previous.split(/[?#]/)[0];
  // Do not send an authenticated user back into the login/register flow.
  if (
    previous === current ||
    (!publicPage && ["/login", "/register"].includes(previousPath))
  ) {
    return { useHistory: false, destination: fallback };
  }
  return { useHistory: true, destination: previous };
}
