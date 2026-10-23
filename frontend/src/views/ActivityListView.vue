<script setup lang="ts">
import { onBeforeUnmount, ref, watch } from "vue";
import { listActivitiesPage } from "../api/activities";
import { getOrganizationAccess } from "../api/organizations";
import type { ActivitySummary, EntityId } from "../api/types";
const props = defineProps<{ organizationId: EntityId }>();
const activities = ref<ActivitySummary[]>([]);
const loading = ref(true);
const failed = ref(false);
const page = ref(1);
const total = ref(0);
const permissions = ref<string[]>([]);
const draftId = ref("");
let generation = 0;
async function load() {
  const request = ++generation;
  loading.value = true;
  failed.value = false;
  try {
    const result = await listActivitiesPage(
      props.organizationId,
      page.value,
      20,
    );
    if (request !== generation) return;
    activities.value = result.data;
    total.value = result.meta.total;
    page.value = result.meta.page;
  } catch {
    if (request === generation) failed.value = true;
  } finally {
    if (request === generation) loading.value = false;
  }
}
watch(
  () => props.organizationId,
  async (orgId) => {
    page.value = 1;
    permissions.value = [];
    try {
      draftId.value = localStorage.getItem(`activity-draft:${orgId}`) || "";
    } catch {
      draftId.value = "";
    }
    void load();
    try {
      const access = await getOrganizationAccess(orgId);
      if (orgId === props.organizationId)
        permissions.value = access.permissionCodes;
    } catch {
      /* Fail closed; listing remains available. */
    }
  },
  { immediate: true },
);
onBeforeUnmount(() => {
  generation++;
});
function movePage(value: number) {
  page.value = value;
  void load();
}
const format = (v: string) =>
  new Intl.DateTimeFormat("zh-CN", {
    month: "long",
    day: "numeric",
    weekday: "short",
    hour: "2-digit",
    minute: "2-digit",
  }).format(new Date(v));
const capacity = (item: ActivitySummary) =>
  item.positions.reduce((sum, p) => sum + p.capacity, 0);
</script>
<template>
  <section class="page activity-page">
    <div class="page-heading">
      <div>
        <h1>发现志愿活动</h1>
        <p>选择适合你的岗位，参与校园服务。</p>
      </div>
      <div class="list-actions">
        <RouterLink
          v-if="permissions.includes('activity:create')"
          class="primary"
          :to="`/organizations/${organizationId}/activities/new`"
          >创建活动</RouterLink
        >
        <RouterLink
          v-if="draftId && permissions.includes('activity:create')"
          class="text-action"
          :to="`/activities/${draftId}/edit`"
          >继续最近草稿</RouterLink
        >
        <RouterLink class="text-action" to="/join">加入组织</RouterLink>
      </div>
    </div>
    <nav class="workspace-links" aria-label="组织工作区">
      <RouterLink to="/activities?userView=1">切换组织</RouterLink>
      <RouterLink
        v-if="
          permissions.some((code) =>
            [
              'member:read',
              'member:invite',
              'role:read',
              'role:create',
            ].includes(code),
          )
        "
        :to="`/organizations/${organizationId}/manage`"
        >组织管理</RouterLink
      >
      <RouterLink
        v-if="permissions.includes('audit:read')"
        :to="`/organizations/${organizationId}/audit`"
        >审计日志</RouterLink
      >
    </nav>
    <div class="tabs">
      <span class="active">已发布活动</span>
    </div>
    <p v-if="loading" class="state">正在加载活动…</p>
    <div v-else-if="failed" class="state">
      <strong>活动加载失败</strong>
      <p>请检查网络后重试。</p>
      <button class="secondary" @click="load">重新加载</button>
    </div>
    <div v-else-if="!activities.length" class="state">
      <strong>暂时没有已发布活动</strong>
      <p>负责人发布活动后会显示在这里。</p>
    </div>
    <div v-else class="activity-feed">
      <RouterLink
        v-for="item in activities"
        :key="item.activity.id"
        class="activity-row"
        :to="`/activities/${item.activity.id}`"
        ><h2>{{ item.activity.title }}</h2>
        <dl>
          <div>
            <dt>时间</dt>
            <dd>{{ format(item.activity.activityStartAt) }}</dd>
          </div>
          <div>
            <dt>地点</dt>
            <dd>{{ item.activity.location }}</dd>
          </div>
          <div>
            <dt>岗位</dt>
            <dd>
              {{ item.positions.length }} 个 · 共 {{ capacity(item) }} 个名额
            </dd>
          </div>
        </dl>
        <p>{{ item.activity.description }}</p>
        <span class="chevron" aria-hidden="true"
          ><svg viewBox="0 0 24 24"><path d="m9 18 6-6-6-6" /></svg></span
      ></RouterLink>
    </div>
    <nav v-if="!failed" class="pagination" aria-label="活动分页">
      <button
        class="secondary"
        :disabled="loading || page <= 1"
        @click="movePage(page - 1)"
      >
        上一页
      </button>
      <span>第 {{ page }} 页 · 共 {{ total }} 项</span>
      <button
        class="secondary"
        :disabled="loading || page * 20 >= total"
        @click="movePage(page + 1)"
      >
        下一页
      </button>
    </nav>
  </section>
</template>
<style scoped>
.list-actions,
.pagination {
  display: flex;
  gap: 12px;
  align-items: center;
  flex-wrap: wrap;
}
.pagination {
  justify-content: center;
  margin-top: 28px;
}
.page-heading h1 {
  font-size: 28px;
}
.pagination button {
  border-radius: 8px;
}
@media (max-width: 600px) {
  .page-heading {
    align-items: flex-start;
    gap: 16px;
    flex-direction: column;
  }
  .pagination {
    gap: 8px;
  }
}
</style>
