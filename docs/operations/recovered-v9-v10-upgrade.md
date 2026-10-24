# 恢复库 V9/V10 受控升级说明

这是针对 2026-10-24 已核实恢复库形态的一次性工具，不是通用数据库修复器。实际执行结果见[验收报告](../reports/2026-10-24-original-db-upgrade-offline-reliability.md)。已经升级成功的原库**不要重复运行**恢复流程。

## 必须满足的前提

- 用户明确授权原库升级；有完整备份、克隆恢复全表比对、克隆升级及可靠性测试成功证据。
- 目标是原 `volunteer_flow`，仅 V9 失败，其签到字段与四个索引定义与工具预期完全一致；其他历史迁移不漂移，V10 尚未运行。
- 原 Outbox 为 0；本工具没有旧事件 payload 转换方案。有历史事件须另设计并审批迁移，不能强行运行。
- 对象均为带主键的 InnoDB 基表；无视图/例程/触发器/事件。超出该范围需要重新设计备份及恢复核验。
- 暂停应用进入维护窗口，没有其他数据库连接或活动事务；原库仍与备份快照一致。没有权限或不符合条件即停止，不能跳过门禁。
- 私有工作目录只允许当前用户访问。数据库密码从本地配置读取，给原生 MySQL 客户端使用私有配置文件，不在命令行或输出中打印。

## 工具步骤与保护

`deploy/acceptance/OriginalDatabaseUpgradeTool.java` 读取项目现有本地数据源，不修改配置。需要项目依赖 classpath 及编译后的 `UpgradeSafety`。命令参数依次是：

```text
<action> <source-local.yml> <private-runtime-directory> <migration-directory> <run-UUID> [apply-token]
```

| action | 行为 |
|---|---|
| backup-clone | 预检、备份、创建 UUID 隔离库、恢复并比对 24 张原表 |
| restore-verify-empty-clone | 仅恢复本次拥有且为空的克隆，复核导出安全性和全表一致性；不是覆盖已有数据的恢复 |
| rehearse | 只升级独立克隆；真实执行原版 V9、V10，比较旧业务字段 |
| check-original-gate | 升级前检查原库快照、连接及事务；升级后历史已变化，不应再用此门禁 |
| apply-original | 读取备份/演练证明并重新检查门禁；必须附精确令牌 `APPLY_AFTER_VERIFIED_BACKUP_AND_REHEARSAL` 才能升级原库 |
| verify-upgraded-original | 升级后只读验证 V9/V10、Flyway 校验及原业务字段指纹 |

导出禁止带 `CREATE DATABASE`/`USE`；目标克隆必须匹配本次 UUID，不能输入原库名。备份 SHA-256 必须与状态记录一致。`UpgradeSafetyTest` 对这些基础门禁提供 12 项独立断言。

迁移实现暂存原签到值和更新时间、只移除匹配的四个残留索引，要求 Flyway repair 只移除一条失败 V9、不得对齐或删除其他迁移，然后执行原版 V9、恢复暂存值、执行原版 V10。没有修改历史 SQL，也没有直接修改 Flyway 成功字段。暂存列只在正常恢复完成后移除。

## 失败与交接

MySQL DDL 可能已提交，工具失败时不要循环重试、删除暂存列、篡改历史或自动覆盖原库。保留原备份、故障现场和私有日志，核对执行阶段及数据；任何覆盖式恢复需单独确定目标与授权。`repair` 本身不会帮忙回滚用户表。

此次备份、克隆和证据均已保留。原 IDEA 后端为维护被停止；原库校验成功后由用户在 IDEA 重启。验收 Spring 上下文关闭维护作业和 Kafka consumer，不应照搬为正式运行配置。真实 Kafka 验收按用户要求未执行，后续若需要，须另行授权启动及故障演练。
