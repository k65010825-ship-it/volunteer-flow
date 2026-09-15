# VolunteerFlow 后端

Java 17、Spring Boot 3.5、MyBatis-Plus、MySQL、Flyway 和 Spring Data Redis 的模块化单体工程。阶段 1 已实现认证、组织、邀请码、组织级单角色 RBAC，以及活动和多岗位生命周期。

## 在 IDEA 中运行

1. 用 IDEA 打开本目录的 `pom.xml`，项目 SDK 选 Java 17，等待 Maven 导入。
2. 创建 MySQL 数据库 `volunteer_flow`，并为应用账号授予此库的建表、读写权限。
3. 复制一份本地配置到 `backend/config/application-local.yml`，或者在运行配置中设置 `.env.example` 中的 `DB_*` 与 `REDIS_*` 环境变量。`application-local.yml` 已被 Git 忽略，也不会打入 JAR。
4. 运行 `com.volunteerflow.VolunteerFlowApplication`。启动时 Flyway 自动执行 `src/main/resources/db/migration` 中的迁移。
5. 使用 `http://localhost:8080/actuator/health/readiness` 检查应用和 MySQL；使用 `/actuator/health/dependencies` 查看 MySQL 与 Redis。

V1—V8 已在配置的开发数据库中实际执行，包含 22 张领域表、迁移标记表和 Flyway 历史表。数据库使用逻辑引用，不创建物理外键；跨组织和引用完整性由后续 Service 事务校验。Redis 当前只完成 Lettuce、`StringRedisTemplate`、Key 命名和健康检查基础，不参与业务正确性。

## 模块

`auth`、`organization`、`rbac`、`activity`、`registration`、`checkin`、`notification`、`audit` 是业务模块；`infrastructure` 放置 Web、安全与持久化公共配置。阶段 2 之后的报名、签到和通知模块目前只保留数据库结构与包边界。

## 接口约定

- 业务 API 使用 `/api/v1` 前缀；readiness 使用 `/actuator/health/readiness`。
- 成功响应使用 `ApiResponse`；业务错误和参数校验错误使用 `ProblemDetail`，附带 `code` 与 `requestId`。
- 请求 ID 由 `X-Request-Id` 透传或生成，用于响应头和日志关联。
- 注册、登录和刷新接口匿名可访问，其余业务接口默认要求 JWT Bearer 认证。
- 访问令牌只通过响应体返回；刷新令牌只写入 `HttpOnly` Cookie，并在每次刷新时轮换。重放旧刷新令牌会撤销对应令牌家族。
- 前端请求使用 `Authorization: Bearer <access-token>`；访问令牌失效时只允许自动刷新并重试一次。

## 阶段 1 API

- 认证：`POST /api/v1/auth/register`、`/login`、`/refresh`、`/logout`，以及 `GET /api/v1/auth/me`。
- 组织：平台管理员创建/停用组织；成员查看可见组织；负责人创建或停用邀请码；用户凭邀请码幂等加入。
- RBAC：成员单角色分配、权限目录、自定义角色创建/编辑/授权/停用/条件删除。受保护负责人角色始终拥有全部权限，且不能移除最后一名有效负责人。
- 活动：创建和编辑草稿、添加多个岗位、发布、取消，以及成员端已发布活动列表和详情。

典型手工验证顺序：登录取得访问令牌和刷新 Cookie → 创建组织并指定负责人 → 负责人创建邀请码 → 成员加入 → 创建活动与岗位 → 发布 → 成员查询活动列表和详情。邀请码原文只在创建响应中出现一次，数据库仅保存 SHA-256 摘要。

## Redis 边界

- Key 使用 `volunteerflow:{environment}:{module}:{business-key}`。
- Redis 未启动不会阻止应用启动；此时依赖健康组会报告 Redis `DOWN`。
- 活动缓存、限流、动态签到码和分布式辅助锁仍属于后续实现，不在当前基础接入范围内。

## 首个平台管理员

首次部署可以同时设置 `.env.example` 中全部五个 `BOOTSTRAP_ADMIN_*` 变量。系统仅在数据库尚无平台管理员时创建一次；五项全部留空会跳过，只配置部分字段会拒绝启动。管理员密码必须通过环境变量或已被 Git 忽略的本地配置提供，公开注册接口不会创建平台管理员。

## 验证

推荐在 `backend` 目录执行 `.\\mvnw.cmd clean verify`。Maven Wrapper 固定使用 Maven 3.9.12，并从 HTTPS Maven Central 下载 Maven；项目不覆盖你的全局 Maven settings。

连接本地配置中的 VM MySQL 执行阶段 1 完整事务验收：`.\\mvnw.cmd -Dtest=Stage1VmAcceptanceIT test`。该测试创建临时用户、组织、邀请码和多岗位活动，完成发布与成员查询后由测试事务自动回滚；默认测试套件不会自动执行这个 `*IT` 文件。
