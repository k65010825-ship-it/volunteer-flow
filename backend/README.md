# VolunteerFlow 后端

Java 17、Spring Boot 3.5、MyBatis-Plus、MySQL、Flyway 和 Spring Data Redis 的模块化单体工程。阶段 0 数据库结构和 Redis 基础设施已经建立；认证、组织、RBAC、活动、报名、签到等业务接口尚未实现。

## 在 IDEA 中运行

1. 用 IDEA 打开本目录的 `pom.xml`，项目 SDK 选 Java 17，等待 Maven 导入。
2. 创建 MySQL 数据库 `volunteer_flow`，并为应用账号授予此库的建表、读写权限。
3. 复制一份本地配置到 `backend/config/application-local.yml`，或者在运行配置中设置 `.env.example` 中的 `DB_*` 与 `REDIS_*` 环境变量。`application-local.yml` 已被 Git 忽略，也不会打入 JAR。
4. 运行 `com.volunteerflow.VolunteerFlowApplication`。启动时 Flyway 自动执行 `src/main/resources/db/migration` 中的迁移。
5. 使用 `http://localhost:8080/actuator/health/readiness` 检查应用和 MySQL；使用 `/actuator/health/dependencies` 查看 MySQL 与 Redis。

V1—V8 已在配置的开发数据库中实际执行，包含 22 张领域表、迁移标记表和 Flyway 历史表。数据库使用逻辑引用，不创建物理外键；跨组织和引用完整性由后续 Service 事务校验。Redis 当前只完成 Lettuce、`StringRedisTemplate`、Key 命名和健康检查基础，不参与业务正确性。

## 模块

`auth`、`organization`、`rbac`、`activity`、`registration`、`checkin`、`notification`、`audit` 是业务模块；`infrastructure` 放置 Web、安全与持久化公共配置。模块目前以包文档占位，避免在需求实现前引入空 Controller/Service/Mapper。

## 接口约定

- 业务 API 使用 `/api/v1` 前缀；readiness 使用 `/actuator/health/readiness`。
- 成功响应使用 `ApiResponse`；业务错误和参数校验错误使用 `ProblemDetail`，附带 `code` 与 `requestId`。
- 请求 ID 由 `X-Request-Id` 透传或生成，用于响应头和日志关联。
- 除健康检查及预留的 `/api/v1/auth/**` 外，所有路径默认要求认证。JWT 实现将在认证阶段加入；当前骨架没有可供登录的业务接口。

## Redis 边界

- Key 使用 `volunteerflow:{environment}:{module}:{business-key}`。
- Redis 未启动不会阻止应用启动；此时依赖健康组会报告 Redis `DOWN`。
- 活动缓存、限流、动态签到码和分布式辅助锁仍属于后续实现，不在当前基础接入范围内。

## 首个平台管理员

首次部署可以同时设置 `.env.example` 中全部五个 `BOOTSTRAP_ADMIN_*` 变量。系统仅在数据库尚无平台管理员时创建一次；五项全部留空会跳过，只配置部分字段会拒绝启动。管理员密码必须通过环境变量或已被 Git 忽略的本地配置提供，公开注册接口不会创建平台管理员。

## 验证

推荐在 `backend` 目录执行 `.\\mvnw.cmd test`。Maven Wrapper 固定使用 Maven 3.9.12，并从 HTTPS Maven Central 下载 Maven；项目不覆盖你的全局 Maven settings。
