# 个人资料、密码与草稿真实联调报告

日期：2026-10-24。以下结果来自当前 `D:\javaweb\VolunteerFlow` 代码的实际执行，不是部署计划，也不是用 mock 接口替代后端的演示。

## 实现范围

- 姓名、联系方式更新；用户名、学号和平台身份不可通过资料接口修改。
- 当前密码校验、12–72 字符且最多 72 UTF-8 字节的新密码策略。注册入口同步校验字节上限，登录对超限输入返回通用凭据错误，避免 BCrypt 异常变成 500。
- 修改密码事务内递增凭据版本并撤销全部刷新会话；旧 JWT、旧刷新 Cookie 和旧密码均不能继续使用，新密码可以登录。账号操作统一先锁 user，再锁 session。
- 按组织分页读取草稿、专用草稿详情、服务端恢复；草稿岗位编辑和逻辑删除，锁顺序为 activity → position。发布后拒绝草稿岗位修改。
- 显式 SQL 保存空值：清除活动免费取消截止时间和岗位集合地点后，重新读取数据库均为空，而非只在页面上消失。
- 删除岗位不物理删除历史问题，但禁止再读取或编辑它们。现有唯一键仍保留已删岗位名称，同名重建返回冲突；本轮没有调整该产品限制或修改共享库索引。

## 验证环境与隔离

- 独立数据库：`vf_acceptance_1987806a0b3c4c31a721847bd38bf317`。
- 后端：Java 17 / 最新打包 jar，独立 loopback:8082，`acceptance` profile，真实 Spring 事务、MyBatis-Plus、MySQL 与 Outbox。
- 前端：独立 loopback:5176，Vite 代理到 8082；Chrome headless，桌面及 390px 视口。没有 API interception，没有伪造业务时钟。
- DataSource 在 Flyway 前精确核验新库；拒绝错误库、专用 Flyway 连接及 schema 覆盖。匿名身份 GET 仅在验收 profile 放行，写入不放行。
- 五类维护作业与 Kafka consumer 停用，避免多实例扫描和共享消息消费。该开关正常环境默认启用，不改变生产业务主事务。
- 原 `volunteer_flow` 只读预检发现 V9 失败记录及 V10 表字段缺失；没有执行 repair、迁移、数据修复、容器重启，也没有修改现有 MySQL/Redis 地址。

## 实际结果

| 验证 | 结果 |
|---|---|
| 后端普通回归与 jar 打包 | 1057 tests，0 failures / errors / skips，BUILD SUCCESS |
| 前端完整回归与生产构建 | 30 个文件、237 tests 通过；TypeScript 检查与 Vite 构建通过 |
| Node 验收隔离检查 | 5 tests 通过 |
| 真实 Chrome + HTTP 核心流程 | `PASSED_CORE`，19 项检查通过 |
| 新库 Flyway | V1–V10 共 10 条版本迁移成功，失败数 0 |
| 实际业务落库 | 1 个已结束活动、1 个已接受递补、2 条签到记录 |
| Outbox 与通知 | 13 条 Outbox，已发布 0，通知 0；不能宣称 Kafka 通知送达 |

后端普通回归明确排除了七类部署脚本合约：`Stage3E2eFixtureContractTest`、`Stage3SmokeScriptContractTest`、`Stage3StackLauncherContractTest`、`Stage4dEnvironmentPreparationContractTest`、`Stage4dReadinessScriptTest`、`Stage4dRunnerContractTest`、`Stage5RunnerContractTest`。本轮未运行容器专用 `stage5-integration`、VM 压测、Nginx/HTTPS、真实手机或真实同学试用，不能外推为生产验收。

## 真实流程

1. 脚本先核验精确 UUID 数据库，再允许登录或注册。
2. 浏览器修改资料，真实 API 重新读取确认保存；资料页适配 390px，无横向溢出。
3. 浏览器修改密码并回到登录页；独立旧会话的 JWT、刷新 Cookie 和旧密码均被拒绝，新密码重新登录成功。
4. 平台管理员通过真实 HTTP 创建组织、生成邀请码，两名专属验收成员加入。
5. 浏览器新建活动草稿，从服务端列表重新进入；编辑岗位容量、清空可选字段，删除另一个岗位，重新加载验证持久化。
6. 浏览器配置必填公共问题后发布；普通成员读取草稿被拒绝，发布后的草稿岗位修改被拒绝。
7. 两名成员分别在真实浏览器填写动态报名表：容量 1 的岗位得到 1 名正式成员、1 名候补。
8. 正式成员取消，产生真实待确认递补邀请；候补在浏览器接受后成为正式成员。
9. 另一成员改报审核岗位，管理员通过审核；一人浏览器签到，另一人人工补签，补签包含原因。
10. 关闭签到、手动结束活动，最终统计确认 2 人签到；真实审计分页可读取操作轨迹。浏览器无未捕获 JavaScript 错误。

## 联调中发现并修复的问题

- **刷新页面掉线**：AppShell 在账号恢复前启动通知轮询，与路由恢复同时轮换 refresh token。现在仅在已认证非公开页面轮询，路由和 401 拦截器共用 single-flight 刷新；新增 RED→GREEN 回归。
- **零未读红点**：后端 Long 计数为字符串 `"0"`，前端直接按 truthy 显示。现在在 API 边界转换并校验为非负安全整数，空计数隐藏。
- **密码超限 500**：注册只校验字符数，25 个中文字符会超过 BCrypt 字节上限。现在注册与改密统一校验，登录保持通用错误。
- **草稿空值未落库**：MyBatis-Plus 默认跳过 null 字段。草稿更新改为组织、状态和 version 限定的显式 SQL，零行更新不记录成功审计。

前期失败的运行保留在独立证据目录，用于记录修复过程；也修正了验收脚本选择器约定和重新登录时误携带旧 bearer 的问题。最终通过运行于北京时间 14:53:47–14:54:05。隔离库中的 13 个账号来自多次自动验收尝试，**不是 13 名真实用户**；28 条审计为该专属库累计计数。

## 证据与复现

- 非敏感原始证据目录：`C:\Users\lenovo\Documents\Codex\2026-07-21\校园志愿活动报名与候补协同平台\output\playwright\live-core-1987806a-verified`。
- `live-core-report.json`：19 项逐条结果、开始/结束 UTC 时间、Kafka 未验证标记。
- `database-evidence.json`：只读迁移与行数证明，无账号密码、令牌或事件正文。
- 仓库内保留[19 项原始检查结果](evidence/2026-10-24-account-drafts/live-core-report.json)及[数据库只读计数](evidence/2026-10-24-account-drafts/database-evidence.json)，可独立查看，无需访问私密运行目录。
- `profile-desktop.png`、`profile-mobile.png`、`draft-desktop.png`、`draft-mobile.png`：密码为空、签到码未出现时截图；已人工查看页面布局。
- 后端测试证据：`backend/target/surefire-reports`。该目录是本地构建产物，不作为永久提交证据。
- [独立验收操作手册](../operations/isolated-core-acceptance.md)；所有私密运行配置、凭据及日志均不进入 Git。

## 保留边界

验收后仅停止本轮 PID 对应的 8082 后端和 5176 前端，未停止用户原有 IDEA 后端。独立验收库、测试记录和受限运行目录保留用于复查，未执行数据库删除或共享数据清理；临时地址不再作为常驻演示入口。

Kafka 的消息中继、消费、重复处理、停机恢复和通知中心真实送达仍需在可用 broker 环境单独验收。原共享库的 V9 恢复与 V10 升级需要另行备份、核对实际结构并制定迁移方案，本轮没有自动修复。
