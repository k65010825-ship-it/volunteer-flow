<script setup lang="ts">
import { computed, onBeforeUnmount, ref, watch } from "vue";
import { useRouter } from "vue-router";
import { getActivity } from "../api/activities";
import {
  getRegistrationForm,
  registrationErrorMessage,
  submitRegistration,
} from "../api/registrations";
import type {
  ActivityDetail,
  EntityId,
  RegistrationForm,
  RegistrationQuestion,
} from "../api/types";
import RegistrationQuestionField from "../components/RegistrationQuestionField.vue";
const props = defineProps<{ id: EntityId }>();
const router = useRouter();
const detail = ref<ActivityDetail | null>(null);
const loading = ref(true);
const failed = ref(false);
const selected = ref<EntityId | null>(null);
const form = ref<RegistrationForm | null>(null);
const formLoading = ref(false);
const formError = ref("");
const answers = ref<Record<string, unknown>>({});
const errors = ref<Record<string, string>>({});
const submitting = ref(false);
const submitError = ref("");
const activePositions = computed(
  () =>
    detail.value?.positions.filter(
      (position) => position.status === "ACTIVE",
    ) ?? [],
);
let activityRequest = 0;
let formRequest = 0;

async function loadActivity() {
  const request = ++activityRequest;
  ++formRequest;
  selected.value = null;
  form.value = null;
  answers.value = {};
  errors.value = {};
  submitError.value = "";
  submitting.value = false;
  detail.value = null;
  loading.value = true;
  failed.value = false;
  try {
    const result = await getActivity(props.id);
    if (request === activityRequest) detail.value = result;
  } catch {
    if (request === activityRequest) failed.value = true;
  } finally {
    if (request === activityRequest) loading.value = false;
  }
}

async function loadForm() {
  const request = ++formRequest;
  const positionId = selected.value;
  form.value = null;
  answers.value = {};
  errors.value = {};
  submitError.value = "";
  formError.value = "";
  formLoading.value = Boolean(positionId);
  if (!positionId) return;
  try {
    const result = await getRegistrationForm(props.id, positionId);
    if (request === formRequest) form.value = result;
  } catch (error) {
    if (request === formRequest)
      formError.value = registrationErrorMessage(error);
  } finally {
    if (request === formRequest) formLoading.value = false;
  }
}

const questionKey = (question: RegistrationQuestion) =>
  `${question.scope}:${question.id}`;

function isEmpty(value: unknown) {
  return (
    value == null ||
    (typeof value === "string" && !value.trim()) ||
    (Array.isArray(value) && !value.length)
  );
}

function validAnswer(question: RegistrationQuestion, value: unknown) {
  switch (question.type) {
    case "TEXT":
      return typeof value === "string" && Boolean(value.trim());
    case "BOOLEAN":
      return typeof value === "boolean";
    case "SINGLE_CHOICE":
      return typeof value === "string" && question.options.includes(value);
    case "MULTIPLE_CHOICE":
      return (
        Array.isArray(value) &&
        value.length > 0 &&
        new Set(value).size === value.length &&
        value.every((item) => question.options.includes(item))
      );
  }
}

async function submit() {
  if (submitting.value || formLoading.value || !form.value || !selected.value)
    return;
  errors.value = {};
  submitError.value = "";
  for (const question of form.value.questions) {
    const value = answers.value[questionKey(question)];
    if (isEmpty(value) && !question.required) continue;
    if (!validAnswer(question, value))
      errors.value[questionKey(question)] = "请完成必填问题或选择有效答案。";
  }
  if (Object.keys(errors.value).length) return;
  const request = activityRequest;
  const payload = {
    positionId: selected.value,
    answers: form.value.questions
      .filter((question) => !isEmpty(answers.value[questionKey(question)]))
      .map((question) => ({
        questionScope: question.scope,
        questionId: question.id,
        answer: answers.value[questionKey(question)],
      })),
  };
  submitting.value = true;
  try {
    const result = await submitRegistration(props.id, payload);
    if (request === activityRequest)
      await router.push(`/registrations/${result.registrationId}`);
  } catch (error) {
    if (request === activityRequest)
      submitError.value = registrationErrorMessage(error);
  } finally {
    if (request === activityRequest) submitting.value = false;
  }
}

watch(() => props.id, loadActivity, { immediate: true });
watch(selected, loadForm);
onBeforeUnmount(() => {
  ++activityRequest;
  ++formRequest;
});
const format = (v: string) =>
  new Intl.DateTimeFormat("zh-CN", {
    year: "numeric",
    month: "long",
    day: "numeric",
    weekday: "short",
    hour: "2-digit",
    minute: "2-digit",
  }).format(new Date(v));
</script>
<template>
  <section class="page detail-page">
    <button class="back" @click="$router.back()">← 返回活动</button>
    <p v-if="loading" class="state">正在加载活动…</p>
    <div v-else-if="failed || !detail" class="state">
      <strong>活动不存在或你无权查看</strong>
      <button class="secondary" @click="loadActivity">重新加载</button>
    </div>
    <template v-else
      ><header class="detail-header">
        <h1>{{ detail.activity.title }}</h1>
        <p>{{ detail.activity.description }}</p>
      </header>
      <dl class="facts">
        <div>
          <dt>活动时间</dt>
          <dd>
            {{ format(detail.activity.activityStartAt) }} –
            {{ format(detail.activity.activityEndAt) }}
          </dd>
        </div>
        <div>
          <dt>活动地点</dt>
          <dd>{{ detail.activity.location }}</dd>
        </div>
        <div>
          <dt>报名截止</dt>
          <dd>{{ format(detail.activity.registrationEndAt) }}</dd>
        </div>
      </dl>
      <section class="positions">
        <h2 id="positions-heading">可报名的志愿岗位</h2>
        <p v-if="!activePositions.length" class="state">暂无可报名岗位。</p>
        <div role="radiogroup" aria-labelledby="positions-heading">
          <label
            v-for="position in activePositions"
            :key="position.id"
            class="position"
            :class="{ selected: selected === position.id }"
            ><input
              v-model="selected"
              type="radio"
              name="position"
              :value="position.id"
              :disabled="submitting"
            /><span class="radio"></span
            ><span class="position-copy"
              ><strong>{{ position.name }}</strong
              ><small>{{ position.description }}</small
              ><small
                >录取方式：{{
                  position.registrationMode === "FIRST_COME"
                    ? "先到先得"
                    : "负责人审核"
                }}
                · {{ position.capacity }} 个名额</small
              ></span
            ></label
          >
        </div>
        <p v-if="formLoading" class="state" role="status">正在加载报名表…</p>
        <div v-else-if="formError" class="state" role="alert">
          <p>{{ formError }}</p>
          <button class="secondary" @click="loadForm">重新加载报名表</button>
        </div>
        <form
          v-else-if="form"
          class="registration-form"
          novalidate
          @submit.prevent="submit"
        >
          <h2>填写报名信息</h2>
          <p v-if="!form.questions.length" class="muted">
            此岗位无需填写额外问题，可以直接提交报名。
          </p>
          <fieldset class="question-list" :disabled="submitting">
            <legend class="visually-hidden">报名问题</legend>
            <RegistrationQuestionField
              v-for="question in form.questions"
              :key="questionKey(question)"
              v-model="answers[questionKey(question)]"
              :question="question"
              :error="errors[questionKey(question)]"
            />
          </fieldset>
          <p v-if="submitError" class="error" role="alert">{{ submitError }}</p>
          <button
            class="primary"
            data-test="submit-registration"
            type="button"
            :disabled="submitting"
            @click="submit"
          >
            {{ submitting ? "正在提交…" : "提交报名" }}
          </button>
        </form>
        <p v-else-if="!selected && activePositions.length" class="phase-note">
          请先选择岗位，再填写对应报名表。
        </p>
      </section></template
    >
  </section>
</template>
