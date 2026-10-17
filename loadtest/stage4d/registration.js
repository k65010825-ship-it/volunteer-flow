import http from "k6/http";
import { sleep } from "k6";
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
  scenarios: {
    registration: {
      executor: "per-vu-iterations",
      vus: 100,
      iterations: 1,
      maxDuration: "60s",
    },
  },
  thresholds: {
    business_checks_failed: ["count==0"],
    unexpected_status: ["count==0"],
  },
};

export function setup() {
  return { startAt: Date.now() + 5000 };
}

function waitForStart(startAt) {
  while (Date.now() < startAt) {
    sleep(0.01);
  }
}

export default function runRegistration(data) {
  waitForStart(data.startAt);
  const member = manifest.members[__VU - 1];
  if (!member) {
    businessChecksFailed.add(1);
    unexpectedStatus.add(1);
    return;
  }

  const response = http.post(
    `${BASE_URL}/api/v1/activities/${manifest.activityId}/registrations`,
    JSON.stringify({ positionId: manifest.positionId, answers: [] }),
    {
      headers: authHeaders(tokenByUserId[member.userId]),
      tags: { operation: "stage4d_registration", dataset: DATASET },
    },
  );
  if (response.status !== 201) {
    businessChecksFailed.add(1);
    unexpectedStatus.add(1);
  }
}

export function handleSummary(data) {
  return writeSummary(data, "registration");
}
