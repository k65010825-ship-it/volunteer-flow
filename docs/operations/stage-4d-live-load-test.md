# 阶段 4D 双后端部署与真实压测操作手册

本文用于在一台可用的 Linux 虚拟机上运行 VolunteerFlow 的双后端、Nginx、Redis 正常/故障和 k6 验收。当前仓库仅完成执行源码与自动化契约；在 evidence 产物通过检查前，不能写成“阶段 4D 已通过”。

压测器、两个后端、Nginx、MySQL 和 Redis 位于同一台虚拟机或同一局域网时，得到的是同一台虚拟机上的工程基线，不能代表生产容量，也不能外推互联网延迟。

## 前置条件

- Windows 开发机安装 Java 17、Git、Windows OpenSSH 的 `ssh`、`scp` 和 `ssh-keygen`。
- 当前分支必须是 `feat/stage-4d`，所有源码已经提交，`git status --short` 为空。
- Linux 虚拟机安装 Docker Engine、Docker Compose v2、Bash、curl、timeout，并至少保留 5 GiB 可用空间。
- 虚拟机上的既有 MySQL 与 Redis 正常运行，且它们的地址和宿主机映射端口可从 Stage 4D 后端容器访问。
- 公开端口（默认 8088）未被其他服务占用。
- 从 `deploy/stage4d/.env.example` 复制出被 Git 忽略的 `deploy/stage4d/.env`，逐项替换占位值。
- 恢复后的演示数据库已经迁移到 V9，因此本次必须保持 `SPRING_FLYWAY_ENABLED=false`。这不是新数据库的通用配置。

不要停止、重启或重建现有 MySQL/Redis 容器。正常场景复用既有 Redis；故障场景只把本轮两个 Stage 4D 后端重建为连接不可达的 `127.0.0.1:6379`，不会停止 Redis，也不会改变 Redis 容器。

## 1. 准备一次性 SSH 密钥

在仓库根目录执行：

```powershell
$result = .\deploy\stage4d\bootstrap-temporary-ssh.ps1 `
  -VmHost '<VM_HOST>' `
  -SshPort 22 `
  -SshUser root
$result
```

脚本会创建新的 Ed25519 密钥，并打开一个具有可见的密码提示的 SSH 进程。密码只在 OpenSSH 窗口中输入，不得写进命令、脚本、环境文件或聊天记录。记录输出中的 `IdentityFile=...`；后续命令用它替换 `<IDENTITY_FILE>`。

脚本只向 `~/.ssh/authorized_keys` 追加一条带唯一 `volunteerflow-stage4d-<uuid>` 注释的公钥行，并使用 `BatchMode=yes` 验证密钥登录。

## 2. 启动自动验收

```powershell
.\deploy\stage4d\run-stage4d.ps1 `
  -VmHost '<VM_HOST>' `
  -SshPort 22 `
  -SshUser root `
  -IdentityFile '<IDENTITY_FILE>' `
  -ComposeEnvFile '.\deploy\stage4d\.env'
```

不传 `-RunId` 时，脚本生成规范的小写 UUID。每次重跑使用新的 runId，不覆盖旧证据，也不复用旧夹具身份。远端根目录固定为 `/opt/volunteerflow-stage4d/<runId>`，本地证据写入 `output/stage4d/<runId>/`。

本地脚本在第一次 SSH 前完成以下检查：

1. 环境文件和私钥/公钥文件存在，必需配置没有占位符。
2. Flyway 对恢复库保持关闭。
3. 当前分支是 `feat/stage-4d`，工作树干净且 HEAD 可解析。
4. 使用 `git archive` 打包已提交内容，不上传 `.git`、忽略文件或工作区临时改动。
5. 生成只用于本轮的随机 `RATE_LIMIT_KEY_SECRET`，写入受保护的临时环境文件，不输出其值。

## 3. 自动执行顺序与验收点

远端脚本按以下顺序失败关闭：

| 顺序 | 动作 | 主要 evidence |
|---|---|---|
| 1 | Docker、Compose、磁盘、端口、MySQL、Redis preflight | `preflight.json`、`docker-compose-version.txt` |
| 2 | 生成不展开密钥的 Compose 配置并构建镜像 | `compose-config.yml`、`build.log` |
| 3 | 启动 `backend-a`、`backend-b` 与 Nginx，等待 readiness | `readiness-initial.json` |
| 4 | 创建 normal 隔离夹具，100 VU 同步报名并验证 20/80 | `k6-registration-normal.log`、`registration-<runId>-normal-summary.json`、`verification-normal.json` |
| 5 | 等待限流窗口后检查 401、允许次数、429 与 `Retry-After` | `k6-rate-limit-normal.log`、`rate-limit-<runId>-normal-summary.json` |
| 6 | 创建 outage 夹具，只把本轮后端 Redis 地址改为 `127.0.0.1` | `redis-outage.log` |
| 7 | Redis 不可达时再次执行 100 VU 报名和数据库事实验证 | `k6-registration-outage.log`、`verification-outage.json` |
| 8 | 恢复真实 Redis 地址、重建本轮后端并执行恢复读取 | `redis-restore.log`、`k6-recovery-outage.log`、`readiness-recovered.json` |
| 9 | 证明 Nginx 实际访问两个 upstream | `nginx-upstream.log`、`upstream-addresses.txt` |
| 10 | 保存容器日志，按 outage→normal 清理夹具并比对基线 | `container.log`、`fixture-cleanup-outage.log`、`fixture-cleanup-normal.log`、`fixture-assert-snapshot-*.log` |
| 11 | 成功时只移除本轮 Compose 栈 | `stack-down.log` |

业务事实必须是 100 个不同成员、20 个 `CONFIRMED`、80 个 `WAITLISTED`、候补序号 1–80、下一候补序号 81。k6 的业务阈值只判断意外状态与业务失败为 0；延迟仅记录，不预设或伪造 SLA。

## 4. 证据与状态判定

先查看：

```powershell
$run = '<runId>'
Get-Content ".\output\stage4d\$run\run-status.json"
Get-Content ".\output\stage4d\$run\remote-run-status.json"
```

只有下列条件同时成立，才可以把本轮写为通过：

- `primaryStatus`、`recoveryStatus`、`cleanupStatus`、`evidenceDownloadStatus`、`keyRemovalStatus` 都是 `success`。
- normal 与 outage 的 verification 都是严格的 20/80，且无重复用户或候补序号。
- k6 summary 中 `business_checks_failed` 和 `unexpected_status` 为 0。
- upstream evidence 同时包含 `backend-a` 与 `backend-b` 对应的地址。
- cleanup 的 `assert-snapshot` 成功，既有数据库指纹没有变化。
- 日志和报告不包含访问令牌、Authorization、Cookie、数据库密码或密钥。

若任一项失败，保留“失败”结论。恢复成功不能覆盖主流程失败，清理成功也不能覆盖恢复失败。

## 5. 一次性密钥移除

远端目录创建成功后，`run-stage4d.ps1` 会在证据下载后调用 `remove-temporary-ssh.ps1`。该脚本只过滤完整匹配的公钥行，验证该行已经消失后才删除本地私钥和 `.pub` 文件。

如果本地预检在首次 SSH 前失败，自动移除不会运行，需要手动执行：

```powershell
.\deploy\stage4d\remove-temporary-ssh.ps1 `
  -VmHost '<VM_HOST>' `
  -SshPort 22 `
  -SshUser root `
  -IdentityFile '<IDENTITY_FILE>'
```

不得用覆盖整个 `authorized_keys`、删除该文件或按注释模糊匹配的方式代替。

## 失败后的手动恢复

自动执行失败时，本地会先尝试下载 evidence，再移除一次性密钥。远端失败目录不会自动删除；受保护的环境文件保留为 `600` 权限，供恢复使用。若密钥已移除，先重新执行第 1 节，取得新的临时密钥，再登录：

```powershell
ssh -p 22 -i '<NEW_IDENTITY_FILE>' root@'<VM_HOST>'
```

在虚拟机中用失败轮次的 runId 执行：

```bash
export STAGE4D_RUN_ID=<runId>
export STAGE4D_OUTPUT_DIR="/opt/volunteerflow-stage4d/<runId>/output"
export STAGE4D_DATASET=outage
export STAGE4D_FIXTURE_ACTION=cleanup
ROOT="/opt/volunteerflow-stage4d/<runId>"
PROJECT="vf4d${STAGE4D_RUN_ID//-/}"
COMPOSE="$ROOT/repo/deploy/stage4d/docker-compose.yml"
ENV_FILE="$ROOT/stage4d.env"

# 环境文件保存的是真实 Redis 地址；只重建本轮两个后端。
docker compose --project-name "$PROJECT" --file "$COMPOSE" --env-file "$ENV_FILE" \
  up --detach --force-recreate backend-a backend-b
curl --fail "http://127.0.0.1:8088/actuator/health/readiness"

# 只处理带本轮 snapshot 标记的数据集，顺序必须 outage 后 normal。
for dataset in outage normal; do
  if test -f "$ROOT/snapshot-$dataset"; then
    export STAGE4D_DATASET="$dataset"
    export STAGE4D_FIXTURE_ACTION=cleanup
    docker compose --project-name "$PROJECT" --file "$COMPOSE" --env-file "$ENV_FILE" \
      --profile tools run --rm fixture
    export STAGE4D_FIXTURE_ACTION=assert-snapshot
    docker compose --project-name "$PROJECT" --file "$COMPOSE" --env-file "$ENV_FILE" \
      --profile tools run --rm fixture
  fi
done

docker compose --project-name "$PROJECT" --file "$COMPOSE" --env-file "$ENV_FILE" \
  logs --no-color backend-a backend-b frontend-nginx > "$ROOT/evidence/manual-recovery.log" 2>&1
docker compose --project-name "$PROJECT" --file "$COMPOSE" --env-file "$ENV_FILE" \
  down --remove-orphans
rm -f "$ROOT/output"/tokens-*.json "$ENV_FILE"
```

如果 Redis 恢复或 readiness 仍失败，不要执行夹具清理；先保留数据库状态和 evidence 排查。不要对 MySQL 使用 `TRUNCATE`、全表删除或物理外键级联。恢复完成后再次精确移除临时 SSH 公钥。

## 重跑与报告

- 修复原因并提交新 commit；不要对脏工作树重跑。
- 每次重跑使用新的 runId 和新的一次性 SSH 密钥。
- 不覆盖失败轮次 evidence；报告同时记录失败轮次与最终通过轮次。
- 只从本轮 JSON、日志和数据库 verification 填写[报告模板](../reports/stage-4d-report-template.md)。
- 任何请求数、吞吐量、p50/p90/p95/p99/max 和错误率在真实 evidence 产生前都保持“待填写”。
