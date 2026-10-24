<script setup lang="ts">
import { computed, onBeforeUnmount, reactive, ref, watch } from "vue";
import { isAxiosError } from "axios";
import {
  createActivity,
  updateActivity,
  publishActivity,
  getActivityDraft,
  updateDraftPosition,
  deleteDraftPosition,
  listActivityQuestions,
  listPositionQuestions,
  createPosition,
  createActivityQuestion,
  updateActivityQuestion,
  deleteActivityQuestion,
  createPositionQuestion,
  updatePositionQuestion,
  deletePositionQuestion,
  type ActivityDraftRequest,
  type PositionDraftRequest,
  type PositionQuestionRequest,
} from "../api/activities";
import { getOrganizationAccess } from "../api/organizations";
import type {
  Activity,
  EntityId,
  Position,
  PositionManagementQuestion,
  QuestionType,
} from "../api/types";
const props = defineProps<{ organizationId?: EntityId; id?: EntityId }>();
const activity = ref<Activity | null>(null);
const savedId = ref<EntityId | undefined>(props.id);
const organizationId = ref(props.organizationId || "");
const permissions = ref<string[]>([]);
const loading = ref(false);
const busy = ref(false);
const error = ref("");
const notice = ref("");
const positions = ref<Position[]>([]);
const editingPositionId = ref<EntityId>();
const questions = reactive<Record<string, PositionManagementQuestion[]>>({
  activity: [],
});
const form = reactive<ActivityDraftRequest>({
  title: "",
  description: "",
  location: "",
  registrationStartAt: "",
  registrationEndAt: "",
  freeCancelDeadlineAt: "",
  activityStartAt: "",
  activityEndAt: "",
});
const position = reactive<PositionDraftRequest>({
  name: "",
  description: "",
  capacity: 1,
  registrationMode: "FIRST_COME",
  promotionTimeoutMinutes: 120,
  serviceStartAt: "",
  serviceEndAt: "",
  meetingLocation: "",
});
const question = reactive<{
  id?: string;
  scope: string;
  title: string;
  type: QuestionType;
  required: boolean;
  optionsText: string;
  sortOrder: number;
}>({
  scope: "activity",
  title: "",
  type: "TEXT",
  required: false,
  optionsText: "",
  sortOrder: 1,
});
const draft = computed(
  () => !activity.value || activity.value.status === "DRAFT",
);
const canEdit = computed(
  () => permissions.value.includes("activity:create") && draft.value,
);
const canPublish = computed(
  () =>
    permissions.value.includes("activity:publish") &&
    activity.value?.status === "DRAFT",
);
let generation = 0;
const fields = [
  { key: "registrationStartAt", label: "报名开始时间" },
  { key: "registrationEndAt", label: "报名截止时间" },
  { key: "freeCancelDeadlineAt", label: "免费取消截止时间" },
  { key: "activityStartAt", label: "活动开始时间" },
  { key: "activityEndAt", label: "活动结束时间" },
] as const;
const activityDirty = computed(
  () =>
    !!activity.value &&
    (Object.keys(form) as (keyof ActivityDraftRequest)[]).some((key) => {
      const original = activity.value?.[key] || "";
      return (
        (form[key] || "") !==
        (fields.some((field) => field.key === key)
          ? original.slice(0, 16)
          : original)
      );
    }),
);
const readyToPublish = computed(
  () =>
    !!positions.value.length &&
    !activityDirty.value &&
    !editingPositionId.value &&
    !question.title.trim() &&
    !position.name.trim() &&
    !position.description.trim() &&
    position.capacity === 1 &&
    position.registrationMode === "FIRST_COME" &&
    position.promotionTimeoutMinutes === 120 &&
    !position.serviceStartAt &&
    !position.serviceEndAt &&
    !position.meetingLocation &&
    !question.id &&
    question.type === "TEXT" &&
    !question.required &&
    !question.optionsText.trim() &&
    question.sortOrder === 1,
);
function displayError(value: unknown) {
  return isAxiosError(value)
    ? value.response?.data?.error?.message ||
        value.response?.data?.message ||
        "操作失败，请检查权限、时间顺序和活动状态后重试。"
    : "操作失败，请稍后重试。";
}
function setActivity(value: Activity) {
  activity.value = value;
  savedId.value = value.id;
  organizationId.value = value.organizationId;
  for (const key of Object.keys(form) as (keyof ActivityDraftRequest)[]) {
    const original = value[key] ?? "";
    form[key] = fields.some((f) => f.key === key)
      ? original.slice(0, 16)
      : original;
  }
}
async function load() {
  const request = ++generation;
  loading.value = true;
  busy.value = false;
  notice.value = "";
  error.value = "";
  permissions.value = [];
  activity.value = null;
  positions.value = [];
  resetPosition();
  savedId.value = props.id;
  organizationId.value = props.organizationId || "";
  editQuestion("activity");
  for (const key of Object.keys(form) as (keyof ActivityDraftRequest)[])
    form[key] = "";
  for (const key of Object.keys(questions)) delete questions[key];
  questions.activity = [];
  try {
    if (props.id) {
      const detail = await getActivityDraft(props.id);
      if (request !== generation) return;
      setActivity(detail.activity);
      positions.value = detail.positions;
    }
    const access = await getOrganizationAccess(organizationId.value);
    if (request !== generation) return;
    permissions.value = access.permissionCodes;
    if (savedId.value && permissions.value.includes("activity:create")) {
      const [common, ...positionQuestions] = await Promise.all([
        listActivityQuestions(savedId.value),
        ...positions.value.map((p) => listPositionQuestions(p.id)),
      ]);
      if (request !== generation) return;
      questions.activity = common;
      positions.value.forEach((p, index) => {
        questions[p.id] = positionQuestions[index];
      });
    }
  } catch (value) {
    if (request === generation) {
      permissions.value = [];
      error.value =
        isAxiosError(value) && value.response?.status === 403
          ? "没有草稿管理权限，需要本组织的 activity:create 权限。草稿 ID 仍保留，请联系组织负责人。"
          : displayError(value);
    }
  } finally {
    if (request === generation) loading.value = false;
  }
}
watch(() => [props.id, props.organizationId], load, { immediate: true });
onBeforeUnmount(() => {
  generation++;
});
async function run(action: () => Promise<void>) {
  if (busy.value) return;
  const request = generation;
  busy.value = true;
  error.value = "";
  notice.value = "";
  try {
    await action();
  } catch (value) {
    if (request === generation) error.value = displayError(value);
  } finally {
    if (request === generation) busy.value = false;
  }
}
function validateForm() {
  return (
    [form.title, form.description, form.location].every((v) => v.trim()) &&
    form.registrationStartAt < form.registrationEndAt &&
    form.registrationEndAt < form.activityStartAt &&
    form.activityStartAt < form.activityEndAt &&
    (!form.freeCancelDeadlineAt ||
      form.freeCancelDeadlineAt <= form.activityStartAt)
  );
}
async function save() {
  if (!canEdit.value) return;
  if (!validateForm()) {
    error.value =
      "时间顺序须为报名开始 < 报名截止 < 活动开始 < 活动结束；免费取消截止不得晚于活动开始。";
    return;
  }
  const request = generation;
  await run(async () => {
    const body = {
      ...form,
      freeCancelDeadlineAt: form.freeCancelDeadlineAt || null,
    };
    for (const field of fields) {
      const original = activity.value?.[field.key];
      if (original && form[field.key] === original.slice(0, 16))
        body[field.key] = original;
    }
    const result = savedId.value
      ? await updateActivity(savedId.value, body)
      : await createActivity(organizationId.value, body);
    if (request !== generation) return;
    setActivity(result);
    notice.value = "草稿已保存；可使用下方链接再次进入。";
  });
}
async function addPosition() {
  if (!savedId.value || !canEdit.value) return;
  if (
    !position.name.trim() ||
    !position.description.trim() ||
    !Number.isInteger(position.capacity) ||
    position.capacity < 1 ||
    position.promotionTimeoutMinutes < 30 ||
    position.promotionTimeoutMinutes > 720
  ) {
    error.value = "请填写岗位名称、说明、有效容量和 30–720 分钟递补期限。";
    return;
  }
  const request = generation;
  const id = savedId.value;
  await run(async () => {
    const body = {
      ...position,
      serviceStartAt: position.serviceStartAt || null,
      serviceEndAt: position.serviceEndAt || null,
      meetingLocation: position.meetingLocation || null,
    };
    const editingId = editingPositionId.value;
    const original = positions.value.find((p) => p.id === editingId);
    for (const key of ["serviceStartAt", "serviceEndAt"] as const) {
      if (original?.[key] && position[key] === original[key]?.slice(0, 16))
        body[key] = original[key];
    }
    const result = editingId
      ? await updateDraftPosition(editingId, body)
      : await createPosition(id, body);
    if (request !== generation) return;
    const index = positions.value.findIndex((p) => p.id === result.id);
    if (index < 0) positions.value.push(result);
    else positions.value[index] = result;
    questions[result.id] ||= [];
    resetPosition();
    notice.value = "岗位已保存。";
  });
}
function resetPosition() {
  editingPositionId.value = undefined;
  Object.assign(position, {
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
function editPosition(value: Position) {
  if (busy.value) return;
  editingPositionId.value = value.id;
  Object.assign(position, {
    name: value.name,
    description: value.description,
    capacity: value.capacity,
    registrationMode: value.registrationMode,
    promotionTimeoutMinutes: value.promotionTimeoutMinutes,
    serviceStartAt: value.serviceStartAt?.slice(0, 16) || "",
    serviceEndAt: value.serviceEndAt?.slice(0, 16) || "",
    meetingLocation: value.meetingLocation || "",
  });
}
async function removePosition(id: EntityId) {
  if (
    !canEdit.value ||
    busy.value ||
    !window.confirm("确定删除此岗位及其报名问题配置？")
  )
    return;
  const request = generation;
  await run(async () => {
    await deleteDraftPosition(id);
    if (request !== generation) return;
    positions.value = positions.value.filter((p) => p.id !== id);
    delete questions[id];
    if (question.scope === id) editQuestion("activity");
    if (editingPositionId.value === id) resetPosition();
    notice.value = "岗位已删除。";
  });
}
function editQuestion(scope: string, value?: PositionManagementQuestion) {
  question.scope = scope;
  question.id = value?.id;
  question.title = value?.title || "";
  question.type = value?.questionType || "TEXT";
  question.required = value?.requiredQuestion || false;
  question.sortOrder = value?.sortOrder || 1;
  try {
    question.optionsText = JSON.parse(value?.optionsJson || "[]").join("\n");
  } catch {
    question.optionsText = "";
  }
}
async function saveQuestion() {
  if (!savedId.value || !canEdit.value) return;
  const choice =
    question.type === "SINGLE_CHOICE" || question.type === "MULTIPLE_CHOICE";
  const options = question.optionsText
    .split("\n")
    .map((v) => v.trim())
    .filter(Boolean);
  if (
    !question.title.trim() ||
    !Number.isInteger(question.sortOrder) ||
    question.sortOrder < 1 ||
    (choice &&
      (options.length < 2 ||
        options.length > 20 ||
        new Set(options).size !== options.length))
  ) {
    error.value = "问题标题和正整数排序必填；选择题需要 2–20 个不重复选项。";
    return;
  }
  if (!question.id) {
    const common = questions.activity?.length || 0;
    const exceeds =
      question.scope === "activity"
        ? common >= 10 ||
          positions.value.some(
            (p) => common + (questions[p.id]?.length || 0) >= 10,
          )
        : common + (questions[question.scope]?.length || 0) >= 10;
    if (exceeds) {
      error.value = "公共问题与每个岗位的问题合计最多 10 个。";
      return;
    }
  }
  const body: PositionQuestionRequest = {
    title: question.title.trim(),
    type: question.type,
    required: question.required,
    sortOrder: question.sortOrder,
    options: choice ? options : null,
  };
  const scope = question.scope,
    id = question.id,
    activityId = savedId.value,
    request = generation;
  await run(async () => {
    const result =
      scope === "activity"
        ? id
          ? await updateActivityQuestion(activityId, id, body)
          : await createActivityQuestion(activityId, body)
        : id
          ? await updatePositionQuestion(scope, id, body)
          : await createPositionQuestion(scope, body);
    if (request !== generation) return;
    questions[scope] ||= [];
    const index = questions[scope].findIndex((v) => v.id === result.id);
    if (index < 0) questions[scope].push(result);
    else questions[scope][index] = result;
    editQuestion(scope);
    notice.value = "问题已保存。";
  });
}
async function removeQuestion(scope: string, id: string) {
  if (!savedId.value || !canEdit.value || !window.confirm("确定删除此问题？"))
    return;
  const activityId = savedId.value,
    request = generation;
  await run(async () => {
    if (scope === "activity") await deleteActivityQuestion(activityId, id);
    else await deletePositionQuestion(scope, id);
    if (request !== generation) return;
    questions[scope] = questions[scope].filter((v) => v.id !== id);
    if (question.id === id) editQuestion(scope);
  });
}
async function publish() {
  if (
    !savedId.value ||
    !canPublish.value ||
    !readyToPublish.value ||
    !window.confirm(
      "确认发布活动？发布后需通过正式变更流程修改，公共问题将锁定。请先保存活动、岗位和问题。",
    )
  )
    return;
  const request = generation,
    id = savedId.value;
  await run(async () => {
    const result = await publishActivity(id);
    if (request !== generation) return;
    setActivity(result);
    notice.value = "活动已发布。";
  });
}
</script>
<template>
  <section class="page editor-page">
    <h1>{{ savedId ? "活动草稿编辑" : "创建志愿活动" }}</h1>
    <p class="muted editor-intro">
      先保存活动信息，再配置岗位和报名问题。确认无误后即可发布。
    </p>
    <p v-if="loading" class="state">正在加载…</p>
    <p v-if="error" class="error" role="alert">{{ error }}</p>
    <p v-if="notice" class="success" role="status">{{ notice }}</p>
    <template v-if="!loading">
      <p v-if="!canEdit" class="state">
        当前无草稿编辑权限，或活动已经发布/结束。
      </p>
      <p v-if="savedId">
        活动 ID：{{ savedId }} ·
        <RouterLink :to="`/activities/${savedId}/edit`">草稿固定链接</RouterLink
        ><template v-if="activity"> · 状态 {{ activity.status }}</template>
      </p>
      <nav class="workspace-links" aria-label="活动相关页面">
        <RouterLink
          v-if="organizationId"
          :to="`/organizations/${organizationId}/drafts`"
          >全部草稿</RouterLink
        >
        <RouterLink
          v-if="organizationId"
          :to="`/organizations/${organizationId}/activities`"
          >活动列表</RouterLink
        >
        <RouterLink
          v-if="organizationId"
          :to="`/organizations/${organizationId}/manage`"
          >组织管理</RouterLink
        >
        <RouterLink
          v-if="activity?.status === 'PUBLISHED'"
          :to="`/activities/${savedId}`"
          >查看已发布活动</RouterLink
        >
      </nav>
      <form class="editor-card" @submit.prevent="save">
        <fieldset class="activity-fields" :disabled="!canEdit || busy">
          <legend>活动信息</legend>
          <label
            >活动标题<input
              v-model="form.title"
              data-test="draft-title"
              required
              maxlength="160"
          /></label>
          <label>活动介绍<textarea v-model="form.description" required /></label
          ><label
            >活动地点<input v-model="form.location" required maxlength="255"
          /></label>
          <label v-for="field in fields" :key="field.key"
            >{{ field.label
            }}<input
              v-model="form[field.key]"
              type="datetime-local"
              :required="field.key !== 'freeCancelDeadlineAt'"
          /></label>
          <button class="primary" data-test="save-activity-draft">
            保存活动草稿
          </button>
        </fieldset>
      </form>
      <template v-if="savedId && canEdit">
        <section class="editor-card">
          <h2>岗位与报名问题</h2>
          <p>
            活动至少需要一个岗位；公共问题与每个岗位问题合计最多 10
            个。发布前可编辑或删除已保存岗位。
          </p>
          <button class="secondary" @click="editQuestion('activity')">
            编辑公共问题
          </button>
          <article v-for="p in positions" :key="p.id">
            <h3>{{ p.name }} · {{ p.capacity }} 人</h3>
            <p>{{ p.description }}</p>
            <button
              class="secondary"
              :disabled="busy"
              :data-test="`edit-position-${p.id}`"
              @click="editPosition(p)"
            >
              编辑岗位
            </button>
            <button
              class="text-action"
              :disabled="busy"
              :data-test="`delete-position-${p.id}`"
              @click="removePosition(p.id)"
            >
              删除岗位
            </button>
            <button
              class="secondary"
              :disabled="busy"
              :data-test="`position-questions-${p.id}`"
              @click="editQuestion(p.id)"
            >
              编辑此岗位问题
            </button>
          </article>
        </section>
        <form class="editor-card" @submit.prevent="addPosition">
          <fieldset :disabled="busy">
            <legend>{{ editingPositionId ? "编辑岗位" : "新增岗位" }}</legend>
            <label
              >岗位名称<input
                v-model="position.name"
                data-test="position-name"
                required
                maxlength="128" /></label
            ><label
              >岗位说明<textarea
                v-model="position.description"
                required
                maxlength="1000"
              />
            </label>
            <label
              >容量<input
                v-model.number="position.capacity"
                type="number"
                min="1"
                required /></label
            ><label
              >报名方式<select v-model="position.registrationMode">
                <option value="FIRST_COME">先到先得</option>
                <option value="REVIEW">负责人审核</option>
              </select></label
            >
            <label
              >递补确认期限（分钟）<input
                v-model.number="position.promotionTimeoutMinutes"
                type="number"
                min="30"
                max="720"
                required
            /></label>
            <label
              >岗位服务开始<input
                v-model="position.serviceStartAt"
                type="datetime-local" /></label
            ><label
              >岗位服务结束<input
                v-model="position.serviceEndAt"
                type="datetime-local" /></label
            ><label
              >集合地点<input
                v-model="position.meetingLocation"
                maxlength="255"
            /></label>
            <button class="secondary" data-test="save-position">
              {{ editingPositionId ? "保存岗位修改" : "保存新岗位" }}
            </button>
            <button
              v-if="editingPositionId"
              class="text-action"
              type="button"
              @click="resetPosition"
            >
              取消编辑岗位
            </button>
          </fieldset>
        </form>
        <section class="editor-card">
          <h2>{{ question.scope === "activity" ? "公共" : "岗位" }}问题管理</h2>
          <p class="muted">
            问题直接保存在服务端，重新打开草稿会加载完整问题。活动发布后公共问题不可修改。
          </p>
          <ul>
            <li v-for="q in questions[question.scope] || []" :key="q.id">
              {{ q.title }}
              <button
                class="text-action"
                :disabled="busy"
                @click="editQuestion(question.scope, q)"
              >
                编辑
              </button>
              <button
                class="text-action"
                :disabled="busy"
                @click="removeQuestion(question.scope, q.id)"
              >
                删除
              </button>
            </li>
          </ul>
          <form @submit.prevent="saveQuestion">
            <fieldset :disabled="busy">
              <legend>{{ question.id ? "编辑问题" : "新增问题" }}</legend>
              <label
                >标题<input
                  v-model="question.title"
                  required
                  maxlength="255" /></label
              ><label
                >类型<select v-model="question.type">
                  <option value="TEXT">文本</option>
                  <option value="BOOLEAN">是/否</option>
                  <option value="SINGLE_CHOICE">单选</option>
                  <option value="MULTIPLE_CHOICE">多选</option>
                </select></label
              >
              <label v-if="question.type.includes('CHOICE')"
                >选项（每行一个）<textarea
                  v-model="question.optionsText"
                /></label
              ><label
                >排序<input
                  v-model.number="question.sortOrder"
                  type="number"
                  min="1"
                  required /></label
              ><label class="check"
                ><input
                  v-model="question.required"
                  type="checkbox"
                />必填</label
              ><button class="secondary" data-test="save-question">
                保存问题
              </button>
            </fieldset>
          </form>
        </section>
      </template>
      <p v-if="canPublish && !readyToPublish" class="muted">
        至少保存一个岗位，并先保存活动、岗位和问题的未提交编辑，才能发布。
      </p>
      <button
        v-if="canPublish"
        class="primary"
        :disabled="busy || !readyToPublish"
        data-test="publish-activity"
        @click="publish"
      >
        确认发布活动
      </button>
    </template>
  </section>
</template>
<style scoped>
.editor-page {
  max-width: 860px;
}
.editor-page h1 {
  font-size: 28px;
  color: #142b40;
}
.editor-card {
  background: #fff;
  border: 1px solid #e0e7e5;
  border-radius: 12px;
  padding: 24px;
  margin: 20px 0;
}
.editor-card fieldset {
  border: 0;
  padding: 0;
  margin: 0;
  display: grid;
  gap: 16px;
  min-width: 0;
}
.editor-card legend {
  font-size: 20px;
  font-weight: 650;
  margin-bottom: 18px;
}
.editor-card label {
  display: grid;
  gap: 6px;
}
.editor-card input,
.editor-card select,
.editor-card textarea {
  box-sizing: border-box;
  width: 100%;
  padding: 10px;
  border: 1px solid #cdd9d5;
  border-radius: 8px;
  font: inherit;
}
.editor-card textarea {
  min-height: 86px;
  resize: vertical;
}
.editor-card button,
.editor-page > button {
  border-radius: 8px;
}
.editor-card .check {
  display: flex;
  align-items: center;
}
.check input {
  width: auto;
}
.editor-card article {
  padding: 16px 0;
  border-top: 1px solid #e0e7e5;
  margin-top: 16px;
}
.muted {
  color: #66786e;
  font-size: 14px;
  line-height: 1.7;
}
@media (max-width: 600px) {
  .editor-card {
    padding: 16px;
  }
  .editor-page {
    padding: 20px 16px;
  }
  .editor-page button {
    min-height: 40px;
  }
}
</style>
