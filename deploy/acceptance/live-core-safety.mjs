/** Fail closed before login/registration can create refresh sessions or business records. */
export function verifyAcceptanceIdentity(runId, identity) {
  if (
    !/^[0-9a-f]{8}-[0-9a-f]{4}-4[0-9a-f]{3}-[89ab][0-9a-f]{3}-[0-9a-f]{12}$/.test(
      runId,
    )
  ) {
    throw new Error("Invalid isolated acceptance run identity");
  }
  const database = `vf_acceptance_${runId.replaceAll("-", "")}`;
  if (
    !identity ||
    identity.database !== database ||
    identity.maintenanceDisabled !== true ||
    identity.consumerDisabled !== true
  ) {
    throw new Error(
      "Backend isolation identity did not match; no acceptance writes are permitted",
    );
  }
}
