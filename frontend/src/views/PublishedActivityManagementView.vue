<script setup lang="ts">
import { isAxiosError } from "axios";
import { computed, onBeforeUnmount, reactive, ref, watch } from "vue";
import {
  changeActivity,
  cancelActivity,
  completeActivity,
  getActivity,
  changePosition,
  createPositionQuestion,
  createPosition,
  deletePositionQuestion,
  getManagementActivity,
  publishPosition,
  updatePositionQuestion,
  type ActivityChangeRequest,
  type PositionChangeRequest,
  type PositionDraftRequest,
  type PositionQuestionRequest,
} from "../api/activities";
import { getOrganizationAccess } from "../api/organizations";
import type {
  Activity,
  ActivityManagementDetail,
  EntityId,
  Position,
  PositionManagementQuestion,
  QuestionType,
} from "../api/types";

const props = defineProps<{ id: EntityId }>();

type ActivityForm = Omit<
  ActivityChangeRequest,
  "reason" | "freeCancelDeadlineAt"
> & { freeCancelDeadlineAt: string };
type PositionForm = Omit<
  PositionChangeRequest,
  "reason" | "serviceStartAt" | "serviceEndAt" | "meetingLocation"
> & {
  serviceStartAt: string;
  serviceEndAt: string;
  meetingLocation: string;
};
type DraftPositionForm = Omit<
  PositionDraftRequest,
  "serviceStartAt" | "serviceEndAt" | "meetingLocation"
> & {
  serviceStartAt: string;
  serviceEndAt: string;
  meetingLocation: string;
};
type Difference = { label: string; before: string; after: string };
type PendingChange = { kind: "activity" } | { kind: "position"; id: EntityId };
type QuestionForm = {
  id?: EntityId;
  type: QuestionType;
  title: string;
  required: boolean;
  optionsText: string;
  sortOrder: number;
  savedSignature?: string;
};

const detail = ref<ActivityManagementDetail | null>(null);
const loading = ref(true);
const loadError = ref("");
const actionError = ref("");
const confirmationError = ref("");
const notification = ref("");
const activityForm = ref<ActivityForm | null>(null);
const positionForms = reactive<Record<string, PositionForm>>({});
const questionForms = reactive<Record<string, QuestionForm[]>>({});
const newPosition = reactive<DraftPositionForm>({
  name: "",
  description: "",
  capacity: 1,
  registrationMode: "FIRST_COME",
  promotionTimeoutMinutes: 120,
  serviceStartAt: "",
  serviceEndAt: "",
  meetingLocation: "",
});
const pending = ref<PendingChange | null>(null);
const reason = ref("");
const submitting = ref(false);
const permissions = ref<string[]>([]);
const lifecycle = ref<"cancel" | "complete" | null>(null);
const lifecycleReason = ref("");
const canChange = computed(
  () =>
    detail.value?.activity.status === "PUBLISHED" &&
    permissions.value.includes("activity:change"),
);
const canCancel = computed(
  () =>
    !!detail.value &&
    ["DRAFT", "PUBLISHED"].includes(detail.value.activity.status) &&
    permissions.value.includes("activity:cancel"),
);
let generation = 0;
let alive = true;

const pendingDifferences = computed(() => {
  if (!pending.value || !detail.value) return [];
  if (pending.value.kind === "activity") {
    return activityDifferences(detail.value.activity, activityForm.value);
  }
  const positionId = pending.value.id;
  return positionDifferences(
    detail.value.positions.find((item) => item.id === positionId),
    positionForms[positionId],
  );
});

function localDate(value?: string | null) {
  return value ? value.slice(0, 16) : "";
}

// datetime-local is a minute-resolution editor, not the source of unchanged timestamps.
function timePayload(value: string, original?: string | null) {
  return value === localDate(original) ? (original ?? null) : value || null;
}

function activityToForm(activity: Activity): ActivityForm {
  return {
    title: activity.title,
    description: activity.description,
    location: activity.location,
    registrationEndAt: localDate(activity.registrationEndAt),
    freeCancelDeadlineAt: localDate(activity.freeCancelDeadlineAt),
    activityStartAt: localDate(activity.activityStartAt),
    activityEndAt: localDate(activity.activityEndAt),
  };
}

function questionToForm(question: PositionManagementQuestion): QuestionForm {
  let options: string[] = [];
  try {
    options = question.optionsJson ? JSON.parse(question.optionsJson) : [];
  } catch {
    options = [];
  }
  const form: QuestionForm = {
    id: question.id,
    type: question.questionType,
    title: question.title,
    required: question.requiredQuestion,
    optionsText: options.join("\n"),
    sortOrder: question.sortOrder,
  };
  form.savedSignature = questionSignature(form);
  return form;
}

function positionToForm(position: Position): PositionForm {
  return {
    name: position.name,
    description: position.description,
    capacity: position.capacity,
    registrationMode: position.registrationMode,
    promotionTimeoutMinutes: position.promotionTimeoutMinutes,
    serviceStartAt: localDate(position.serviceStartAt),
    serviceEndAt: localDate(position.serviceEndAt),
    meetingLocation: position.meetingLocation ?? "",
  };
}

function setPage(result: ActivityManagementDetail) {
  detail.value = result;
  activityForm.value = activityToForm(result.activity);
  for (const key of Object.keys(positionForms)) delete positionForms[key];
  for (const key of Object.keys(questionForms)) delete questionForms[key];
  for (const position of result.positions)
    positionForms[position.id] = positionToForm(position);
  for (const position of result.positions)
    questionForms[position.id] = position.questions.map(questionToForm);
}

function resetInteractionState() {
  loading.value = true;
  loadError.value = "";
  actionError.value = "";
  confirmationError.value = "";
  notification.value = "";
  pending.value = null;
  reason.value = "";
  submitting.value = false;
  permissions.value = [];
  lifecycle.value = null;
  lifecycleReason.value = "";
  detail.value = null;
  activityForm.value = null;
  resetNewPosition();
  for (const key of Object.keys(positionForms)) delete positionForms[key];
  for (const key of Object.keys(questionForms)) delete questionForms[key];
}

async function load() {
  const request = ++generation;
  resetInteractionState();
  try {
    let result: ActivityManagementDetail;
    try {
      result = await getManagementActivity(props.id);
    } catch (error) {
      if (!isAxiosError(error) || error.response?.status !== 403) throw error;
      const publicDetail = await getActivity(props.id).catch(() => {
        throw error;
      });
      result = {
        ...publicDetail,
        commonQuestionCount: 0,
        positions: publicDetail.positions.map((p) => ({ ...p, questions: [] })),
      };
    }
    const access = await getOrganizationAccess(result.activity.organizationId);
    if (alive && request === generation) {
      permissions.value = access.permissionCodes;
      setPage(result);
    }
  } catch (error) {
    if (!alive || request !== generation) return;
    loadError.value =
      isAxiosError(error) && error.response?.status === 403
        ? "你没有管理此活动的权限。"
        : "活动管理信息加载失败，请刷新重试。";
  } finally {
    if (alive && request === generation) loading.value = false;
  }
}

async function submitLifecycle() {
  if (submitting.value || !detail.value || !lifecycle.value) return;
  if (
    (lifecycle.value === "cancel" && !canCancel.value) ||
    (lifecycle.value === "complete" && !canChange.value)
  )
    return;
  const why = lifecycleReason.value.trim();
  if (!why || why.length > 500) {
    actionError.value = "请填写 1–500 字的操作原因。";
    return;
  }
  const request = generation,
    kind = lifecycle.value;
  submitting.value = true;
  actionError.value = "";
  try {
    const result =
      kind === "cancel"
        ? await cancelActivity(props.id, why)
        : await completeActivity(props.id, why);
    if (!alive || request !== generation) return;
    detail.value = { ...detail.value, activity: result };
    activityForm.value = activityToForm(result);
    lifecycle.value = null;
    pending.value = null;
    notification.value =
      kind === "cancel"
        ? "活动已取消。"
        : "活动已提前结束，签到与候补流程已关闭。";
  } catch (error) {
    if (alive && request === generation)
      actionError.value = errorMessage(error);
  } finally {
    if (alive && request === generation) submitting.value = false;
  }
}

function changed(
  label: string,
  before: string | number,
  after: string | number,
) {
  return String(before) === String(after)
    ? []
    : [{ label, before: String(before), after: String(after) }];
}

function changedTime(
  label: string,
  original: string | null | undefined,
  value: string,
) {
  return changed(label, original ?? "", timePayload(value, original) ?? "");
}

const trimmed = (value: string) => value.trim();

function activityDifferences(
  activity: Activity,
  form: ActivityForm | null,
): Difference[] {
  if (!form) return [];
  return [
    ...changed("活动标题", trimmed(activity.title), trimmed(form.title)),
    ...changed(
      "活动介绍",
      trimmed(activity.description),
      trimmed(form.description),
    ),
    ...changed("活动地点", trimmed(activity.location), trimmed(form.location)),
    ...changedTime(
      "报名截止时间",
      activity.registrationEndAt,
      form.registrationEndAt,
    ),
    ...changedTime(
      "免费取消截止时间",
      activity.freeCancelDeadlineAt,
      form.freeCancelDeadlineAt,
    ),
    ...changedTime(
      "活动开始时间",
      activity.activityStartAt,
      form.activityStartAt,
    ),
    ...changedTime("活动结束时间", activity.activityEndAt, form.activityEndAt),
  ];
}

function positionDifferences(
  position?: Position,
  form?: PositionForm,
): Difference[] {
  if (!position || !form) return [];
  return [
    ...changed("岗位名称", trimmed(position.name), trimmed(form.name)),
    ...changed(
      "岗位说明",
      trimmed(position.description),
      trimmed(form.description),
    ),
    ...changed("岗位容量", position.capacity, form.capacity),
    ...changed(
      "递补确认期限",
      position.promotionTimeoutMinutes,
      form.promotionTimeoutMinutes,
    ),
    ...changedTime(
      "服务开始时间",
      position.serviceStartAt,
      form.serviceStartAt,
    ),
    ...changedTime("服务结束时间", position.serviceEndAt, form.serviceEndAt),
    ...changed(
      "集合地点",
      trimmed(position.meetingLocation ?? ""),
      trimmed(form.meetingLocation),
    ),
  ];
}

function reviewActivity() {
  actionError.value = "";
  if (
    !detail.value ||
    !activityDifferences(detail.value.activity, activityForm.value).length
  ) {
    actionError.value = "没有可提交的实际变更。";
    return;
  }
  pending.value = { kind: "activity" };
  reason.value = "";
  confirmationError.value = "";
}

function reviewPosition(id: EntityId) {
  const position = detail.value?.positions.find((item) => item.id === id);
  const form = positionForms[id];
  actionError.value = "";
  if (!position || !form) return;
  if (form.capacity < position.capacity) {
    actionError.value = "岗位容量只能增加，不能减少。";
    return;
  }
  if (!positionDifferences(position, form).length) {
    actionError.value = "没有可提交的实际变更。";
    return;
  }
  pending.value = { kind: "position", id };
  reason.value = "";
  confirmationError.value = "";
}

function activityPayload(
  form: ActivityForm,
  why: string,
): ActivityChangeRequest {
  const { freeCancelDeadlineAt, ...rest } = form;
  const freeCancelChanged =
    Boolean(detail.value) &&
    localDate(detail.value!.activity.freeCancelDeadlineAt) !==
      freeCancelDeadlineAt;
  return {
    ...rest,
    title: trimmed(rest.title),
    description: trimmed(rest.description),
    location: trimmed(rest.location),
    registrationEndAt: timePayload(
      form.registrationEndAt,
      detail.value!.activity.registrationEndAt,
    )!,
    activityStartAt: timePayload(
      form.activityStartAt,
      detail.value!.activity.activityStartAt,
    )!,
    activityEndAt: timePayload(
      form.activityEndAt,
      detail.value!.activity.activityEndAt,
    )!,
    ...(freeCancelChanged
      ? { freeCancelDeadlineAt: freeCancelDeadlineAt || null }
      : {}),
    reason: why,
  };
}

function positionPayload(
  form: PositionForm,
  original: Position,
  why: string,
): PositionChangeRequest {
  return {
    ...form,
    name: trimmed(form.name),
    description: trimmed(form.description),
    serviceStartAt: timePayload(form.serviceStartAt, original.serviceStartAt),
    serviceEndAt: timePayload(form.serviceEndAt, original.serviceEndAt),
    meetingLocation: form.meetingLocation.trim() || null,
    reason: why,
  };
}

function errorMessage(error: unknown) {
  if (!isAxiosError(error)) return "操作失败，请检查网络后重试。";
  if (error.response?.status === 403) return "你没有执行此变更的权限。";
  if (error.response?.status === 409) return "活动状态已变化，请刷新后重试。";
  if (error.response?.status === 422) {
    return error.response.data?.code === "NO_EFFECTIVE_CHANGE"
      ? "后端确认没有有效变更，请刷新后检查。"
      : "变更内容不符合活动规则，请检查后重试。";
  }
  return "操作失败，请检查网络后重试。";
}

async function submitChange() {
  if (submitting.value || !pending.value || !detail.value) return;
  const why = reason.value.trim();
  if (!why) {
    confirmationError.value = "请填写变更原因。";
    return;
  }
  const request = generation;
  const change = pending.value;
  submitting.value = true;
  confirmationError.value = "";
  try {
    if (change.kind === "activity") {
      const form = activityForm.value;
      if (!form) return;
      const result = await changeActivity(props.id, activityPayload(form, why));
      if (!alive || request !== generation) return;
      detail.value = { ...detail.value, activity: result.activity };
      activityForm.value = activityToForm(result.activity);
      notification.value = `已通知 ${result.notifiedRecipients} 位受影响成员。`;
    } else {
      const form = positionForms[change.id];
      const original = detail.value.positions.find(
        (position) => position.id === change.id,
      );
      if (!form || !original) return;
      const result = await changePosition(
        change.id,
        positionPayload(form, original, why),
      );
      if (!alive || request !== generation) return;
      detail.value = {
        ...detail.value,
        positions: detail.value.positions.map((item) =>
          item.id === change.id ? { ...item, ...result.position } : item,
        ),
      };
      positionForms[change.id] = positionToForm(result.position);
      notification.value = `已通知 ${result.notifiedRecipients} 位受影响成员。`;
    }
    pending.value = null;
    reason.value = "";
  } catch (error) {
    if (alive && request === generation)
      confirmationError.value = errorMessage(error);
  } finally {
    if (alive && request === generation) submitting.value = false;
  }
}

function addQuestion(positionId: EntityId) {
  questionForms[positionId].push({
    type: "TEXT",
    title: "",
    required: false,
    optionsText: "",
    sortOrder: questionForms[positionId].length + 1,
  });
}

function questionOptions(form: QuestionForm) {
  return form.optionsText.split("\n").map(trimmed).filter(Boolean);
}

function validQuestion(form: QuestionForm) {
  if (!trimmed(form.title) || form.sortOrder < 1) return false;
  if (form.type === "SINGLE_CHOICE" || form.type === "MULTIPLE_CHOICE") {
    const options = questionOptions(form);
    return (
      options.length >= 2 &&
      options.length <= 20 &&
      new Set(options).size === options.length
    );
  }
  return true;
}

function questionPayload(form: QuestionForm): PositionQuestionRequest {
  return {
    type: form.type,
    title: trimmed(form.title),
    required: form.required,
    options:
      form.type === "SINGLE_CHOICE" || form.type === "MULTIPLE_CHOICE"
        ? questionOptions(form)
        : null,
    sortOrder: form.sortOrder,
  };
}

function questionSignature(form: QuestionForm) {
  return JSON.stringify(questionPayload(form));
}

function isQuestionSaved(form: QuestionForm) {
  return Boolean(form.id) && form.savedSignature === questionSignature(form);
}

function applyQuestion(
  positionId: EntityId,
  previous: QuestionForm,
  question: PositionManagementQuestion,
) {
  const forms = questionForms[positionId];
  const index = previous.id
    ? forms.findIndex((item) => item.id === previous.id)
    : forms.indexOf(previous);
  const next = questionToForm(question);
  if (index === -1) forms.push(next);
  else forms[index] = next;
}

async function saveQuestion(positionId: EntityId, form: QuestionForm) {
  if (submitting.value) return;
  if (!validQuestion(form)) {
    actionError.value =
      "请填写有效的问题内容；选择题需提供 2 至 20 个不重复选项。";
    return;
  }
  const request = generation;
  submitting.value = true;
  actionError.value = "";
  try {
    const question = form.id
      ? await updatePositionQuestion(positionId, form.id, questionPayload(form))
      : await createPositionQuestion(positionId, questionPayload(form));
    if (!alive || request !== generation) return;
    applyQuestion(positionId, form, question);
  } catch (error) {
    if (alive && request === generation)
      actionError.value = errorMessage(error);
  } finally {
    if (alive && request === generation) submitting.value = false;
  }
}

async function removeQuestion(positionId: EntityId, form: QuestionForm) {
  if (submitting.value) return;
  const forms = questionForms[positionId];
  if (!form.id) {
    forms.splice(forms.indexOf(form), 1);
    return;
  }
  const request = generation;
  submitting.value = true;
  actionError.value = "";
  try {
    await deletePositionQuestion(positionId, form.id);
    if (!alive || request !== generation) return;
    forms.splice(
      forms.findIndex((item) => item.id === form.id),
      1,
    );
  } catch (error) {
    if (alive && request === generation)
      actionError.value = errorMessage(error);
  } finally {
    if (alive && request === generation) submitting.value = false;
  }
}

function canPublish(positionId: EntityId) {
  const forms = questionForms[positionId] ?? [];
  return (
    commonQuestionCount() + forms.length <= 10 &&
    forms.every((form) => validQuestion(form) && isQuestionSaved(form))
  );
}

function commonQuestionCount() {
  return detail.value?.commonQuestionCount ?? 0;
}

function canAddQuestion(positionId: EntityId) {
  return commonQuestionCount() + (questionForms[positionId]?.length ?? 0) < 10;
}

function resetNewPosition() {
  Object.assign(newPosition, {
    name: "",
    description: "",
    capacity: 1,
    registrationMode: "FIRST_COME",
    promotionTimeoutMinutes: 120,
    serviceStartAt: "",
    serviceEndAt: "",
    meetingLocation: "",
  });
}

function draftPayload(): PositionDraftRequest {
  return {
    ...newPosition,
    name: newPosition.name.trim(),
    description: newPosition.description.trim(),
    serviceStartAt: newPosition.serviceStartAt || null,
    serviceEndAt: newPosition.serviceEndAt || null,
    meetingLocation: newPosition.meetingLocation.trim() || null,
  };
}

async function saveDraft() {
  if (submitting.value) return;
  if (!newPosition.name.trim() || !newPosition.description.trim()) {
    actionError.value = "请填写岗位名称和岗位说明。";
    return;
  }
  const request = generation;
  submitting.value = true;
  actionError.value = "";
  try {
    const position = await createPosition(props.id, draftPayload());
    if (!alive || request !== generation || !detail.value) return;
    detail.value = {
      ...detail.value,
      positions: [...detail.value.positions, { ...position, questions: [] }],
    };
    positionForms[position.id] = positionToForm(position);
    questionForms[position.id] = [];
    resetNewPosition();
    notification.value = "岗位草稿已保存，请配置问题后单独发布。";
  } catch (error) {
    if (alive && request === generation)
      actionError.value = errorMessage(error);
  } finally {
    if (alive && request === generation) submitting.value = false;
  }
}

async function publishDraft(id: EntityId) {
  if (submitting.value) return;
  if (!canPublish(id)) {
    actionError.value = "请先修正岗位问题，再发布岗位。";
    return;
  }
  const request = generation;
  submitting.value = true;
  actionError.value = "";
  try {
    const result = await publishPosition(id);
    if (!alive || request !== generation || !detail.value) return;
    detail.value = {
      ...detail.value,
      positions: detail.value.positions.map((item) =>
        item.id === id ? { ...item, ...result.position } : item,
      ),
    };
    positionForms[id] = positionToForm(result.position);
    notification.value = `岗位已发布，已通知 ${result.notifiedRecipients} 位受影响成员。`;
  } catch (error) {
    if (alive && request === generation)
      actionError.value = errorMessage(error);
  } finally {
    if (alive && request === generation) submitting.value = false;
  }
}

watch(() => props.id, load, { immediate: true });
onBeforeUnmount(() => {
  alive = false;
  ++generation;
});
</script>

<template>
  <section class="page detail-page management-page">
    <p v-if="loading" class="state">正在加载活动管理信息…</p>
    <div
      v-else-if="loadError || !detail || !activityForm"
      class="state"
      role="alert"
    >
      <strong>{{ loadError || "活动不存在或当前无法管理。" }}</strong>
      <button class="secondary" @click="load">重新加载</button>
    </div>
    <template v-else>
      <header class="detail-header">
        <h1>正式变更管理</h1>
        <p>已发布活动只能提交正式变更；确认前请核对字段差异和变更原因。</p>
      </header>
      <p v-if="notification" class="success" role="status">
        {{ notification }}
      </p>
      <p v-if="actionError" class="error" role="alert">{{ actionError }}</p>
      <div class="management-buttons">
        <button
          v-if="canCancel"
          class="secondary"
          data-test="cancel-activity"
          :disabled="submitting"
          @click="
            lifecycle = 'cancel';
            lifecycleReason = '';
          "
        >
          取消活动
        </button>
        <button
          v-if="canChange"
          class="secondary"
          data-test="complete-activity"
          :disabled="submitting"
          @click="
            lifecycle = 'complete';
            lifecycleReason = '';
          "
        >
          提前结束活动
        </button>
        <RouterLink
          v-if="
            detail.activity.status === 'DRAFT' &&
            permissions.includes('activity:create')
          "
          :to="`/activities/${id}/edit`"
          >编辑草稿</RouterLink
        >
      </div>
      <section
        v-if="lifecycle"
        class="confirmation-panel"
        role="dialog"
        aria-labelledby="lifecycle-heading"
      >
        <h2 id="lifecycle-heading">
          {{ lifecycle === "cancel" ? "确认取消活动" : "确认提前结束活动" }}
        </h2>
        <p>
          {{
            lifecycle === "cancel"
              ? "活动将停止报名并取消相关候补邀请；此操作不可直接撤销。"
              : "将立即关闭签到和候补流程并终结活动；此操作不可直接撤销。"
          }}
        </p>
        <label
          >操作原因<textarea
            v-model="lifecycleReason"
            data-test="lifecycle-reason"
            maxlength="500"
            required
          />
        </label>
        <button
          class="primary compact"
          data-test="submit-lifecycle"
          :disabled="submitting || !lifecycleReason.trim()"
          @click="submitLifecycle"
        >
          确认执行
        </button>
        <button
          class="secondary"
          :disabled="submitting"
          @click="lifecycle = null"
        >
          返回
        </button>
      </section>
      <p v-if="!canChange" class="muted">当前状态或权限不允许正式变更。</p>
      <fieldset class="lifecycle-fields" :disabled="!canChange || submitting">
        <form class="change-form" @submit.prevent="reviewActivity">
          <h2>活动信息</h2>
          <label
            >活动标题<input
              v-model="activityForm.title"
              data-test="activity-title"
              maxlength="160"
              required
          /></label>
          <label
            >活动介绍<textarea v-model="activityForm.description" required />
          </label>
          <label
            >活动地点<input
              v-model="activityForm.location"
              maxlength="255"
              required
          /></label>
          <label
            >报名截止时间<input
              v-model="activityForm.registrationEndAt"
              type="datetime-local"
              required
          /></label>
          <label
            >免费取消截止时间<input
              v-model="activityForm.freeCancelDeadlineAt"
              data-test="free-cancel-deadline"
              type="datetime-local"
          /></label>
          <label
            >活动开始时间<input
              v-model="activityForm.activityStartAt"
              type="datetime-local"
              required
          /></label>
          <label
            >活动结束时间<input
              v-model="activityForm.activityEndAt"
              type="datetime-local"
              required
          /></label>
          <button
            class="secondary"
            data-test="review-activity-change"
            type="button"
            :disabled="submitting"
            @click="reviewActivity"
          >
            查看活动变更差异
          </button>
        </form>

        <section
          class="managed-positions"
          aria-labelledby="managed-positions-heading"
        >
          <h2 id="managed-positions-heading">已有岗位</h2>
          <article
            v-for="position in detail.positions"
            :key="position.id"
            class="managed-position"
          >
            <template v-if="position.status === 'DRAFT'">
              <h3>
                {{ position.name }} <span class="status-badge">草稿</span>
              </h3>
              <p class="muted">
                配置完成后单独发布；草稿不会出现在成员报名列表。
              </p>
              <div
                :data-test="`draft-questions-${position.id}`"
                class="draft-questions"
              >
                <h4>岗位问题</h4>
                <p v-if="!questionForms[position.id]?.length" class="muted">
                  尚未添加岗位问题。
                </p>
                <section
                  v-for="(question, index) in questionForms[position.id]"
                  :key="question.id || `new-${index}`"
                  class="draft-question"
                >
                  <label
                    >问题类型<select v-model="question.type">
                      <option value="TEXT">文本</option>
                      <option value="SINGLE_CHOICE">单选</option>
                      <option value="MULTIPLE_CHOICE">多选</option>
                      <option value="BOOLEAN">是/否</option>
                    </select></label
                  >
                  <label
                    >问题标题<input
                      v-model="question.title"
                      :data-test="`question-title-${position.id}-${index}`"
                      maxlength="255"
                  /></label>
                  <label
                    v-if="
                      question.type === 'SINGLE_CHOICE' ||
                      question.type === 'MULTIPLE_CHOICE'
                    "
                    >选项（每行一项）<textarea v-model="question.optionsText" />
                  </label>
                  <label
                    >排序<input
                      v-model.number="question.sortOrder"
                      type="number"
                      min="1"
                  /></label>
                  <label class="inline-check"
                    ><input v-model="question.required" type="checkbox" />
                    必填</label
                  >
                  <div class="management-buttons">
                    <button
                      class="secondary compact"
                      type="button"
                      :disabled="submitting"
                      @click="saveQuestion(position.id, question)"
                    >
                      保存问题</button
                    ><button
                      class="secondary compact"
                      type="button"
                      :disabled="submitting"
                      @click="removeQuestion(position.id, question)"
                    >
                      删除问题
                    </button>
                  </div>
                </section>
                <button
                  class="secondary compact"
                  :data-test="`add-question-${position.id}`"
                  type="button"
                  :disabled="submitting || !canAddQuestion(position.id)"
                  @click="addQuestion(position.id)"
                >
                  新增岗位问题
                </button>
              </div>
              <p v-if="!canPublish(position.id)" class="muted">
                请先保存所有问题；公共问题与岗位问题合计最多 10 个。
              </p>
              <button
                class="primary compact"
                :data-test="`publish-position-${position.id}`"
                :disabled="submitting || !canPublish(position.id)"
                @click="publishDraft(position.id)"
              >
                发布岗位
              </button>
            </template>
            <template v-else-if="positionForms[position.id]">
              <h3>{{ position.name }}</h3>
              <label
                >岗位名称<input
                  v-model="positionForms[position.id].name"
                  maxlength="128"
                  required
              /></label>
              <label
                >岗位说明<textarea
                  v-model="positionForms[position.id].description"
                  required
                />
              </label>
              <label
                >岗位容量<input
                  v-model.number="positionForms[position.id].capacity"
                  :data-test="`position-capacity-${position.id}`"
                  type="number"
                  min="1"
                  required
              /></label>
              <p class="muted">
                报名方式：{{
                  position.registrationMode === "FIRST_COME"
                    ? "先到先得"
                    : "负责人审核"
                }}（发布后不可修改）
              </p>
              <label
                >递补确认期限（分钟）<input
                  v-model.number="
                    positionForms[position.id].promotionTimeoutMinutes
                  "
                  type="number"
                  min="30"
                  max="720"
                  required
              /></label>
              <label
                >服务开始时间<input
                  v-model="positionForms[position.id].serviceStartAt"
                  type="datetime-local"
              /></label>
              <label
                >服务结束时间<input
                  v-model="positionForms[position.id].serviceEndAt"
                  type="datetime-local"
              /></label>
              <label
                >集合地点<input
                  v-model="positionForms[position.id].meetingLocation"
                  maxlength="255"
              /></label>
              <button
                class="secondary"
                :data-test="`review-position-${position.id}`"
                :disabled="submitting"
                @click="reviewPosition(position.id)"
              >
                查看岗位变更差异
              </button>
            </template>
          </article>
        </section>

        <form
          class="change-form draft-position-form"
          @submit.prevent="saveDraft"
        >
          <h2>新增岗位草稿</h2>
          <label
            >岗位名称<input
              v-model="newPosition.name"
              data-test="new-position-name"
              maxlength="128"
              required
          /></label>
          <label
            >岗位说明<textarea
              v-model="newPosition.description"
              data-test="new-position-description"
              required
            />
          </label>
          <label
            >岗位容量<input
              v-model.number="newPosition.capacity"
              type="number"
              min="1"
              required
          /></label>
          <label
            >报名方式<select v-model="newPosition.registrationMode">
              <option value="FIRST_COME">先到先得</option>
              <option value="REVIEW">负责人审核</option>
            </select></label
          >
          <label
            >递补确认期限（分钟）<input
              v-model.number="newPosition.promotionTimeoutMinutes"
              type="number"
              min="30"
              max="720"
              required
          /></label>
          <label
            >服务开始时间<input
              v-model="newPosition.serviceStartAt"
              type="datetime-local"
          /></label>
          <label
            >服务结束时间<input
              v-model="newPosition.serviceEndAt"
              type="datetime-local"
          /></label>
          <label
            >集合地点<input
              v-model="newPosition.meetingLocation"
              maxlength="255"
          /></label>
          <button
            class="secondary"
            data-test="save-draft-position"
            type="button"
            :disabled="submitting"
            @click="saveDraft"
          >
            保存岗位草稿
          </button>
        </form>

        <div
          v-if="pending"
          class="confirmation-panel"
          role="dialog"
          aria-labelledby="change-confirmation-heading"
        >
          <h2 id="change-confirmation-heading">确认正式变更</h2>
          <dl data-test="change-summary" class="change-summary">
            <template
              v-for="difference in pendingDifferences"
              :key="difference.label"
            >
              <dt>{{ difference.label }}</dt>
              <dd>
                <s>{{ difference.before || "未设置" }}</s> →
                {{ difference.after || "未设置" }}
              </dd>
            </template>
          </dl>
          <p v-if="pending.kind === 'position'" class="muted">
            容量增加可能会触发按顺序的候补邀请。
          </p>
          <label
            >变更原因<textarea
              v-model="reason"
              data-test="change-reason"
              maxlength="500"
              required
            />
          </label>
          <p v-if="confirmationError" class="error" role="alert">
            {{ confirmationError }}
          </p>
          <div class="management-buttons">
            <button
              class="primary compact"
              data-test="submit-activity-change"
              :disabled="submitting"
              @click="submitChange"
            >
              确认提交</button
            ><button
              class="secondary"
              :disabled="submitting"
              @click="pending = null"
            >
              取消
            </button>
          </div>
        </div>
      </fieldset>
    </template>
  </section>
</template>
<style scoped>
.lifecycle-fields {
  border: 0;
  padding: 0;
  min-width: 0;
}
.lifecycle-fields:disabled {
  opacity: 0.6;
}
.management-page h1 {
  font-size: 28px;
}
</style>
