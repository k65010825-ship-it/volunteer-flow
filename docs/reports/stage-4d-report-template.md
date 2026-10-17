# VolunteerFlow 阶段 4D 真实部署与压测报告

> 状态：待填写。只允许从本次 evidence 文件填写；不得用计划值、静态测试或历史轮次替代。

## 1. 运行身份

| 字段 | 实测值 |
|---|---|
| 日期与时区 | 待填写 |
| Git commit | 待填写 |
| runId | 待填写 |
| 执行人 | 待填写 |
| 最终结论（通过/失败） | 待填写 |
| evidence 路径 | `output/stage4d/<runId>/` |

## 2. 环境

| 字段 | 实测值 |
|---|---|
| VM OS / kernel | 待填写 |
| CPU 型号与核数 | 待填写 |
| 内存 | 待填写 |
| 磁盘类型与可用空间 | 待填写 |
| Docker 版本 | 待填写 |
| Compose 版本 | 待填写 |
| Java / Spring Boot | Java 17 / 待由构建证据确认 |
| MySQL / Redis 版本与部署方式 | 待填写 |
| 压测拓扑 | 同一台虚拟机 / 其他：待填写 |

## 3. 部署与路由证据

| 检查 | evidence | 结果 |
|---|---|---|
| preflight | `preflight.json` | 待填写 |
| backend-a readiness | `readiness-initial.json`、容器状态 | 待填写 |
| backend-b readiness | `readiness-initial.json`、容器状态 | 待填写 |
| Nginx 命中 backend-a | `nginx-upstream.log`、`upstream-addresses.txt` | 待填写 |
| Nginx 命中 backend-b | `nginx-upstream.log`、`upstream-addresses.txt` | 待填写 |
| 日志无凭据泄漏 | `container.log`、`nginx-access.log` | 待填写 |

## 4. 数据库事实

| 数据集 | 预期事实 | 实测事实 | 结果 |
|---|---|---|---|
| normal | 100 个唯一成员；20 CONFIRMED；80 WAITLISTED；候补 1–80；next=81 | 待填写 | 待填写 |
| outage | 100 个唯一成员；20 CONFIRMED；80 WAITLISTED；候补 1–80；next=81 | 待填写 | 待填写 |
| 清理后 | 原有表行数与 SHA-256 指纹等于 baseline | 待填写 | 待填写 |

## 5. k6 指标

每个数据集分别填写；不要只报告最好的一轮。

| 场景 | 请求数 | 吞吐量 | p50 | p90 | p95 | p99 | max | HTTP/业务错误 | evidence |
|---|---:|---:|---:|---:|---:|---:|---:|---:|---|
| normal 100 VU 报名 | 待填写 | 待填写 | 待填写 | 待填写 | 待填写 | 待填写 | 待填写 | 待填写 | 待填写 |
| 限流 | 待填写 | 待填写 | 待填写 | 待填写 | 待填写 | 待填写 | 待填写 | 待填写 | 待填写 |
| outage 100 VU 报名 | 待填写 | 待填写 | 待填写 | 待填写 | 待填写 | 待填写 | 待填写 | 待填写 | 待填写 |
| Redis 恢复读取 | 待填写 | 待填写 | 待填写 | 待填写 | 待填写 | 待填写 | 待填写 | 待填写 | 待填写 |

额外填写：

- `business_checks_failed`：待填写。
- `unexpected_status`：待填写。
- `rate_limit_allowed`：待填写。
- `rate_limit_rejected`：待填写。
- 429 是否全部包含合法 `Retry-After`：待填写。

## 6. 故障、恢复和清理

| 项目 | 结果 | evidence / 说明 |
|---|---|---|
| Redis 故障注入只影响 Stage 4D 后端 | 待填写 | 待填写 |
| Redis 恢复 | 待填写 | `redis-restore.log`、`readiness-recovered.json` |
| 恢复后活动与报名状态读取 | 待填写 | `recovery-<runId>-outage-summary.json` |
| 夹具清理 | 待填写 | `fixture-cleanup-*.log` |
| baseline 指纹恢复 | 待填写 | `fixture-assert-snapshot-*.log` |
| 本轮 Compose 栈移除 | 待填写 | `stack-down.log` |
| 一次性 SSH 公钥移除 | 待填写 | 本地 `run-status.json` |

失败明细（保留第一个失败，不用恢复结果覆盖）：待填写。

## 7. 限制与结论

### 同机压测限制

- k6、Nginx、两个后端及可能的 MySQL/Redis 共享 CPU、内存、磁盘和网络栈。
- 本结果是当前虚拟机和当前数据规模下的回归基线，不是生产容量、SLA 或容量上限。
- 单轮结果不能证明长期稳定性；需要多轮、独立压测机和持续监控才能进一步判断。

### 最终结论

待填写。结论必须同时引用业务事实、k6 指标、两个 upstream、Redis 恢复和夹具清理证据。
