<script setup lang="ts">
import { computed, onBeforeUnmount, reactive, ref, watch } from "vue";
import * as api from "../api/organizations";
import type {
  OrganizationAccess,
  OrganizationMember,
  OrganizationRole,
  OrganizationPermission,
  Invitation,
} from "../api/organizationTypes";
const props = defineProps<{ organizationId: string }>();
const access = ref<OrganizationAccess | null>(null),
  members = ref<OrganizationMember[]>([]),
  roles = ref<OrganizationRole[]>([]),
  permissions = ref<OrganizationPermission[]>([]);
const loading = ref(false),
  pending = ref(false),
  error = ref(""),
  success = ref(""),
  memberPage = ref(1),
  memberTotal = ref(0);
const assignments = reactive<Record<string, string>>({});
const can = (code: string) =>
  access.value?.permissionCodes.includes(code) ?? false;
const canManage = computed(() =>
  ["member:read", "member:invite", "role:read", "role:create"].some(can),
);
const activeRoles = computed(() =>
  roles.value.filter((r) => r.status === "ACTIVE"),
);
const roleForm = reactive({
  id: "",
  name: "",
  description: "",
  permissionCodes: [] as string[],
});
const editing = ref(false),
  grantsLoaded = ref(false),
  grantsLoading = ref(false);
const confirmAction = ref<{
  kind: "deactivate" | "delete";
  role: OrganizationRole;
} | null>(null);
const inviteForm = reactive({ maxUses: 50, expiresAt: "" });
const invitation = ref<Invitation | null>(null),
  inviteDeactivated = ref(false);
let generation = 0;
type OrganizationOperation = { organizationId: string; generation: number };

// 每个异步流程只使用启动时的组织与代次，导航后禁止继续请求或回写。
function captureOperation(): OrganizationOperation {
  return { organizationId: props.organizationId, generation };
}
function isCurrent(operation: OrganizationOperation): boolean {
  return (
    operation.generation === generation &&
    operation.organizationId === props.organizationId
  );
}
async function loadMembers(operation: OrganizationOperation, page: number) {
  if (!isCurrent(operation)) return;
  const result = await api.listOrganizationMembers(
    operation.organizationId,
    page,
  );
  if (!isCurrent(operation)) return;
  members.value = result.data;
  memberTotal.value = Number(result.meta.total);
  members.value.forEach((m) => (assignments[m.id] = m.roleId));
}
async function load() {
  const token = ++generation,
    id = props.organizationId;
  const operation = captureOperation();
  loading.value = true;
  pending.value = false;
  grantsLoading.value = false;
  grantsLoaded.value = false;
  error.value = "";
  access.value = null;
  members.value = [];
  roles.value = [];
  permissions.value = [];
  editing.value = false;
  invitation.value = null;
  try {
    const result = await api.getOrganizationAccess(id);
    if (token !== generation) return;
    access.value = result;
    const tasks: Promise<void>[] = [];
    if (can("member:read"))
      tasks.push(loadMembers(operation, memberPage.value));
    if (can("role:read"))
      tasks.push(
        Promise.all([
          api.listOrganizationRoles(id),
          api.listOrganizationPermissions(id),
        ]).then(([r, p]) => {
          if (token === generation) {
            roles.value = r;
            permissions.value = p;
          }
        }),
      );
    await Promise.all(tasks);
  } catch (e) {
    if (token === generation) error.value = api.organizationErrorMessage(e);
  } finally {
    if (token === generation) loading.value = false;
  }
}
async function perform(
  operation: OrganizationOperation,
  action: () => Promise<unknown>,
  message: string,
) {
  if (pending.value || !isCurrent(operation)) return;
  pending.value = true;
  error.value = "";
  success.value = "";
  try {
    await action();
    if (isCurrent(operation)) success.value = message;
  } catch (e) {
    if (isCurrent(operation)) error.value = api.organizationErrorMessage(e);
  } finally {
    if (isCurrent(operation)) pending.value = false;
  }
}
async function pageBy(delta: number) {
  const operation = captureOperation();
  const page = memberPage.value + delta;
  await perform(
    operation,
    async () => {
      memberPage.value = page;
      await loadMembers(operation, page);
    },
    "",
  );
}
async function assign(member: OrganizationMember) {
  const operation = captureOperation();
  const memberId = member.id;
  const roleId = assignments[memberId];
  const page = memberPage.value;
  await perform(
    operation,
    async () => {
      await api.assignMemberRole(operation.organizationId, memberId, roleId);
      if (!isCurrent(operation)) return;
      await loadMembers(operation, page);
    },
    "成员角色已更新。",
  );
}
function newRole() {
  Object.assign(roleForm, {
    id: "",
    name: "",
    description: "",
    permissionCodes: [],
  });
  editing.value = true;
  grantsLoaded.value = true;
}
async function editRole(role: OrganizationRole) {
  if (role.protectedRole || pending.value || grantsLoading.value) return;
  const id = props.organizationId,
    token = generation;
  Object.assign(roleForm, {
    id: role.id,
    name: role.name,
    description: role.description ?? "",
    permissionCodes: [],
  });
  editing.value = true;
  grantsLoaded.value = false;
  grantsLoading.value = true;
  error.value = "";
  try {
    const codes = await api.getRolePermissions(id, role.id);
    if (token === generation && roleForm.id === role.id) {
      roleForm.permissionCodes = codes;
      grantsLoaded.value = true;
    }
  } catch (e) {
    if (token === generation) error.value = api.organizationErrorMessage(e);
  } finally {
    if (token === generation) grantsLoading.value = false;
  }
}
async function saveRole() {
  if (!grantsLoaded.value || !roleForm.name.trim()) return;
  const operation = captureOperation();
  // 表单必须在第一个 await 前复制，不能在后续步骤读取另一组织的表单。
  const roleId = roleForm.id;
  const permissionCodes = [...roleForm.permissionCodes];
  const input = {
    name: roleForm.name.trim(),
    description: roleForm.description.trim(),
  };
  await perform(
    operation,
    async () => {
      if (roleId) {
        await api.updateOrganizationRole(
          operation.organizationId,
          roleId,
          input,
        );
        // 两次独立事务；导航后不继续第二步，失败时保留当前组织的表单。
        if (!isCurrent(operation)) return;
        await api.setRolePermissions(
          operation.organizationId,
          roleId,
          permissionCodes,
        );
      } else
        await api.createOrganizationRole(operation.organizationId, {
          ...input,
          permissionCodes,
        });
      if (!isCurrent(operation)) return;
      const updatedRoles = await api.listOrganizationRoles(
        operation.organizationId,
      );
      if (!isCurrent(operation)) return;
      roles.value = updatedRoles;
      editing.value = false;
    },
    "角色已保存。",
  );
}
async function mutateRole() {
  const action = confirmAction.value;
  if (!action) return;
  const operation = captureOperation();
  const kind = action.kind;
  const roleId = action.role.id;
  await perform(
    operation,
    async () => {
      if (kind === "delete")
        await api.deleteOrganizationRole(operation.organizationId, roleId);
      else
        await api.deactivateOrganizationRole(operation.organizationId, roleId);
      if (!isCurrent(operation)) return;
      const updatedRoles = await api.listOrganizationRoles(
        operation.organizationId,
      );
      if (!isCurrent(operation)) return;
      roles.value = updatedRoles;
      confirmAction.value = null;
    },
    "角色状态已更新。",
  );
}
async function invite() {
  const operation = captureOperation();
  const maxUses = inviteForm.maxUses;
  const expiresAt = inviteForm.expiresAt;
  await perform(
    operation,
    async () => {
      const input: { maxUses: number; expiresAt?: string } = { maxUses };
      if (expiresAt) input.expiresAt = new Date(expiresAt).toISOString();
      const created = await api.createInvitation(
        operation.organizationId,
        input,
      );
      if (!isCurrent(operation)) return;
      invitation.value = created;
      inviteDeactivated.value = false;
    },
    "邀请码已生成，请妥善分享。",
  );
}
async function deactivateInvite() {
  const id = invitation.value?.inviteId;
  if (!id) return;
  const operation = captureOperation();
  await perform(
    operation,
    async () => {
      await api.deactivateInvitation(operation.organizationId, id);
      if (!isCurrent(operation)) return;
      inviteDeactivated.value = true;
      invitation.value = null;
    },
    "本次邀请码已停用。",
  );
}
onBeforeUnmount(() => {
  generation++;
});
watch(
  () => props.organizationId,
  () => {
    memberPage.value = 1;
    memberTotal.value = 0;
    success.value = "";
    confirmAction.value = null;
    Object.keys(assignments).forEach((key) => delete assignments[key]);
    Object.assign(roleForm, {
      id: "",
      name: "",
      description: "",
      permissionCodes: [],
    });
    Object.assign(inviteForm, { maxUses: 50, expiresAt: "" });
    inviteDeactivated.value = false;
    void load();
  },
  { immediate: true },
);
</script>
<template>
  <section class="page organization-management">
    <h1>组织协作管理</h1>
    <p class="muted">成员、角色权限与邀请 · 组织 {{ organizationId }}</p>
    <p v-if="error" class="error" role="alert">
      {{ error }}
      <button class="secondary" :disabled="loading || pending" @click="load">
        重新加载
      </button>
    </p>
    <p v-if="success" class="success" role="status">{{ success }}</p>
    <p v-if="loading" class="state" role="status">正在核对组织权限…</p>
    <template v-else-if="access">
      <p class="access-note">
        当前角色：<strong>{{ access.roleName }}</strong
        ><span class="muted"> · 操作仍由服务端实时校验</span>
      </p>
      <p v-if="!canManage" class="state">
        你没有成员、角色或邀请管理权限，请联系组织负责人。
      </p>
      <section v-if="can('member:read')" class="panel">
        <h2>组织成员</h2>
        <p class="muted">
          以用户 ID
          识别成员，不展示接口未提供的姓名。组织必须保留至少一位负责人。
        </p>
        <div v-if="members.length" class="table-wrap">
          <table>
            <thead>
              <tr>
                <th>用户 ID</th>
                <th>当前角色</th>
                <th>状态</th>
                <th v-if="can('member:role_assign') && can('role:read')">
                  分配角色
                </th>
              </tr>
            </thead>
            <tbody>
              <tr v-for="member in members" :key="member.id">
                <td>{{ member.userId }}</td>
                <td>
                  {{
                    roles.find((r) => r.id === member.roleId)?.name ||
                    member.roleId
                  }}
                </td>
                <td>
                  {{ member.status === "ACTIVE" ? "正常" : member.status }}
                </td>
                <td v-if="can('member:role_assign') && can('role:read')">
                  <div class="actions">
                    <select
                      v-model="assignments[member.id]"
                      :aria-label="`用户 ${member.userId} 的角色`"
                      :disabled="pending || member.status !== 'ACTIVE'"
                    >
                      <option
                        v-for="role in activeRoles"
                        :key="role.id"
                        :value="role.id"
                      >
                        {{ role.name }}
                      </option></select
                    ><button
                      class="secondary"
                      :disabled="
                        pending ||
                        member.status !== 'ACTIVE' ||
                        assignments[member.id] === member.roleId
                      "
                      @click="assign(member)"
                    >
                      保存
                    </button>
                  </div>
                </td>
              </tr>
            </tbody>
          </table>
        </div>
        <p v-else class="state">暂无成员。</p>
        <div class="pager">
          <button
            class="secondary"
            :disabled="pending || memberPage <= 1"
            @click="pageBy(-1)"
          >
            上一页</button
          ><span>第 {{ memberPage }} 页 · 共 {{ memberTotal }} 人</span
          ><button
            class="secondary"
            :disabled="pending || memberPage * 20 >= memberTotal"
            @click="pageBy(1)"
          >
            下一页
          </button>
        </div>
      </section>
      <section v-if="can('role:read')" class="panel">
        <div class="section-heading">
          <h2>角色与权限</h2>
          <button
            v-if="can('role:create')"
            class="primary"
            :disabled="pending || grantsLoading"
            @click="newRole"
          >
            创建角色
          </button>
        </div>
        <div class="table-wrap">
          <table>
            <thead>
              <tr>
                <th>角色</th>
                <th>类型 / 状态</th>
                <th>操作</th>
              </tr>
            </thead>
            <tbody>
              <tr v-for="role in roles" :key="role.id">
                <td>
                  <strong>{{ role.name }}</strong>
                  <p class="muted">{{ role.description || "暂无说明" }}</p>
                </td>
                <td>{{ role.builtInType }} / {{ role.status }}</td>
                <td>
                  <span v-if="role.protectedRole" class="muted"
                    >受保护的负责人角色</span
                  >
                  <div v-else-if="can('role:update')" class="actions">
                    <button
                      v-if="role.status === 'ACTIVE'"
                      :data-test="`role-edit-${role.id}`"
                      class="secondary"
                      :disabled="pending || grantsLoading"
                      @click="editRole(role)"
                    >
                      编辑权限</button
                    ><button
                      v-if="role.status === 'ACTIVE'"
                      class="secondary"
                      :disabled="pending"
                      @click="confirmAction = { kind: 'deactivate', role }"
                    >
                      停用</button
                    ><button
                      v-if="
                        role.builtInType === 'CUSTOM' &&
                        role.status !== 'DELETED'
                      "
                      class="secondary danger"
                      :disabled="pending"
                      @click="confirmAction = { kind: 'delete', role }"
                    >
                      删除
                    </button>
                  </div>
                  <span v-else class="muted">只读</span>
                </td>
              </tr>
            </tbody>
          </table>
        </div>
        <form v-if="editing" class="role-form" @submit.prevent="saveRole">
          <h3>{{ roleForm.id ? "编辑角色" : "创建角色" }}</h3>
          <label
            >角色名称<input
              v-model="roleForm.name"
              required
              maxlength="64"
              :disabled="pending || !grantsLoaded" /></label
          ><label
            >角色说明<textarea
              v-model="roleForm.description"
              maxlength="255"
              :disabled="pending || !grantsLoaded"
            />
          </label>
          <p v-if="grantsLoading" role="status">正在读取现有权限…</p>
          <p v-else-if="!grantsLoaded" class="error">
            现有权限加载失败，保存已禁用。请关闭后重新编辑。
          </p>
          <fieldset :disabled="pending || !grantsLoaded">
            <legend>权限（勾选后生效）</legend>
            <label
              v-for="permission in permissions"
              :key="permission.code"
              class="permission"
              ><input
                v-model="roleForm.permissionCodes"
                type="checkbox"
                :value="permission.code"
              /><span
                >{{ permission.name }}<small>{{ permission.code }}</small></span
              ></label
            >
          </fieldset>
          <p class="muted">
            名称与权限为两次独立保存；若第二步失败，请保留表单并重试。
          </p>
          <div class="actions">
            <button
              data-test="role-save"
              class="primary"
              :disabled="pending || !grantsLoaded || !roleForm.name.trim()"
            >
              保存角色</button
            ><button
              type="button"
              class="secondary"
              :disabled="pending || grantsLoading"
              @click="editing = false"
            >
              关闭
            </button>
          </div>
        </form>
      </section>
      <section v-else-if="can('role:create')" class="state">
        你有创建角色权限，但缺少
        role:read，无法加载权限目录。请联系负责人补充权限。
      </section>
      <section
        v-if="confirmAction"
        class="panel confirmation"
        role="alertdialog"
        aria-labelledby="role-confirm-title"
      >
        <h2 id="role-confirm-title">
          确认{{ confirmAction.kind === "delete" ? "删除" : "停用" }}角色「{{
            confirmAction.role.name
          }}」？
        </h2>
        <p>
          已被成员使用的角色可能无法操作。内置角色不可删除，负责人角色始终受保护。
        </p>
        <div class="actions">
          <button
            class="secondary"
            :disabled="pending"
            @click="confirmAction = null"
          >
            取消</button
          ><button class="primary" :disabled="pending" @click="mutateRole">
            确认操作
          </button>
        </div>
      </section>
      <section v-if="can('member:invite')" class="panel">
        <h2>邀请新成员</h2>
        <p class="muted">
          新成员默认获得内置 MEMBER
          角色。邀请码仅在本次创建响应中显示，不保存到浏览器存储。
        </p>
        <form class="invite-form" @submit.prevent="invite">
          <label
            >最多使用次数<input
              v-model.number="inviteForm.maxUses"
              required
              type="number"
              min="1"
              max="1000"
              step="1"
              :disabled="pending" /></label
          ><label
            >到期时间（本地时间，可选）<input
              v-model="inviteForm.expiresAt"
              type="datetime-local"
              :disabled="pending"
            /><small class="muted"
              >留空时使用服务端默认 7 天有效期。</small
            ></label
          ><button
            data-test="invite-create"
            class="primary"
            :disabled="pending || !!invitation"
          >
            生成邀请码
          </button>
        </form>
        <div v-if="invitation" class="invite-result">
          <strong>请现在复制并分享；离开或重新加载后无法找回。</strong
          ><code>{{ invitation.code }}</code>
          <p>
            最多使用 {{ invitation.maxUses }} 次 · 到期
            {{ new Date(invitation.expiresAt).toLocaleString("zh-CN") }}
          </p>
          <div class="actions">
            <button
              v-if="invitation.inviteId"
              class="secondary danger"
              :disabled="pending"
              @click="deactivateInvite"
            >
              停用本次邀请码</button
            ><button
              class="secondary"
              :disabled="pending"
              @click="invitation = null"
            >
              我已保存，隐藏邀请码
            </button>
          </div>
          <p v-if="!invitation.inviteId" class="muted">
            当前服务未返回邀请码 ID，不能在此停用。
          </p>
        </div>
        <p v-if="inviteDeactivated" role="status">邀请码已失效。</p>
      </section>
    </template>
  </section>
</template>
<style scoped>
.organization-management h1 {
  font-size: 28px;
}
.panel {
  margin: 26px 0;
  border: 1px solid #e5e9f0;
  border-radius: 12px;
  padding: 24px;
  min-width: 0;
}
.panel h2 {
  font-size: 20px;
  margin: 0;
}
.section-heading,
.actions,
.pager {
  display: flex;
  flex-wrap: wrap;
  gap: 10px;
  align-items: center;
}
.section-heading {
  justify-content: space-between;
}
.primary,
.secondary {
  width: auto;
  border-radius: 8px;
  padding: 10px 15px;
}
.access-note {
  padding: 15px 0;
  border-bottom: 1px solid #e5e9f0;
}
.table-wrap {
  overflow-x: auto;
  margin-top: 20px;
}
table {
  width: 100%;
  min-width: 540px;
  text-align: left;
  border-collapse: collapse;
}
th,
td {
  border-bottom: 1px solid #e5e9f0;
  padding: 14px;
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
.pager {
  justify-content: flex-end;
  margin-top: 20px;
  font-size: 14px;
}
.role-form,
.invite-form {
  display: grid;
  gap: 16px;
  margin-top: 22px;
}
.role-form {
  padding-top: 22px;
  border-top: 1px solid #e5e9f0;
}
.role-form > label,
.invite-form > label {
  display: grid;
  gap: 8px;
  font-size: 14px;
  font-weight: 600;
}
input:not([type="checkbox"]),
select,
textarea {
  font: inherit;
  max-width: 100%;
  border: 1px solid #cfd5df;
  border-radius: 8px;
  padding: 10px 12px;
  background: white;
  color: #102044;
}
textarea {
  min-height: 72px;
  resize: vertical;
}
.role-form h3 {
  margin: 0;
}
.role-form fieldset {
  border: 1px solid #e5e9f0;
  border-radius: 8px;
  display: grid;
  grid-template-columns: repeat(auto-fit, minmax(210px, 1fr));
  gap: 12px;
  padding: 18px;
}
.permission {
  display: flex;
  gap: 9px;
  align-items: start;
}
.permission input {
  accent-color: #16a05d;
  width: 18px;
  height: 18px;
}
.permission small {
  display: block;
  color: #68738c;
  margin-top: 4px;
}
.role-form > .muted {
  font-size: 13px;
  margin: 0;
}
.invite-form {
  grid-template-columns: 1fr 1fr;
  align-items: start;
}
.invite-form > button {
  justify-self: start;
  grid-column: 1/-1;
}
.invite-result {
  margin-top: 20px;
  padding: 20px;
  background: #f1fbf5;
  border-radius: 8px;
  overflow-wrap: anywhere;
}
.invite-result code {
  display: block;
  margin-top: 16px;
  font-size: 24px;
  user-select: all;
}
.danger {
  color: #c9374b;
}
.confirmation {
  border-color: #e5bbc1;
}
@media (max-width: 600px) {
  .panel {
    padding: 16px;
  }
  .invite-form {
    grid-template-columns: 1fr;
  }
  .pager {
    justify-content: flex-start;
  }
  .role-form fieldset {
    grid-template-columns: 1fr;
  }
  .section-heading {
    gap: 16px;
  }
}
</style>
