<script setup lang="ts">
import { computed, onMounted, reactive, ref } from "vue";
import { useAuthStore } from "../stores/auth";
import {
  createOrganization,
  deactivateOrganization,
  listOrganizations,
  organizationErrorMessage,
} from "../api/organizations";
import type { Organization } from "../api/types";
const auth = useAuthStore();
const rows = ref<Organization[]>([]),
  loading = ref(false),
  pending = ref(false),
  error = ref(""),
  success = ref("");
const query = ref(""),
  status = ref(""),
  confirmId = ref("");
const form = reactive({ name: "", description: "", ownerUserId: "" });
const filtered = computed(() =>
  rows.value.filter(
    (r) =>
      r.name.toLowerCase().includes(query.value.toLowerCase()) &&
      (!status.value || r.status === status.value),
  ),
);
async function load() {
  loading.value = true;
  error.value = "";
  try {
    rows.value = await listOrganizations();
  } catch (e) {
    error.value = organizationErrorMessage(e);
  } finally {
    loading.value = false;
  }
}
async function create() {
  if (
    pending.value ||
    !form.name.trim() ||
    !/^[1-9]\d*$/.test(form.ownerUserId)
  )
    return;
  pending.value = true;
  error.value = "";
  success.value = "";
  try {
    await createOrganization({
      name: form.name.trim(),
      description: form.description.trim(),
      ownerUserId: form.ownerUserId,
    });
    form.name = "";
    form.description = "";
    success.value = "组织已创建。";
    await load();
  } catch (e) {
    error.value = organizationErrorMessage(e);
  } finally {
    pending.value = false;
  }
}
async function deactivate() {
  if (pending.value || !confirmId.value) return;
  pending.value = true;
  error.value = "";
  try {
    await deactivateOrganization(confirmId.value);
    confirmId.value = "";
    success.value = "组织已停用。";
    await load();
  } catch (e) {
    error.value = organizationErrorMessage(e);
  } finally {
    pending.value = false;
  }
}
onMounted(() => {
  if (auth.user?.platformRole === "PLATFORM_ADMIN") void load();
});
</script>
<template>
  <section class="page organization-admin">
    <h1>组织管理</h1>
    <p class="muted">平台管理 · 创建组织与维护组织状态</p>
    <p v-if="auth.user?.platformRole !== 'PLATFORM_ADMIN'" class="state">
      仅平台管理员可访问此页面。
    </p>
    <template v-else>
      <p v-if="error" role="alert" class="error">
        {{ error }}
        <button
          type="button"
          class="secondary"
          :disabled="loading || pending"
          @click="load"
        >
          重新加载
        </button>
      </p>
      <p v-if="success" role="status" class="success">{{ success }}</p>
      <form class="panel form-grid" @submit.prevent="create">
        <h2>创建组织</h2>
        <label
          >组织名称<input
            v-model="form.name"
            required
            maxlength="128"
            :disabled="pending"
        /></label>
        <label
          >负责人用户 ID<input
            v-model="form.ownerUserId"
            required
            pattern="[1-9][0-9]*"
            inputmode="numeric"
            :disabled="pending"
          /><small class="muted"
            >须为已存在的有效用户。你的用户 ID：{{ auth.user?.id }}</small
          ></label
        >
        <label class="wide"
          >组织简介<textarea
            v-model="form.description"
            maxlength="1000"
            :disabled="pending"
          />
        </label>
        <button
          class="primary"
          :disabled="
            pending || !form.name.trim() || !/^[1-9]\d*$/.test(form.ownerUserId)
          "
        >
          {{ pending ? "处理中…" : "创建组织" }}
        </button>
      </form>
      <div class="filters">
        <label
          >名称筛选<input
            v-model="query"
            data-test="organization-filter"
            type="search"
            placeholder="搜索组织名称" /></label
        ><label
          >组织状态<select v-model="status">
            <option value="">全部状态</option>
            <option value="ACTIVE">正常</option>
            <option value="INACTIVE">已停用</option>
          </select></label
        ><span class="muted">共 {{ filtered.length }} 个组织（本地筛选）</span>
      </div>
      <p v-if="loading" class="state" role="status">正在加载组织…</p>
      <div v-else-if="!rows.length && !error" class="state">
        暂无组织，创建第一个组织后即可开始协作。
      </div>
      <div v-else-if="!filtered.length && !error" class="state">
        没有匹配的组织，请调整筛选条件。
      </div>
      <div v-else class="table-wrap">
        <table>
          <thead>
            <tr>
              <th>组织</th>
              <th>ID</th>
              <th>状态</th>
              <th>操作</th>
            </tr>
          </thead>
          <tbody>
            <tr v-for="org in filtered" :key="org.id">
              <td>
                <strong>{{ org.name }}</strong>
                <p class="muted">{{ org.description || "暂无简介" }}</p>
              </td>
              <td>{{ org.id }}</td>
              <td>{{ org.status === "ACTIVE" ? "正常" : "已停用" }}</td>
              <td>
                <button
                  v-if="org.status === 'ACTIVE'"
                  class="secondary danger"
                  :disabled="pending"
                  @click="confirmId = org.id"
                >
                  停用组织</button
                ><span v-else class="muted">—</span>
              </td>
            </tr>
          </tbody>
        </table>
      </div>
      <section
        v-if="confirmId"
        class="panel confirmation"
        role="alertdialog"
        aria-modal="false"
        aria-labelledby="deactivate-title"
      >
        <h2 id="deactivate-title">确认停用组织？</h2>
        <p>组织停用后，成员将无法继续访问组织业务。此页面不提供恢复操作。</p>
        <div class="actions">
          <button class="secondary" :disabled="pending" @click="confirmId = ''">
            取消</button
          ><button
            class="primary danger"
            :disabled="pending"
            @click="deactivate"
          >
            确认停用
          </button>
        </div>
      </section>
      <p class="muted boundary">
        平台管理权限不代表组织成员身份；成员、角色和邀请操作须由拥有组织权限的用户执行。
      </p>
    </template>
  </section>
</template>
<style scoped>
.organization-admin h1 {
  font-size: 28px;
}
.panel {
  border: 1px solid #e5e9f0;
  border-radius: 12px;
  padding: 24px;
  margin: 28px 0;
}
.panel h2 {
  font-size: 20px;
  margin: 0;
}
.form-grid {
  display: grid;
  grid-template-columns: 1fr 1fr;
  gap: 18px;
}
.form-grid h2,
.wide {
  grid-column: 1/-1;
}
label {
  display: grid;
  gap: 8px;
  font-size: 14px;
  font-weight: 600;
}
input,
select,
textarea {
  font: inherit;
  min-width: 0;
  border: 1px solid #cfd5df;
  border-radius: 8px;
  padding: 11px 12px;
  background: #fff;
  color: #102044;
}
textarea {
  resize: vertical;
  min-height: 80px;
}
.primary,
.secondary {
  border-radius: 8px;
  width: auto;
}
.form-grid > .primary {
  justify-self: start;
}
.filters,
.actions {
  display: flex;
  gap: 12px;
  flex-wrap: wrap;
  align-items: end;
}
.filters > span {
  padding-bottom: 10px;
}
.table-wrap {
  overflow-x: auto;
  margin-top: 20px;
}
table {
  width: 100%;
  border-collapse: collapse;
  text-align: left;
  min-width: 560px;
}
th,
td {
  padding: 16px;
  border-bottom: 1px solid #e5e9f0;
  vertical-align: top;
}
th {
  background: #f5f7fa;
  color: #68738c;
  font-size: 13px;
}
td p {
  margin: 6px 0 0;
}
.danger {
  color: #c9374b;
}
.primary.danger {
  color: white;
  background: #c9374b;
  border-color: #c9374b;
}
.boundary {
  font-size: 13px;
  margin-top: 24px;
}
.confirmation {
  border-color: #e5bbc1;
}
@media (max-width: 600px) {
  .form-grid {
    grid-template-columns: 1fr;
  }
  .panel {
    padding: 18px;
  }
  .filters {
    align-items: stretch;
  }
  .filters label {
    flex: 1 1 160px;
  }
}
</style>
