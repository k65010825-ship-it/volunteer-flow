<script setup lang="ts">
import { isAxiosError } from "axios";
import { computed, nextTick, onBeforeUnmount, ref, watch } from "vue";
import {
  createPromotionOffer,
  getRegistrationForm,
  listPositionRegistrations,
  registrationErrorMessage,
  registrationStatusLabel,
  reviewRegistration,
  type ManagedRegistration,
  type ReviewDecision,
} from "../api/registrations";
import type {
  RegistrationAnswer,
  RegistrationForm,
  RegistrationStatus,
} from "../api/types";

const props = defineProps<{ positionId: string }>();
const items = ref<ManagedRegistration[]>([]);
const status = ref<RegistrationStatus | "">("");
const page = ref(1);
const pageSize = 20;
const total = ref(0n);
const loading = ref(false);
const busy = ref(false);
const error = ref("");
const reasons = ref<Record<string, string>>({});
const form = ref<RegistrationForm | null>(null);
const hasNext = computed(() => BigInt(page.value * pageSize) < total.value);
let generation = 0;
let alive = true;

async function load(keepError = false) {
  const request = ++generation;
  const positionId = props.positionId;
  loading.value = true;
  if (!keepError) error.value = "";
  try {
    const result = await listPositionRegistrations(positionId, {
      ...(status.value ? { status: status.value } : {}),
      page: page.value,
      size: pageSize,
    });
    if (!alive || request !== generation) return;
    items.value = result.items;
    total.value = BigInt(result.total);
    // A decision may remove the last item on a filtered page.
    if (
      !items.value.length &&
      page.value > 1 &&
      BigInt((page.value - 1) * pageSize) >= total.value
    ) {
      page.value = Math.max(1, Math.ceil(Number(total.value) / pageSize));
      await load(keepError);
      return;
    }
    const first = result.items[0];
    if (first && !form.value) {
      void enrichForm(first.activityId, positionId, request);
    }
  } catch (cause) {
    if (!alive || request !== generation) return;
    items.value = [];
    error.value =
      isAxiosError(cause) && cause.response?.status === 403
        ? "你没有查看此岗位报名的权限。"
        : "报名加载失败，请刷新重试。";
  } finally {
    if (alive && request === generation) loading.value = false;
  }
}

async function enrichForm(
  activityId: string,
  positionId: string,
  request: number,
) {
  try {
    const definition = await getRegistrationForm(activityId, positionId);
    if (alive && request === generation) form.value = definition;
  } catch {
    // The list and its ID fallbacks remain usable if optional labels are unavailable.
  }
}

function canPromote(item: ManagedRegistration) {
  // Only REVIEW candidates are unordered; FIRST_COME always carries a sequence.
  return (
    item.status === "WAITLISTED" &&
    item.waitlistSequence === null &&
    !item.offer
  );
}

function answerTitle(answer: RegistrationAnswer) {
  return (
    form.value?.questions.find(
      (question) =>
        question.scope === answer.questionScope &&
        question.id === answer.questionId,
    )?.title ??
    `${answer.questionScope === "ACTIVITY" ? "活动" : "岗位"}问题 #${answer.questionId}`
  );
}

function answerText(answer: unknown): string {
  if (typeof answer === "boolean") return answer ? "是" : "否";
  if (Array.isArray(answer)) return answer.map(answerText).join("、");
  if (answer == null) return "未填写";
  return typeof answer === "object" ? JSON.stringify(answer) : String(answer);
}

async function command(item: ManagedRegistration, decision?: ReviewDecision) {
  if (busy.value || loading.value) return;
  const positionId = props.positionId;
  const requestGeneration = generation;
  const reason = (reasons.value[item.cycleId] ?? "").trim();
  if ((decision && !reason) || reason.length > 500) {
    error.value = "请填写 1–500 个字符的审核原因（递补原因可不填）。";
    await nextTick();
    document.getElementById(`reason-${item.cycleId}`)?.focus();
    return;
  }
  busy.value = true;
  error.value = "";
  try {
    if (decision)
      await reviewRegistration(item.registrationId, { decision, reason });
    else
      await createPromotionOffer(item.registrationId, reason ? { reason } : {});
    if (
      !alive ||
      positionId !== props.positionId ||
      requestGeneration !== generation
    )
      return;
    delete reasons.value[item.cycleId];
    await load();
  } catch (cause) {
    if (
      !alive ||
      positionId !== props.positionId ||
      requestGeneration !== generation
    )
      return;
    error.value =
      isAxiosError(cause) && cause.response?.status === 403
        ? "你没有执行此操作的权限。"
        : registrationErrorMessage(cause);
    if (isAxiosError(cause) && cause.response?.status === 409) await load(true);
  } finally {
    if (alive) busy.value = false;
  }
}

function changeFilter() {
  page.value = 1;
  void load();
}
function changePage(delta: number) {
  if (
    loading.value ||
    busy.value ||
    (delta < 0 && page.value === 1) ||
    (delta > 0 && !hasNext.value)
  )
    return;
  page.value += delta;
  void load();
}
watch(
  () => props.positionId,
  () => {
    page.value = 1;
    status.value = "";
    items.value = [];
    reasons.value = {};
    form.value = null;
    void load();
  },
  { immediate: true },
);
onBeforeUnmount(() => {
  alive = false;
  ++generation;
});
</script>

<template>
  <section class="page detail-page">
    <div class="page-heading">
      <div>
        <h1>报名管理</h1>
        <p>{{ form?.position.name ?? `岗位 #${positionId}` }} · 审核与候选池</p>
      </div>
      <button
        class="secondary"
        data-test="refresh"
        :disabled="loading || busy"
        @click="load()"
      >
        刷新
      </button>
    </div>
    <div class="management-toolbar">
      <label for="registration-status-filter">报名状态</label>
      <select
        id="registration-status-filter"
        v-model="status"
        :disabled="loading || busy"
        @change="changeFilter"
      >
        <option value="">全部状态</option>
        <option
          v-for="(label, value) in registrationStatusLabel"
          :key="value"
          :value="value"
        >
          {{ label }}
        </option>
      </select>
    </div>
    <p v-if="error" class="state" role="alert">{{ error }}</p>
    <p v-if="loading" class="state" role="status">正在加载报名…</p>
    <p v-else-if="!items.length && !error" class="state">暂无报名</p>
    <div v-else-if="!loading" class="registration-list">
      <article
        v-for="item in items"
        :key="item.cycleId"
        class="registration-card"
      >
        <h2>{{ item.realName }}</h2>
        <p>学号：{{ item.studentNumber }} · 联系方式：{{ item.contact }}</p>
        <span class="status-badge">{{
          registrationStatusLabel[item.status]
        }}</span>
        <p>
          报名时间：{{ item.submittedAt.replace("T", " ") }} · 第
          {{ item.cycleNumber }} 次报名
        </p>
        <p v-if="item.waitlistSequence !== null">
          候补序号：{{ item.waitlistSequence }}（按顺序自动递补）
        </p>
        <dl v-if="item.answers.length" class="management-answers">
          <template
            v-for="answer in item.answers"
            :key="`${answer.questionScope}-${answer.questionId}`"
          >
            <dt>{{ answerTitle(answer) }}</dt>
            <dd>{{ answerText(answer.answer) }}</dd>
          </template>
        </dl>
        <p v-else class="muted">无报名答案</p>
        <p v-if="item.reviewReason">审核原因：{{ item.reviewReason }}</p>
        <p v-if="item.offer">
          递补邀请：{{ item.offer.status }} · 截止
          {{ item.offer.expiresAt.replace("T", " ") }}
        </p>
        <div
          v-if="item.status === 'PENDING_REVIEW' || canPromote(item)"
          class="management-actions"
        >
          <label :for="`reason-${item.cycleId}`">{{
            item.status === "PENDING_REVIEW"
              ? "审核原因（必填）"
              : "递补原因（选填）"
          }}</label>
          <textarea
            :id="`reason-${item.cycleId}`"
            v-model="reasons[item.cycleId]"
            :disabled="busy"
            :required="item.status === 'PENDING_REVIEW'"
            rows="3"
          />
          <div class="management-buttons">
            <template v-if="item.status === 'PENDING_REVIEW'">
              <button
                data-test="confirm"
                :disabled="busy"
                @click="command(item, 'CONFIRM')"
              >
                录取
              </button>
              <button
                data-test="waitlist"
                class="secondary"
                :disabled="busy"
                @click="command(item, 'WAITLIST')"
              >
                加入候选池
              </button>
              <button
                data-test="reject"
                class="secondary"
                :disabled="busy"
                @click="command(item, 'REJECT')"
              >
                拒绝
              </button>
            </template>
            <button
              v-else
              data-test="promote"
              :disabled="busy"
              @click="command(item)"
            >
              发送递补邀请
            </button>
          </div>
        </div>
      </article>
    </div>
    <nav class="management-buttons" aria-label="报名分页">
      <button
        data-test="previous"
        class="secondary"
        :disabled="loading || busy || page === 1"
        @click="changePage(-1)"
      >
        上一页
      </button>
      <span>第 {{ page }} 页 · 共 {{ total.toString() }} 条</span>
      <button
        data-test="next"
        class="secondary"
        :disabled="loading || busy || !hasNext"
        @click="changePage(1)"
      >
        下一页
      </button>
    </nav>
  </section>
</template>
