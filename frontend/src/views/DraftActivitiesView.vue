<script setup lang="ts">
import { onBeforeUnmount, ref, watch } from "vue";
import { listActivityDraftsPage } from "../api/activities";
import type { ActivitySummary, EntityId } from "../api/types";
const props = defineProps<{ organizationId: EntityId }>();
const drafts = ref<ActivitySummary[]>([]);
const loading = ref(false),
  failed = ref(false),
  page = ref(1),
  total = ref(0);
let generation = 0;
async function load() {
  const request = ++generation;
  loading.value = true;
  failed.value = false;
  drafts.value = [];
  try {
    const result = await listActivityDraftsPage(
      props.organizationId,
      page.value,
      20,
    );
    if (request !== generation) return;
    drafts.value = result.data;
    page.value = result.meta.page;
    total.value = result.meta.total;
  } catch {
    if (request === generation) failed.value = true;
  } finally {
    if (request === generation) loading.value = false;
  }
}
watch(
  () => props.organizationId,
  () => {
    page.value = 1;
    total.value = 0;
    void load();
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
</script>
<template>
  <section class="page draft-page">
    <div class="page-heading">
      <div>
        <h1>活动草稿</h1>
        <p>继续编辑本组织尚未发布的活动。</p>
      </div>
      <RouterLink
        class="primary"
        :to="`/organizations/${organizationId}/activities/new`"
        >创建活动</RouterLink
      >
    </div>
    <nav class="workspace-links">
      <RouterLink :to="`/organizations/${organizationId}/activities`"
        >已发布活动</RouterLink
      >
    </nav>
    <p v-if="loading" class="state">正在加载草稿…</p>
    <div v-else-if="failed" class="state" role="alert">
      <p>草稿加载失败，请确认拥有本组织 activity:create 权限后重试。</p>
      <button class="secondary" data-test="retry" @click="load">
        重新加载
      </button>
    </div>
    <p v-else-if="!drafts.length" class="state">暂无活动草稿</p>
    <div v-else class="activity-feed">
      <RouterLink
        v-for="item in drafts"
        :key="item.activity.id"
        class="activity-row"
        :to="`/activities/${item.activity.id}/edit`"
      >
        <h2>{{ item.activity.title }}</h2>
        <p>{{ item.activity.location }} · {{ item.positions.length }} 个岗位</p>
        <span>继续编辑草稿</span>
      </RouterLink>
    </div>
    <nav v-if="!failed" class="pagination" aria-label="草稿分页">
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
        data-test="next-page"
        :disabled="loading || page * 20 >= total"
        @click="movePage(page + 1)"
      >
        下一页
      </button>
    </nav>
  </section>
</template>
<style scoped>
.pagination {
  display: flex;
  gap: 12px;
  align-items: center;
  justify-content: center;
  flex-wrap: wrap;
  margin-top: 28px;
}
.page-heading h1 {
  font-size: 28px;
}
@media (max-width: 600px) {
  .page-heading {
    flex-direction: column;
    align-items: flex-start;
    gap: 16px;
  }
}
</style>
