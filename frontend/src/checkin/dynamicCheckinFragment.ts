import type { DynamicCheckinCode, DynamicCheckinInput } from "../api/checkins";
import type { EntityId } from "../api/types";

interface FragmentLocation {
  hash: string;
  pathname: string;
  search: string;
}

interface FragmentHistory {
  replaceState(data: unknown, unused: string, url?: string | URL | null): void;
}

/** Builds a camera-friendly URL whose credential is never sent in the HTTP request target. */
export function buildDynamicCheckinUrl(
  origin: string,
  activityId: EntityId,
  credential: DynamicCheckinCode,
): string {
  const url = new URL(`/activities/${encodeURIComponent(String(activityId))}/check-in`, origin);
  url.searchParams.set("sessionId", String(credential.sessionId));
  url.hash = new URLSearchParams({
    window: String(credential.windowEpochSecond),
    token: credential.token,
  }).toString();
  return url.toString();
}

/**
 * Reads the credential once and removes it from browser history before exposing it to the caller.
 * Base64URL tokens remain case-sensitive and are therefore never normalized.
 */
export function consumeDynamicCheckinFragment(
  location: FragmentLocation,
  history: FragmentHistory,
): DynamicCheckinInput | null {
  const fragment = location.hash.startsWith("#") ? location.hash.slice(1) : location.hash;
  const parameters = new URLSearchParams(fragment);
  const rawWindow = parameters.get("window");
  const token = parameters.get("token");
  // Credential-shaped fragments are removed even when malformed so secrets never linger in history.
  if (parameters.has("window") || parameters.has("token")) {
    history.replaceState(null, "", `${location.pathname}${location.search}`);
  }
  if (!rawWindow || !/^\d+$/.test(rawWindow) || !token || !/^[A-Za-z0-9_-]+$/.test(token)) {
    return null;
  }

  const windowEpochSecond = Number(rawWindow);
  if (!Number.isSafeInteger(windowEpochSecond) || windowEpochSecond <= 0) {
    return null;
  }

  return { windowEpochSecond, token };
}
