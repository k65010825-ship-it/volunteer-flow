<script setup lang="ts">
import { onBeforeUnmount, onMounted, ref } from "vue";
import { getActivity } from "../api/activities";
import {
  listMyRegistrations,
  registrationStatusLabel,
} from "../api/registrations";
import type { ActivityDetail, OwnRegistration } from "../api/types";

const registrations = ref<OwnRegistration[]>([]);
const details = ref<Record<string, ActivityDetail>>({});
const loading = ref(true);
const failed = ref(false);
let generation = 0;

async function load() {
  const request = ++generation;
  loading.value = true;
  failed.value = false;
  details.value = {};
  try {
    const result = await listMyRegistrations();
    if (request !== generation) return;
    registrations.value = result;
    loading.value = false;
    // Enrichment is optional: a missing activity must never hide the member's status.
    await Promise.all(
      [...new Set(result.map((item) => item.activityId))].map(
        async (activityId) => {
          try {
            const detail = await getActivity(activityId);
            if (request === generation) details.value[activityId] = detail;
          } catch {
            // Keep explicit ID fallbacks if the activity is no longer accessible.
          }
        },
      ),
    );
  } catch {
    if (request === generation) failed.value = true;
  } finally {
    if (request === generation) loading.value = false;
  }
}

function position(item: OwnRegistration) {
  return details.value[item.activityId]?.positions.find(
    (candidate) => candidate.id === item.positionId,
  );
}

onMounted(load);
onBeforeUnmount(() => {
  ++generation;
});
</script>

<template>
  <section class="page detail-page">
    <div class="page-heading">
      <div>
        <h1>我的报名</h1>
        <p>查看报名结果、候补进度和递补邀请。</p>
      </div>
      <button class="secondary" :disabled="loading" @click="load">刷新</button>
    </div>
    <p v-if="loading" class="state" role="status">正在加载报名…</p>
    <div v-else-if="failed" class="state" role="alert">
      报名加载失败，请刷新重试。
    </div>
    <div v-else-if="!registrations.length" class="state">
      <strong>暂无报名</strong>
      <RouterLink class="text-action" to="/activities"
        >去发现志愿活动</RouterLink
      >
    </div>
    <div v-else class="registration-list">
      <RouterLink
        v-for="item in registrations"
        :key="item.registrationId"
        class="registration-card"
        :to="`/registrations/${item.registrationId}`"
      >
        <h2>
          {{
            details[item.activityId]?.activity.title ??
            `活动 #${item.activityId}`
          }}
        </h2>
        <p>{{ position(item)?.name ?? `岗位 #${item.positionId}` }}</p>
        <span class="status-badge">{{
          registrationStatusLabel[item.status]
        }}</span>
        <p v-if="position(item)" class="muted">
          {{
            position(item)?.registrationMode === "FIRST_COME"
              ? "先到先得"
              : "负责人审核"
          }}
        </p>
        <p v-else class="muted">活动详情暂不可用，可继续查看报名状态。</p>
        <p
          v-if="item.status === 'WAITLISTED' && item.waitlistSequence !== null"
        >
          当前位次：{{ item.currentWaitlistPosition ?? "待刷新" }} ·
          原始序号：{{ item.waitlistSequence }} · 候补人数：{{
            item.waitlistCount ?? "待刷新"
          }}
        </p>
        <p v-else-if="item.status === 'WAITLISTED'">
          无序候选池，等待负责人选择递补。
        </p>
        <p v-if="item.pendingOffer?.status === 'PENDING'" class="offer-hint">
          有待处理的递补邀请，请进入查看并及时确认。
        </p>
      </RouterLink>
    </div>
  </section>
</template>
