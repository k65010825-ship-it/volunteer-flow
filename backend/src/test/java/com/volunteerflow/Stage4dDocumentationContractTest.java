package com.volunteerflow;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import org.junit.jupiter.api.Test;

class Stage4dDocumentationContractTest {

  private static final Path REPOSITORY_ROOT = locateRepositoryRoot();

  @Test
  void runbookCoversTheCompleteSafeOperatorLifecycle() throws IOException {
    String guide = read("docs/operations/stage-4d-live-load-test.md");

    assertThat(guide)
        .contains("## 前置条件")
        .contains("可见的密码提示")
        .contains("bootstrap-temporary-ssh.ps1")
        .contains("run-stage4d.ps1")
        .contains("preflight.json")
        .contains("registration-normal")
        .contains("rate-limit-normal")
        .contains("registration-outage")
        .contains("recovery-outage")
        .contains("nginx-upstream")
        .contains("fixture-cleanup")
        .contains("stack-down.log")
        .contains("remove-temporary-ssh.ps1")
        .contains("output/stage4d/<runId>/")
        .contains("/opt/volunteerflow-stage4d/<runId>")
        .contains("## 失败后的手动恢复")
        .contains("STAGE4D_RUN_ID=<runId>")
        .contains("docker compose")
        .contains("cleanup")
        .contains("assert-snapshot")
        .contains("SPRING_FLYWAY_ENABLED=false")
        .contains("vm-os-release.txt")
        .contains("service-containers.jsonl")
        .contains("mysql-version.txt")
        .contains("redis-version.txt")
        .contains("outage-login-fallback-<runId>-outage-summary.json")
        .contains("outage-dynamic-checkin-<runId>-outage-summary.json")
        .contains("recovered-shared-rate-limit-<runId>-outage-summary.json")
        .contains("evidence-secret-scan.txt")
        .contains("不要停止、重启或重建现有 MySQL/Redis 容器")
        .contains("127.0.0.1")
        .contains("不会停止 Redis")
        .contains("同一台虚拟机")
        .contains("不能代表生产容量")
        .contains("每次重跑使用新的 runId");
  }

  @Test
  void reportTemplateRequiresFactsMetricsFailuresAndLimitations() throws IOException {
    String template = read("docs/reports/stage-4d-report-template.md");

    assertThat(template)
        .contains("Git commit")
        .contains("runId")
        .contains("CPU")
        .contains("内存")
        .contains("Docker")
        .contains("Compose")
        .contains("backend-a")
        .contains("backend-b")
        .contains("20 CONFIRMED")
        .contains("80 WAITLISTED")
        .contains("请求数")
        .contains("吞吐量")
        .contains("p50")
        .contains("p90")
        .contains("p95")
        .contains("p99")
        .contains("max")
        .contains("错误")
        .contains("Redis 恢复")
        .contains("夹具清理")
        .contains("同机压测限制")
        .contains("待填写")
        .contains("只允许从本次 evidence 文件填写")
        .doesNotContain("1000 req/s")
        .doesNotContain("0% 错误率");
  }

  @Test
  void readmePublishesTheMeasuredStage4dCandidateAndItsOpenGates() throws IOException {
    String readme = read("README.md");
    String report = read("docs/reports/2026-10-17-stage-4d-live-load-test.md");

    assertThat(readme)
        .contains("646f807f-94a9-4a41-94cd-a9480cd12bd9")
        .contains("正常与 Redis 故障场景均为 20 个正式名额、80 个候补")
        .contains("同机压测基线")
        .contains("阶段 4D 仍为部分完成")
        .contains("docs/operations/stage-4d-live-load-test.md")
        .contains("docs/reports/2026-10-17-stage-4d-live-load-test.md")
        .doesNotContain("尚未在虚拟机完成真实运行");

    assertThat(report)
        .contains("19338ad3606bc2abaabec66a427ecd7f4a7272cf")
        .contains("646f807f-94a9-4a41-94cd-a9480cd12bd9")
        .contains("normal")
        .contains("outage")
        .contains("rate_limit_allowed=3")
        .contains("Redis 恢复")
        .contains("夹具清理与指纹恢复")
        .contains("远程运行目录移除")
        .contains("一次性 SSH 公私钥移除")
        .contains("同一台虚拟机")
        .contains("部分通过")
        .contains("恢复后共享限流复验")
        .contains("登录本机降级")
        .contains("DYNAMIC_CHECKIN_UNAVAILABLE")
        .contains("不能外推为生产容量");
  }

  private static String read(String relativePath) throws IOException {
    return Files.readString(REPOSITORY_ROOT.resolve(relativePath));
  }

  private static Path locateRepositoryRoot() {
    Path workingDirectory = Path.of(System.getProperty("user.dir"));
    if (Files.isRegularFile(workingDirectory.resolve("backend/pom.xml"))) {
      return workingDirectory;
    }
    if (Files.isRegularFile(workingDirectory.resolve("pom.xml"))) {
      return workingDirectory.getParent();
    }
    throw new IllegalStateException("Cannot locate repository root from " + workingDirectory);
  }
}
