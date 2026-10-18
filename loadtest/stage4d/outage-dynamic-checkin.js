import http from "k6/http";
import { Counter } from "k6/metrics";
import {
  BASE_URL,
  DATASET,
  requiredEnv,
  MANIFEST_PATH,
  RUN_ID,
  TOKENS_PATH,
  loadManifest,
  loadTokens,
  writeSummary,
} from "./lib/config.js";
import { authHeaders, businessChecksFailed, unexpectedStatus } from "./lib/http.js";

const manifest = loadManifest(MANIFEST_PATH, RUN_ID, DATASET);
const tokenByUserId = loadTokens(TOKENS_PATH, manifest);
const dynamicCheckinUnavailable = new Counter("dynamic_checkin_unavailable");
const dynamicCheckinWindow = requiredEnv("DYNAMIC_CHECKIN_WINDOW");
const windowMatch = /^([1-9][0-9]*)s$/.exec(dynamicCheckinWindow);
if (windowMatch === null) {
  throw new Error("DYNAMIC_CHECKIN_WINDOW must use positive whole seconds for this scenario");
}
const dynamicCheckinWindowSeconds = Number(windowMatch[1]);

export const options = {
  vus: 1,
  iterations: 1,
  thresholds: {
    business_checks_failed: ["count==0"],
    unexpected_status: ["count==0"],
    dynamic_checkin_unavailable: ["count==1"],
  },
};

function findConfirmedMember() {
  const requests = manifest.members.map((member) => ({
    method: "GET",
    url: `${BASE_URL}/api/v1/users/me/registrations`,
    params: { headers: authHeaders(tokenByUserId[member.userId]) },
  }));
  const responses = http.batch(requests);
  for (let index = 0; index < responses.length; index += 1) {
    const response = responses[index];
    if (response.status !== 200) {
      businessChecksFailed.add(1);
      unexpectedStatus.add(1);
      continue;
    }
    const registrations = response.json("data");
    if (
      Array.isArray(registrations) &&
      registrations.some(
        (item) => item.activityId === manifest.activityId && item.status === "CONFIRMED",
      )
    ) {
      return manifest.members[index];
    }
  }
  return null;
}

export default function verifyDynamicCheckinOutage() {
  const member = findConfirmedMember();
  if (member === null) {
    businessChecksFailed.add(1);
    return;
  }

  const response = http.post(
    `${BASE_URL}/api/v1/check-in-sessions/${manifest.checkinSessionId}/dynamic-check-ins`,
    JSON.stringify({
      windowEpochSecond:
        Math.floor(Date.now() / 1000 / dynamicCheckinWindowSeconds) * dynamicCheckinWindowSeconds,
      token: "stage4d-invalid-token",
    }),
    { headers: authHeaders(tokenByUserId[member.userId]) },
  );
  if (response.status === 503 && response.json("code") === "DYNAMIC_CHECKIN_UNAVAILABLE") {
    dynamicCheckinUnavailable.add(1);
  } else {
    businessChecksFailed.add(1);
    unexpectedStatus.add(1);
  }
}

export function handleSummary(data) {
  return writeSummary(data, "outage-dynamic-checkin");
}
