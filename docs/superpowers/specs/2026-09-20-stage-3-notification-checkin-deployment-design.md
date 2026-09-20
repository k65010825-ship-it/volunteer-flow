# VolunteerFlow 阶段 3：通知、签到、出勤与演示部署设计

- 日期：2026-09-20
- 状态：已确认
- 技术基线：Java 17、Spring Boot 3.x、MyBatis-Plus、MySQL、Vue 3、TypeScript、Nginx、Docker Compose
- 前置版本：阶段 1 认证、组织、RBAC、活动；阶段 2 报名、审核、候补、取消与递补

## 1. 目标和成功标准

阶段 3 在现有报名闭环上增加站内通知、已发布活动正式变更、普通签到码、人工补签、活动结束归档、出勤统计和演示部署。

完整业务链路为：

```text
报名或审核结果
→ 站内通知
→ 已发布活动发生正式变更并通知仍受影响的成员
→ 管理员开启短期签到场次
→ 已录取成员输入签到码
→ 管理员处理人工补签
→ 活动到期后自动归档出勤和未到场状态
→ 查看活动及岗位签到统计
```

阶段 3 验收必须证明：

- 通知、原业务状态和审计在同一 MySQL 事务中保持一致。
- 已发布活动不能被静默修改，变更必须有原因、前后快照和受影响成员通知。
- 同一活动同一成员最多存在一条有效签到记录。
- 签到码明文不进入数据库、日志或审计。
- 活动结束任务按索引小批量处理，不暴力扫描全表，不使用大偏移分页。
- Nginx 和 Spring Boot 可以通过 Docker Compose 启动并连接虚拟机上已有的 MySQL 容器。

## 2. 范围与非目标

本阶段实现：

- 报名结果、审核结果、候补邀请、邀请过期、活动变更、活动取消和签到开启通知。
- 通知游标分页、未读计数、单条已读和全部已读。
- 已发布活动和已有岗位的受控变更。
- 已发布活动新增岗位、配置岗位问题并单独发布。
- 5 至 120 分钟的普通签到码，默认 15 分钟。
- 自助签到、人工补签、签到场次关闭和活动出勤统计。
- 活动自动结束和 `CHECKED_IN`、`NO_SHOW` 归档。
- Nginx、Spring Boot Docker 镜像以及连接外部 MySQL 的 Compose。

本阶段不实现：

- Redis 动态二维码、缓存或限流。
- Kafka、Outbox 发布器或异步通知消费者。
- 邮件、短信、QQ 机器人等外部通知渠道。
- WebSocket 实时推送；通知未读数使用短轮询。
- 地理围栏、人脸识别、签到惩罚或报名优先级调整。
- 通用系统广播后台。
- MySQL 容器的创建、迁移、删除或数据卷接管。

## 3. 架构与交付边界

后端继续采用模块化单体：

```text
activity
├─ 已发布活动正式变更
├─ 已有岗位正式变更
└─ 新岗位草稿与发布

notification
├─ 同事务通知写入与去重
├─ 用户通知查询
└─ 未读状态

checkin
├─ 签到场次和普通签到码
├─ 自助签到和人工补签
├─ 活动结束任务
└─ 出勤统计

registration
└─ 报名周期出勤状态
```

业务服务只依赖窄接口 `NotificationWriter`，不直接操作通知 Mapper。阶段 5 引入 Outbox 与 Kafka 时，可以替换写入实现或调用边界，不改变现有控制器 API 和通知查询模型。

阶段 3 分三个可独立回归的增量：

1. 通知中心与已发布活动正式变更。
2. 签到、人工补签、自动结束与出勤统计。
3. Nginx、Spring Boot Docker 化及真实环境验收。

## 4. 数据库迁移

新增 Flyway `V9`，不得修改已经执行的 V1 至 V8。

### 4.1 报名周期出勤状态

`registration_cycle` 新增：

```text
attendance_status VARCHAR(32) NOT NULL DEFAULT 'NOT_REQUIRED'
```

状态语义：

- `NOT_REQUIRED`：未录取、已取消、已拒绝或已退出本次报名周期。
- `PENDING`：当前为正式录取，活动尚未完成出勤归档。
- `CHECKED_IN`：已有有效签到记录。
- `NO_SHOW`：活动结束归档时仍未签到。

迁移按现有事实回填：已有签到记录的正式报名为 `CHECKED_IN`；已结束活动中未签到的正式报名为 `NO_SHOW`；其余正式报名为 `PENDING`；非正式报名为 `NOT_REQUIRED`。

报名、审核、递补接受和取消服务必须同步维护出勤状态：

- 进入 `CONFIRMED` 时设为 `PENDING`。
- 离开有效录取状态时设为 `NOT_REQUIRED`。
- 签到或补签时设为 `CHECKED_IN`。

### 4.2 索引

新增以下索引：

```text
activity(status, activity_end_at, id)
registration_cycle(activity_id, status, id)
notification(user_id, create_time, id)
checkin_session(activity_id, status, closes_at, id)
```

第一个索引用于到期活动扫描；第二个用于活动归档和统计；第三个用于通知游标分页；第四个用于当前签到场次查询。

继续沿用：

- `checkin_record(activity_id, user_id)` 唯一约束。
- `notification(user_id, deduplication_key)` 唯一约束。
- 逻辑外键，不增加物理外键。

## 5. 已发布活动和岗位正式变更

### 5.1 活动变更

新增：

```text
POST /api/v1/activities/{activityId}/changes
```

请求包含目标字段和必填的 `reason`。只允许修改：

- 标题、介绍和地点。
- 报名截止时间。
- 免费取消截止时间。
- 活动开始和结束时间。

规则：

- 报名开始后不得修改报名开始时间。
- 报名截止时间可以延后；提前时必须晚于当前时间，且不能早于已经发生的报名事实。
- 活动开始后不得再修改活动时间。
- 所有时间仍满足报名开始、报名截止、活动开始、活动结束的严格顺序。
- 已取消或已结束活动不能变更。
- 没有字段发生实际变化时返回 `NO_EFFECTIVE_CHANGE`。

服务锁定活动行并读取当前值，完成规则校验后更新。审计服务保存字段级变更前后快照、原因和操作者，并返回本次审计 ID。通知去重键使用审计 ID。

### 5.2 岗位变更

新增：

```text
POST /api/v1/positions/{positionId}/changes
```

已有岗位允许修改名称、说明、服务时间、集合地点、递补确认期限和容量。限制为：

- 报名模式不可修改。
- 容量只能增加，不能减少。
- 岗位不能被物理删除。
- 服务时间必须保持有效，并处于活动允许的时间范围内。

先到先得岗位增加容量后，在同一事务持有岗位锁，并按原始候补序号为可用名额逐个创建递补邀请。不得直接把候补成员静默改为正式成员。

### 5.3 已发布活动新增岗位

在已发布活动中新增岗位时，岗位初始状态为 `DRAFT`，不会出现在成员可报名列表中。管理员可以配置该岗位的问题，然后调用：

```text
POST /api/v1/positions/{positionId}/publication
```

发布时锁定活动、岗位和问题，校验最多 10 个组合问题，将岗位改为 `ACTIVE`，并发送活动变更通知。已存在的活动公共问题仍不可修改。

### 5.4 通知接收范围

活动或岗位变更只通知仍受活动影响的成员：

- `PENDING_REVIEW`
- `CONFIRMED`
- `WAITLISTED`
- 持有尚未结束的有效递补邀请

已经取消、拒绝、放弃递补或邀请已过期的成员不接收变更通知。接收用户 ID 去重后分批写入，但所有批次仍属于原业务事务。

## 6. 站内通知

### 6.1 通知触发点

阶段 3 接入以下通知：

- 报名提交后的当前结果。
- 人工审核后的录取、候选池或拒绝结果。
- 候补邀请创建。
- 候补邀请过期。
- 活动或岗位正式变更。
- 活动取消。
- 签到场次开启。

用户自己执行取消、接受邀请或拒绝邀请后，页面已经同步展示最终结果，不额外生成自通知。

### 6.2 事务和去重

通知由 `NotificationWriter` 在调用方事务中写入。任何通知写入异常都会使对应业务事务回滚。

典型去重键：

```text
registration-result:{cycleId}:{status}
registration-review:{cycleId}:{status}
promotion-offer:{offerId}:created
promotion-offer:{offerId}:expired
activity-change:{activityId}:{auditId}
position-change:{positionId}:{auditId}
activity:{activityId}:canceled
checkin-session:{sessionId}:opened
```

批量通知使用受控大小的多值写入和 `ON DUPLICATE KEY UPDATE id=id`，只把业务唯一键冲突处理为幂等成功，不使用会掩盖其他数据错误的 `INSERT IGNORE`。

### 6.3 查询 API

```text
GET  /api/v1/users/me/notifications?cursor=&size=
GET  /api/v1/users/me/notifications/unread-count
POST /api/v1/notifications/{notificationId}/read
POST /api/v1/users/me/notifications/read-all
```

列表按 `(create_time, id)` 倒序游标分页。游标由服务端编码，客户端不能借此访问他人数据。单条已读使用用户 ID 和通知 ID 共同限定；不存在或不属于当前用户时统一返回 `NOTIFICATION_NOT_FOUND`。

## 7. 签到场次和签到码

### 7.1 签到场次 API

```text
POST /api/v1/activities/{activityId}/check-in-sessions
POST /api/v1/check-in-sessions/{sessionId}/closure
GET  /api/v1/activities/{activityId}/check-in-sessions/current
```

开启请求提供 5 至 120 分钟的有效期，默认 15 分钟。开启流程：

1. 校验 `checkin:manage`。
2. 锁定活动，确认活动为 `PUBLISHED` 且尚未结束。
3. 将该活动已经到期的旧 `OPEN` 场次标记为 `EXPIRED`。
4. 确认没有仍有效的开放场次。
5. 使用 `SecureRandom` 从去除易混淆字符的 32 字符表生成 8 位签到码，提供约 40 bit 搜索空间。
6. 标准化为大写后计算 SHA-256，只保存摘要。
7. 返回一次明文码，并通知正式录取成员。

同一活动允许保留多个历史场次，但任何时刻最多一个有效开放场次。明文码不提供重新查询；管理员丢失后必须关闭当前场次并重新开启。

场次有效条件为 `status='OPEN' AND opens_at <= now AND closes_at > now`。即使状态尚未被惰性修正，到期时间也会阻止签到。

### 7.2 自助签到

```text
POST /api/v1/check-in-sessions/{sessionId}/check-ins
```

请求只包含签到码。服务必须校验：

- 用户为活动所属组织的有效成员。
- 场次属于目标活动且仍有效。
- 当前活动尚未结束。
- 用户当前报名周期为 `CONFIRMED`。
- 签到码摘要匹配。
- `(activity_id, user_id)` 尚无签到记录。

签到码比较使用安全字节比较。成功后在同一事务插入 `checkin_record`、更新报名周期为 `CHECKED_IN` 并写审计。

### 7.3 人工补签

```text
POST /api/v1/check-in-sessions/{sessionId}/manual-check-ins
```

请求包含报名 ID 和 1 至 500 字原因。管理员必须具有 `checkin:manage`。人工补签允许在场次关闭或活动结束后执行，但成员必须曾经被正式录取，且该活动尚无其签到记录。

人工补签插入 `MANUAL` 签到记录，将 `PENDING` 或 `NO_SHOW` 改为 `CHECKED_IN`，并保存操作者、原因和审计。自助签到与人工补签并发时，由唯一约束保证只有一条记录成功。

## 8. 活动自动结束和出勤统计

### 8.1 索引批量扫描

结束任务使用可配置的固定延迟、批量大小和单次最大批次数。默认每批 100 个活动。查询形态为：

```sql
SELECT id
FROM activity
WHERE status = 'PUBLISHED'
  AND activity_end_at <= :now
  AND (activity_end_at > :cursorEndAt
       OR (activity_end_at = :cursorEndAt AND id > :cursorId))
ORDER BY activity_end_at, id
LIMIT :batchSize;
```

首批从最小游标开始，后续批次使用上一条 `(activity_end_at, id)`，不使用 `OFFSET`。联合索引 `(status, activity_end_at, id)` 必须通过真实 MySQL `EXPLAIN` 验证命中。

### 8.2 单活动独立事务

扫描器只读取 ID。每个活动由独立的 `REQUIRES_NEW` 事务处理：

1. 通过 `UPDATE ... WHERE id=? AND status='PUBLISHED' AND activity_end_at<=?` 竞争处理权。
2. 受影响行数不是 1 时结束，不重复处理。
3. 将已有签到记录的正式报名归档为 `CHECKED_IN`。
4. 将其余正式报名归档为 `NO_SHOW`。
5. 写入活动结束审计。

事务失败会回滚活动状态，下次任务可以重试。单个活动失败只记录活动 ID 和 requestId，不中断本批其他活动。

### 8.3 统计 API

```text
GET /api/v1/activities/{activityId}/attendance-summary
```

需要 `checkin:manage` 权限，返回：

- 正式录取总人数。
- 已签到人数。
- 未到场人数。
- 签到率。
- 各岗位的录取、签到和未到场人数。
- 活动是否已经完成最终归档。

活动进行中可以返回当前签到人数，但只有 `FINISHED` 状态的数据标记为最终统计。

## 9. 前端设计

### 9.1 通知中心

启用底部导航通知入口，新增通知列表和未读角标：

- 游标加载更多，不使用页码跳转。
- 未读通知醒目标记。
- 候补邀请和活动取消使用高优先级视觉样式。
- 点击时先标记已读，再按业务类型跳转到报名状态、活动详情或签到页。
- 支持单条已读和全部已读。

登录后每 30 秒刷新未读数；页面不可见时暂停，恢复可见时立即刷新；退出登录或会话失效时停止。阶段 3 不引入 WebSocket。

### 9.2 成员签到页

页面展示活动、岗位、截止时间和倒计时。用户输入普通签到码后提交。请求期间按钮禁用；刷新后从服务端恢复签到结果。场次关闭、代码错误、没有资格和重复签到使用不同的用户提示。

### 9.3 签到管理页

管理员可以：

- 选择有效期并开启签到。
- 查看只返回一次的签到码、复制提示和倒计时。
- 提前关闭场次。
- 查看当前签到人数。
- 按姓名或学号查找正式成员并填写原因后补签。
- 活动结束后查看总计和岗位明细。

### 9.4 正式变更页

管理页面只展示允许修改的字段，提交前显示字段级差异，并强制填写原因。容量增加时提示可能触发候补邀请。新增岗位先保存草稿，配置问题后独立发布。

前端权限只负责改善入口展示，所有权限和归属仍由后端判定。每个页面都处理加载、空数据、断网、403、状态冲突和过期响应；使用请求代次避免旧响应覆盖新页面。

## 10. 错误和安全边界

新增业务错误码至少包括：

```text
ACTIVITY_CHANGE_NOT_ALLOWED
POSITION_CHANGE_NOT_ALLOWED
NO_EFFECTIVE_CHANGE
CHECKIN_SESSION_ALREADY_OPEN
CHECKIN_SESSION_CLOSED
INVALID_CHECKIN_CODE
CHECKIN_NOT_ELIGIBLE
ALREADY_CHECKED_IN
MANUAL_REASON_REQUIRED
NOTIFICATION_NOT_FOUND
```

安全规则：

- 签到码明文不写数据库、日志、审计、指标或异常消息。
- 跨组织活动、岗位、场次、报名和通知必须显式校验逻辑引用。
- 普通成员不能读取他人的通知、签到记录或敏感资料。
- 关键操作使用数据库唯一约束、行锁和状态条件更新实现幂等。
- 阶段 3 不宣称已实现签到限流；限流明确属于阶段 4。

## 11. Docker 演示部署

新增：

```text
backend/Dockerfile
frontend/Dockerfile
deploy/docker-compose.yml
deploy/nginx/nginx.conf
deploy/.env.example
```

部署规则：

- 后端使用 Maven/Java 17 多阶段构建，运行层使用 Java 17 JRE。
- 前端使用 Node 多阶段构建，产物由 Nginx 托管。
- Nginx 使用 SPA fallback，并将 `/api`、`/actuator` 代理到后端。
- Compose 只管理 `backend` 和 `frontend-nginx`，不定义 MySQL 服务或数据卷。
- 后端通过 `DB_HOST`、`DB_PORT`、`DB_NAME`、`DB_USERNAME`、`DB_PASSWORD` 连接虚拟机上已有的 MySQL 容器。
- 不修改开发用 `application-local.yml`，不硬编码现有虚拟机地址和端口。
- `.env.example` 只保存占位值，真实凭据不得提交。
- Nginx 对外服务前等待后端健康检查通过。

## 12. 测试策略

严格执行测试先行：每个业务行为先增加会按预期失败的测试，确认失败原因后再写最小实现。

### 12.1 后端单元和接口测试

覆盖：

- 正式变更允许字段、禁止字段、时间边界、快照和通知事务回滚。
- 通知去重、游标分页、未读计数、已读幂等和越权隐藏。
- 签到码只保存摘要，正确码成功，错误码和过期码失败。
- 未录取、已取消、跨组织和活动结束后不能自助签到。
- 并发开启签到时最多一个有效场次。
- 自助签到与人工补签并发时最多一条记录。
- 结束任务的批量边界、条件更新、多实例重复扫描和单活动失败隔离。
- `NO_SHOW` 人工补签后变为 `CHECKED_IN`。
- 容量增加后按序递补且不超卖。

### 12.2 前端测试

Vitest 覆盖：

- 通知加载、已读、全部已读、未读角标和可见性轮询。
- 签到倒计时、表单错误、业务冲突和重复提交保护。
- 管理员开启、关闭、补签及统计展示。
- 正式变更差异确认、原因校验和旧响应隔离。

Playwright 覆盖成员通知跳转、普通签到、管理员补签、正式变更和越权页面。

### 12.3 真实 MySQL 验收

新增显式运行的 `Stage3VmAcceptanceIT`，连接用户现有虚拟机 MySQL 容器：

1. 创建隔离组织、活动、岗位和成员。
2. 产生报名、审核、候补和递补通知。
3. 正式修改活动并验证 A 类接收范围和终态排除。
4. 开启 15 分钟签到场次并验证数据库只有摘要。
5. 多成员自助签到，同一成员并发签到只有一次成功。
6. 管理员补签并验证原因和审计。
7. 活动到期后生成 `CHECKED_IN` 和 `NO_SHOW`。
8. 补签后将 `NO_SHOW` 改为 `CHECKED_IN`。
9. 使用 `EXPLAIN` 验证结束扫描命中联合索引。
10. 精确清理本轮生成的数据，不使用 `TRUNCATE`、全表删除或处理其他用户邀请。

## 13. 回归和验收命令

完成阶段 3 前必须执行：

```powershell
cd backend
.\mvnw.cmd clean verify

cd ..\frontend
npm test -- --run
npm run typecheck
npm run build

cd ..\deploy
docker compose --env-file .env config
docker compose --env-file .env up --build
```

随后从 Nginx 入口验证 Vue 页面、`/api` 和 `/actuator/health`，并确认后端实际连接已有 MySQL，而不是新建数据库容器。

## 14. 完成定义

阶段 3 只有在以下条件全部满足时才算完成：

- 已发布活动和岗位只能通过正式变更流程修改。
- 所有约定通知都可查询、去重、标记已读并遵守接收范围。
- 普通签到、人工补签、关闭场次和最终统计形成完整闭环。
- 活动结束任务有索引、批量上限、键集扫描、条件竞争和失败隔离证据。
- 后端、前端、真实 MySQL、浏览器关键流程和 Docker 演示入口均通过验收。
- README 更新为阶段 3 已完成，并明确 Redis 动态码、限流和 Kafka 仍未实现。
