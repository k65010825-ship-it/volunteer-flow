import { flushPromises, mount } from "@vue/test-utils";
import MockAdapter from "axios-mock-adapter";
import { afterEach, beforeEach, describe, expect, it } from "vitest";
import { createMemoryHistory, createRouter } from "vue-router";
import { apiClient } from "../api/http";
import { activityDetail, question } from "../test/registrationFixtures";
import ActivityDetailView from "./ActivityDetailView.vue";

const mock = new MockAdapter(apiClient);
const wrappers: ReturnType<typeof mount>[] = [];
beforeEach(() => {
  mock.onGet("/api/v1/activities/10").reply(200, { data: activityDetail });
  mock.onGet("/api/v1/activities/10/registration-form").reply((config) => [
    200,
    {
      data: {
        activity: activityDetail.activity,
        position: activityDetail.positions.find(
          (p) => p.id === config.params.positionId,
        ),
        questions: [
          question("BOOLEAN", []),
          { ...question("TEXT", []), scope: "POSITION", required: false },
        ],
      },
    },
  ]);
});
afterEach(() => {
  wrappers.splice(0).forEach((wrapper) => wrapper.unmount());
  mock.reset();
});
async function open() {
  const router = createRouter({
    history: createMemoryHistory(),
    routes: [
      { path: "/activities/:id", component: ActivityDetailView },
      {
        path: "/registrations/:id",
        component: { template: "<p>报名状态</p>" },
      },
    ],
  });
  await router.push("/activities/10");
  const wrapper = mount(ActivityDetailView, {
    props: { id: "10" },
    global: { plugins: [router] },
  });
  wrappers.push(wrapper);
  await flushPromises();
  return { wrapper, router };
}

describe("ActivityDetailView registration", () => {
  it("validates text and multiple-choice requirements and submits scoped answers without collisions", async () => {
    mock.onGet("/api/v1/activities/10/registration-form").reply(200, {
      data: {
        activity: activityDetail.activity,
        position: activityDetail.positions[0],
        questions: [
          question("TEXT", []),
          { ...question("MULTIPLE_CHOICE"), scope: "POSITION" },
        ],
      },
    });
    mock
      .onPost("/api/v1/activities/10/registrations")
      .reply(201, {
        data: {
          registrationId: "30",
          cycleId: "40",
          status: "CONFIRMED",
          waitlistSequence: null,
          currentPosition: null,
        },
      });
    const { wrapper } = await open();
    await wrapper.get('input[name="position"][value="20"]').setValue();
    await flushPromises();
    await wrapper.get("textarea").setValue("   ");
    await wrapper.get('[data-test="submit-registration"]').trigger("click");
    expect(mock.history.post).toHaveLength(0);
    expect(wrapper.findAll('[role="alert"]')).toHaveLength(2);
    await wrapper.get("textarea").setValue("擅长接待");
    await wrapper.get('input[value="可以"]').setValue(true);
    await wrapper.get('[data-test="submit-registration"]').trigger("click");
    await flushPromises();
    expect(JSON.parse(mock.history.post[0].data).answers).toEqual([
      { questionScope: "ACTIVITY", questionId: "1", answer: "擅长接待" },
      { questionScope: "POSITION", questionId: "1", answer: ["可以"] },
    ]);
  });

  it("retries a failed form request and permits a zero-question form", async () => {
    mock.onGet("/api/v1/activities/10/registration-form").reply(503);
    const { wrapper } = await open();
    await wrapper.get('input[name="position"][value="20"]').setValue();
    await flushPromises();
    expect(wrapper.find('[data-test="submit-registration"]').exists()).toBe(
      false,
    );
    mock.onGet("/api/v1/activities/10/registration-form").reply(200, {
      data: {
        activity: activityDetail.activity,
        position: activityDetail.positions[0],
        questions: [],
      },
    });
    await wrapper.get('[role="alert"] button').trigger("click");
    await flushPromises();
    expect(wrapper.text()).toContain("无需填写额外问题");
    mock
      .onPost("/api/v1/activities/10/registrations")
      .reply(422, { code: "REGISTRATION_WINDOW_CLOSED" });
    await wrapper.get('[data-test="submit-registration"]').trigger("click");
    await flushPromises();
    expect(JSON.parse(mock.history.post[0].data)).toEqual({
      positionId: "20",
      answers: [],
    });
    expect(wrapper.text()).toContain("当前不在报名时间内");
  });

  it("does not offer inactive positions and shows the empty state", async () => {
    mock.onGet("/api/v1/activities/10").reply(200, {
      data: {
        ...activityDetail,
        positions: activityDetail.positions.map((position) => ({
          ...position,
          status: "INACTIVE",
        })),
      },
    });
    const { wrapper } = await open();
    expect(wrapper.findAll('input[name="position"]')).toHaveLength(0);
    expect(wrapper.text()).toContain("暂无可报名岗位");
  });

  it("loads only after selection, validates missing boolean, submits false and navigates", async () => {
    mock
      .onPost("/api/v1/activities/10/registrations")
      .reply(201, {
        data: {
          registrationId: "30",
          cycleId: "40",
          status: "CONFIRMED",
          waitlistSequence: null,
          currentPosition: null,
        },
      });
    const { wrapper, router } = await open();
    expect(
      mock.history.get.filter((r) => r.url?.endsWith("registration-form")),
    ).toHaveLength(0);
    await wrapper.get('input[name="position"][value="20"]').setValue();
    await flushPromises();
    await wrapper.get('[data-test="submit-registration"]').trigger("click");
    expect(mock.history.post).toHaveLength(0);
    expect(wrapper.text()).toContain("请完成必填问题");
    await wrapper.get('input[value="false"]').setValue();
    await wrapper.get('[data-test="submit-registration"]').trigger("click");
    await flushPromises();
    expect(JSON.parse(mock.history.post[0].data)).toEqual({
      positionId: "20",
      answers: [{ questionScope: "ACTIVITY", questionId: "1", answer: false }],
    });
    expect(router.currentRoute.value.path).toBe("/registrations/30");
  });

  it("resets answers when switching positions and ignores a stale form response", async () => {
    let resolveOld!: (value: [number, unknown]) => void;
    mock.onGet("/api/v1/activities/10/registration-form").reply((config) =>
      config.params.positionId === "20"
        ? new Promise((resolve) => {
            resolveOld = resolve;
          })
        : [
            200,
            {
              data: {
                activity: activityDetail.activity,
                position: activityDetail.positions[1],
                questions: [question("TEXT", [])],
              },
            },
          ],
    );
    const { wrapper } = await open();
    await wrapper.get('input[name="position"][value="20"]').setValue();
    await flushPromises();
    await wrapper.get('input[name="position"][value="21"]').setValue();
    await flushPromises();
    await wrapper.get("textarea").setValue("新答案");
    resolveOld([
      200,
      {
        data: {
          activity: activityDetail.activity,
          position: activityDetail.positions[0],
          questions: [question()],
        },
      },
    ]);
    await flushPromises();
    expect(wrapper.find("textarea").exists()).toBe(true);
    expect(wrapper.find('input[value="可以"]').exists()).toBe(false);
    await wrapper.get('input[name="position"][value="20"]').setValue();
    expect(wrapper.find("textarea").exists()).toBe(false);
  });

  it("blocks duplicate submissions and allows retry after a server error", async () => {
    let finish!: (value: [number, unknown]) => void;
    mock.onPost("/api/v1/activities/10/registrations").reply(
      () =>
        new Promise((resolve) => {
          finish = resolve;
        }),
    );
    const { wrapper } = await open();
    await wrapper.get('input[name="position"][value="20"]').setValue();
    await flushPromises();
    await wrapper.get('input[value="false"]').setValue();
    await wrapper.get('[data-test="submit-registration"]').trigger("click");
    await wrapper.get('[data-test="submit-registration"]').trigger("click");
    expect(mock.history.post).toHaveLength(1);
    expect(
      wrapper.get('[data-test="submit-registration"]').attributes("disabled"),
    ).toBeDefined();
    finish([409, { code: "REGISTRATION_ALREADY_ACTIVE" }]);
    await flushPromises();
    expect(wrapper.get('[role="alert"]').text()).toContain("已有有效报名");
    expect(
      wrapper.get('[data-test="submit-registration"]').attributes("disabled"),
    ).toBeUndefined();
  });
});
