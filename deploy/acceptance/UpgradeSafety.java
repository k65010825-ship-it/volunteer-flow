import java.util.UUID;

/** Pure fail-closed rules shared by the one-off recovered-schema upgrade tooling. */
public final class UpgradeSafety {
  public static String cloneName(String runId) {
    UUID id = UUID.fromString(runId);
    if (!id.toString().equals(runId) || id.version() != 4) throw new IllegalArgumentException();
    return "vf_acceptance_" + runId.replace("-", "");
  }

  public static String cloneUrl(String original, String clone) {
    if (!clone.matches("vf_acceptance_[0-9a-f]{32}")
        || !original.matches("jdbc:mysql://[^/]+/volunteer_flow(?:\\?.*)?"))
      throw new IllegalArgumentException();
    return original.replaceFirst("/volunteer_flow(?=\\?|$)", "/" + clone);
  }

  public static void requireSafeDump(String sql) {
    if (sql == null
        || sql.isBlank()
        || sql.matches("(?s).*?(?im:^\\s*(?:USE\\s|CREATE\\s+DATABASE\\b)).*"))
      throw new IllegalArgumentException("Dump must not select or create a database");
  }

  public static boolean allowOriginal(
      boolean backupVerified,
      boolean rehearsalPassed,
      boolean sourceUnchanged,
      long otherSessions) {
    return backupVerified && rehearsalPassed && sourceUnchanged && otherSessions == 0;
  }
}
