# Stage 5 Kafka / Outbox 操作手册

当前交付是静态工具链，不是通过的实测报告；不代表 VM 容器、数据库迁移、停机恢复或原库指纹验收成功。
只有显式批准后运行下面的 live 流程，并通过全部数据库、指标、指纹、清理和下载门禁，才可发布实测结论。

## 范围与前置条件

- 分支 `feat/stage-5`，Java 17，全部修改已提交且工作树干净；本地需要 Git、Windows PowerShell 5.1（也可用 PowerShell 7）、OpenSSH 与 Python 3.8+。
- 本地工具使用共享的 `deploy/lib/native-tools.psm1` 解析为单一绝对路径：显式提供且验证的路径优先；否则优先 Windows 自带 OpenSSH、标准 Program Files Git，再按 PATH 顺序选择兼容程序。Git 要求 2+，Python 要求 3.8+；OpenSSH 检查二进制版本元数据（不等同签名验证）。不拼接多个 `ApplicationInfo.Source`，拒绝脚本 shim、WindowsApps 别名、非 Application 候选、缺失文件及不兼容版本，版本探测有 10 秒上限。
- Linux VM 需要 Bash 4.2+、GNU coreutils（timeout/sha256sum/realpath 等）、tar、Docker Engine / Compose v2、MySQL 8 的 `mysql` / `mysqldump` 客户端、`redis-cli`。最低版本只降到已实际测得目标运行时所属的 Bash 4.2 系列；NUL 分隔 argv 的兼容修复仍须在实际 4.2.46 上通过仓库行为 harness 后才能作为 live 验收依据，本地现代 Bash 通过不能替代该证据。每轮预检将非秘密的实际 `BASH_VERSION` 写入 `bash-version.txt`。不要求、不安装宿主 Python 或额外软件包。
- 此工具链只支持已批准的 root 运维方式：Windows runner 要求 `-SshUser root`，VM 门禁要求 `id -u=0`，其他账号直接拒绝，不能自行放宽。Python 容器固定 `65534:65534`；宿主只对精确的 private/evidence 目录设置该 UID/GID，run root 与未挂载的 `host-private/` 仍为 root/700。Linux DAC/容器权限效果必须在获批的 live 预检核实，Windows 离线合同不等同内核隔离实测。
- Python 驱动固定使用官方 `python:3.13.12-slim-bookworm` 对应不可变 index digest `sha256:a58daefb915e1e03ad48f3ca4df8832065412c5c35cacb9d39f4229184de12b6`（2026-10-20 从 Docker 官方 registry HEAD 验证）。先检查 Docker/Compose，再检查该 digest 的本地镜像；缺失时最多 180 秒拉取，失败明确停止，可由获批运维预先导入完全相同的 digest，不能临时换标签。
- 每次 Python 操作 deadline 为 600 秒（TERM/KILL 宽限 10 秒，精确容器检查/清理另有 15/20 秒上限），包括读帧、解码、验证、执行和响应写入。读帧另有最多 10 秒期限；宿主工具为 1..300 秒且裁剪到剩余预算。独立容器 `--network none`、只读根文件系统、drop ALL capabilities、no-new-privileges、1 CPU / 768 MiB / 64 PID、固定 `65534:65534` 与 umask 077。镜像 ID/RepoDigests/双方 UID 记入 `python-runtime.txt`、驱动哈希记入 `acceptance.sha256`；每次记录精确容器清理状态。CID 回执不在容器挂载中；没有 socket、宿主网络、特权模式或全仓库挂载。
- 恰好 4 个挂载：单个已校验 SHA-256 的 driver RO、root/444 的本轮 `container.env` 副本 RO、private RW、evidence RW。dotenv 的宿主父目录仍为 root/700。`host-private/` 的 MySQL option file、空 HOME、管道工作文件和客户端 stderr **从不挂载**。原始 SSH 上传 dotenv 为 600，原始 LF archive-member hash 每次启动前复核。
- **没有文件 RPC**：请求与响应只走宿主创建的双向内核 pipe（Docker stdin/stdout，日志驱动 none），不打开容器创建的 request/stdin/stdout/stderr/response 路径。7 字段 ASCII 帧固定 `VF5|1|requestId|action|deadline/status|base64-argv/output|base64-stdin/error`；ID 从 00000001 严格递增，单线程顺序执行，显式 done 结束，拒绝重放、重复、未知版本/动作、额外字段、NUL、EOF/半帧与非规范 base64。请求帧 ≤196608 字节，解码 argv ≤8192、stdin ≤128 KiB、argv ≤128 项；普通 stdout ≤16 MiB、mysqldump ≤64 MiB、stderr 独立 ≤64 KiB，超限/崩溃/对端不读均失败退出。响应先完整构造于宿主私有文件，再有界写 pipe；Python 对响应 ID/模式/编码/大小和整次读写 deadline 也独立检查。
- Bash 白名单检查本轮 Compose scope、工具/参数及 Redis 前缀，不执行 eval/source/任意 shell；宿主 MySQL 配置仅由可信 dotenv 创建，使用 `--defaults-file` 与空 HOME，不再读取容器可写 option file。查询使用非交互 `--binary-mode=1 --local-infile=0`，执行前拒绝 NUL、超限、所有反斜杠、source/system 等客户端命令及文件导入导出语句；扫描错误也拒绝。`--commands=OFF` 是较新版本选项，目标版本未核实，因此不启用；参见 [MySQL 官方选项与客户端命令](https://dev.mysql.com/doc/refman/8.0/en/mysql-command-options.html#option_mysql_binary-mode)。SQL/HTTP 敏感数据仅走 pipe/stdin，Redis 密码由宿主从可信环境读取，只让 redis-cli 继承；不会进 argv、日志或镜像层。数据库授权仍按既有维护窗口管理，不宣称此工具隔离了所有恶意 SQL 数据库操作。
- Kafka 不接受通用 argv：pipe 仅允许 `kafka-list-topics`（零字段、零 stdin）、`kafka-describe-group`（一个字段，必须字面等于本轮 `vf5<32位小写hex>-notification`，零 stdin）、`kafka-produce-duplicate`（一个字段，必须字面等于本轮 `.registration-events.v1` topic；stdin ≤128 KiB 且仅一条 `数字:数字<TAB>JSON<LF>` 记录）。宿主构造全部固定参数、脚本、顺序和 `kafka:9092` bootstrap；列表/组查询最多30秒，单条重复发送最多45秒且 delivery timeout=30000ms。拒绝 equals-form、前导选项、额外字段、外部 bootstrap、regex scope 及任意 create/delete/alter/config/script。六个精确主/DLT topic 仅由宿主可信编排逐个有界创建，先记录 `topics-create.txt`，然后 driver 只核对存在；容器 RPC 无创建/删除/修改动作。
- MySQL stdin 中 UTF-8 BOM 一律拒绝，不依赖客户端 BOM 去除次序；大小写不敏感拒绝 `DELIMITER` 前缀（包括 suffix），并保守拒绝注释后/任意 token 边界的 charset/system/source/tee/pager 等客户端命令。词法扫描是额外防御而非 SQL 权限沙箱，现有 binary-mode、local-infile、NUL/反斜杠与秘密保护不变。
- 宿主产生的公开证据也先写入未挂载私有文件，再以 `mv -T` 原子替换叶节点，绝不打开容器可替换的证据输出路径；公开文件 444，可由 UID65534 扫描，宿主 run root 仍为 700。readiness 的原始响应也先写宿主私有目录并受文件大小上限保护。
- VM 留出至少 5 GiB 磁盘空间与足够运行两个 640 MiB 后端及 1 GiB Kafka 的内存。单节点 KRaft 只是教学/验收，不是生产 HA。
- 既有 MySQL、Redis 从 VM 客户端和后端容器均可访问。Compose 不声明、不创建、不重建、不停止这些服务；只读取其 ID、名称、镜像与运行状态。
- 预先安排无业务写入、无其他应用实例/作业运行的维护窗口。工具不会停止用户的其他应用。指纹覆盖整库，其他写入也会让验收失败，不能把失败归因成“可忽略噪声”。
- 恢复演示库继续 `SPRING_FLYWAY_ENABLED=false`。不修复/篡改旧 Flyway history，不重新执行 V9。恢复目录若在 `/tmp/mysql`，仍需由既有运维措施保护；本工具不处理数据目录。
- V10 之前必须审查当前 schema、历史 Outbox 及备份。验收要求原 Outbox 与 reconciliation 表没有任务；如果存在历史记录，停止并单独审核迁移/历史路由方案，不能删除它们来通过门禁。
- 原库不能有未来 24 小时内到期的已发布活动/待处理递补邀请；否则现有自动作业会改变历史数据，启动前门禁直接拒绝。不要为通过门禁改动旧业务记录，应单独审核运行环境或使用获批的隔离恢复副本。

Kafka 仅通过本轮 Compose 网络访问，端口不映射到宿主机。两个后端也不公开端口；验收驱动通过 `docker compose exec` 在各后端容器内请求 `localhost:8080`。
KRaft 日志目录与卷挂载统一为镜像准备的 `/var/lib/kafka/data`；静态契约固定检查这个路径。新卷实际格式化/权限仍需 Docker-capable 主机上的独立 live 验证。
后端不依赖 Kafka 容器健康才启动；readiness 只包含 `readinessState,db`，事件链路退化单独通过指标/health 观察。

## 1. 配置与临时密钥

从 `deploy/stage5/.env.example` 复制为被 Git 忽略的 `.env`，替换全部占位符。
设置 `STAGE5_MAINTENANCE_APPROVED=true` 必须代表维护窗口已经获得实际批准。
值采用严格的未引用 dotenv 子集：每行一个 `KEY=value`，不允许重复键、未知键、空必填项、空白、引号、反斜杠、`$`、`#`。密码若含这些字符，应走单独审核的配置适配，不应猜测转义或 `source` 环境文件。
输入只接受严格 UTF-8，可带文件开头的 UTF-8 BOM，支持 LF / CRLF；拒绝 UTF-16、无效 UTF-8 与值中的控制字符。只按第一个字面 `=` 分隔，后续 `=` 原样保留（包括 Base64 padding）。本地 JSON evidence 统一写为显式 UTF-8、无 BOM，不依赖 PowerShell 5.1/7 的默认编码。
`JWT_SECRET` 使用现有应用要求的标准 Base64 密钥；`RATE_LIMIT_KEY_SECRET` 使用足够强的随机值。

Stage 5 复用已审计的 Stage 4D 一次性 Ed25519 密钥安装脚本；移除由本轮有界 helper 完成：

```powershell
.\deploy\stage4d\bootstrap-temporary-ssh.ps1 -VmHost '<VM_HOST>' -SshUser root
```

使用输出的 `IdentityFile`，不要使用个人长期密钥。SSH host key 必须事先经可信渠道核实；不关闭 host-key 校验，不把 SSH 密码放入命令或环境文件。
本地预检失败时不会连接 VM，也不会自动移除密钥；需要显式调用 `deploy/stage5/remove-temporary-key.py`，提供 `--host/--user/--port/--identity/--status-file`（状态文件的父目录须已存在）。该 helper 只移除这一条公钥，SSH 使用 connect timeout 10 秒、server-alive 15 秒/3 次及整体 60 秒 deadline。超时返回 124、保留本地密钥并写入失败状态；其他非零码也不能记作成功。

## 2. 备份与独立 V10 审核门禁

经授权、密钥安装完成且工作树干净后，先执行仅本地预检：

```powershell
.\deploy\stage5\run-stage5.ps1 `
  -VmHost '<VM_HOST>' -IdentityFile '<TEMP_IDENTITY_FILE>' `
  -ComposeEnvFile '.\deploy\stage5\.env' -AuthorizeLiveRun -LocalPreflightOnly
```

它仍要求显式授权、root/目标/runId、临时 OpenSSH 私钥封装与公钥格式、严格配置、确定性工具/版本、正确分支与干净已提交 HEAD，并真实执行 `git archive` 和精确 driver hash。本地使用独立临时目录，在成功/失败的 `finally` 清理后才输出固定成功类别 `STAGE5_LOCAL_PREFLIGHT_PASS`。不创建 `deploy/stage5/output`，不启动 SSH/scp、不连接 VM、不移除密钥，也不输出配置值；不能同时传 `-MigrationReviewed`。通过只证明本地门禁，不证明密钥配对/远程认证、VM 前置条件或实际验收。

首次经批准的 live 准备运行不传 `-MigrationReviewed`：

```powershell
.\deploy\stage5\run-stage5.ps1 `
  -VmHost '<VM_HOST>' -IdentityFile '<TEMP_IDENTITY_FILE>' `
  -ComposeEnvFile '.\deploy\stage5\.env' -AuthorizeLiveRun
```

它在任何本轮容器启动前检查外部服务、生成保护的整库备份和数据指纹，然后复制 V10 候选 SQL 及 SHA-256 到 evidence 并失败关闭。此轮不是通过的验收。
备份为远端 `/opt/volunteerflow-stage5/<runId>/private/backup-private.sql`，目录 `700`、文件 `600`；备份包含敏感业务数据，不进入 evidence、不自动下载、不提交仓库。客户端配置与 token manifest 也只位于本轮 `private/`。
`backup.sha256` 是原始备份的哈希，`fingerprint-before.sha256` 是排序、逐行、无结构/时间注释的数据转储哈希。
需按既有安全备份策略另行验证恢复能力；生成文件和哈希并不等于完成恢复演练。

必须在该备份之后独立审查 `backend/src/main/resources/db/migration/V10__add_stage5_event_pipeline.sql`、当前表结构、空历史队列约束和 SQL 哈希，再由获授权的运维在独立步骤中只应用 V10。
runner **从不执行迁移**；`-MigrationReviewed` 仅声明已完成独立审核与应用，脚本还会核实新列/新表存在。不得通过打开 Flyway、修改迁移历史、全表删除或覆盖数据库来绕过。
保存备份、审核记录、实际应用输出及结构前后对比。应用后用新的 runId、新临时密钥运行正式验收。

## 3. 经批准的完整 live 验收

```powershell
.\deploy\stage5\run-stage5.ps1 `
  -VmHost '<VM_HOST>' -IdentityFile '<NEW_TEMP_IDENTITY_FILE>' `
  -ComposeEnvFile '.\deploy\stage5\.env' -AuthorizeLiveRun -MigrationReviewed
```

不传 runId 时生成规范小写 UUID。每轮使用新 runId，不复用或覆盖已有目录。
远端根目录固定 `/opt/volunteerflow-stage5/<runId>`，Compose 项目为 `vf5<去连字符runId>`，本地 evidence 位于 `deploy/stage5/output/<runId>/`（Git 忽略）。
Kafka topic / group、Redis `APP_ENV` 命名空间、数据库夹具名称/ID 均来自本轮身份。源代码由 `git archive HEAD` 打包，不能把未提交本地代码误认成已提交验收版本。

固定的仓库内 `acceptance.py` 驱动无需用户另写脚本，按以下顺序执行：

| 阶段 | 检查与证据 |
|---|---|
| preflight / backup | 外部服务身份、环境白名单与密钥脱敏、维护窗口、备份和原始指纹、只读 V10 schema 门禁 |
| stack | 单 broker KRaft、两个私有后端、分别有界 readiness、Kafka broker readiness、创建本轮三个主 topic 和 DLT |
| seed / normal | HTTP 注册三个隔离用户；固定 SQL 创建隔离组织/活动/岗位；真实报名 HTTP；注册、审计、Outbox 均已提交，最终恰好一条消费标记和通知 |
| outage | 只 `compose stop kafka`；两个后端仍 ready；另一成员真实报名成功，审计存在；Outbox pending/retryable 且实际观察到 retry，通知/消费标记仍为零 |
| recover | 只 `compose start kafka`；等待同一事件 `PUBLISHED` 并记录 partition/offset；恰好一条通知/消费标记 |
| duplicate | 从原 Outbox 读取原 key / eventId，向同一主 topic 再发一次；重复指标增加，通知/标记仍为一 |
| reconcile | 隔离零容量岗位通过真实报名得到候补；在事务中只给该岗位增加容量并请求 reconciliation；首次一条有效 offer；再次请求处理后总 offer 仍为一 |
| metrics / offsets | before/after 指标、客户端 lag meter、消费组 offset、精确 Outbox/通知/consumed/岗位数据快照 |
| finish | 恢复本轮 Kafka；停本轮后端成功后才清理夹具；精确清理本轮 Redis 前缀；验证整库指纹及原服务身份；仅 down 本轮 Compose 网络/容器/卷；秘密扫描；证据下载；精确移除临时 SSH 公钥 |

seed 在任何账户/夹具写入前检查全部清理表的组织范围（包括无物理外键的孤立行）、所有计划 ID 与账户身份，并保存每个相关表的原始主键清单。主键预检最多每表 100000 条，本轮记录最多每表 1000 条，超界直接拒绝。
私有 manifest 持久化 `original_keys/created`：seed 异常（含 HTTP 已提交但响应丢失）也会检查点记录已创建的精确 ID；历史 refresh-session/consumed 孤立记录即使引用后来新建的账户/事件也不会被认领。
清理前先核对所有范围与记录清单，出现任何未记录行或身份歧义则在执行任何删除前整轮拒绝。删除语句同时约束记录的 ID 与明确身份，不再只按整个 `organization_id` 范围或用户名连接批量删除。

`verify-stage5.sql` 是额外的只读诊断模板，变量必须从本轮 manifest 选取；未设置变量时不查询任意事件。
驱动将 API token 和 MySQL client 配置保存在 evidence 外的受保护文件中，通过 stdin 传 SQL / HTTP credentials，不放进进程参数或日志。失败输出只包含安全类别，不包含原始 SQL/响应/密钥。
等待、HTTP、MySQL、Kafka、构建均有超时；超时为失败，不输出成功状态。

## 4. 通过判定与证据边界

必须同时检查本地 `run-status.json` 与远端下载的 `remote-run-status.json`：

- `remoteExit=0`；远端 `primaryStatus/recoveryStatus/cleanupStatus/secretScanStatus` 均 `success`。
- 本地 `evidenceDownloadStatus/keyRemovalStatus/secretRemovalStatus` 均 `success`。
- `key-removal-status.json` 为成功且 `keyRemovalExitCode=0`。超时/失败不阻塞本地最终状态文件写入，也不能被其他成功门禁覆盖。
- `database-normal/recover/duplicate.json` 对目标事件终态三元组均为 `[1,1,1]`；`database-outage.json` 为 `[0,0,0]`，同时有已提交的报名/审计及 retry 事实。
- reconciliation 第一次/第二次已处理且有效与总 offer 均为一；对应数据库快照和前后指标存在。
- lag / offsets、故障与重复计数、backlog / DLT / reconciliation 指标全部可读取；数值必须来自该轮采样。`metrics-*.json` 保留 `perInstance` 原始值，只在 `counterSums` 汇总进程计数器；两个实例的 gauge 代表同一 DB，不能相加当作不同积压。
- 原始数据 fingerprint 一致，MySQL/Redis 容器 ID 未变化，本轮 Redis namespace 已清空，清理记录存在，`secret-scan.txt=PASS`。

主流程失败不能被恢复/清理成功覆盖。容器已启动、HTTP 200、静态契约通过、Testcontainers 历史运行均不代表本轮真实验收通过。
本轮没有吞吐/SLA 压测，不能据此捏造吞吐、p95、可用性或生产容量。
只有上述实际 evidence 通过后，下一阶段才创建实测报告与 `Stage5DocumentationContractTest`。

## 5. DLT 有界重放

先修复根因并审查目标 eventId，明确 topic、partition、左闭右开的 `[start,end)`，最多 1000 条：

```bash
bash deploy/stage5/replay-dlt.sh --project "vf5<RUN_COMPACT>" \
  --compose-file deploy/stage5/docker-compose.yml --env-file deploy/stage5/.env \
  --topic 'vf5<RUN_COMPACT>.registration-events.v1.DLT' --partition 0 \
  --start-offset 12 --end-offset 15 --dry-run
```

dry-run 不连接 Docker/Kafka；执行时改成 `--confirm '<TOPIC>:0:12:15'`。必须保留本轮 `STAGE5_RUN_COMPACT`，从 VM 本轮源码目录执行，并使用该轮保护的环境文件；正式清理前才可操作。
工具不重置/提交业务消费组 offset，不默认读取整 topic；只接受连续 offset 的完整范围，稀疏/截断范围失败关闭。保留原 key/eventId，将原值发回去掉 `.DLT` 的主 topic。重放后再次核对 consumed_event 和 notification 的精确唯一性；不能把控制台命令成功当成数据库效果。

## 6. 失败恢复、秘密与备份保留

自动 finish 会尝试恢复本轮 Kafka、停止本轮后端、清理精确夹具，再移除本轮 Compose 栈；如果停止后端失败，禁止删除夹具数据。
数据库清理或指纹校验失败时不删除本轮 Kafka 卷，保留现场用于恢复。`primaryExitCode` 保留原始失败码，恢复/清理失败也会使最终退出非零。
失败时保护的环境、manifest、备份可能仍需保留，目录不可当成“清理全部完成”。再次登录应申请新的临时密钥，先检查失败 step、status、当前容器和精确 runId，不启动其他轮次的清理。

历史失败轮次不会自动重用或递归删除。例如缺少宿主 Python 而停在 preflight 的旧版轮次，可能只有旧路径下的受保护 dotenv/source 文件，并没有新版 `private/` 或数据库备份。后续必须先获批、按该 runId 只读盘点，再单独审核精确文件的保留/清理；本轮静态修复不登录或清理这些目录。
如果清理报告未记录行，先保留所有数据和原始/创建 ID 清单并单独审查来源；不要调用检查点命令自动把未知行认领为本轮所有，也不要改成按组织批量删除来通过指纹校验。
确认本轮后端已停止后，可按远端 driver 的固定 `cleanup` / `assert-clean` 动作恢复，并使用精确 `-p vf5<runId>` / 本轮 Compose/env 路径移除该项目；不得使用 system prune、全局 stop/down、`FLUSHALL`、`TRUNCATE`、模糊数据库前缀删除或递归删工作区。
若原数据指纹不一致，先保存证据并调查，不自动恢复/覆盖用户数据。备份恢复是新的高风险授权事项。

成功轮次删除精确环境/客户端配置/token manifest 后保留受保护备份；失败轮次保留恢复所需私密文件并在状态中明确标识。备份的受控导出、保留周期与最终销毁需按用户批准的策略另行执行，本工具不做递归目录删除。
不要提交 `.env`、本轮 evidence、原始转储、令牌、私钥或失败日志。发布报告前同时做已知秘密值扫描和模式扫描，扫描失败不能发布。

## 静态检查（不触碰运行环境）

```powershell
cd backend
.\mvnw.cmd -q '-Dtest=Stage5DeploymentContractTest,Stage5RunnerContractTest' test
.\mvnw.cmd test
```

仓库根目录可执行 `python -B deploy/stage5/test_acceptance.py`、`python -B deploy/stage5/test_runtime.py`、`python -B deploy/stage5/test_pipe_bridge.py`、Bash `-n`、PowerShell AST parse、`git diff --check`。本地离线测试使用 Windows Python，不表示 VM 必须安装 Python；Docker/主机 UID 与权限操作在边界使用 double，真实 pipe、FIFO、硬链接与原子替换可本地验证，不连接 daemon。Windows 若无创建原生符号链接权限会明确打印限制，不把这些用例宣称为 Linux 内核实测。
`powershell -NoProfile -NonInteractive -File deploy/stage5/test-native-tools.ps1` 可离线验证同名 Git/Python 多候选、优先顺序及安全拒绝，不启动 bootstrap 或连接 VM。
`powershell.exe -Version 5.1 -NoProfile -NonInteractive -File deploy/stage5/test-powershell51-compatibility.ps1` 必须使用实际 Windows PowerShell 5.1，不能用 pwsh parse 冒充。套件在独立本地 Git 仓库验证 dotenv/BOM/CRLF、全部本地门禁、archive/hash 失败清理和无网络/输出目录/密钥移除，网络边界使用失败关闭 double；还检查正常失败路径 JSON 的无 BOM UTF-8 写入。存在 pwsh 时追加 PowerShell 7 交叉验证。它不使用真实配置或密钥，不是 live 预检结果。
有本地 Compose 解析器时只运行 `docker compose -f deploy/stage5/docker-compose.yml config --no-interpolate`。
没有 Docker binary 时只能报告“未执行 Compose 官方解析/运行”，不能把 YAML 结构契约等同于 Docker runtime 验证。
