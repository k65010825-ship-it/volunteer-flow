# 核心列表分页接口

本次分页适用于以下三个现有GET接口，数组项格式不变：

```text
GET /api/v1/organizations/{orgId}/activities?page=1&size=20
GET /api/v1/users/me/registrations?page=1&size=20
GET /api/v1/organizations/{orgId}/members?page=1&size=20
```

`page`默认1，最小1；`size`默认20，限制为1..100。所有数据筛选、分页、总量统计都在数据库执行。用户身份仅来自当前认证主体；活动需组织成员身份，成员列表需`member:read`，我的报名只能查看本人。

## 响应示例

保持原有`data`数组，新增顶层`meta`，不改成`data.items`：

```json
{
  "data": [],
  "meta": {
    "total": "205",
    "page": "12",
    "size": "20"
  }
}
```

示例为超出末页的空数组，`total`仍是整个授权筛选范围的数量。项目使用long字符串序列化，客户端应按现有数字处理约定转换这些字段。

```ts
const response = await apiClient.get(url, { params: { page: 2, size: 20 } });
const items = response.data.data; // 仍是数组
const { total, page, size } = response.data.meta;
const hasNext = Number(page) * Number(size) < Number(total);
```

此后继续请求`page + 1`取得下一页。原先只读取`.data.data`的客户端在结构上仍兼容，但现在只会看到默认第一页；客户端必须读取meta并提供翻页才能访问后续数据。本轮不包含前端翻页UI。

## 排序和查询范围

- 活动：组织内已发布活动，按`activity_start_at ASC,id ASC`；本页活动的ACTIVE岗位一次批量读取。
- 我的报名：本人报名，按`update_time DESC,id DESC`；本页报名关联周期、答案、邀请和候补统计仍批量读取。
- 成员：组织内ACTIVE成员，按`joined_at ASC,id ASC`。

同时间使用ID打破并列，不承诺并发插入/更新时间变化时offset分页的快照一致性。角色和权限作为管理配置集合暂保持既有接口，不属于本次三个核心列表；通知游标分页与岗位报名管理分页保持原协议。
