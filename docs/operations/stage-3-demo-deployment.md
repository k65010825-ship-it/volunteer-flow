# VolunteerFlow 阶段 3 演示部署操作手册

本文只描述 `backend` 与 `frontend-nginx` 两个应用服务的演示部署。MySQL 是虚拟机上已经存在、由用户单独维护的外部服务，不属于本 Compose 项目。

## 1. 当前验证边界

截至 2026-10-15，仓库已经具备 Java 17 后端镜像、Vue/Nginx 前端镜像、两服务 Compose、公开入口冒烟脚本和 Playwright 场景的源代码。当前开发主机没有 Docker CLI，也没有可直接使用的 Nginx/Compose 运行环境，因此以下项目仍待在可用 Docker 主机执行：

- 后端、前端镜像的真实构建；
- 容器内 Java 17 与 Nginx 配置检查；
- Compose 启动和数据库就绪探针；
- 经 Nginx 公开入口执行冒烟测试；
- Playwright 完整流程及连续两轮无状态泄漏验证。

所以本文不是“部署成功报告”。已完成的本地测试结果见 README，不能外推为真实用户试用、性能测试、Redis 或 Kafka 已完成。

## 2. 前置条件

部署主机需要：

- Windows PowerShell 5.1 或 PowerShell 7；当前 E2E wrapper 调用 `mvnw.cmd`，不支持直接从 Linux shell 运行；
- Java 17，供 Maven fixture 的 seed/cleanup 测试进程使用；
- Node.js 22.13.0 或更高版本与 npm，并已在 `frontend` 执行 `npm ci`；
- Chromium；首次验收前在 `frontend` 执行 `npx playwright install chromium`；
- Docker Engine 或 Docker Desktop，以及 Docker Compose v2；
- 能拉取 Eclipse Temurin 17、Node 22 和 Nginx 1.27 基础镜像；
- 能从 `backend` 容器访问外部 MySQL 的网络路径；
- 目标 MySQL 已创建 VolunteerFlow 数据库和最小权限账号；
- 目标库结构与当前应用兼容。当前仓库包含 V1 至 V9，迁移只允许向前追加；共享数据库在执行任何迁移前必须先检查 `flyway_schema_history` 和备份策略。

从 Compose 容器视角，`localhost` 指向容器自身，通常不能用于连接宿主机或远程虚拟机 MySQL。`DB_HOST` 应填写容器可达的虚拟机地址、宿主机地址或已经接入同一可达网络的 DNS 名；`DB_PORT` 必须替换为该 MySQL 对容器实际开放的端口，不能默认照抄 `3306`。同时检查 MySQL 监听地址、用户授权范围和防火墙。

## 3. 创建本地部署配置

在仓库根目录执行：

```powershell
Copy-Item deploy/.env.example deploy/.env
git check-ignore deploy/.env
```

第二条命令应输出 `deploy/.env`。如果没有输出，先停止，不要填写凭据，也不要提交文件。

编辑 `deploy/.env`，替换全部必填占位符：

- `DB_HOST`：容器可达的外部 MySQL 地址或 DNS 名；
- `DB_PORT`：外部 MySQL 对部署主机/容器开放的真实端口；
- `DB_NAME`、`DB_USERNAME`、`DB_PASSWORD`：目标库连接信息；
- `JWT_SECRET`：至少 32 个随机字节的标准 Base64 编码；32 字节通常编码为 44 个字符；
- `SPRING_FLYWAY_ENABLED`：本模板针对已经完成 V1 至 V9 迁移的恢复库，预检通过后保持 `false`；
- `APP_PORT`：Nginx 对外端口，默认 `8088`；
- `BOOTSTRAP_ADMIN_*`：仅在确实需要初始化管理员时填写，否则保持为空。

自由文本、密码和密钥应保留模板中的单引号。这样 `$` 等特殊字符不会再次被 Compose 插值。不要把真实值写入 README、命令历史、截图、工单或 Git；也不要执行会把完整渲染结果打印到终端的 `docker compose config`。

环境文件解析器只支持整行注释，不支持行尾内联注释；需要包含空格、`#`、`$` 等字符的值必须使用单引号。后续所有 Compose 操作都必须通过 `run-stage3-stack.ps1`：即使调用者进程已经设置同名变量，该脚本也会临时使用同一份 `deploy/.env` 覆盖受管变量，并在命令结束或失败后恢复调用者环境。这样 Compose 与 Maven fixture 使用同一份 `deploy/.env`，避免 shell 环境变量优先级导致二者连接不同数据库。

本模板中的 `SPRING_FLYWAY_ENABLED=false` 只适用于已经完成 V1 至 V9 迁移的恢复库。启动前必须核对目标库结构、备份状态与 `flyway_schema_history`，确认现有结构确实与当前应用兼容；不得删除、修改或伪造 Flyway 历史。全新数据库应按其独立建库与迁移策略决定是否启用 Flyway，不能照抄恢复库的 `false`。

## 4. 安全预检

只做静默配置校验，不输出渲染后的环境变量：

```powershell
.\deploy\run-stage3-stack.ps1 -Action Config -ComposeEnvFile "deploy/.env"
```

如需先确认数据库连通性，应使用部署环境已有的安全客户端，并只报告成功/失败；不要在命令行直接拼接密码。Compose 文件只声明 `backend` 和 `frontend-nginx`，没有 MySQL 服务、MySQL 数据卷或数据库端口映射。

## 5. 构建并启动应用栈

```powershell
.\deploy\run-stage3-stack.ps1 -Action Up -ComposeEnvFile "deploy/.env"
.\deploy\run-stage3-stack.ps1 -Action Ps -ComposeEnvFile "deploy/.env"
```

预期只看到两个应用服务：

- `backend`：不映射宿主机端口，由数据库就绪探针检查 `/actuator/health/readiness`；
- `frontend-nginx`：等待后端健康后启动，并把 `APP_PORT` 映射到容器 80 端口。

查看运行状态时限制日志范围，避免无关历史输出：

```powershell
.\deploy\run-stage3-stack.ps1 -Action Logs -Service backend -ComposeEnvFile "deploy/.env"
.\deploy\run-stage3-stack.ps1 -Action Logs -Service frontend-nginx -ComposeEnvFile "deploy/.env"
```

日志中不得出现数据库密码、JWT、Cookie、Authorization 头或一次性签到码。若后端不健康，优先检查数据库网络、账号权限、目标库结构以及后端日志中的完整异常链，不要通过删除迁移历史来绕过错误。

## 6. 健康检查、冒烟与浏览器验收

默认端口下执行：

```powershell
Invoke-RestMethod http://localhost:8088/actuator/health/readiness
.\deploy\smoke-test.ps1 -BaseUrl "http://localhost:8088"
```

就绪接口必须返回 `UP`；冒烟脚本会检查就绪接口、前端根路径和 `/notifications` 深链接的 SPA 回退。

Playwright 运行前还需要：

- 前端依赖已经通过 `npm ci` 安装；
- Chromium 已通过 `npx playwright install chromium` 安装；
- `deploy/.env` 存在且所有必填占位符已经替换；wrapper 会安全解析单引号值，并拒绝缺失项、占位符、无效端口或不能解码到至少 32 字节的 JWT Base64；
- Compose 与 Maven fixture 使用同一份 `deploy/.env`。wrapper 把其中的数据库、JWT 和 bootstrap 值仅注入 seed/cleanup 子进程，并在 `finally` 中恢复调用者原有环境；它不会打印这些值。
- Compose 后端与 Maven fixture 还会接收同一个 `SPRING_FLYWAY_ENABLED`；恢复库示例必须为 `false`，避免一个进程迁移而另一个进程跳过迁移。

执行完整场景：

```powershell
.\deploy\run-stage3-e2e.ps1 -BaseUrl "http://localhost:8088" -ComposeEnvFile "deploy/.env"
.\deploy\run-stage3-e2e.ps1 -BaseUrl "http://localhost:8088" -ComposeEnvFile "deploy/.env"
```

两轮都成功后，才能记录“公开 Nginx 浏览器流程通过”。脚本使用独立 UUID 生成 `s3e2e-` 数据，并在 `finally` 中精确清理；若主体流程和清理同时失败，必须检查两段输出，不得用 `TRUNCATE`、全表删除或模糊前缀删除补救。还要检查后端日志，确认浏览器进程持有的一次性签到码没有出现在日志中。

## 7. 停止应用栈

```powershell
.\deploy\run-stage3-stack.ps1 -Action Down -ComposeEnvFile "deploy/.env"
```

`down` 只停止并删除本 Compose 项目的 `backend`、`frontend-nginx` 容器和应用网络；它不会停止、删除、重启或管理外部 MySQL，也不会删除 MySQL 数据卷。当前 Compose 没有声明数据库服务和命名卷。

## 8. 应用回滚

回滚前先记录当前应用提交、镜像和错误证据，并确认工作区没有未保存修改。选择已经验证过的应用提交或镜像，仅重建/重启 `backend` 与 `frontend-nginx`：

```powershell
git rev-parse HEAD
git switch --detach <known-good-application-commit>
.\deploy\run-stage3-stack.ps1 -Action Up -ComposeEnvFile "deploy/.env"
.\deploy\smoke-test.ps1 -BaseUrl "http://localhost:8088"
```

如团队使用镜像仓库，应把 Compose 中的应用镜像切换到已验证标签后只重启这两个应用服务。不要把“应用版本回滚”扩展为数据库容器操作。

数据库迁移采用 forward-only 策略：通过新的修复迁移前向纠正。不得删除或修改 Flyway 历史，不得改写已经执行的 V1 至 V9，不得手工删除共享库表或数据来匹配旧应用。若旧应用与已迁移结构不兼容，应停止回滚并准备兼容性修复版本。

## 9. 发布记录最低要求

每次演示部署至少记录：

- 应用 Git 提交与镜像摘要；
- `config --quiet`、镜像构建、容器 Java/Nginx 检查的退出状态；
- `ps` 中仅有两个应用服务的证据；
- readiness、冒烟、两轮 Playwright 的结果；
- E2E 精确清理结果和签到码未进入日志的检查结果；
- 所有未通过或未执行的门禁。

记录中禁止包含 `.env` 内容、数据库凭据、JWT、会话信息或签到码。
