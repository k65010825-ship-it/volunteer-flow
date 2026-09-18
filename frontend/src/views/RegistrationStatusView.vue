<script setup lang="ts">
import {
  computed,
  nextTick,
  onBeforeUnmount,
  onMounted,
  ref,
  watch,
} from "vue";
import { getActivity } from "../api/activities";
import {
  cancelRegistration,
  getRegistration,
  registrationErrorCode,
  registrationErrorMessage,
  registrationStatusLabel,
  respondToPromotionOffer,
} from "../api/registrations";
import type { ActivityDetail, EntityId, OwnRegistration } from "../api/types";

const props = defineProps<{ id: EntityId }>();
const registration = ref<OwnRegistration | null>(null);
const detail = ref<ActivityDetail | null>(null);
const loading = ref(true);
const loadError = ref("");
const actionError = ref("");
const busy = ref(false);
const reasonRequired = ref(false);
const reason = ref("");
const reasonInput = ref<HTMLTextAreaElement | null>(null);
const now = ref(Date.now());
let generation = 0;
let timer: ReturnType<typeof setInterval> | undefined;
let checkedExpiry = "";
const position = computed(() =>
  detail.value?.positions.find(
    (candidate) => candidate.id === registration.value?.positionId,
  ),
);
const pendingOffer = computed(() =>
  registration.value?.pendingOffer?.status === "PENDING"
    ? registration.value.pendingOffer
    : null,
);
const cancelable = computed(
  () =>
    registration.value &&
    ["PENDING_REVIEW", "CONFIRMED", "WAITLISTED"].includes(
      registration.value.status,
    ),
);
const remaining = computed(() => {
  if (!pendingOffer.value) return null;
  const expiry = Date.parse(pendingOffer.value.expiresAt);
  return Number.isFinite(expiry)
    ? Math.max(0, Math.ceil((expiry - now.value) / 1000))
    : null;
});
const countdown = computed(() => {
  if (remaining.value === null) return "时间暂不可用";
  const minutes = Math.floor(remaining.value / 60)
    .toString()
    .padStart(2, "0");
  const seconds = (remaining.value % 60).toString().padStart(2, "0");
  return `${minutes}:${seconds}`;
});

async function load() {
  const request = ++generation;
  loading.value = true;
  loadError.value = "";
  try {
    const result = await getRegistration(props.id);
    if (request !== generation) return;
    registration.value = result;
    now.value = Date.now();
    loading.value = false;
    if (detail.value?.activity.id !== result.activityId) {
      detail.value = null;
      try {
        const activity = await getActivity(result.activityId);
        if (request === generation) detail.value = activity;
      } catch {
        // Status and actions remain usable even when activity enrichment fails.
      }
    }
  } catch (error) {
    if (request === generation)
      loadError.value = registrationErrorMessage(error);
  } finally {
    if (request === generation) loading.value = false;
  }
}

async function respond(decision: "ACCEPT" | "DECLINE") {
  if (busy.value || loading.value || loadError.value || !pendingOffer.value)
    return;
  const id = props.id;
  const request = generation;
  busy.value = true;
  actionError.value = "";
  try {
    await respondToPromotionOffer(pendingOffer.value.id, decision);
  } catch (error) {
    if (request === generation)
      actionError.value = registrationErrorMessage(error);
  } finally {
    if (id === props.id && request === generation) {
      // A conflict may mean another tab accepted or the server expired this invitation.
      busy.value = false;
      await load();
    }
  }
}

async function cancel() {
  if (busy.value || loading.value || loadError.value || !cancelable.value)
    return;
  const trimmedReason = reason.value.trim();
  if (reasonRequired.value && (!trimmedReason || trimmedReason.length > 500)) {
    actionError.value = "请填写 1 至 500 个字符的取消原因。";
    return;
  }
  const id = props.id;
  const request = generation;
  busy.value = true;
  actionError.value = "";
  let needsRefresh = true;
  let shouldFocusReason = false;
  try {
    await cancelRegistration(
      id,
      reasonRequired.value ? { reason: trimmedReason } : {},
    );
    if (request === generation) reasonRequired.value = false;
  } catch (error) {
    if (request === generation) {
      if (registrationErrorCode(error) === "CANCELLATION_REASON_REQUIRED") {
        reasonRequired.value = true;
        needsRefresh = false;
        shouldFocusReason = true;
      }
      actionError.value = registrationErrorMessage(error);
    }
  } finally {
    if (id === props.id && request === generation) {
      busy.value = false;
      if (needsRefresh) await load();
      if (shouldFocusReason) {
        // Wait until the revealed field is rendered and no longer disabled.
        await nextTick();
        if (id === props.id && request === generation && reasonRequired.value) {
          reasonInput.value?.focus();
        }
      }
    }
  }
}

watch(
  () => props.id,
  () => {
    registration.value = null;
    detail.value = null;
    busy.value = false;
    actionError.value = "";
    reasonRequired.value = false;
    reason.value = "";
    checkedExpiry = "";
    void load();
  },
  { immediate: true },
);

onMounted(() => {
  timer = setInterval(() => {
    now.value = Date.now();
    const offer = pendingOffer.value;
    if (!offer || remaining.value !== 0 || loading.value || busy.value) return;
    const expiryKey = `${offer.id}:${offer.expiresAt}`;
    if (expiryKey === checkedExpiry) return;
    checkedExpiry = expiryKey;
    // The client clock is only a display hint; never transition local status at zero.
    void load();
  }, 1000);
});
onBeforeUnmount(() => {
  ++generation;
  clearInterval(timer);
});
</script>

<template>
  <section class="page detail-page">
    <RouterLink class="back" to="/registrations">← 我的报名</RouterLink>
    <div class="page-heading">
      <div>
        <h1>报名状态</h1>
        <p>最新结果以服务器记录为准。</p>
      </div>
      <button
        class="secondary"
        data-test="refresh"
        :disabled="loading || busy"
        @click="load"
      >
        刷新
      </button>
    </div>
    <p v-if="loading" class="state" role="status">正在刷新报名状态…</p>
    <p v-else-if="loadError" class="state" role="alert">{{ loadError }}</p>
    <template v-else-if="registration">
      <header class="detail-header">
        <h2>
          {{ detail?.activity.title ?? `活动 #${registration.activityId}` }}
        </h2>
        <p>{{ position?.name ?? `岗位 #${registration.positionId}` }}</p>
        <span class="status-badge" role="status">{{
          registrationStatusLabel[registration.status]
        }}</span>
      </header>
      <dl class="facts">
        <div>
          <dt>报名周期</dt>
          <dd>第 {{ registration.cycleNumber }} 次</dd>
        </div>
        <div>
          <dt>提交时间</dt>
          <dd>{{ registration.submittedAt.replace("T", " ") }}</dd>
        </div>
        <div v-if="position">
          <dt>录取方式</dt>
          <dd>
            {{
              position.registrationMode === "FIRST_COME"
                ? "先到先得"
                : "负责人审核"
            }}
          </dd>
        </div>
      </dl>
      <div v-if="registration.status === 'WAITLISTED'" class="state">
        <template v-if="registration.waitlistSequence !== null">
          <strong
            >当前位次：{{
              registration.currentWaitlistPosition ?? "待刷新"
            }}</strong
          >
          <p>
            原始序号：{{ registration.waitlistSequence }} · 候补人数：{{
              registration.waitlistCount ?? "待刷新"
            }}
          </p>
        </template>
        <template v-else
          ><strong>无序候选池</strong>
          <p>等待负责人选择递补，不按报名先后排序。</p></template
        >
      </div>
      <section
        v-if="pendingOffer"
        class="offer-panel"
        aria-labelledby="offer-heading"
      >
        <h2 id="offer-heading">递补邀请</h2>
        <p v-if="pendingOffer.reason">{{ pendingOffer.reason }}</p>
        <p>确认截止：{{ pendingOffer.expiresAt.replace("T", " ") }}</p>
        <p class="countdown">剩余时间 {{ countdown }}</p>
        <p v-if="remaining === 0 || remaining === null" class="muted">
          本地倒计时仅供参考，是否过期以后端确认为准；可刷新或提交确认。
        </p>
        <div class="registration-actions">
          <button
            class="primary"
            data-test="accept"
            :disabled="busy"
            @click="respond('ACCEPT')"
          >
            接受递补
          </button>
          <button
            class="secondary"
            data-test="decline"
            :disabled="busy"
            @click="respond('DECLINE')"
          >
            拒绝递补
          </button>
        </div>
      </section>
      <p v-if="actionError" class="error" role="alert">{{ actionError }}</p>
      <form
        v-if="cancelable"
        class="registration-form"
        @submit.prevent="cancel"
      >
        <div v-if="reasonRequired" class="question-field">
          <label for="cancellation-reason"
            >迟取消原因（必填，最多 500 字）</label
          >
          <textarea
            id="cancellation-reason"
            ref="reasonInput"
            v-model="reason"
            data-test="cancellation-reason"
            rows="3"
            :disabled="busy"
            aria-required="true"
          />
        </div>
        <button
          class="secondary"
          type="button"
          data-test="cancel"
          :disabled="busy"
          @click="cancel"
        >
          {{ reasonRequired ? "提交原因并取消报名" : "取消报名" }}
        </button>
      </form>
      <RouterLink
        v-else
        class="text-action link-button"
        :to="`/activities/${registration.activityId}`"
        >返回活动查看</RouterLink
      >
    </template>
  </section>
</template>
