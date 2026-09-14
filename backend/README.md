# VolunteerFlow 后端

Java 17、Spring Boot 3.5、MyBatis-Plus、MySQL 和 Flyway 的模块化单体工程。当前只搭建阶段 0 骨架；认证、组织、RBAC、活动、报名、签到等业务接口尚未实现。

## 在 IDEA 中运行

1. 用 IDEA 打开本目录的 `pom.xml`，项目 SDK 选 Java 17，等待 Maven 导入。
2. 创建本地 MySQL 8 数据库 `volunteer_flow`，并为应用账号授予此库的建表、读写权限。
3. 在运行配置中设置 `DB_USERNAME`、`DB_PASSWORD`；远程数据库再设置 `DB_HOST`、`DB_PORT`、`DB_NAME`。`.env.example` 是变量名示例，不能把实际密码提交到 Git。
4. 运行 `com.volunteerflow.VolunteerFlowApplication`。启动时 Flyway 自动执行 `src/main/resources/db/migration` 中的迁移。
5. 浏览 `http://localhost:8080/actuator/health`，应返回 `UP`。

当前迁移仅验证迁移链路，并未创建业务表；完整领域表结构将按正式设计规格继续实现。因为本机未安装 Docker，本次尚未进行 MySQL 容器启动及真实数据库迁移验证。

## 模块

`auth`、`organization`、`rbac`、`activity`、`registration`、`checkin`、`notification`、`audit` 是业务模块；`infrastructure` 放置 Web、安全与持久化公共配置。模块目前以包文档占位，避免在需求实现前引入空 Controller/Service/Mapper。

## 接口约定

- 业务 API 使用 `/api/v1` 前缀；健康检查使用 `/actuator/health`。
- 成功响应使用 `ApiResponse`；业务错误和参数校验错误使用 `ProblemDetail`，附带 `code` 与 `requestId`。
- 请求 ID 由 `X-Request-Id` 透传或生成，用于响应头和日志关联。
- 除健康检查及预留的 `/api/v1/auth/**` 外，所有路径默认要求认证。JWT 实现将在认证阶段加入；当前骨架没有可供登录的业务接口。

## 验证

推荐在 `backend` 目录执行 `.\\mvnw.cmd test`。Maven Wrapper 固定使用 Maven 3.9.12，并从 HTTPS Maven Central 下载 Maven；项目不覆盖你的全局 Maven settings。
