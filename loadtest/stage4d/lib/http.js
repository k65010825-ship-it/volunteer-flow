import { Counter } from "k6/metrics";

export const businessChecksFailed = new Counter("business_checks_failed");
export const unexpectedStatus = new Counter("unexpected_status");
export const rateLimitAllowed = new Counter("rate_limit_allowed");
export const rateLimitRejected = new Counter("rate_limit_rejected");

export function authHeaders(token) {
  if (typeof token !== "string" || token === "") {
    throw new Error("a non-empty access token is required");
  }
  return {
    Authorization: `Bearer ${token}`,
    "Content-Type": "application/json",
  };
}

export function recordExpectedStatus(response, expectedStatus) {
  const accepted = response.status === expectedStatus;
  if (!accepted) {
    businessChecksFailed.add(1);
    unexpectedStatus.add(1);
  }
  return accepted;
}
