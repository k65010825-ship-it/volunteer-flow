public final class AcceptanceDatabaseToolTest {
  public static void main(String[] args) {
    String name = "vf_acceptance_0123456789abcdef0123456789abcdef";
    require(AcceptanceDatabaseTool.validDatabase(name), "UUID database accepted");
    for (String rejected :
        new String[] {
          "volunteer_flow",
          "vf_acceptance_test",
          name + ";DROP DATABASE volunteer_flow",
          "mysql",
          ""
        }) require(!AcceptanceDatabaseTool.validDatabase(rejected), "unsafe database rejected");
    require(
        AcceptanceDatabaseTool.isolatedUrl("jdbc:mysql://db:3307/volunteer_flow?a=b", name)
            .equals("jdbc:mysql://db:3307/" + name + "?a=b"),
        "query preserved while database replaced");
    require(
        AcceptanceDatabaseTool.isolatedUrl("jdbc:mysql://db:3307/other", name) == null,
        "unexpected original schema rejected");
    require(
        AcceptanceDatabaseTool.isolatedUrl("jdbc:mysql://db:3307/volunteer_flow", "volunteer_flow")
            == null,
        "original database cannot be target");
    System.out.println("AcceptanceDatabaseToolTest: 9 assertions passed");
  }

  static void require(boolean condition, String label) {
    if (!condition) throw new AssertionError(label);
  }
}
