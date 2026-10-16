<script setup lang="ts">
import { isAxiosError } from "axios";
import { computed, inject, onBeforeUnmount, ref, watch } from "vue";
import { routerKey } from "vue-router";
import {
  checkinErrorMessage,
  checkinTime,
  getCurrentCheckin,
  submitCheckin,
  submitDynamicCheckin,
  type CurrentCheckin,
  type DynamicCheckinInput,
} from "../api/checkins";
import { consumeDynamicCheckinFragment } from "../checkin/dynamicCheckinFragment";
const props = defineProps<{ id: string }>();
const router = inject(routerKey, null);
const current = ref<CurrentCheckin | null>(null);
const loading = ref(false);
const busy = ref(false);
const error = ref("");
const success = ref("");
const code = ref("");
const dynamicCredential = ref<DynamicCheckinInput | null>(null);
const dynamicSessionId = ref<string | null>(null);
const now = ref(Date.now());
let generation = 0;
let routeEpoch = 0;
const timer = setInterval(() => { now.value = Date.now(); }, 1000);
const checkedIn = computed(() => current.value?.attendance?.attendanceStatus === "CHECKED_IN");
const remaining = computed(() => current.value?.session ? Math.max(0, Math.ceil((checkinTime(current.value.session.closesAt) - now.value) / 1000)) : 0);
const effective = computed(() => current.value?.session?.status === "OPEN" && remaining.value > 0);
const countdown = computed(() => `${String(Math.floor(remaining.value / 60)).padStart(2, "0")}:${String(remaining.value % 60).padStart(2, "0")}`);
const dynamicReady = computed(() => Boolean(
  dynamicCredential.value
  && current.value?.session?.sessionType === "DYNAMIC_QR"
  && String(current.value.session.sessionId) === dynamicSessionId.value,
));
const disabled = computed(() => loading.value || busy.value || !effective.value || checkedIn.value || !code.value.trim());
function consumeCredential() {
  const parameters = new URLSearchParams(window.location.search);
  dynamicSessionId.value = parameters.get("sessionId");
  dynamicCredential.value = consumeDynamicCheckinFragment(window.location, window.history);
}
async function load() {
  const request = ++generation;
  loading.value = true; error.value = "";
  try {
    const result = await getCurrentCheckin(props.id);
    if (request === generation) current.value = result;
  } catch (cause) {
    if (request === generation) { current.value = null; error.value = checkinErrorMessage(cause); }
  } finally { if (request === generation) loading.value = false; }
}
async function submit() {
  if (disabled.value || !current.value?.session) return;
  const request = generation;
  const epoch = routeEpoch;
  busy.value = true; error.value = ""; success.value = "";
  try {
    await submitCheckin(current.value.session.sessionId, code.value);
    if (request !== generation) return;
    code.value = ""; success.value = "签到成功。";
    await load();
  } catch (cause) {
    if (request !== generation) return;
    code.value = ""; error.value = checkinErrorMessage(cause);
  } finally {
    if (epoch === routeEpoch) busy.value = false;
  }
}
async function submitDynamic() {
  if (loading.value || busy.value || !effective.value || checkedIn.value
      || !dynamicReady.value || !current.value?.session || !dynamicCredential.value) return;
  const request = generation;
  const epoch = routeEpoch;
  const credential = dynamicCredential.value;
  busy.value = true; error.value = ""; success.value = "";
  try {
    await submitDynamicCheckin(current.value.session.sessionId, credential);
    if (request !== generation) return;
    dynamicCredential.value = null;
    success.value = "签到成功。";
    await load();
  } catch (cause) {
    if (request !== generation) return;
    error.value = checkinErrorMessage(cause);
    const responseCode = isAxiosError<{ code?: string }>(cause)
      ? cause.response?.data?.code
      : undefined;
    if (responseCode !== "DYNAMIC_CHECKIN_UNAVAILABLE") dynamicCredential.value = null;
  } finally {
    if (epoch === routeEpoch) busy.value = false;
  }
}
function cancelDynamic() {
  if (busy.value) return;
  dynamicCredential.value = null;
  success.value = "已取消本次扫码签到。";
}
watch(() => [props.id, router?.currentRoute.value.fullPath], () => {
  ++routeEpoch;
  code.value = ""; dynamicCredential.value = null; dynamicSessionId.value = null;
  current.value = null; success.value = ""; busy.value = false;
  consumeCredential();
  void load();
}, { immediate: true, flush: "sync" });
onBeforeUnmount(() => {
  ++routeEpoch; ++generation; clearInterval(timer); code.value = "";
  dynamicCredential.value = null; dynamicSessionId.value = null;
});
</script>
<template>
  <section class="page checkin-page">
    <RouterLink class="back" :to="`/activities/${id}`">← 返回活动</RouterLink>
    <header class="page-heading"><div><h1>活动签到</h1><p>扫描现场二维码或按管理员提供的方式完成签到。</p></div></header>
    <p v-if="loading" class="state" role="status">正在加载签到状态…</p>
    <p v-if="error" class="state" role="alert">{{ error }}</p>
    <p v-if="success" class="success" role="status">{{ success }}</p>
    <template v-if="current && !loading">
      <section v-if="checkedIn" class="confirmation-panel" role="status">
        <h2>已签到</h2><p>{{ current.attendance?.positionName }}</p>
        <p v-if="current.attendance?.checkedInAt">签到时间：{{ new Date(checkinTime(current.attendance.checkedInAt)).toLocaleString("zh-CN") }}</p>
      </section>
      <p v-else-if="!current.attendance" class="state">你尚未录取，不能参加本次签到。</p>
      <section v-else-if="current.session?.sessionType === 'DYNAMIC_QR'" class="change-form">
        <h2>{{ current.attendance.positionName }}</h2>
        <p data-test="countdown">{{ effective ? `距离关闭 ${countdown}` : "签到已关闭，请联系管理员补签。" }}</p>
        <template v-if="dynamicReady">
          <p>已读取现场动态二维码，请确认由本人完成本次签到。</p>
          <div class="checkin-actions">
            <button class="primary" data-test="confirm-dynamic-checkin" :disabled="busy || !effective" @click="submitDynamic">{{ busy ? "正在签到…" : "确认签到" }}</button>
            <button class="secondary" data-test="cancel-dynamic-checkin" :disabled="busy" @click="cancelDynamic">取消</button>
          </div>
        </template>
        <p v-else class="muted">请使用手机相机扫描现场最新二维码；已失效的二维码需要重新扫描。</p>
      </section>
      <form v-else class="change-form" @submit.prevent="submit">
        <h2>{{ current.attendance.positionName }}</h2>
        <p data-test="countdown">{{ effective ? `距离关闭 ${countdown}` : "签到已关闭，请联系管理员补签。" }}</p>
        <label for="checkin-code">签到码
          <input id="checkin-code" v-model="code" data-test="code" autocomplete="off" autocapitalize="characters" spellcheck="false" :disabled="busy || !effective" />
        </label>
        <button class="primary" data-test="submit-checkin" :disabled="disabled">{{ busy ? "正在签到…" : "提交签到" }}</button>
      </form>
    </template>
    <button class="secondary" :disabled="loading || busy" @click="load">刷新签到状态</button>
  </section>
</template>
