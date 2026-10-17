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
  void readmeStatesPreparedNotPassedAndLinksTheRunbook() throws IOException {
    String readme = read("README.md");

    assertThat(readme)
        .contains("阶段 4D：双后端、Nginx、隔离夹具、k6 与远程执行源码已准备")
        .contains("尚未在虚拟机完成真实运行")
        .contains("docs/operations/stage-4d-live-load-test.md")
        .contains("docs/reports/stage-4d-report-template.md")
        .contains("不得填写吞吐量或延迟")
        .doesNotContain("阶段 4D 已通过")
        .doesNotContain("Stage 4D 已通过");
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
