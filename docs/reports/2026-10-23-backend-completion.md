# 2026-10-23 后端核心缺口交付说明

## 交付范围与当前状态

本轮六类核心后端缺口及最后递补邀请创建门检查已完成，本地最终clean回归975项通过；不代表整个产品、全部后续阶段或未来功能已经完成。真实MySQL/Kafka链路、真实数据库锁竞争、实际部署和前端完整交互仍在本报告的验证边界之外。

本报告的951项通过结果保留为最后递补邀请创建门检查之前的历史回归基线；最后补丁已完成RED→GREEN及独立复审，最终975项结果见文末，不与历史基线混用。

| 类别 | 本轮交付 | 边界 |
| --- | --- | --- |
| 组织停用后访问控制 | 有效成员鉴权同时要求组织ACTIVE；停用组织不能继续通过组织业务授权或邀请码新增加入 | 已经获得授权的并发事务可能完成；不宣称真实数据库并发已验证 |
| 角色生命周期与成员分配 | 编辑、停用、删除、成员角色分配沿组织锁串行化；保护负责人角色、最后一名有效负责人及在用角色引用 | 不新增权限目录；角色/权限配置列表本轮不分页 |
| 组织审计只读查询 | `audit:read`鉴权、组织范围分页、条件筛选、稳定排序和安全快照投影 | 无审计修改/删除入口；不回传原始敏感JSON或自由备注 |
| 管理员提前结束活动 | 明确completion动作、`activity:change`、必填原因、FINISHED幂等，复用出勤归档、审计和缓存失效 | 保留原计划结束时间；不伪造到期时间、不创造pending清理或结束通知状态协议 |
| 人工审核递补需管理处理通知 | REVIEW岗位邀请被拒绝或过期后，通知组织内有效且有`registration:promote`权限的管理员；收件人在业务事务内固定，复用Outbox/通知处理链路 | 不自动替管理员选择候选人；本地测试不等于真实Kafka通知送达 |
| 三个核心列表数据库分页 | 活动、我的报名、组织成员保持`data[]`，新增`meta`；数据库分页和count；稳定排序；本页依赖批量读取 | 前端翻页尚未接入；不宣称所有配置集合或所有列表已分页 |

## API交付与兼容说明

### 审计日志

```text
GET /api/v1/organizations/{orgId}/audit-logs
    ?page=1&size=20&actorUserId=21&action=position.changed
    &resourceType=activity_position&resourceId=50
    &from=2026-10-01T00:00:00&to=2026-11-01T00:00:00
```

- 身份来自当前认证用户；要求组织内`audit:read`，非成员404、缺权限403。
- `page`默认1、最小1；`size`默认20，限制1..100。
- 时间范围为`[from,to)`，双端存在时要求`from < to`；按`create_time DESC,id DESC`排序。
- 返回`data[]`和`meta={total,page,size}`。条目包含动作、操作者ID、资源引用、时间和安全的变更摘要。
- `before/after`只保留明确白名单且类型正确的结构化业务值；标题、说明、地点等自由文本只通过`changedFields`说明哪些字段改变，不回传值；未知键、原始JSON、reason、IP和requestId不回传。历史坏快照不会原样暴露。

### 手动结束

```http
POST /api/v1/activities/{id}/completion
Content-Type: application/json

{"reason":"天气变化，提前结束"}
```

- 要求`activity:change`；原因必须为1..500字符。
- PUBLISHED→FINISHED；授权用户重复结束已FINISHED活动返回200，无重复归档/审计/缓存副作用；DRAFT/CANCELED返回409。
- 保留`activityEndAt`，使用专用、组织隔离的状态条件更新，不把未来时间伪装成当前时间去调用自动到期任务。
- 手动与自动共用当前CONFIRMED报名出勤归档；有有效签到为CHECKED_IN，其他为NO_SHOW，计数不匹配或审计写入失败会使事务失败。
- 普通/动态自助签到以及递补接受已有FINISHED拒绝检查，即使计划结束时间仍在未来也不能继续录取或自助签到。人工补签在结束后仍按阶段3规则允许。
- 自动归档原有到期谓词与独立事务保留；不把已有pending报名/邀请自行改成新终态，也不新增结束事件协议。

### 角色管理与组织停用相关接口

本轮保护覆盖已有组织停用、邀请码加入、组织业务授权和角色管理边界：

```text
POST   /api/v1/organizations/{id}/deactivation
POST   /api/v1/organization-memberships
PUT    /api/v1/organizations/{id}/roles/{roleId}
POST   /api/v1/organizations/{id}/roles/{roleId}/deactivation
DELETE /api/v1/organizations/{id}/roles/{roleId}
PATCH  /api/v1/organizations/{id}/members/{memberId}/role
```

角色写操作先锁组织，再授权和校验状态/引用，避免在等待锁前建立旧角色/成员快照；不将单元内存锁模型当作真实MySQL锁行为证据。

### 核心列表分页

```text
GET /api/v1/organizations/{orgId}/activities?page=1&size=20
GET /api/v1/users/me/registrations?page=1&size=20
GET /api/v1/organizations/{orgId}/members?page=1&size=20
```

三个接口默认page=1、size=20；page最小1、size限制1..100。保留原有数组项和`data[]`，新增顶层meta，不改成`data.items`：

```json
{
  "data": [],
  "meta": { "total": "205", "page": "12", "size": "20" }
}
```

示例为超出末页的空页，总量仍保留；项目long字段使用字符串序列化。客户端应读取meta并请求下一页：

```ts
const response = await apiClient.get(url, { params: { page: 2, size: 20 } });
const items = response.data.data;
const { total, page, size } = response.data.meta;
const hasNext = Number(page) * Number(size) < Number(total);
```

活动固定按`activity_start_at,id`升序，我的报名按`update_time DESC,id DESC`，成员按`joined_at,id`升序。活动岗位改为本页ID的一次批量查询，我的报名继续使用本页ID的cycle/answer/offer/metrics批量投影；不以全量读取后内存slice代替数据库分页。

前端原有`.data.data`读取在结构上仍兼容，但现在只显示默认第一页；需要后续接入meta和翻页，才能访问第20条之后的数据。本轮没有交付翻页UI。角色/权限作为管理配置集合保留既有非分页协议，不属于本轮三个核心列表的完成声明。详细契约见[核心列表分页接口](../specs/2026-10-23-core-list-pagination.md)。

## 相关提交

以下commit已在当前分支核对：

- `def06df` — 组织停用后的访问与邀请码加入拒绝。
- `aed04c0` — 组织范围审计只读查询。
- `d3438a2` — 角色状态与成员角色变更串行保护。
- `aa5a374` — 经授权、幂等的管理员手动结束。

审核递补管理员通知、核心分页以及最后邀请创建门检查的最终提交由主代理统一完成；本报告不为未提交内容编造commit。

## 已运行的本地验证基线

环境：Java17.0.10，未修改项目配置；共享工作树测试按串行窗口运行。

1. 分页与受影响Controller/Service定向回归：105项，0失败/0错误/0跳过。
2. 最终clean选择性后端回归：949项，0失败/0错误/0跳过，2026-10-23 19:08:38 +08:00结束，BUILD SUCCESS。
3. 另补`VolunteerFlowApplicationTests`：2项，0失败/0错误/0跳过，19:09:15结束，BUILD SUCCESS，test profile。
4. clean后Surefire XML保留95个suite，合计951项，0失败/0错误/0跳过。该结果是在最后邀请创建门检查补丁前获得的基线。

实际命令：

```powershell
.\mvnw.cmd clean test '-Dtest=*Test,!Stage3E2eFixtureContractTest,!Stage3SmokeScriptContractTest,!Stage3StackLauncherContractTest,!Stage4dEnvironmentPreparationContractTest,!Stage4dReadinessScriptTest,!Stage4dRunnerContractTest,!Stage5RunnerContractTest'
.\mvnw.cmd '-Dtest=VolunteerFlowApplicationTests' test
```

精确排除的7个离线部署脚本测试类为：

- Stage3E2eFixtureContractTest
- Stage3SmokeScriptContractTest
- Stage3StackLauncherContractTest
- Stage4dEnvironmentPreparationContractTest
- Stage4dReadinessScriptTest
- Stage4dRunnerContractTest
- Stage5RunnerContractTest

已核对这7类没有出现在clean后Surefire报告中。排除不等于skip，951不包含这7类；其他普通部署文档契约测试仍保留。Maven默认排除的integration目录、VM acceptance IT和stage5-integration opt-in集成没有执行。日志中的原有deprecation/unchecked提示、JVM CDS警告和负向故障注入WARN不改变上述结果。

## 尚未验证与范围外事项

- 未访问恢复VM或真实MySQL/Kafka；没有新增迁移或执行部署。
- 内存事务回滚/行锁模型、mock数据库边界和生成SQL测试不等于真实MySQL回滚、隔离级别或并发竞争验证；真实Kafka投递、消费、重放和用户可见送达也未验证。
- 缓存测试/复用提交后失效接口不等于实际部署缓存链路已验收。
- 未做线上容量或时延验收；稳定双键排序不保证跨页请求在并发变更时拥有同一快照。
- 前端需meta翻页接入；角色/权限配置集合未分页；结束后的pending收尾/结束通知需另行明确规格。
- 本轮不包含聊天室、未来AI能力或其他未列出的产品功能，不能把后端六类缺口补齐扩大成“所有未来功能已完成”。

## 最后邀请创建门检查与最终验证

- 状态：已完成，独立规格/质量复审PASS。门检查专属24项先RED（21项失败），修复后GREEN；此为主代理提供的最终执行证据。
- 创建递补邀请前，在持有活动父行锁后确认活动仍允许产生邀请；活动已FINISHED/CANCELED或已到计划结束时间时不得产生新邀请。不通过伪造时间或引入反向组织/成员锁处理该边界。
- 并发契约为明确的弱边界：已经越过最终门检查、与组织停用等状态变化重叠的事务可以完成；不承诺事后撤回已授权事务，也不以新增反向加锁制造更强保证。
- 主代理最终执行下述clean命令，`*Test,*Tests`同时包含VolunteerFlowApplicationTests的2项；2026-10-23 19:17:58 +08:00结束，BUILD SUCCESS，975项/0失败/0错误/0跳过。无需在此轮额外重复应用上下文测试。

```powershell
.\mvnw.cmd clean test '-Dtest=*Test,*Tests,!Stage3E2eFixtureContractTest,!Stage3SmokeScriptContractTest,!Stage3StackLauncherContractTest,!Stage4dEnvironmentPreparationContractTest,!Stage4dReadinessScriptTest,!Stage4dRunnerContractTest,!Stage5RunnerContractTest'
```

975项仍精确排除前述同7个离线部署脚本测试类，不包含VM acceptance IT或stage5-integration opt-in集成；零skip不表示被显式排除的类已执行。951仅是历史基线，最终完成结论采用最后补丁后的975项结果。

最终提交commit由主代理统一提交后记录；本说明写入时未为该批尚未提交内容编造commit。本轮完成结论仅限六类核心后端及其最后门检查，不包含live集成、部署或未实施前端功能。
