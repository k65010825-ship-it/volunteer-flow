<script setup lang="ts">
import { computed, ref } from "vue";
import type { RegistrationQuestion } from "../api/types";

const props = defineProps<{
  question: RegistrationQuestion;
  modelValue: unknown;
  error?: string;
}>();
const emit = defineEmits<{ "update:modelValue": [value: unknown] }>();
const fieldElement = ref<HTMLElement | null>(null);
const fieldId = computed(
  () => `question-${props.question.scope}-${props.question.id}`,
);
const choices = computed(() =>
  props.question.type === "BOOLEAN"
    ? [
        { label: "是", value: true },
        { label: "否", value: false },
      ]
    : props.question.options.map((option) => ({
        label: option,
        value: option,
      })),
);

function toggleChoice(option: string, checked: boolean) {
  const previous = Array.isArray(props.modelValue) ? props.modelValue : [];
  emit(
    "update:modelValue",
    checked
      ? [...previous, option]
      : previous.filter((value) => value !== option),
  );
}

/** Keep the parent independent of the input markup used for each question type. */
function focusControl() {
  fieldElement.value
    ?.querySelector<HTMLElement>("textarea:not(:disabled), input:not(:disabled)")
    ?.focus();
}

defineExpose({ focusControl });
</script>

<template>
  <div ref="fieldElement" class="question-field">
    <template v-if="question.type === 'TEXT'">
      <label :for="fieldId"
        >{{ question.title }}
        <span v-if="question.required" class="required-mark"
          >（必填）</span
        ></label
      >
      <textarea
        :id="fieldId"
        :value="typeof modelValue === 'string' ? modelValue : ''"
        :aria-required="question.required"
        :aria-invalid="Boolean(error)"
        :aria-describedby="error ? `${fieldId}-error` : undefined"
        rows="3"
        @input="
          emit(
            'update:modelValue',
            ($event.target as HTMLTextAreaElement).value,
          )
        "
      />
    </template>
    <fieldset
      v-else
      :aria-describedby="error ? `${fieldId}-error` : undefined"
      :aria-invalid="Boolean(error)"
    >
      <legend>
        {{ question.title }}
        <span v-if="question.required" class="required-mark">（必填）</span>
      </legend>
      <label
        v-for="(choice, index) in choices"
        :key="index"
        class="choice-option"
      >
        <input
          :type="question.type === 'MULTIPLE_CHOICE' ? 'checkbox' : 'radio'"
          :name="fieldId"
          :value="choice.value"
          :checked="
            question.type === 'MULTIPLE_CHOICE'
              ? Array.isArray(modelValue) && modelValue.includes(choice.value)
              : modelValue === choice.value
          "
          @change="
            question.type === 'MULTIPLE_CHOICE'
              ? toggleChoice(
                  String(choice.value),
                  ($event.target as HTMLInputElement).checked,
                )
              : emit('update:modelValue', choice.value)
          "
        />
        <span>{{ choice.label }}</span>
      </label>
    </fieldset>
    <p v-if="error" :id="`${fieldId}-error`" class="error" role="alert">
      {{ error }}
    </p>
  </div>
</template>
