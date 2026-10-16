# 阶段 4B：Redis 动态签到二维码操作手册

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
