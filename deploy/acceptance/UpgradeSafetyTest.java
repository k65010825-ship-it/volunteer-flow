public final class UpgradeSafetyTest {
  public static void main(String[] args) {
    String run = "4f01c0c8-ef0e-47ec-bfb3-c7fc877d0aee";
    String clone = UpgradeSafety.cloneName(run);
    require(
        UpgradeSafety.cloneUrl("jdbc:mysql://db:3307/volunteer_flow?a=b", clone)
            .equals("jdbc:mysql://db:3307/" + clone + "?a=b"));
    rejects(() -> UpgradeSafety.cloneUrl("jdbc:mysql://db:3307/other", clone));
    rejects(() -> UpgradeSafety.cloneUrl("jdbc:mysql://db:3307/volunteer_flow", "volunteer_flow"));
    rejects(() -> UpgradeSafety.cloneName("../../original"));
    UpgradeSafety.requireSafeDump("CREATE TABLE x (id BIGINT);\nINSERT INTO x VALUES (1);");
    rejects(
        () -> UpgradeSafety.requireSafeDump("USE volunteer_flow;\nCREATE TABLE x (id BIGINT);"));
    rejects(() -> UpgradeSafety.requireSafeDump("\nCREATE DATABASE volunteer_flow;"));
    require(UpgradeSafety.allowOriginal(true, true, true, 0));
    require(!UpgradeSafety.allowOriginal(false, true, true, 0));
    require(!UpgradeSafety.allowOriginal(true, false, true, 0));
    require(!UpgradeSafety.allowOriginal(true, true, false, 0));
    require(!UpgradeSafety.allowOriginal(true, true, true, 1));
    System.out.println("UPGRADE_SAFETY_TESTS=12_PASSED");
  }

  static void require(boolean value) {
    if (!value) throw new AssertionError();
  }

  static void rejects(Runnable action) {
    try {
      action.run();
    } catch (IllegalArgumentException expected) {
      return;
    }
    throw new AssertionError("Unsafe upgrade input was accepted");
  }
}
