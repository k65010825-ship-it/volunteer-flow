# VolunteerFlow 阶段 2：报名、候补与递补设计

- 日期：2026-09-15
- 状态：待用户复核
- 技术基线：Java 17、Spring Boot 3.x、MyBatis-Plus、MySQL 8、Vue 3
- 前置版本：阶段 1 组织、RBAC、活动和岗位功能

## 1. 目标和范围

阶段 2 建立从“填写报名表”到“确认、审核、候补、取消、递补确认”的完整业务闭环。

本阶段实现：

- 活动公共问题和岗位专属问题。
- `FIRST_COME` 先到先得报名。
- `REVIEW` 人工审核报名。
- 有序候补队列和无序候选池。
- 报名取消、迟取消和重新报名周期。
- 限时递补邀请、接受、拒绝和过期处理。
- 成员报名状态页和管理员审核页面。
- 报名、审核、取消和递补的关键操作审计。
- MySQL 并发正确性测试。

本阶段不实现：

- Redis 分布式锁、限流或缓存。
- Kafka、Outbox 发布器或异步消费者。
- 站内通知中心；阶段 2 直接通过报名状态和递补邀请页面展示结果。
- 签到、活动聊天室和 AI。

## 2. 架构选择

采用 MySQL 作为唯一正确性来源。报名主事务依靠 InnoDB 行锁、唯一约束、状态条件更新和事务回滚保证不超卖、不重复报名以及候补顺序稳定。

Redis 不进入本阶段的正确性路径。阶段 4 接入 Redis 后，只能作为数据库事务之前的并发辅助和体验增强；Redis 不可用时仍必须回退到本设计。

后端保持模块化单体：

```text
activity
├─ 报名问题定义与发布冻结规则
registration
├─ 报名主记录与报名周期
├─ 答案校验与保存
├─ 审核与候选池
├─ 取消
└─ 递补邀请与过期扫描
audit
└─ 同事务记录关键状态变化
```

`registration` 模块只能通过活动模块公开的查询服务读取活动、岗位和问题，不直接任意调用活动模块内部 Mapper。为现有代码渐进改造时，可以先提供只读的 `ActivityRegistrationPolicyService` 作为模块边界。

## 3. 数据模型

沿用已存在的 Flyway 表：

- `activity_question`：活动公共问题。
- `activity_position_question`：岗位专属问题。
- `registration`：用户和活动之间的稳定主记录，唯一键为 `(activity_id, user_id)`。
- `registration_cycle`：每次报名尝试及其状态历史。
- `registration_answer`：某次报名周期的答案快照。
- `promotion_offer`：限时递补邀请。

所有关联继续使用逻辑外键，不增加物理外键。服务层必须检查组织、活动、岗位、报名周期和当前用户之间的归属关系。

如现有表无法可靠表达幂等状态条件或高频查询，只能通过新的 Flyway 迁移增加索引或约束，不修改已经执行的 V1 至 V8 文件。

## 4. 报名问题

报名问题分为：

- `ACTIVITY`：选择任意岗位都必须回答的活动公共问题。
- `POSITION`：仅报名指定岗位时回答的岗位问题。

支持题型：

- `TEXT`
- `SINGLE_CHOICE`
- `MULTIPLE_CHOICE`
- `BOOLEAN`

规则：

- 公共问题和所选岗位问题合计最多 10 个。
- 标题必填，长度不超过 255 个字符。
- `SINGLE_CHOICE` 和 `MULTIPLE_CHOICE` 必须提供 2 至 20 个去重后的非空选项。
- `TEXT` 和 `BOOLEAN` 不保存选项。
- 必填问题必须提供有效答案。
- 单选答案必须属于选项集合；多选答案必须是选项集合的非空子集且不能重复。
- 活动发布后，已有公共问题和已有岗位的问题不能修改或删除。
- 问题答案以 JSON 快照保存，历史报名不受后续新增岗位问题影响。

发布活动时，对每个有效岗位分别计算“公共问题数 + 该岗位问题数”，任一岗位超过 10 题都拒绝发布。没有自定义问题的活动仍允许发布。

## 5. 报名状态机

报名周期状态：

```text
PENDING_REVIEW
CONFIRMED
WAITLISTED
REJECTED
CANCELED
LATE_CANCELED
PROMOTION_DECLINED
PROMOTION_EXPIRED
```

允许的主要转换：

```text
FIRST_COME:
提交 -> CONFIRMED
提交 -> WAITLISTED
WAITLISTED -> CONFIRMED（接受递补）
WAITLISTED -> PROMOTION_DECLINED
WAITLISTED -> PROMOTION_EXPIRED

REVIEW:
提交 -> PENDING_REVIEW
PENDING_REVIEW -> CONFIRMED
PENDING_REVIEW -> WAITLISTED（进入无序候选池）
PENDING_REVIEW -> REJECTED
WAITLISTED -> CONFIRMED（接受管理员发出的递补）
WAITLISTED -> PROMOTION_DECLINED
WAITLISTED -> PROMOTION_EXPIRED

可取消状态:
PENDING_REVIEW / CONFIRMED / WAITLISTED -> CANCELED 或 LATE_CANCELED
```

终态不能再次转换。成员如需再次报名，复用 `registration` 主记录并新建 `cycle_number + 1` 的报名周期；旧周期、旧答案和旧审计记录保持不变。

同一用户在同一活动任何时刻只能存在一个非终态报名周期。换岗必须先取消当前周期，再创建新周期，新岗位不继承旧候补序号。

## 6. 两种报名模式

### 6.1 先到先得

提交时锁定目标岗位并计算占用数：

```text
占用数 = CONFIRMED 报名数 + expires_at > 数据库当前时间的 PENDING 邀请数
```

- 占用数小于岗位容量：创建 `CONFIRMED` 周期。
- 占用数达到容量：创建 `WAITLISTED` 周期，并使用岗位的 `next_waitlist_sequence` 分配不可修改的序号，然后原子递增该字段。

页面展示用户的原始候补序号、当前有效位次和当前候补总人数，不返回其他候补者身份。

### 6.2 人工审核

提交后创建 `PENDING_REVIEW` 周期。具有 `registration:review` 权限的管理员可以：

- `CONFIRM`：有空位时转为 `CONFIRMED`；无空位时返回容量冲突，不允许超额录取。
- `WAITLIST`：转为 `WAITLISTED`，进入无序候选池，不分配候补序号。
- `REJECT`：转为 `REJECTED`。

审核必须记录操作者、时间和原因。人工审核岗位出现空位时不自动选择候选人，由具有 `registration:promote` 权限的管理员选择候选周期并发出递补邀请。

## 7. 并发和锁顺序

报名、审核、取消和递补事务统一采用以下锁顺序；不需要的锁可以跳过，但不能逆序获取：

1. 锁定当前用户在该组织的 `organization_member` 记录。
2. 校验并锁定目标 `activity_position` 记录。
3. 查询或创建 `(activity_id, user_id)` 的 `registration` 主记录。
4. 检查现有非终态周期。
5. 计算容量并创建新周期、答案和审计记录。

先锁成员记录可以串行化同一用户同时报名同一活动不同岗位的请求；再锁岗位记录可以串行化岗位容量、候补序号和递补邀请的变化。所有事务遵循相同顺序，降低死锁风险。

数据库约束继续作为最后防线：

- `registration(activity_id, user_id)` 唯一。
- `registration_cycle(registration_id, cycle_number)` 唯一。
- `registration_cycle(position_id, waitlist_sequence)` 唯一；人工候选池使用 `NULL`。
- `promotion_offer(registration_cycle_id)` 唯一。

遇到唯一键竞争或乐观锁冲突时，接口返回稳定的 `409` 业务错误，不向客户端暴露 SQL 异常。

## 8. 取消和递补

取消时按统一顺序锁定成员、岗位和报名周期：

- `free_cancel_deadline_at` 为空时，以 `activity_start_at` 作为免费取消截止时间。
- 当前时间不晚于实际免费取消截止时间：状态变为 `CANCELED`。
- 超过免费取消截止时间：必须提交非空原因，状态变为 `LATE_CANCELED`。
- 如果存在 `PENDING` 递补邀请，同时将邀请变为 `CANCELED`。
- 如果释放了正式名额或预留名额，执行对应岗位的递补逻辑。

`FIRST_COME` 岗位在同一事务中选择最小 `waitlist_sequence` 的有效候补并创建 `PENDING` 邀请。邀请到期时间等于数据库当前时间加岗位的 `promotion_timeout_minutes`。

`REVIEW` 岗位只释放名额，不自动选择候选人。管理员创建邀请前必须重新锁定岗位、检查候选周期和容量。

接受邀请时必须同时满足：

- 操作者是该报名周期所属用户。
- 邀请状态为 `PENDING`。
- 数据库当前时间早于 `expires_at`。
- 报名周期仍为 `WAITLISTED`。

接受后邀请变为 `ACCEPTED`，周期变为 `CONFIRMED`。拒绝后邀请变为 `DECLINED`，周期变为 `PROMOTION_DECLINED`；先到先得岗位继续邀请下一位。

定时任务默认每 30 秒扫描一次，每批最多处理 100 条已到期的 `PENDING` 邀请，通过 `SELECT ... FOR UPDATE SKIP LOCKED` 分批处理。扫描间隔和批次大小使用外部配置。过期后邀请变为 `EXPIRED`，周期变为 `PROMOTION_EXPIRED`；先到先得岗位继续递补。任务可重复执行且不会重复处理同一邀请。

## 9. API 设计

问题管理：

```text
POST   /api/v1/activities/{activityId}/questions
PUT    /api/v1/activities/{activityId}/questions/{questionId}
DELETE /api/v1/activities/{activityId}/questions/{questionId}
POST   /api/v1/positions/{positionId}/questions
PUT    /api/v1/positions/{positionId}/questions/{questionId}
DELETE /api/v1/positions/{positionId}/questions/{questionId}
GET    /api/v1/activities/{activityId}/registration-form?positionId={positionId}
```

成员报名：

```text
POST /api/v1/activities/{activityId}/registrations
GET  /api/v1/registrations/{registrationId}
GET  /api/v1/users/me/registrations
POST /api/v1/registrations/{registrationId}/cancellation
POST /api/v1/promotion-offers/{offerId}/responses
```

管理员操作：

```text
GET  /api/v1/positions/{positionId}/registrations?status={status}
POST /api/v1/registrations/{registrationId}/review-decisions
POST /api/v1/registrations/{registrationId}/promotion-offers
```

报名提交体包含 `positionId` 和答案数组；答案项包含 `questionScope`、`questionId` 和 `answer`。服务端不信任客户端提交的问题标题、类型或选项。

递补响应只接受 `ACCEPT` 或 `DECLINE`。审核决策只接受 `CONFIRM`、`WAITLIST` 或 `REJECT`。

错误语义：

- `403`：缺少审核或递补权限。
- `404`：资源不存在、跨组织访问或不属于当前用户。
- `409`：重复报名、状态冲突、容量竞争失败、重复响应或邀请过期。
- `422`：报名窗口关闭、活动未发布、答案无效、迟取消缺少原因。

## 10. 前端设计

活动详情页加载所选岗位对应的完整报名表。提交期间禁用按钮，成功后跳转报名状态页。

成员端新增：

- 动态题型控件和答案校验提示。
- 我的报名列表与报名详情。
- `CONFIRMED`、`PENDING_REVIEW`、`WAITLISTED` 等状态说明。
- 先到先得候补位次。
- 递补邀请倒计时以及接受、拒绝按钮。
- 取消报名和迟取消原因输入。

管理员端新增：

- 报名问题配置。
- 按岗位和状态查看报名。
- 人工审核决策。
- 人工候选池选择和递补邀请。

前端倒计时只用于显示，是否过期始终以后端数据库时间判断。前端路由和按钮隐藏只改善体验，不能替代后端 RBAC。

## 11. 审计

以下操作与业务数据在同一事务中写入 `audit_log`：

- 报名提交及最终初始状态。
- 审核确认、进入候选池和拒绝。
- 正常取消和迟取消。
- 递补邀请创建、接受、拒绝和过期。
- 重新报名周期创建。

审计详情只保存必要的状态、岗位和原因，不保存完整联系方式、令牌或无必要的完整报名答案。

## 12. 测试和验收

采用测试驱动开发，每个状态转换先写失败测试，再实现最小逻辑。

单元测试覆盖：

- 四种题型和必填答案校验。
- 两种报名模式的初始状态。
- 重复报名和重报周期。
- 审核三种决策。
- 正常取消、迟取消和原因校验。
- 邀请接受、拒绝、过期和错误操作者。

MySQL 集成测试覆盖：

- 多请求竞争最后一个岗位名额时不超卖。
- 同一用户并发报名不同岗位时最多一个有效周期。
- 候补序号唯一且严格递增。
- 取消正式报名后只创建一个有效递补邀请。
- 过期扫描重复运行保持幂等。
- 跨组织和越权访问被拒绝。

核心验收场景：

1. 创建容量为 20 的先到先得岗位。
2. 前 20 人报名后状态为 `CONFIRMED`。
3. 第 21 人状态为 `WAITLISTED`，当前位次为 1。
4. 一名正式成员取消。
5. 第 21 人获得一条限时 `PENDING` 递补邀请。
6. 第 21 人接受后转为 `CONFIRMED`。
7. 岗位占用数始终不超过 20。
8. 报名、候补、取消、邀请和接受均存在审计记录。

阶段 2 完成时，前后端核心流程、自动化测试和数据库约束必须同时通过；不能仅以接口能够手工调用作为完成依据。
