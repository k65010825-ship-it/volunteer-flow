const UUID_PATTERN =
  /^[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}$/;

export function requiredEnv(name) {
  const value = __ENV[name];
  if (value === undefined || value.trim() === "") {
    throw new Error(`${name} is required`);
  }
  return value.trim();
}

export const BASE_URL = requiredEnv("BASE_URL").replace(/\/$/, "");
export const RUN_ID = requiredEnv("RUN_ID");
export const DATASET = requiredEnv("DATASET");
export const MANIFEST_PATH = requiredEnv("MANIFEST_PATH");
export const TOKENS_PATH = requiredEnv("TOKENS_PATH");

if (!/^https?:\/\//.test(BASE_URL) || /\s/.test(BASE_URL)) {
  throw new Error("BASE_URL must be an absolute HTTP(S) URL");
}
if (!UUID_PATTERN.test(RUN_ID)) {
  throw new Error("RUN_ID must be a canonical lowercase UUID");
}
if (DATASET !== "normal" && DATASET !== "outage") {
  throw new Error("DATASET must be normal or outage");
}

const manifestDocument = open(MANIFEST_PATH);
const tokensDocument = open(TOKENS_PATH);

function parseJson(document, description) {
  try {
    return JSON.parse(document);
  } catch (_) {
    throw new Error(`${description} must contain valid JSON`);
  }
}

function decimalId(value, description) {
  if (typeof value !== "string" || !/^[1-9][0-9]*$/.test(value)) {
    throw new Error(`${description} must be a positive decimal string`);
  }
  return value;
}

export function loadManifest(path, runId, dataset) {
  if (path !== MANIFEST_PATH || runId !== RUN_ID || dataset !== DATASET) {
    throw new Error("manifest arguments must match validated environment inputs");
  }
  const manifest = parseJson(manifestDocument, "manifest");
  if (manifest.runId !== RUN_ID) {
    throw new Error("manifest run ID does not match RUN_ID");
  }
  if (manifest.dataset !== DATASET) {
    throw new Error("manifest dataset does not match DATASET");
  }
  decimalId(manifest.organizationId, "manifest organizationId");
  decimalId(manifest.activityId, "manifest activityId");
  decimalId(manifest.positionId, "manifest positionId");
  decimalId(manifest.checkinSessionId, "manifest checkinSessionId");
  if (!Array.isArray(manifest.members) || manifest.members.length !== 100) {
    throw new Error("manifest must contain exactly 100 members");
  }
  const userIds = new Set();
  for (const member of manifest.members) {
    const userId = decimalId(member.userId, "manifest member userId");
    if (userIds.has(userId)) {
      throw new Error("manifest member user IDs must be unique");
    }
    userIds.add(userId);
  }
  return manifest;
}

export function loadTokens(path, manifest) {
  if (path !== TOKENS_PATH) {
    throw new Error("token path must match TOKENS_PATH");
  }
  const tokens = parseJson(tokensDocument, "tokens");
  if (!Array.isArray(tokens) || tokens.length !== manifest.members.length) {
    throw new Error("token user IDs must exactly match manifest member IDs");
  }
  const expectedIds = new Set(manifest.members.map((member) => member.userId));
  const tokenByUserId = {};
  for (const token of tokens) {
    const userId = decimalId(token.userId, "token userId");
    if (
      !expectedIds.has(userId) ||
      Object.prototype.hasOwnProperty.call(tokenByUserId, userId) ||
      typeof token.accessToken !== "string" ||
      token.accessToken.trim() === ""
    ) {
      throw new Error("token user IDs must exactly match manifest member IDs");
    }
    tokenByUserId[userId] = token.accessToken;
  }
  if (Object.keys(tokenByUserId).length !== expectedIds.size) {
    throw new Error("token user IDs must exactly match manifest member IDs");
  }
  return tokenByUserId;
}

export function writeSummary(data, scenario) {
  const metrics = {};
  for (const [name, metric] of Object.entries(data.metrics)) {
    metrics[name] = {
      type: metric.type,
      contains: metric.contains,
      values: metric.values,
    };
  }
  return {
    [`/output/${scenario}-${RUN_ID}-${DATASET}-summary.json`]: JSON.stringify(
      { scenario, runId: RUN_ID, dataset: DATASET, metrics },
      null,
      2,
    ),
  };
}
