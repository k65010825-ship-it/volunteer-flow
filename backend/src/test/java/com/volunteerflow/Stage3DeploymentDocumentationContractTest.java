package com.volunteerflow;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import org.junit.jupiter.api.Test;

class Stage3DeploymentDocumentationContractTest {

  private static final Path REPOSITORY_ROOT = locateRepositoryRoot();

  @Test
  void runbookDocumentsSecretSafeExternalDatabaseDeploymentAndApplicationOnlyTeardown()
      throws IOException {
    String runbook = Files.readString(REPOSITORY_ROOT.resolve("docs/operations/stage-3-demo-deployment.md"));

    assertThat(runbook)
        .contains("Copy-Item deploy/.env.example deploy/.env")
        .contains("单引号")
        .contains("DB_PORT")
        .contains("docker compose --file deploy/docker-compose.yml --env-file deploy/.env config --quiet")
        .contains("docker compose --file deploy/docker-compose.yml --env-file deploy/.env up --build --detach")
        .contains("docker compose --file deploy/docker-compose.yml --env-file deploy/.env logs")
        .contains(".\\deploy\\smoke-test.ps1 -BaseUrl \"http://localhost:8088\"")
        .contains(".\\deploy\\run-stage3-e2e.ps1 -BaseUrl \"http://localhost:8088\"")
        .contains("docker compose --file deploy/docker-compose.yml --env-file deploy/.env down")
        .contains("不会停止、删除、重启或管理外部 MySQL")
        .contains("不得删除或修改 Flyway 历史")
        .doesNotContain("docker compose config\n")
        .doesNotContain("docker compose config\r\n");
  }

  @Test
  void readmeLinksRunbookAndKeepsUnverifiedDeploymentGatesExplicit() throws IOException {
    String readme = Files.readString(REPOSITORY_ROOT.resolve("README.md"));

    assertThat(readme)
        .contains("[阶段 3 演示部署操作手册](docs/operations/stage-3-demo-deployment.md)")
        .contains("Docker 镜像构建、Nginx 配置实测、Compose 启动、公开入口冒烟和浏览器 E2E 仍待在可用 Docker 主机执行")
        .contains("不代表真实用户试用、性能测试、Redis 或 Kafka 已完成");
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
