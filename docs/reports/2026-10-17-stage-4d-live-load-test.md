# VolunteerFlow 阶段 4D 真实部署与压测报告

> 结论：本次候选运行部分通过。双实例路由、正常 Redis 下的共享限流、正常/故障报名正确性、恢复后读取和精确清理已有证据；恢复后共享限流复验、故障登录本机降级、动态签到 `503 DYNAMIC_CHECKIN_UNAVAILABLE` 和完整环境元数据没有执行或采集，因此不能标记阶段 4D 完成。

## 1. 运行身份

| 字段 | 实测值 |
|---|---|
| 日期与时区 | 2026-10-18，Asia/Shanghai（业务场景约 12:54–13:04；原始日志为 UTC） |
| Git commit | `19338ad3606bc2abaabec66a427ecd7f4a7272cf` |
| runId | `646f807f-94a9-4a41-94cd-a9480cd12bd9` |
| 执行方式 | 本地 PowerShell runner，经一次性 root SSH 密钥执行远程脚本 |
| 最终结论 | 部分通过；详见未完成门禁 |
| 本地 evidence | `output/stage4d/646f807f-94a9-4a41-94cd-a9480cd12bd9/`（被 Git 忽略） |

`run-status.json` 中 `primaryStatus`、`recoveryStatus`、`cleanupStatus`、`evidenceDownloadStatus`、`remoteRunDirectoryStatus` 和 `keyRemovalStatus` 均为 `success`。

## 2. 环境

| 字段 | 实测值 |
|---|---|
| VM OS / kernel | 宿主发行版未写入 evidence；Nginx 启动日志记录共享内核 `Linux 3.10.0-1160.el7.x86_64` |
| CPU 型号与核数 | 本轮 evidence 未采集，不能补写 |
| 内存 | 本轮 evidence 未采集，不能补写 |
| 磁盘 | `preflight.json` 记录 `disk:true`；类型和精确可用空间未采集 |
| Docker / Compose | Docker Engine 版本未采集；Docker Compose `v2.27.1` |
| Java / Spring Boot | Java 17 / Spring Boot 3.5.16 |
| Nginx | 1.27.5 |
| MySQL / Redis | 复用虚拟机已有 Docker 容器；版本未写入本轮 evidence；测试没有停止、重建或替换它们 |
| 压测拓扑 | k6、Nginx、两个 Spring Boot 后端与外部 MySQL/Redis 均位于同一台虚拟机 |

环境硬件与 Docker Engine 版本没有被本轮自动化持久化，这是报告证据完整性的缺口，但不改变已由机器产物验证的业务正确性、恢复和清理结论。下一轮应把这些字段写入 `preflight.json`，而不是依赖人工补记。

## 3. 部署与路由证据

| 检查 | evidence | 结果 |
|---|---|---|
| Docker、磁盘、MySQL、Redis、端口预检 | `preflight.json` | 五项均为 `true` |
| backend-a / backend-b 健康 | `stack-up.log`、`readiness-initial.json` | 两容器均进入 `Healthy`，公开 readiness 为 `UP` |
| Nginx 命中 backend-a | `nginx-upstream.log`、`upstream-addresses.txt` | `172.23.0.3:8080`，109 次 |
| Nginx 命中 backend-b | `nginx-upstream.log`、`upstream-addresses.txt` | `172.23.0.2:8080`，108 次 |
| 共享限流跨实例 | `k6` 限流摘要、`nginx-upstream.log` | 3 次未限流请求分布到两个 upstream，第 4 次由另一 upstream 返回 429 |
| 日志凭据模式扫描 | `container.log`、`nginx-access.log`、`compose-config.yml` | 未发现 JWT、Bearer、私钥；5 处 `DB_PASSWORD` 均为未展开占位符 |

## 4. 数据库事实

| 数据集 | 预期事实 | 实测事实 | 结果 |
|---|---|---|---|
| normal | 100 个唯一成员；20 `CONFIRMED`；80 `WAITLISTED`；候补 1–80；next=81 | 100 个唯一 userId；20 / 80；序号完整；next=81 | 通过 |
| outage | 100 个唯一成员；20 `CONFIRMED`；80 `WAITLISTED`；候补 1–80；next=81 | 100 个唯一 userId；20 / 80；序号完整；next=81 | 通过 |
| 清理后 | 原有表行数与 SHA-256 指纹等于各自 baseline | normal/outage 的 `cleanup` 和 `assert-snapshot` 均为 1 测试、0 失败、`BUILD SUCCESS` | 通过 |

事实来源分别是 `verification-normal.json`、`verification-outage.json`、`baseline-normal.json`、`baseline-outage.json` 和四份清理/指纹断言日志。两个 baseline 是在各自数据集执行前独立采集，不能互相替代。

## 5. k6 指标

单位：吞吐量为 req/s，延迟为秒。p99 由对应 `k6-raw-*.json` 中的 `http_req_duration` 点按线性分位数计算；其余指标直接取场景 summary。

| 场景 | 请求数 | 吞吐量 | p50 | p90 | p95 | p99 | max | HTTP / 业务结果 |
|---|---:|---:|---:|---:|---:|---:|---:|---|
| normal 100 VU 报名 | 100 | 3.613 | 16.818 | 21.900 | 22.332 | 22.587 | 22.647 | HTTP 失败 0；业务失败 0；非预期状态 0 |
| 共享限流 | 5 | 2.880 | 0.427 | 0.619 | 0.668 | 0.708 | 0.717 | 1 次预期 401、3 次预期 409、1 次预期 429；业务失败 0；非预期状态 0 |
| outage 100 VU 报名 | 100 | 4.682 | 11.840 | 15.119 | 15.793 | 16.143 | 16.282 | HTTP 失败 0；业务失败 0；非预期状态 0 |
| Redis 恢复读取 | 3 | 1.169 | 0.958 | 1.438 | 1.498 | 1.546 | 1.558 | 3 次 HTTP 200；业务失败 0；非预期状态 0 |

限流场景的 k6 通用 `http_req_failed` 为 100%，因为该脚本故意产生 401、409 和 429 等非 2xx 响应；这不是业务失败。场景专用指标记录 `rate_limit_allowed=3`、`rate_limit_rejected=1`、`business_checks_failed=0`、`unexpected_status=0`。`rate_limit_rejected` 只有在响应为 429 且 `Retry-After` 是正整数时才累加，因此本次 429 的响应头校验通过。

两轮并发报名延迟很高，正常场景 p95 为 22.332 秒，故障场景 p95 为 15.793 秒。本阶段没有预设延迟 SLA，因此正确性门禁通过不等于性能优秀；后续需要独立压测机、数据库慢查询分析和多轮基线才能定位瓶颈。

## 6. 故障、恢复和清理

| 项目 | 结果 | evidence / 说明 |
|---|---|---|
| Redis 故障注入只影响 Stage 4D 后端 | 通过 | `redis-outage.log` 只重建本轮 `backend-a`、`backend-b`，没有管理已有 Redis 容器 |
| Redis 故障时报名正确性 | 通过 | 100 请求、HTTP/业务错误均为 0；数据库终态 20/80 |
| Redis 配置恢复与读取 | 部分通过 | `redis-restore.log`、readiness、活动详情、个人报名状态均成功；没有恢复后共享限流复验，不能据此证明 Redis 全局限流已恢复 |
| 夹具清理与指纹恢复 | 通过 | normal/outage 的 cleanup 与 assert-snapshot 均 `BUILD SUCCESS` |
| 本轮 Compose 栈和镜像移除 | 通过 | `stack-down.log` 记录两个后端、Nginx、网络和四个本轮镜像均已移除 |
| 远程运行目录移除 | 通过 | 本地 `run-status.json` 的 `remoteRunDirectoryStatus=success` |
| 一次性 SSH 公私钥移除 | 通过 | 本地 `run-status.json` 的 `keyRemovalStatus=success` |

自动化 runner 对本 runId 返回成功，但后续按设计逐项审查发现测试编排漏掉了硬性场景。因此该退出码不能替代完整性审查。

## 7. 限制与最终结论

- k6 与被测系统同机，CPU、内存、磁盘和网络栈相互竞争；吞吐量和延迟不能外推为生产容量。
- 这是一次短时单轮基线，不能证明长时间稳定性。
- CPU、内存、Docker Engine 以及 MySQL/Redis 精确版本未写入 evidence；下次运行应扩展预检产物。
- 本轮没有执行 Redis 故障下两个后端各自的登录本机降级、动态签到 `503 DYNAMIC_CHECKIN_UNAVAILABLE`，也没有执行恢复后共享限流复验。
- 本轮没有验证真实手机动态二维码扫码，也没有替代阶段 3 浏览器 E2E。

在上述边界内，只能确认双实例分流、正常 Redis 下的共享限流、两轮报名正确性、恢复后业务读取、精确清理和资源回收。阶段 4D 仍为部分完成，必须补齐恢复后共享限流复验、登录本机降级、动态签到 503 和环境证据后重新判定。性能侧只能记录为“当前虚拟机上的同机压测基线”，不能外推为生产容量。
