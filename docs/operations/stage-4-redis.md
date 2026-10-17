# 阶段 4B–4C：Redis 动态签到与接口限流操作手册

本阶段在已有普通签到码和人工补签能力之上增加动态二维码。后端仍以 MySQL
事务、行锁和唯一约束决定签到结果；Redis 只保存短期凭证，不参与报名名额正确性，
也不进入 MySQL 签到事务。

## 配置

部署环境复用现有虚拟机 Redis，不创建或管理新的 Redis 容器。将
`deploy/.env.example` 复制为被 Git 忽略的 `deploy/.env`，填写现有虚拟机 Redis
的可达地址、端口、密码和逻辑库；不要把真实地址或密码提交到仓库。

动态凭证参数如下：

```dotenv
DYNAMIC_CHECKIN_WINDOW=30s
DYNAMIC_CHECKIN_TTL=45s
DYNAMIC_CHECKIN_TOKEN_BYTES=24
```

- 每 30 秒进入一个新窗口，并为新窗口生成随机凭证。
- 凭证从窗口起点计算 45 秒绝对有效期，因此上一窗口只有有限宽限期。
- 24 字节随机值使用无填充 Base64URL 编码，大小写敏感。
- `DYNAMIC_CHECKIN_TTL` 必须大于 `DYNAMIC_CHECKIN_WINDOW`。

Redis 连接参数为 `REDIS_HOST`、`REDIS_PORT`、`REDIS_PASSWORD`、
`REDIS_DATABASE`、`REDIS_CONNECT_TIMEOUT` 和 `REDIS_COMMAND_TIMEOUT`。
后端 readiness 仍只包含 `readinessState,db`，Redis 故障不能让普通接口和人工补签
整体失去就绪状态。

## 接口和浏览器流程

管理员开启 `DYNAMIC_QR` 会话后，前端调用：

```http
GET /api/v1/check-in-sessions/{sessionId}/dynamic-code
```

前端在浏览器本地生成二维码，并根据 `expiresAt` 临近时间请求下一张，不进行高频轮询。
二维码目标形如：

```text
/activities/<activityId>/check-in?sessionId=<sessionId>#window=<window>&token=<token>
```

`window` 与 `token` 只位于 `#` 后的 Fragment，不在查询字符串中，因此不会进入普通
Nginx HTTP 请求行和访问日志。成员页面读取成功后，先用 `history.replaceState` 清除
Fragment，再显示确认按钮；取消确认不会提交请求。确认后调用：

```http
POST /api/v1/check-in-sessions/{sessionId}/dynamic-check-ins
```

Redis 校验在 MySQL 事务外完成，进入事务的内部 proof 只包含会话、窗口和绝对过期时间。
数据库行锁取得后会再次检查 proof 是否过期；唯一约束继续阻止重复签到。

## 故障和回退

- 无效、缺失、过期、未来窗口或跨会话凭证统一返回
  `422 INVALID_DYNAMIC_CHECKIN_CODE`，不说明具体失败字段。
- Redis 发放或校验不可用时返回 `503 DYNAMIC_CHECKIN_UNAVAILABLE`，不进入动态签到的
  MySQL 写事务。
- 管理员仍可使用已有人工补签流程；普通静态签到码兼容接口也保持可用。
- 关闭动态会话后，仅在 MySQL 事务提交成功时清理当前和上一窗口键；清理失败只记录
  会话 ID 与异常类型，凭证依靠 TTL 最终过期，也不会回滚已关闭的会话。

## 本地验证

```powershell
cd backend
.\mvnw.cmd test
cd ..\frontend
npm test -- --run
npm run build
```

上述命令验证源码契约、单元/组件测试与生产构建，不需要在线 Redis。要形成真实运行证据，
还必须在能访问现有虚拟机 Redis 的部署主机上完成 Compose 启动、管理员展示二维码、真实
手机扫描、成员确认签到、Redis 停机返回 503、人工补签回退及恢复后轮换测试。

当前仓库只声称源码与自动化测试已完成；尚未完成真实手机与 Redis 联调验证，也未据此
声称真实网络、Nginx 访问日志或多用户现场流程已经验收。

## 阶段 4C 接口限流

限流覆盖登录、报名、候补邀请响应、普通签到和动态签到五类写命令。注册、刷新令牌、
取消报名、人工审核、人工补签、签到会话管理及查询接口不在本轮限流范围内。默认策略为：

| 动作 | 主体 | 默认策略 |
|---|---|---|
| 登录 IP | Nginx 看到的直接客户端地址 | 20 次/1 分钟 |
| 登录用户名 | 去除首尾空格并转小写的用户名 | 5 次/5 分钟 |
| 报名 | 用户 ID + 活动 ID | 3 次/10 秒 |
| 候补响应 | 用户 ID + 邀请 ID | 5 次/1 分钟 |
| 普通签到 | 用户 ID + 会话 ID | 5 次/1 分钟 |
| 动态签到 | 用户 ID + 会话 ID | 10 次/1 分钟 |

后端使用 HMAC-SHA-256 将带用途隔离的主体转换为不可逆摘要，Redis 键和日志都不写入原始
IP、用户名或业务 ID。达到上限时返回 `429 Too Many Requests`、
`Content-Type: application/problem+json`、错误码 `RATE_LIMITED`，并通过 `Retry-After`
返回至少 1 秒的建议等待时间。

### 密钥与参数

`RATE_LIMIT_KEY_SECRET` 必须是独立于 `JWT_SECRET` 的至少 32 字节随机 UTF-8 字符串。
可以在受控主机生成后，只写入被 Git 忽略的 `deploy/.env`：

```powershell
$bytes = New-Object byte[] 32
[Security.Cryptography.RandomNumberGenerator]::Fill($bytes)
[Convert]::ToBase64String($bytes)
```

更换密钥会让后续请求使用全新的 Redis 键空间，相当于重置当前限流窗口；应在低流量维护
时段轮换，并让所有后端实例同时使用新值。旧键会按 TTL 自动过期，无需扫表或批量删除。
所有默认值及覆盖变量见 `deploy/.env.example`。若临时设置 `RATE_LIMIT_ENABLED=false`，
保护器会绕过 Redis，但 Bean 和应用上下文仍正常启动。

### Redis 故障策略

- 登录需要同时限制 IP 和规范化用户名。任一 Redis 检查不可用时，两项都切换到容量受限的
  本机固定窗口计数器；它只能保护当前后端进程，不是多实例全局限流证据。
- 报名、候补响应、普通签到和动态签到的限流在 Redis 不可用时放行，让 MySQL 事务、行锁、
  唯一约束及原有业务校验继续决定结果。
- 只有明确的存储不可用异常会触发降级；编程错误不会被当作 Redis 故障吞掉。
- 动态二维码凭证本身的 Redis 故障策略仍是 `503 DYNAMIC_CHECKIN_UNAVAILABLE`，不要与
  动态签到接口外围的请求频率限制混淆。

### 代理信任边界

Compose 只发布前端 Nginx，后端不映射宿主机端口。Nginx 覆盖而不是追加
`X-Forwarded-For`：`proxy_set_header X-Forwarded-For $remote_addr;`。Spring 使用
`server.forward-headers-strategy=native`，登录控制器只读取 Servlet 解析后的远端地址，
忽略浏览器直接提供的转发链。若将后端端口公开、在 Nginx 前增加负载均衡器，或允许绕过
Nginx 访问后端，必须重新设计可信代理范围，不能沿用当前 IP 限流结论。

### 4C 自动化验证与待办

```powershell
cd backend
.\mvnw.cmd test
cd ..\frontend
npm test -- --run
npm run build
```

这些检查覆盖 Lua 原子计数、本机降级并发与容量、429 契约、控制器边界、Spring Bean、
Compose 环境变量和 Nginx 静态配置。它们不证明虚拟机 Redis 已连通、不证明多后端实例共享
限流生效，也不证明真实 Nginx 客户端 IP、吞吐量、延迟或容量。上述运行态与性能证据留到
阶段 4D，在可用 Docker/虚拟机环境中执行 Redis 故障演练和 k6 压测后再记录。
