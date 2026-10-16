<script setup lang="ts">
import { isAxiosError } from "axios";
import QRCode from "qrcode";
import { computed, inject, onBeforeUnmount, ref, watch } from "vue";
import { routerKey } from "vue-router";
import {
  attendanceLabel, checkinErrorMessage, checkinTime, closeCheckinSession, getAttendanceSummary,
  getCurrentCheckin, getDynamicCheckinCode, listCheckinMembers, manualCheckin, openCheckinSession,
  type AttendanceSummary, type CheckinMember, type CheckinSession,
} from "../api/checkins";
import { buildDynamicCheckinUrl } from "../checkin/dynamicCheckinFragment";
const props = defineProps<{ id: string }>();
const router = inject(routerKey, null);
const summary = ref<AttendanceSummary | null>(null);
const session = ref<CheckinSession | null>(null);
// Only the open response populates this ref. Never persist it or attach it to safe session projections.
const plaintextCode = ref("");
const copied = ref("");
const qrDataUrl = ref("");
const qrExpiresAt = ref("");
const qrLoading = ref(false);
const qrError = ref("");
const duration = ref<number | string>(15);
const members = ref<CheckinMember[]>([]);
const search = ref("");
const reasons = ref<Record<string, string>>({});
const loading = ref(false);
const membersLoading = ref(false);
const busy = ref(false);
const error = ref("");
const memberError = ref("");
const success = ref("");
const now = ref(Date.now());
let generation = 0;
let routeEpoch = 0;
let qrGeneration = 0;
let qrRefreshTimer: ReturnType<typeof setTimeout> | undefined;
const timer = setInterval(() => { now.value = Date.now(); }, 1000);
const effective = computed(() => session.value?.status === "OPEN" && checkinTime(session.value.closesAt) > now.value);
const finalActivity = computed(() => summary.value?.activityStatus === "FINISHED" || summary.value?.activityStatus === "CANCELED");
const qrRemaining = computed(() => qrExpiresAt.value
  ? Math.max(0, Math.ceil((Date.parse(qrExpiresAt.value) - now.value) / 1000))
  : 0);
const visibleMembers = computed(() => {
  const term = search.value.trim().toLocaleLowerCase();
  return members.value.filter(m => `${m.realName} ${m.studentNumber} ${m.registrationId}`.toLocaleLowerCase().includes(term));
});
const percent = (rate: number) => `${(rate * 100).toFixed(2)}%`;
function clearCode() { plaintextCode.value = ""; copied.value = ""; }
function clearQr() {
  qrGeneration++;
  if (qrRefreshTimer !== undefined) clearTimeout(qrRefreshTimer);
  qrRefreshTimer = undefined;
  qrDataUrl.value = "";
  qrExpiresAt.value = "";
  qrLoading.value = false;
  qrError.value = "";
}
watch(effective, active => {
  if (!active) {
    clearCode();
    clearQr();
  }
});
async function refreshDynamicCode() {
  const active = session.value;
  if (!active || active.sessionType !== "DYNAMIC_QR" || !effective.value) return;
  const request = ++qrGeneration;
  if (qrRefreshTimer !== undefined) clearTimeout(qrRefreshTimer);
  qrRefreshTimer = undefined;
  qrLoading.value = true;
  qrError.value = "";
  try {
    const credential = await getDynamicCheckinCode(active.sessionId);
    const target = buildDynamicCheckinUrl(window.location.origin, active.activityId, credential);
    const image = await QRCode.toDataURL(target, { errorCorrectionLevel: "M", margin: 2, width: 320 });
    if (request !== qrGeneration || session.value?.sessionId !== active.sessionId) return;
    qrDataUrl.value = image;
    qrExpiresAt.value = credential.expiresAt;
    const refreshDelay = Math.max(1_000, Date.parse(credential.expiresAt) - Date.now() - 1_000);
    qrRefreshTimer = setTimeout(() => { void refreshDynamicCode(); }, refreshDelay);
  } catch (cause) {
    if (request === qrGeneration) {
      qrDataUrl.value = "";
      qrExpiresAt.value = "";
      qrError.value = checkinErrorMessage(cause);
    }
  } finally {
    if (request === qrGeneration) qrLoading.value = false;
  }
}
async function loadMembers(request: number, positions: AttendanceSummary["positions"]) {
  membersLoading.value = true; memberError.value = "";
  const result: CheckinMember[] = [];
  try {
    for (const position of positions) {
      let page = 1;
      while (request === generation) {
        const response = await listCheckinMembers(position.positionId, page);
        if (request !== generation) return;
        result.push(...response.items);
        if (response.items.length === 0 || BigInt(page * 100) >= BigInt(response.total)) break;
        page++;
      }
    }
    if (request === generation) members.value = result;
  } catch (cause) {
    if (request === generation) {
      members.value = [];
      memberError.value = isAxiosError(cause) && cause.response?.status === 403
        ? "成员搜索需要额外的报名审核权限（registration:review），请联系组织管理员。"
        : "成员列表加载失败，请刷新重试。";
    }
  } finally { if (request === generation) membersLoading.value = false; }
}
async function load() {
  const request = ++generation;
  loading.value = true; error.value = "";
  const id = props.id;
  try {
    const result = await getAttendanceSummary(id);
    if (request !== generation) return;
    summary.value = result;
    void loadMembers(request, result.positions);
    const current = await getCurrentCheckin(id);
    if (request === generation) {
      session.value = current.session;
      if (current.session?.sessionType === "DYNAMIC_QR") void refreshDynamicCode();
    }
  } catch (cause) {
    if (request === generation) { error.value = checkinErrorMessage(cause); summary.value = null; members.value = []; clearCode(); }
  } finally { if (request === generation) loading.value = false; }
}
async function openSession() {
  if (busy.value || loading.value || !summary.value || effective.value || finalActivity.value) return;
  clearCode(); success.value = ""; error.value = "";
  const minutes = Number(duration.value);
  if (!Number.isInteger(minutes) || minutes < 5 || minutes > 120) { error.value = "签到时长须为 5–120 分钟的整数。"; return; }
  const request = generation;
  busy.value = true;
  try {
    const result = await openCheckinSession(props.id, minutes);
    if (request !== generation) return;
    session.value = { sessionId: result.sessionId, activityId: result.activityId, sessionType: result.sessionType, status: "OPEN", opensAt: result.opensAt, closesAt: result.closesAt };
    plaintextCode.value = result.code ?? "";
    if (result.sessionType === "DYNAMIC_QR") void refreshDynamicCode();
    // The open result provides only a safe routing reference for immediate manual corrections.
    summary.value.correctionSession = session.value;
    success.value = "签到已开启。";
  } catch (cause) { if (request === generation) { clearCode(); error.value = checkinErrorMessage(cause); } }
  finally { if (request === generation) busy.value = false; }
}
async function closeSession() {
  if (busy.value || loading.value || !session.value || !effective.value) return;
  clearCode(); clearQr();
  const request = generation;
  const epoch = routeEpoch;
  busy.value = true; error.value = ""; success.value = "";
  try {
    const closed = await closeCheckinSession(session.value.sessionId);
    if (request !== generation) return;
    session.value = closed; success.value = "签到已关闭。";
    await load();
  } catch (cause) { if (request === generation) error.value = checkinErrorMessage(cause); }
  finally { if (epoch === routeEpoch) busy.value = false; }
}
async function copyCode() {
  if (!plaintextCode.value || busy.value) return;
  const request = generation;
  try {
    await navigator.clipboard.writeText(plaintextCode.value);
    if (request === generation && plaintextCode.value) copied.value = "已复制；剪贴板由你管理，请勿转发给无关人员。";
  } catch { if (request === generation && plaintextCode.value) copied.value = "复制失败，请手动复制签到码。"; }
}
function canCorrect(member: CheckinMember) {
  return !busy.value && !loading.value && !membersLoading.value && Boolean(summary.value?.correctionSession)
    && ["PENDING", "NO_SHOW"].includes(member.attendanceStatus);
}
async function correct(member: CheckinMember) {
  if (!canCorrect(member) || !summary.value?.correctionSession) return;
  const reason = (reasons.value[member.registrationId] ?? "").trim();
  if (!reason || reason.length > 500) { error.value = "请填写 1–500 个字符的补签原因。"; return; }
  const request = generation;
  const epoch = routeEpoch;
  busy.value = true; error.value = ""; success.value = "";
  try {
    await manualCheckin(summary.value.correctionSession.sessionId, member.registrationId, reason);
    if (request !== generation) return;
    delete reasons.value[member.registrationId]; success.value = "补签成功。";
    await load();
  } catch (cause) { if (request === generation) error.value = checkinErrorMessage(cause); }
  finally { if (epoch === routeEpoch) busy.value = false; }
}
watch(() => [props.id, router?.currentRoute.value.fullPath], () => {
  ++routeEpoch;
  membersLoading.value = false;
  clearCode(); clearQr(); summary.value = null; session.value = null; members.value = []; reasons.value = {};
  search.value = ""; duration.value = 15; busy.value = false; success.value = ""; memberError.value = "";
  void load();
}, { immediate: true, flush: "sync" });
onBeforeUnmount(() => { ++routeEpoch; ++generation; clearInterval(timer); clearCode(); clearQr(); });
</script>
<template>
  <section class="page checkin-page">
    <RouterLink class="back" :to="`/activities/${id}`">← 返回活动</RouterLink>
    <header class="page-heading"><div><h1>签到管理</h1><p>开启现场签到、补签并查看到场统计。</p></div></header>
    <p v-if="loading" class="state" role="status">正在加载签到管理…</p>
    <p v-if="error" class="state" role="alert">{{ error }}</p>
    <p v-if="success" class="success" role="status">{{ success }}</p>
    <template v-if="summary">
      <section class="change-form" aria-labelledby="session-heading">
        <h2 id="session-heading">现场签到</h2>
        <p v-if="effective">签到进行中 · 关闭时间 {{ session ? new Date(checkinTime(session.closesAt)).toLocaleString("zh-CN") : "" }}</p>
        <p v-else>当前没有开放的签到会话。</p>
        <label for="checkin-duration">有效时长（分钟，5–120）<input id="checkin-duration" v-model="duration" data-test="duration" type="number" min="5" max="120" step="1" :disabled="busy || effective || finalActivity" /></label>
        <div class="checkin-actions">
          <button class="primary" data-test="open-session" :disabled="busy || loading || effective || finalActivity" @click="openSession">开启签到</button>
          <button v-if="effective" class="secondary" data-test="close-session" :disabled="busy || loading" @click="closeSession">提前关闭签到</button>
        </div>
        <p v-if="finalActivity" class="muted">活动已结束或取消，不能开启新的签到会话。</p>
        <div v-if="plaintextCode" class="confirmation-panel">
          <p>签到码仅本次显示，刷新或离开页面后无法再次获取。请仅向现场已录取成员提供。</p>
          <strong class="checkin-code" data-test="plaintext-code">{{ plaintextCode }}</strong>
          <button class="secondary" data-test="copy-code" :disabled="busy" @click="copyCode">复制签到码</button>
          <p v-if="copied" role="status">{{ copied }}</p>
        </div>
        <div v-if="effective && session?.sessionType === 'DYNAMIC_QR'" class="confirmation-panel">
          <p v-if="qrLoading" role="status">正在生成动态签到二维码…</p>
          <template v-else-if="qrError">
            <p role="alert">{{ qrError }}</p>
            <button class="secondary" data-test="retry-qr" :disabled="busy" @click="refreshDynamicCode">重试二维码</button>
          </template>
          <template v-else-if="qrDataUrl">
            <img data-test="dynamic-qr" :src="qrDataUrl" alt="动态签到二维码" width="320" height="320" />
            <p data-test="qr-countdown">本二维码将在 {{ qrRemaining }} 秒后更新</p>
            <p class="muted">请成员使用手机相机扫描；二维码会自动轮换，请勿截图转发。</p>
          </template>
        </div>
      </section>
      <section aria-labelledby="attendance-heading">
        <h2 id="attendance-heading">到场统计</h2>
        <p class="muted">{{ summary.finalStatistics ? "最终统计" : "实时统计（待签到不等于未到场）" }}</p>
        <div class="checkin-stats" data-test="attendance-totals">
          <span>已录取 {{ summary.confirmedCount }}</span><span>已签到 {{ summary.checkedInCount }}</span>
          <span>未到场 {{ summary.noShowCount }}</span><span>待签到 {{ summary.pendingCount }}</span><span>签到率 {{ percent(summary.checkinRate) }}</span>
        </div>
        <article v-for="position in summary.positions" :key="position.positionId" class="managed-position" :data-test="`position-${position.positionId}`">
          <h3>{{ position.positionName }}</h3><div class="checkin-stats"><span>已录取 {{ position.confirmedCount }}</span><span>已签到 {{ position.checkedInCount }}</span><span>未到场 {{ position.noShowCount }}</span><span>待签到 {{ position.pendingCount }}</span><span>签到率 {{ percent(position.checkinRate) }}</span></div>
        </article>
      </section>
      <section class="change-form" aria-labelledby="members-heading">
        <h2 id="members-heading">成员补签</h2>
        <p v-if="!summary.correctionSession" class="muted">尚无签到会话，无法补签。活动结束后不能为补签新建会话。</p>
        <label for="checkin-member-search">搜索已录取成员（姓名 / 学号 / 报名编号）<input id="checkin-member-search" v-model="search" data-test="member-search" type="search" /></label>
        <p v-if="membersLoading" role="status">正在加载全部已录取成员…</p>
        <p v-else-if="memberError" role="alert">{{ memberError }}</p>
        <template v-else>
          <p v-if="!visibleMembers.length" class="muted">没有匹配的已录取成员。</p>
          <article v-for="member in visibleMembers" :key="member.registrationId" class="managed-position" :data-test="`member-${member.registrationId}`">
            <h3>{{ member.realName }} · {{ member.studentNumber }}</h3>
            <p>报名 #{{ member.registrationId }} · {{ attendanceLabel[member.attendanceStatus] }}</p>
            <label :for="`checkin-reason-${member.registrationId}`">补签原因（必填，最多 500 字）<textarea :id="`checkin-reason-${member.registrationId}`" v-model="reasons[member.registrationId]" :data-test="`reason-${member.registrationId}`" maxlength="500" :disabled="!canCorrect(member)" /></label>
            <button class="secondary" :data-test="`manual-${member.registrationId}`" :disabled="!canCorrect(member)" @click="correct(member)">{{ member.attendanceStatus === "CHECKED_IN" ? "已签到" : "确认补签" }}</button>
          </article>
        </template>
      </section>
    </template>
    <button class="secondary" :disabled="loading || busy || membersLoading" @click="clearCode(); clearQr(); load()">刷新管理数据</button>
    <p class="phase-note">页面入口只是操作提示，所有权限和签到资格均由服务端校验。</p>
  </section>
</template>
