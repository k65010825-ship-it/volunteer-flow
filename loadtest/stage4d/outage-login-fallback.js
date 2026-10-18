import http from "k6/http";
import { Counter } from "k6/metrics";
import { RUN_ID, requiredEnv, writeSummary } from "./lib/config.js";
import { businessChecksFailed, unexpectedStatus } from "./lib/http.js";

const backendUrls = [requiredEnv("BACKEND_A_URL"), requiredEnv("BACKEND_B_URL")];
const expectedAllowed = Number(requiredEnv("EXPECTED_LOCAL_LOGIN_ALLOWED"));
const expectedRejected = Number(requiredEnv("EXPECTED_LOCAL_LOGIN_REJECTED"));
const localLoginAllowed = new Counter("local_login_allowed");
const localLoginRejected = new Counter("local_login_rejected");

if (
  backendUrls.some((url) => !/^https?:\/\/[^\s/]+(?::[0-9]+)?$/.test(url)) ||
  !Number.isInteger(expectedAllowed) ||
  expectedAllowed < 1 ||
  expectedRejected !== 1
) {
  throw new Error("local login fallback inputs are invalid");
}

export const options = {
  vus: 1,
  iterations: 1,
  thresholds: {
    business_checks_failed: ["count==0"],
    unexpected_status: ["count==0"],
    local_login_allowed: [`count==${backendUrls.length * expectedAllowed}`],
    local_login_rejected: [`count==${backendUrls.length * expectedRejected}`],
  },
};

export default function verifyPerProcessLoginFallback() {
  const body = JSON.stringify({
    username: `s4d-${RUN_ID}-missing-login-user`,
    password: "stage4d-invalid-credential",
  });
  const params = { headers: { "Content-Type": "application/json" } };

  for (const backendUrl of backendUrls) {
    for (let index = 0; index < expectedAllowed + expectedRejected; index += 1) {
      const response = http.post(`${backendUrl}/api/v1/auth/login`, body, params);
      if (index < expectedAllowed && response.status === 401) {
        localLoginAllowed.add(1);
      } else if (
        index >= expectedAllowed &&
        response.status === 429 &&
        /^[1-9][0-9]*$/.test(response.headers["Retry-After"] || "")
      ) {
        localLoginRejected.add(1);
      } else {
        businessChecksFailed.add(1);
        unexpectedStatus.add(1);
      }
    }
  }
}

export function handleSummary(data) {
  return writeSummary(data, "outage-login-fallback");
}
