# 独立数据库真实后端联调

本手册用于个人资料、密码、草稿及报名候补签到主链的验收，不是生产部署。真实测试结果见 [2026-10-24 验收报告](../reports/2026-10-24-account-drafts-live-acceptance.md)。

## 安全边界

- 先取得数据库创建与测试数据写入授权。原 `volunteer_flow` 不迁移、不 repair、不清空，不重启已有 MySQL/Redis 容器。
- 只允许新建 `vf_acceptance_<32位UUID>` 库。新库从 V1 开始迁移；不能复制原库中的失败 Flyway 历史。
- `acceptance` profile 仍使用生产业务服务和 Transactional Outbox，不使用 `test` profile 或伪造时钟。
- 五类全局作业与 Kafka consumer 停用；默认开发/生产行为不变。Kafka 不可用时，通知投递、重试和恢复必须明确标记“未验证”。
- DataSource 注入 Flyway 前执行只读 `SELECT DATABASE()`，精确核对库名；禁止专用 Flyway 连接和 schema 覆盖，防止迁移绕过核验。
- 脚本在登录、注册前访问只读身份接口，核对本次 UUID 库及作业停用状态。接口只在 `acceptance` profile 允许匿名 GET；其他环境和所有匿名写入仍受保护。
- 新运行目录限制为当前 Windows 用户访问。包含密码的 YAML、JSON 和日志不得提交、打印或放进截图；截图只在密码为空时生成。

## 工具

| 文件 | 职责 |
|---|---|
| `deploy/acceptance/run-preflight.ps1` | 只读检查原库迁移状态、表字段及 Kafka 元数据；exit 0 仅表示诊断完成 |
| `AcceptanceDatabaseTool.java` | 创建一个尚不存在的 UUID 数据库，生成受限配置和凭据清单 |
| `AcceptanceRuntimeLauncher.java` | 校验隔离配置，生成独立 JWT 信任，复制最新 jar 后启动 loopback:8082 |
| `live-core.mjs` | 真实 Chrome 和 HTTP 验收，不拦截 API，不使用 mock 响应 |
| `AcceptanceEvidence.java` | 只读提取新库中的迁移、签到、审计及 Outbox 数量，不读取事件正文 |

## 执行顺序（Windows / Java 17）

下面的路径需替换为本机路径；不要把数据库密码作为命令行参数。已有运行不能重复 seed，重新验收使用新 UUID，或保留同一个独立库并让脚本创建新的一组带随机标识的数据。

1. 在仓库根目录运行 `./deploy/acceptance/run-preflight.ps1`，先确认原库状态。该操作不会创建数据库。
2. 创建本轮私密目录，并限制 ACL；不要直接复用已有普通输出目录：

   ```powershell
   $taskRunId = [guid]::NewGuid().ToString()
   $taskRuntime = Join-Path $env:TEMP "volunteerflow-acceptance-$taskRunId"
   New-Item -ItemType Directory -Path $taskRuntime
   icacls $taskRuntime /inheritance:r /grant:r "$($env:USERNAME):(OI)(CI)F"
   $taskYaml = Join-Path $env:USERPROFILE '.m2/repository/org/yaml/snakeyaml/2.4/snakeyaml-2.4.jar'
   $taskMysql = Join-Path $env:USERPROFILE '.m2/repository/com/mysql/mysql-connector-j/9.7.0/mysql-connector-j-9.7.0.jar'
   $taskClasspath = $taskYaml + [IO.Path]::PathSeparator + $taskMysql
   java --class-path $taskClasspath ./deploy/acceptance/AcceptanceDatabaseTool.java ./backend/config/application-local.yml $taskRuntime $taskRunId
   ```

   创建成功不代表应用或迁移已经通过；检查工具的固定状态输出与退出码。不要展示生成文件内容。

3. 在 `backend` 中构建最新 jar。部署脚本合约与容器集成测试需要各自的执行环境，不要把它们混同于普通回归：

   ```powershell
   ./mvnw.cmd clean package '-Dtest=*Test,*Tests,!Stage3E2eFixtureContractTest,!Stage3SmokeScriptContractTest,!Stage3StackLauncherContractTest,!Stage4dEnvironmentPreparationContractTest,!Stage4dReadinessScriptTest,!Stage4dRunnerContractTest,!Stage5RunnerContractTest'
   ```

4. 回到仓库根目录，确认 8082 无占用，然后启动仅本轮进程。启动器保留 PID 和私密日志，不影响 IDEA 的 8080：

   ```powershell
   java --class-path $taskYaml ./deploy/acceptance/AcceptanceRuntimeLauncher.java ./backend/target/volunteerflow-backend-0.0.1-SNAPSHOT.jar $taskRuntime
   ```

   先确认 `http://127.0.0.1:8082/api/v1/acceptance/identity` 返回本轮数据库，再继续。不要因启动失败把配置改回原库。

5. 在另一个终端的 `frontend` 目录启动独立前端：

   ```powershell
   $env:VOLUNTEERFLOW_API_TARGET = 'http://127.0.0.1:8082'
   npx vite --host 127.0.0.1 --port 5176 --strictPort
   ```

6. 回到仓库根目录执行真实联调，默认前端地址为 `http://127.0.0.1:5176`；Chrome 可通过 `CHROME_PATH` 指定：

   ```powershell
   node ./deploy/acceptance/live-core.mjs "$taskRuntime/credentials.json" "$taskRuntime/artifacts"
   java --class-path $taskClasspath ./deploy/acceptance/AcceptanceEvidence.java "$taskRuntime/application.yml" "$taskRuntime/artifacts/database-evidence.json"
   ```

   只在 `live-core-report.json` 为 `PASSED_CORE` 时称核心链路通过。`database-evidence.json` 中有 Outbox 行不代表通知送达；未启动 Kafka 中继和消费者时应为未验证。

7. 结束后仅停止本轮 5176 和私密目录 PID 对应、命令行确认为本轮 `acceptance-backend.jar` 的进程。保留独立库及非敏感证据以便复查，不执行宽泛删除。销毁新库需要明确目标并另行确认，绝不能删除原库或共享数据。
