<script setup lang="ts">
import { onMounted, ref, watch } from "vue";
import { listActivities } from "../api/activities";
import type { ActivitySummary, EntityId } from "../api/types";
const props = defineProps<{ organizationId: EntityId }>();
const activities = ref<ActivitySummary[]>([]);
const loading = ref(true);
const failed = ref(false);
async function load() {
  loading.value = true;
  failed.value = false;
  try {
    activities.value = await listActivities(props.organizationId);
  } catch {
    failed.value = true;
  } finally {
    loading.value = false;
  }
}
onMounted(load);
watch(() => props.organizationId, load);
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
      <RouterLink class="text-action" to="/join">加入组织</RouterLink>
    </div>
    <div class="tabs">
      <button class="active">进行中</button><button disabled>即将开始</button>
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
  </section>
</template>
