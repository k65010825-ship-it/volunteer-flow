import { mount } from "@vue/test-utils";
import { describe, expect, it } from "vitest";
import RegistrationQuestionField from "./RegistrationQuestionField.vue";
import { question } from "../test/registrationFixtures";

describe("RegistrationQuestionField", () => {
  it("renders a labelled choice group and emits the selected string", async () => {
    const wrapper = mount(RegistrationQuestionField, {
      props: { question: question(), modelValue: null },
    });
    expect(wrapper.get("legend").text()).toContain("可参加培训吗");
    await wrapper.get('input[value="可以"]').setValue();
    expect(wrapper.emitted("update:modelValue")?.[0]).toEqual(["可以"]);
  });

  it("keeps unanswered boolean distinct from false and supports both choices", async () => {
    const wrapper = mount(RegistrationQuestionField, {
      props: { question: question("BOOLEAN", []), modelValue: null },
    });
    expect(wrapper.findAll("input:checked")).toHaveLength(0);
    await wrapper.get('input[value="false"]').setValue();
    expect(wrapper.emitted("update:modelValue")?.[0]).toEqual([false]);
    await wrapper.get('input[value="true"]').setValue();
    expect(wrapper.emitted("update:modelValue")?.[1]).toEqual([true]);
  });

  it("adds and removes multiple choices without losing previous values", async () => {
    const wrapper = mount(RegistrationQuestionField, {
      props: { question: question("MULTIPLE_CHOICE"), modelValue: ["可以"] },
    });
    await wrapper.get('input[value="不可以"]').setValue(true);
    expect(wrapper.emitted("update:modelValue")?.[0]).toEqual([
      ["可以", "不可以"],
    ]);
    await wrapper.get('input[value="可以"]').setValue(false);
    expect(wrapper.emitted("update:modelValue")?.[1]).toEqual([[]]);
  });

  it("labels text input, exposes required validation, and emits text", async () => {
    const wrapper = mount(RegistrationQuestionField, {
      props: {
        question: question("TEXT", []),
        modelValue: "",
        error: "请填写此题",
      },
    });
    const input = wrapper.get("textarea");
    expect(wrapper.get("label").attributes("for")).toBe(input.attributes("id"));
    expect(input.attributes("aria-invalid")).toBe("true");
    expect(wrapper.get('[role="alert"]').text()).toBe("请填写此题");
    await input.setValue("有经验");
    expect(wrapper.emitted("update:modelValue")?.[0]).toEqual(["有经验"]);
  });
});
