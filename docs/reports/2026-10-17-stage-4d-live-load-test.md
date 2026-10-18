# VolunteerFlow 阶段 4D 真实部署与压测报告

> 结论：阶段 4D 本轮验收通过。双后端部署、Nginx 分流、正常与 Redis 故障下的报名正确性、故障登录降级、动态签到故障语义、Redis 恢复后的跨实例共享限流、数据保护和资源清理均有机器证据。性能数据仅代表同一台低配虚拟机上的单轮回归基线，不能外推为生产容量。

## 1. 运行身份

| 字段 | 实测值 |
|---|---|
| 日期与时区 | 2026-10-18，Asia/Shanghai |
| Git commit | `5a8bc0c84f1fc58e5ae75b552d1dd65146329c25` |
| runId | `605c0379-e6e5-46af-a74d-50a831d38aec` |
| 执行方式 | 本地 PowerShell runner，经一次性 root SSH 密钥执行远程脚本 |
| 最终结论 | 通过 |
| 本地 evidence | `output/stage4d/605c0379-e6e5-46af-a74d-50a831d38aec/`（被 Git 忽略） |

`run-status.json` 记录 `primaryStatus`、`recoveryStatus`、`cleanupStatus`、`evidenceDownloadStatus`、`remoteRunDirectoryStatus` 和 `keyRemovalStatus` 全部为 `success`。`remote-run-status.json` 记录主流程退出码为 0，秘密扫描结果为 `PASS`。

## 2. 实测环境

| 字段 | 实测值 |
|---|---|
| VM OS / kernel | CentOS Linux 7；Linux 3.10.0-1160.el7.x86_64 |
| CPU | 2 vCPU |
| 内存 / Swap | 1,907,716,096 B；2,147,479,552 B Swap |
| 预检磁盘 | XFS 根分区；17,814,528 KB，总可用 4,475,712 KB |
| Docker / Compose | Docker Engine 26.1.4；Compose v2.27.1 |
| Java / Spring Boot | Java 17 / Spring Boot 3.5.16 |
| MySQL | 现有 `mysql:latest` 容器；MySQL Community Server 26.7.0 |
| Redis | 现有 `redis:latest` 容器；Redis 8.10.0 |
| 压测拓扑 | k6、Nginx、两个 Spring Boot 后端与外部 MySQL/Redis 同机 |

本轮 Compose 只管理 runId 对应的两个后端、前端 Nginx、fixture 和 k6，不创建、停止或替换现有 MySQL/Redis 容器。恢复数据库继续使用 `SPRING_FLYWAY_ENABLED=false`。

## 3. 部署与路由

| 检查 | 结果 | evidence |
|---|---|---|
| Docker、磁盘、MySQL、Redis、端口和元数据预检 | 全部通过 | `preflight.json` |
| 初始与恢复 readiness | 均为 `UP` | `readiness-initial.json`、`readiness-recovered.json` |
| Nginx 命中 backend-a | `172.25.0.2:8080`，159 次 | `nginx-upstream.log`、`upstream-addresses.txt` |
| Nginx 命中 backend-b | `172.25.0.3:8080`，159 次 | 同上 |
| 日志与 evidence 秘密扫描 | `PASS` | `evidence-secret-scan.txt` |
| 本轮栈回收 | 容器、网络和四个本轮镜像全部移除 | `stack-down.log` |

构建期间虚拟机 DNS 间歇失败。Maven 依赖预取的前两次尝试失败，第三次成功；Dockerfile 现在最多尝试 3 次、每次间隔 5 秒，第三次仍失败会退出，不会把下载失败误判为构建成功。

## 4. 数据库正确性与数据保护

| 数据集 | 并发报名结果 | 候补序号 | 下一候补号 | 结果 |
|---|---:|---|---:|---|
| Redis 正常 | 20 `CONFIRMED` / 80 `WAITLISTED` | 连续 1–80 | 81 | 通过 |
| Redis 故障 | 20 `CONFIRMED` / 80 `WAITLISTED` | 连续 1–80 | 81 | 通过 |

两组均由 100 个唯一用户并发报名，`business_checks_failed=0`、`unexpected_status=0`、HTTP 请求失败率为 0。事实来源为 `verification-normal.json`、`verification-outage.json` 和两份报名 summary。

normal/outage 两组在执行前分别采集数据库基线，清理后重新计算原有数据的行数与 SHA-256 指纹。两组 cleanup 与 assert-snapshot 都是 1 个测试、0 失败、`BUILD SUCCESS`，证明只删除了本 runId 的隔离数据，没有用 `TRUNCATE`、时间范围或无条件删除影响原有数据。

## 5. k6 指标

单位：吞吐量为 req/s，延迟为秒。p99 由对应 `k6-raw-*.json` 的 `http_req_duration` 点按线性分位数计算，其余值直接取 summary。

| 场景 | 请求数 | 吞吐量 | p50 | p90 | p95 | p99 | max | 业务结果 |
|---|---:|---:|---:|---:|---:|---:|---:|---|
| 正常 100 VU 报名 | 100 | 3.000 | 22.425 | 27.804 | 28.071 | 28.241 | 28.280 | 20/80；业务失败 0；非预期状态 0 |
| 正常共享限流 | 5 | 0.964 | 0.577 | 2.386 | 2.880 | 3.275 | 3.374 | 3 次允许、1 次 429；业务失败 0 |
| Redis 故障 100 VU 报名 | 100 | 5.495 | 9.300 | 12.262 | 12.863 | 13.115 | 13.156 | 20/80；业务失败 0；非预期状态 0 |
| 故障登录本机降级 | 12 | 4.823 | 0.058 | 0.875 | 0.982 | 1.000 | 1.005 | 10 次允许、2 次拒绝；业务失败 0 |
| 故障动态签到 | 101 | 25.266 | 0.215 | 0.313 | 0.342 | 0.410 | 0.410 | 动态签到得到 1 次预期 503；业务失败 0 |
| Redis 恢复读取 | 3 | 10.561 | 0.119 | 0.121 | 0.121 | 0.121 | 0.121 | 3 次成功；业务失败 0 |
| 恢复后跨实例限流 | 4 | 0.368 | 0.921 | 1.717 | 1.745 | 1.768 | 1.774 | A/B 各 2 次；3 次允许、1 次 429 |

限流、登录降级和动态签到场景故意产生 401、409、429 或 503 等非 2xx 响应，因此不能只读 k6 通用 `http_req_failed`。判定使用场景专用计数器、状态码与响应头检查：所有场景的 `business_checks_failed` 和 `unexpected_status` 均为 0。

恢复后共享限流脚本先对齐到新的 10 秒固定窗口，再按 A/B/A/B 直接请求两个后端。结果为 `recovered_backend_a_requests=2`、`recovered_backend_b_requests=2`、`rate_limit_allowed=3`、`rate_limit_rejected=1`、`window_boundary_crossed=0`，证明两个实例恢复后共享 Redis 限流状态。

## 6. 故障、恢复与清理门禁

| 门禁 | 结果 | 说明 |
|---|---|---|
| Redis 故障注入隔离 | 通过 | 只重建本轮两个后端并指向不可达地址，未管理现有 Redis 容器 |
| Redis 故障时报名正确性 | 通过 | 100 次报名，数据库终态 20/80，无重复与超卖 |
| 登录本机降级 | 通过 | 两个后端各 5 次允许、各 1 次拒绝，合计 10/2 |
| 动态签到故障语义 | 通过 | 动态签到 POST 返回 `503 DYNAMIC_CHECKIN_UNAVAILABLE` |
| Redis 恢复读取 | 通过 | readiness、活动详情与个人报名状态均成功 |
| 恢复后共享限流 | 通过 | 同窗 A/B/A/B，第四次跨实例返回 429 |
| 原有数据保护 | 通过 | 两组清理后基线指纹一致 |
| 本轮资源与远程运行目录移除 | 通过 | 仅本 runId 栈、镜像、网络和目录被移除 |
| 一次性 SSH 公私钥移除 | 通过 | `keyRemovalStatus=success` |

## 7. 边界与后续工作

- k6 与被测系统同机，2 vCPU、约 1.78 GiB 内存且存在 Swap；CPU、内存、磁盘和网络互相竞争。
- 这是短时单轮正确性与故障回归，不是容量规划或长期稳定性测试。
- 报名 p95 达 28.071 秒，性能不理想；下一阶段应使用独立压测机、多轮基线、数据库慢查询和 JVM/Redis 指标定位瓶颈。
- 本轮没有替代阶段 3 的真实浏览器 E2E，也没有验证真实手机扫描动态二维码。
- 当前虚拟机 DNS 存在间歇失败；构建重试提高了容错，但持续网络故障仍会使构建明确失败。

在这些边界内，阶段 4D 的既定正确性、故障语义、恢复、共享限流、数据保护和清理门禁已全部完成。后续可进入阶段 5（Kafka 与可靠事件），同时把独立压测与真实终端试用作为阶段 6 的验收工作。
