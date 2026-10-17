import http from "k6/http";
import {
  BASE_URL,
  DATASET,
  MANIFEST_PATH,
  RUN_ID,
  TOKENS_PATH,
  loadManifest,
  loadTokens,
  requiredEnv,
  writeSummary,
} from "./lib/config.js";
import {
  authHeaders,
  businessChecksFailed,
  rateLimitAllowed,
  rateLimitRejected,
  unexpectedStatus,
} from "./lib/http.js";

const manifest = loadManifest(MANIFEST_PATH, RUN_ID, DATASET);
const tokenByUserId = loadTokens(TOKENS_PATH, manifest);
const EXPECTED_RATE_LIMIT_ALLOWED = Number(requiredEnv("EXPECTED_RATE_LIMIT_ALLOWED"));
const EXPECTED_RATE_LIMIT_REJECTED = Number(requiredEnv("EXPECTED_RATE_LIMIT_REJECTED"));

if (
  !Number.isInteger(EXPECTED_RATE_LIMIT_ALLOWED) ||
  EXPECTED_RATE_LIMIT_ALLOWED < 1 ||
  !Number.isInteger(EXPECTED_RATE_LIMIT_REJECTED) ||
  EXPECTED_RATE_LIMIT_REJECTED < 1
) {
  throw new Error("expected rate-limit counts must be positive integers");
}

export const options = {
  vus: 1,
  iterations: 1,
  thresholds: {
    business_checks_failed: ["count==0"],
    unexpected_status: ["count==0"],
  },
};

export default function verifyRateLimit() {
  const authenticationResponse = http.get(
    `${BASE_URL}/api/v1/users/me/registrations`,
    { tags: { operation: "stage4d_authentication_rejection" } },
  );
  if (authenticationResponse.status !== 401) {
    businessChecksFailed.add(1);
    unexpectedStatus.add(1);
  }

  const member = manifest.members[0];
  const total = EXPECTED_RATE_LIMIT_ALLOWED + EXPECTED_RATE_LIMIT_REJECTED;
  for (let index = 0; index < total; index += 1) {
    const response = http.post(
      `${BASE_URL}/api/v1/activities/${manifest.activityId}/registrations`,
      JSON.stringify({ positionId: manifest.positionId, answers: [] }),
      {
        headers: authHeaders(tokenByUserId[member.userId]),
        tags: { operation: "stage4d_registration_rate_limit" },
      },
    );
    if (index < EXPECTED_RATE_LIMIT_ALLOWED) {
      if (response.status === 429 || ![201, 409, 422].includes(response.status)) {
        businessChecksFailed.add(1);
        unexpectedStatus.add(1);
      } else {
        rateLimitAllowed.add(1);
      }
    } else if (
      response.status === 429 &&
      /^[1-9][0-9]*$/.test(response.headers["Retry-After"] || "")
    ) {
      rateLimitRejected.add(1);
    } else {
      businessChecksFailed.add(1);
      unexpectedStatus.add(1);
    }
  }
}

export function handleSummary(data) {
  return writeSummary(data, "rate-limit");
}
