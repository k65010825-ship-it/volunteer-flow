import http from "k6/http";
import {
  BASE_URL,
  DATASET,
  MANIFEST_PATH,
  RUN_ID,
  TOKENS_PATH,
  loadManifest,
  loadTokens,
  writeSummary,
} from "./lib/config.js";
import {
  authHeaders,
  businessChecksFailed,
  unexpectedStatus,
} from "./lib/http.js";

const manifest = loadManifest(MANIFEST_PATH, RUN_ID, DATASET);
const tokenByUserId = loadTokens(TOKENS_PATH, manifest);

export const options = {
  vus: 1,
  iterations: 1,
  thresholds: {
    business_checks_failed: ["count==0"],
    unexpected_status: ["count==0"],
  },
};

function requireOk(response) {
  if (response.status === 200) {
    return;
  }
  businessChecksFailed.add(1);
  unexpectedStatus.add(1);
}

export default function verifyRecovery() {
  const member = manifest.members[0];
  const headers = { headers: authHeaders(tokenByUserId[member.userId]) };

  let response = http.get(`${BASE_URL}/actuator/health/readiness`, {
    tags: { operation: "stage4d_recovery_readiness" },
  });
  requireOk(response);

  response = http.get(`${BASE_URL}/api/v1/activities/${manifest.activityId}`, {
    ...headers,
    tags: { operation: "stage4d_recovery_activity_read" },
  });
  requireOk(response);

  response = http.get(`${BASE_URL}/api/v1/users/me/registrations`, {
    ...headers,
    tags: { operation: "stage4d_recovery_registration_state" },
  });
  requireOk(response);
}

export function handleSummary(data) {
  return writeSummary(data, "recovery");
}
