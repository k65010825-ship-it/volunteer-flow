import http from "k6/http";
import { sleep } from "k6";
import { Counter } from "k6/metrics";
import {
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
const backendUrls = [requiredEnv("BACKEND_A_URL"), requiredEnv("BACKEND_B_URL")];
const expectedAllowed = Number(requiredEnv("EXPECTED_RATE_LIMIT_ALLOWED"));
const expectedRejected = Number(requiredEnv("EXPECTED_RATE_LIMIT_REJECTED"));
const registrationWindow = requiredEnv("RATE_LIMIT_REGISTRATION_WINDOW");
const registrationWindowMatch = /^([1-9][0-9]*)s$/.exec(registrationWindow);
const recoveredBackendARequests = new Counter("recovered_backend_a_requests");
const recoveredBackendBRequests = new Counter("recovered_backend_b_requests");
const windowBoundaryCrossed = new Counter("window_boundary_crossed");

if (
  backendUrls.some((url) => !/^https?:\/\/[^\s/]+(?::[0-9]+)?$/.test(url)) ||
  !Number.isInteger(expectedAllowed) ||
  expectedAllowed < backendUrls.length ||
  !Number.isInteger(expectedRejected) ||
  expectedRejected < 1 ||
  registrationWindowMatch === null
) {
  throw new Error("recovered shared rate-limit inputs are invalid");
}
const registrationWindowMillis = Number(registrationWindowMatch[1]) * 1000;

export const options = {
  vus: 1,
  iterations: 1,
  thresholds: {
    business_checks_failed: ["count==0"],
    unexpected_status: ["count==0"],
    rate_limit_allowed: [`count==${expectedAllowed}`],
    rate_limit_rejected: [`count==${expectedRejected}`],
    recovered_backend_a_requests: [`count==${Math.ceil((expectedAllowed + expectedRejected) / 2)}`],
    recovered_backend_b_requests: [`count==${Math.floor((expectedAllowed + expectedRejected) / 2)}`],
    window_boundary_crossed: ["count==0"],
  },
};

export default function verifyRecoveredSharedRateLimit() {
  const member = manifest.members[0];
  const total = expectedAllowed + expectedRejected;
  const waitUntilNextWindowMillis =
    registrationWindowMillis - (Date.now() % registrationWindowMillis) + 150;
  sleep(waitUntilNextWindowMillis / 1000);
  const expectedWindowStart =
    Math.floor(Date.now() / registrationWindowMillis) * registrationWindowMillis;
  for (let index = 0; index < total; index += 1) {
    const backendUrl = backendUrls[index % backendUrls.length];
    const response = http.post(
      `${backendUrl}/api/v1/activities/${manifest.activityId}/registrations`,
      JSON.stringify({ positionId: manifest.positionId, answers: [] }),
      { headers: authHeaders(tokenByUserId[member.userId]) },
    );
    if (index % backendUrls.length === 0) {
      recoveredBackendARequests.add(1);
    } else {
      recoveredBackendBRequests.add(1);
    }
    if (
      Math.floor(Date.now() / registrationWindowMillis) * registrationWindowMillis !==
      expectedWindowStart
    ) {
      windowBoundaryCrossed.add(1);
      businessChecksFailed.add(1);
      return;
    }
    if (index < expectedAllowed && [201, 409, 422].includes(response.status)) {
      rateLimitAllowed.add(1);
    } else if (
      index >= expectedAllowed &&
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
  return writeSummary(data, "recovered-shared-rate-limit");
}
