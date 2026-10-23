<script setup lang="ts">
import { reactive, ref, watch } from "vue";
import { listAuditLogs, type AuditFilters, type AuditLog } from "../api/audit";
import {
  getOrganizationAccess,
  organizationErrorMessage,
} from "../api/organizations";
const props = defineProps<{ organizationId: string }>();
const allowed = ref(false),
  loading = ref(false),
  error = ref(""),
  records = ref<AuditLog[]>([]),
  page = ref(1),
  total = ref(0);
const filters = reactive({
  actorUserId: "",
  action: "",
  resourceType: "",
  resourceId: "",
  from: "",
  to: "",
});
const applied = ref<Omit<AuditFilters, "page" | "size">>({});
let generation = 0;
async function fetchPage(token = generation) {
  loading.value = true;
  error.value = "";
  const id = props.organizationId;
  try {
    const result = await listAuditLogs(id, {
      page: page.value,
      size: 20,
      ...applied.value,
    });
    if (token === generation && id === props.organizationId) {
      records.value = result.data;
      total.value = Number(result.meta.total);
    }
  } catch (e) {
    if (token === generation) {
      records.value = [];
      error.value = organizationErrorMessage(e);
    }
  } finally {
    if (token === generation) loading.value = false;
  }
}
async function load() {
  const token = ++generation;
  allowed.value = false;
  records.value = [];
  loading.value = true;
  error.value = "";
  try {
    const access = await getOrganizationAccess(props.organizationId);
    if (token !== generation) return;
    allowed.value = access.permissionCodes.includes("audit:read");
    if (allowed.value) await fetchPage(token);
  } catch (e) {
    if (token === generation) error.value = organizationErrorMessage(e);
  } finally {
    if (token === generation) loading.value = false;
  }
}
async function apply() {
  if (loading.value) return;
  if (filters.from && filters.to && filters.from > filters.to) {
    error.value = "开始时间不能晚于结束时间。";
    return;
  }
  applied.value = Object.fromEntries(
    Object.entries(filters)
      .map(([k, v]) => [k, v.trim()])
      .filter(([, v]) => v),
  );
  page.value = 1;
  await fetchPage();
}
async function pageBy(delta: number) {
  if (loading.value) return;
  page.value += delta;
  await fetchPage();
}
watch(
  () => props.organizationId,
  () => {
    page.value = 1;
    total.value = 0;
    applied.value = {};
    Object.keys(filters).forEach((k) => {
      filters[k as keyof typeof filters] = "";
    });
    void load();
  },
  { immediate: true },
);
</script>
<template>
  <section class="page audit-page">
    <h1>组织审计日志</h1>
    <p class="muted">
      组织 {{ organizationId }} · 只读操作记录，快照由服务端脱敏后返回
    </p>
    <p v-if="error" class="error" role="alert">
      {{ error }}
      <button class="secondary" :disabled="loading" @click="load">重试</button>
    </p>
    <template v-if="allowed">
      <form class="filters" @submit.prevent="apply">
        <label
          >操作人用户 ID<input
            v-model="filters.actorUserId"
            inputmode="numeric"
            pattern="[1-9][0-9]*"
            placeholder="全部操作人"
            :disabled="loading" /></label
        ><label
          >操作类型<input
            v-model="filters.action"
            placeholder="如 role.updated（精确值）"
            :disabled="loading" /></label
        ><label
          >资源类型<input
            v-model="filters.resourceType"
            placeholder="如 rbac_role（精确值）"
            :disabled="loading" /></label
        ><label
          >资源 ID<input
            v-model="filters.resourceId"
            inputmode="numeric"
            pattern="[1-9][0-9]*"
            :disabled="loading" /></label
        ><label
          >开始时间（UTC）<input
            v-model="filters.from"
            type="datetime-local"
            :disabled="loading" /></label
        ><label
          >结束时间（UTC）<input
            v-model="filters.to"
            type="datetime-local"
            :disabled="loading" /></label
        ><button class="primary" :disabled="loading">查询日志</button>
      </form>
      <p class="muted note">
        时间筛选使用接口的 UTC
        时间，不自动换算为本地时间。支持精确筛选，不支持模糊搜索。
      </p>
      <p v-if="!loading && !records.length && !error" class="state">
        当前条件下暂无审计记录。
      </p>
      <div v-if="records.length" class="table-wrap">
        <table>
          <thead>
            <tr>
              <th>时间（UTC） / 操作人</th>
              <th>操作 / 资源</th>
              <th>变更字段与快照</th>
            </tr>
          </thead>
          <tbody>
            <tr v-for="item in records" :key="item.id">
              <td>
                <time>{{ item.createTime.replace("T", " ") }}</time>
                <p class="muted">用户 {{ item.actorUserId }}</p>
                <small class="muted">日志 {{ item.id }}</small>
              </td>
              <td>
                <strong>{{ item.action }}</strong>
                <p>{{ item.resourceType }} #{{ item.resourceId }}</p>
              </td>
              <td>
                <p>
                  {{
                    item.changedFields?.length
                      ? item.changedFields.join("、")
                      : "无字段明细"
                  }}
                </p>
                <details>
                  <summary>查看安全快照</summary>
                  <h3>变更前</h3>
                  <pre>{{
                    JSON.stringify(item.before, null, 2) ?? "无快照"
                  }}</pre>
                  <h3>变更后</h3>
                  <pre>{{
                    JSON.stringify(item.after, null, 2) ?? "无快照"
                  }}</pre>
                </details>
              </td>
            </tr>
          </tbody>
        </table>
      </div>
      <div class="pager">
        <button
          class="secondary"
          :disabled="loading || page <= 1"
          @click="pageBy(-1)"
        >
          上一页</button
        ><span>第 {{ page }} 页 · 共 {{ total }} 条</span
        ><button
          class="secondary"
          :disabled="loading || page * 20 >= total"
          @click="pageBy(1)"
        >
          下一页
        </button>
      </div>
    </template>
    <p v-if="loading" class="state" role="status">正在加载审计日志…</p>
    <p v-else-if="!allowed && !error" class="state">
      你没有审计日志查看权限（audit:read），请联系组织负责人。
    </p>
  </section>
</template>
<style scoped>
.audit-page h1 {
  font-size: 28px;
}
.filters {
  display: grid;
  grid-template-columns: repeat(3, minmax(0, 1fr));
  gap: 16px;
  margin-top: 28px;
  padding: 24px;
  border: 1px solid #e5e9f0;
  border-radius: 12px;
}
.filters label {
  display: grid;
  gap: 8px;
  font-size: 14px;
  font-weight: 600;
}
.filters input {
  width: 100%;
  min-width: 0;
  font: inherit;
  border: 1px solid #cfd5df;
  border-radius: 8px;
  padding: 11px 12px;
  color: #102044;
  background: #fff;
}
.primary,
.secondary {
  width: auto;
  border-radius: 8px;
}
.filters > .primary {
  justify-self: start;
}
.note {
  font-size: 13px;
}
.table-wrap {
  overflow-x: auto;
  margin-top: 24px;
}
table {
  width: 100%;
  min-width: 680px;
  border-collapse: collapse;
  text-align: left;
}
th,
td {
  border-bottom: 1px solid #e5e9f0;
  padding: 16px;
  vertical-align: top;
}
th {
  background: #f5f7fa;
  color: #68738c;
  font-size: 13px;
}
td p {
  margin: 7px 0 10px;
}
details {
  max-width: 420px;
}
summary {
  cursor: pointer;
  color: #0b7f47;
  font-weight: 600;
}
h3 {
  font-size: 13px;
  margin: 14px 0 8px;
}
pre {
  font-size: 12px;
  max-height: 280px;
  overflow: auto;
  background: #f5f7fa;
  padding: 12px;
  border-radius: 8px;
  white-space: pre-wrap;
  overflow-wrap: anywhere;
}
.pager {
  display: flex;
  flex-wrap: wrap;
  gap: 14px;
  justify-content: flex-end;
  align-items: center;
  margin-top: 24px;
  font-size: 14px;
}
@media (max-width: 720px) {
  .filters {
    grid-template-columns: repeat(2, minmax(0, 1fr));
    padding: 16px;
  }
}
@media (max-width: 450px) {
  .filters {
    grid-template-columns: 1fr;
  }
  .pager {
    justify-content: flex-start;
  }
}
</style>
