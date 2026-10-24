# 原库 V9/V10 升级与离线通知可靠性验收

日期：2026-10-24（Asia/Shanghai）。运行 ID：`4f01c0c8-ef0e-47ec-bfb3-c7fc877d0aee`。

## 结论与授权边界

用户授权先完整备份与克隆演练，演练通过后升级原库；另明确不必启动 Kafka。本次已完成原 `volunteer_flow` 的 V9/V10 升级与真实 MySQL 离线可靠性验证，**没有进行真实 Kafka 投递验收**。没有重启数据库/Redis 容器，没有修改虚拟机 IP、端口或历史迁移脚本，没有提交或推送 Git。

初次预检的“原库 V9 失败、V10 缺失”已经由本次受控升级解除；第一轮独立库核心流程报告记录的是此前时点，不应据此判断当前原库版本。

## 备份和克隆验证

私有备份：

```text
C:/Users/lenovo/Documents/Codex/2026-07-21/校园志愿活动报名与候补协同平台/output/original-upgrade-4f01c0c8-ef0e-47ec-bfb3-c7fc877d0aee/original-full.sql
```

- 大小：95,803 字节。
- SHA-256：`e238bd512ba7c1cf3db5d0c3e3b89c7800f274b0de9d443c604af41bbda02926`。
- 使用原生 `mysqldump` 完整逻辑导出，包含 routines/events/triggers 参数；预检实际对象均为 InnoDB 基表，无视图、例程、触发器或事件。原 Outbox 为 0 条。
- 导出不包含 `CREATE DATABASE`/`USE`，防止导入误指原库。克隆名称严格限定为 `vf_acceptance_4f01c0c8ef0e47ecbfb3c7fc877d0aee`。
- 恢复后 24 张原表的行数、字段及按主键排序的行数据 SHA-256 全部一致，包含当时失败的 Flyway 记录；不是只比较表数量或账号数量。
- 原生客户端初次恢复因中文绝对配置路径打不开而失败；改用受限运行目录下的相对配置文件后，完成空克隆恢复和全量比对，未改动原库。
- 克隆升级完成于 16:17:11；随后真实 MySQL 离线可靠性测试通过，才允许原库升级。

备份含用户数据和密码哈希，目录限制为当前 Windows 用户访问。SQL、客户端配置、运行 YAML、凭据与完整测试 XML 不进入仓库。保留备份和克隆，未自动清理。

## 原库维护与真实迁移

预检确认 V9 的签到字段及四个索引已存在且定义匹配，只有版本 9 失败，没有其他迁移校验漂移。维护前原库数据仍与备份一致、活动事务为 0；停止已核对启动类及 8080 监听的原后端进程后，复核其他数据库连接为 0。

执行顺序：

1. 暂存原签到状态列，并另存原 `update_time`，避免回填触发自动更新时间改变。
2. 只移除已核对的四个 V9 残留索引。
3. Flyway `repair` 只删除一条失败 V9 记录，要求没有 checksum 对齐、没有标记删除其他迁移；不手写 `UPDATE flyway_schema_history`。
4. 执行仓库原版 V9，回填原签到状态及更新时间，删除临时保留列。
5. 执行仓库原版 V10，再完整校验迁移和原业务字段指纹。

原库完成于 **16:26:21**。V1–V10 全部成功，失败记录为 0。升级后及应用上下文检查后，23 张原非 Flyway 表的原字段指纹仍全部一致；新增字段/表按迁移产生，不纳入旧字段比对。Flyway 历史因实际迁移而变化，不要求与旧失败记录一致。

| 原库业务表 | 升级前后行数 |
|---|---:|
| app_user | 112 |
| organization | 1 |
| activity | 5 |
| activity_position | 7 |
| registration | 15 |
| registration_cycle | 15 |
| checkin_record | 0 |
| notification | 3 |
| outbox_event | 0 |

原库没有写入验收活动、账号或通知。表中数字只是现有数据保留证据，不是用户规模或性能指标。

## 本次测试证据

| 检查 | 本次结果 | 范围 |
|---|---|---|
| UpgradeSafetyTest | 12 项通过 | 克隆名称、目标 URL、危险导出内容、备份/演练/连接门禁 |
| OfflineOutboxMysqlIT | 3 tests，0 失败/错误/跳过 | 克隆库真实 Spring 服务、MySQL 事务、SQL 与故障触发器；模拟 Kafka 网关 |
| OriginalDatabaseReadinessIT | 2 tests，0 失败/错误/跳过 | 原库应用上下文、Flyway validate、readiness UP；非 HTTP 服务 |
| 升级后独立只读复核 | PASSED | V9/V10 成功、Flyway validate、23 张原业务表旧字段指纹 |

离线可靠性三个场景：

- Outbox 插入失败时，发布状态和发布审计一起回滚；移除故障后可重试。业务事务没有调用 Kafka 网关。
- 模拟 Broker 失败时，业务保持已提交，事件进入 `RETRY` 并记录退避时间和安全错误码；模拟 ACK 后验证中继状态处理。将事件交给真实消费处理器重复处理，只创建一条通知和一个消费标记。
- 通知插入失败时，消费标记随事务回滚；移除故障后重试成功，重复消费不重复通知。

初次测试使用不存在的引导管理员作为夹具而失败，后改为只读取克隆中已有有效平台管理员，不修改账号或密码；最终上述 3 项全部通过。故障触发器与测试组织数据按专属 ID 清理，只作用于克隆。

复现测试（离线 IT 需要匹配的私有克隆配置，不可指向原库）：

```powershell
# backend 目录；配置文件不可提交
.\mvnw.cmd test '-Dtest=OfflineOutboxMysqlIT' '-Dspring.config.additional-location=file:///C:/path/to/private-clone/application.yml'
# 仅本次升级后原库快照检查；包含固定行数断言，不是通用生产监控
.\mvnw.cmd test '-Dtest=OriginalDatabaseReadinessIT'
```

非敏感摘要与完成标记见 [evidence](evidence/2026-10-24-original-upgrade/verification.json)。此前的后端 1057/前端 237 回归与 19 项浏览器核心流程属于第一轮结果，本报告不把它们算作本次新跑的测试。

## 尚未验证与运行交接

- Kafka 没有启动。模拟失败、模拟 ACK 和直接调用 handler 不代表真实 Broker 送达；消费者组分配、offset 提交、Broker 重启及真实积压恢复尚未验证。
- 原库应用检查关闭全局维护任务、消费者自动启动及引导管理员写入。没有启动真实 8080 HTTP 服务；原 IDEA 后端已为维护停止，请重新运行启动类。正式运行不应沿用验收关闭维护任务的设置。
- 当前连接的服务器报告版本 26.7，Flyway 11.7.2 给出未充分验证的新版本兼容性告警。本次 SQL 执行和校验成功，不等于厂商已验证此版本组合；上线前应核对数据库发行版本并确定受支持组合，未擅自升级依赖。
- MySQL DDL 不具备本次所需的整体事务回滚保证；失败必须停止、保留备份/暂存列并分析，不能靠 `repair` 假装成功。参见 [Flyway repair](https://documentation.red-gate.com/flyway/reference/commands/repair) 和 [Flyway 事务说明](https://documentation.red-gate.com/fd/migration-transaction-handling-273973399.html)。本次不演练覆盖原库恢复，不自动删除任何库。
