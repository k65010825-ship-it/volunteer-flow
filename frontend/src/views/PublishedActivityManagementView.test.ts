import { flushPromises, mount } from "@vue/test-utils";
import MockAdapter from "axios-mock-adapter";
import { afterEach, beforeEach, describe, expect, it } from "vitest";
import { apiClient } from "../api/http";
import type { ActivityDetail } from "../api/types";
import PublishedActivityManagementView from "./PublishedActivityManagementView.vue";

const mock = new MockAdapter(apiClient);
const wrappers: ReturnType<typeof mount>[] = [];

const activity: ActivityDetail = {
  activity: {
    id: "10",
    organizationId: "1",
    title: "开学典礼",
    description: "服务新同学",
    location: "礼堂",
    registrationStartAt: "2026-09-01T08:00:00",
    registrationEndAt: "2026-09-30T08:00:00",
    freeCancelDeadlineAt: "2026-09-29T08:00:00",
    activityStartAt: "2026-10-01T08:00:00",
    activityEndAt: "2026-10-01T12:00:00",
    status: "PUBLISHED",
  },
  positions: [
    {
      id: "20",
      name: "礼仪",
      description: "迎接来宾",
      capacity: 20,
      registrationMode: "FIRST_COME",
      promotionTimeoutMinutes: 30,
      serviceStartAt: "2026-10-01T09:00:00",
      serviceEndAt: "2026-10-01T11:00:00",
      meetingLocation: "礼堂门口",
      status: "ACTIVE",
    },
  ],
};

beforeEach(() => {
  mock.onGet("/api/v1/activities/10/management").reply(200, {
    data: {
      activity: activity.activity,
      positions: activity.positions.map((position) => ({ position, questions: [] })),
    },
  });
});

afterEach(() => {
  wrappers.splice(0).forEach((wrapper) => wrapper.unmount());
  mock.reset();
});

async function open(id = "10") {
  const wrapper = mount(PublishedActivityManagementView, { props: { id } });
  wrappers.push(wrapper);
  await flushPromises();
  return wrapper;
}

describe("PublishedActivityManagementView", () => {
  it("summarizes only actually changed allowed activity fields", async () => {
    const wrapper = await open();

    await wrapper.get('[data-test="activity-title"]').setValue("迎新典礼");
    await wrapper.get('[data-test="review-activity-change"]').trigger("click");

    const summary = wrapper.get('[data-test="change-summary"]');
    expect(summary.text()).toContain("活动标题");
    expect(summary.text()).toContain("开学典礼");
    expect(summary.text()).toContain("迎新典礼");
    expect(summary.text()).not.toContain("活动地点");
    expect(summary.text()).not.toContain("报名开始时间");
  });

  it("rejects an unchanged activity locally without creating a request", async () => {
    const wrapper = await open();

    await wrapper.get('[data-test="review-activity-change"]').trigger("click");

    expect(wrapper.text()).toContain("没有可提交的实际变更");
    expect(mock.history.post).toHaveLength(0);
  });

  it("requires and trims the change reason before submitting the changed fields", async () => {
    mock.onPost("/api/v1/activities/10/changes").reply(200, {
      data: { activity: { ...activity.activity, title: "迎新典礼" }, notifiedRecipients: 3 },
    });
    const wrapper = await open();

    await wrapper.get('[data-test="activity-title"]').setValue("迎新典礼");
    await wrapper.get('[data-test="review-activity-change"]').trigger("click");
    await wrapper.get('[data-test="change-reason"]').setValue("   ");
    await wrapper.get('[data-test="submit-activity-change"]').trigger("click");

    expect(wrapper.text()).toContain("请填写变更原因");
    expect(mock.history.post).toHaveLength(0);
    await wrapper.get('[data-test="change-reason"]').setValue("  场地安排调整  ");
    await wrapper.get('[data-test="submit-activity-change"]').trigger("click");
    await flushPromises();

    expect(JSON.parse(mock.history.post[0].data)).toMatchObject({
      title: "迎新典礼",
      reason: "场地安排调整",
    });
    expect(wrapper.text()).toContain("已通知 3 位受影响成员");
  });

  it("blocks a capacity decrease locally before the position request", async () => {
    const wrapper = await open();

    await wrapper.get('[data-test="position-capacity-20"]').setValue(19);
    await wrapper.get('[data-test="review-position-20"]').trigger("click");

    expect(wrapper.text()).toContain("岗位容量只能增加");
    expect(mock.history.post).toHaveLength(0);
  });

  it.each([
    [403, "你没有执行此变更的权限"],
    [409, "活动状态已变化，请刷新后重试"],
    [422, "变更内容不符合活动规则"],
  ])("presents %s change errors separately", async (status, message) => {
    mock.onPost("/api/v1/activities/10/changes").reply(status, {
      code: "CHANGE_REJECTED",
    });
    const wrapper = await open();
    await wrapper.get('[data-test="activity-title"]').setValue("迎新典礼");
    await wrapper.get('[data-test="review-activity-change"]').trigger("click");
    await wrapper.get('[data-test="change-reason"]').setValue("场地安排调整");
    await wrapper.get('[data-test="submit-activity-change"]').trigger("click");
    await flushPromises();

    expect(wrapper.text()).toContain(message);
  });

  it("keeps a newer activity when an older request resolves last", async () => {
    let resolveOld!: (value: [number, unknown]) => void;
    mock.reset();
    mock.onGet(/\/api\/v1\/activities\/(10|11)\/management$/).reply((config) =>
      config.url?.endsWith("/10/management")
        ? new Promise((resolve) => {
            resolveOld = resolve;
          })
        : [
            200,
            {
              data: {
                activity: { ...activity.activity, id: "11", title: "新的活动" },
                positions: activity.positions.map((position) => ({ position, questions: [] })),
              },
            },
          ],
    );
    const wrapper = await open();
    await wrapper.setProps({ id: "11" });
    await flushPromises();
    resolveOld([
      200,
      {
        data: {
          activity: activity.activity,
          positions: activity.positions.map((position) => ({ position, questions: [] })),
        },
      },
    ]);
    await flushPromises();

    expect(
      wrapper.get<HTMLInputElement>('[data-test="activity-title"]').element.value,
    ).toBe("新的活动");
  });

  it("creates a draft position and publishes it with the returned recipient count", async () => {
    mock.onPost("/api/v1/activities/10/positions").reply(201, {
      data: {
        ...activity.positions[0],
        id: "99",
        name: "引导",
        status: "DRAFT",
      },
    });
    mock.onPost("/api/v1/positions/99/publication").reply(200, {
      data: {
        position: {
          ...activity.positions[0],
          id: "99",
          name: "引导",
          status: "ACTIVE",
        },
        notifiedRecipients: 2,
      },
    });
    const wrapper = await open();
    await wrapper.get('[data-test="new-position-name"]').setValue("引导");
    await wrapper
      .get('[data-test="new-position-description"]')
      .setValue("引导来宾签到");
    await wrapper.get('[data-test="save-draft-position"]').trigger("click");
    await flushPromises();
    await wrapper.get('[data-test="publish-position-99"]').trigger("click");
    await flushPromises();

    expect(wrapper.text()).toContain("引导");
    expect(wrapper.text()).toContain("已通知 2 位受影响成员");
  });

  it("omits an unchanged free-cancellation deadline from an activity-change request", async () => {
    mock.onPost("/api/v1/activities/10/changes").reply(200, {
      data: { activity: { ...activity.activity, title: "迎新典礼" }, notifiedRecipients: 0 },
    });
    const wrapper = await open();

    await wrapper.get('[data-test="activity-title"]').setValue("迎新典礼");
    await wrapper.get('[data-test="review-activity-change"]').trigger("click");
    await wrapper.get('[data-test="change-reason"]').setValue("场地安排调整");
    await wrapper.get('[data-test="submit-activity-change"]').trigger("click");
    await flushPromises();

    expect(JSON.parse(mock.history.post[0].data)).not.toHaveProperty(
      "freeCancelDeadlineAt",
    );
  });

  it("sends an explicit null only when the free-cancellation deadline is cleared", async () => {
    mock.onPost("/api/v1/activities/10/changes").reply(200, {
      data: { activity: { ...activity.activity, freeCancelDeadlineAt: null }, notifiedRecipients: 0 },
    });
    const wrapper = await open();

    await wrapper.get('[data-test="free-cancel-deadline"]').setValue("");
    await wrapper.get('[data-test="review-activity-change"]').trigger("click");
    await wrapper.get('[data-test="change-reason"]').setValue("取消免费截止时间");
    await wrapper.get('[data-test="submit-activity-change"]').trigger("click");
    await flushPromises();

    expect(JSON.parse(mock.history.post[0].data)).toMatchObject({
      freeCancelDeadlineAt: null,
    });
  });

  it("treats whitespace-only activity edits as a local no-op", async () => {
    const wrapper = await open();

    await wrapper.get('[data-test="activity-title"]').setValue("  开学典礼  ");
    await wrapper.get('[data-test="review-activity-change"]').trigger("click");

    expect(wrapper.text()).toContain("没有可提交的实际变更");
    expect(mock.history.post).toHaveLength(0);
  });

  it("sends an increased capacity in the formal position-change request", async () => {
    mock.onPost("/api/v1/positions/20/changes").reply(200, {
      data: { position: { ...activity.positions[0], capacity: 21 }, notifiedRecipients: 1 },
    });
    const wrapper = await open();

    await wrapper.get('[data-test="position-capacity-20"]').setValue(21);
    await wrapper.get('[data-test="review-position-20"]').trigger("click");
    await wrapper.get('[data-test="change-reason"]').setValue("补充人手");
    await wrapper.get('[data-test="submit-activity-change"]').trigger("click");
    await flushPromises();

    expect(JSON.parse(mock.history.post[0].data)).toMatchObject({ capacity: 21 });
  });

  it("resets old mutation state immediately when navigating to another activity", async () => {
    let finishOld!: (value: [number, unknown]) => void;
    mock.onGet("/api/v1/activities/11/management").reply(200, {
      data: {
        activity: { ...activity.activity, id: "11", title: "新的活动" },
        positions: activity.positions.map((position) => ({ position, questions: [] })),
      },
    });
    mock.onPost("/api/v1/activities/10/changes").reply(
      () =>
        new Promise((resolve) => {
          finishOld = resolve;
        }),
    );
    const wrapper = await open();
    await wrapper.get('[data-test="activity-title"]').setValue("旧活动变更");
    await wrapper.get('[data-test="review-activity-change"]').trigger("click");
    await wrapper.get('[data-test="change-reason"]').setValue("旧活动原因");
    await wrapper.get('[data-test="submit-activity-change"]').trigger("click");

    await wrapper.setProps({ id: "11" });
    await flushPromises();

    expect(
      wrapper.get<HTMLInputElement>('[data-test="activity-title"]').element.value,
    ).toBe("新的活动");
    expect(wrapper.get('[data-test="review-activity-change"]').attributes("disabled")).toBeUndefined();
    expect(wrapper.find('[data-test="change-summary"]').exists()).toBe(false);
    finishOld([
      200,
      { data: { activity: { ...activity.activity, title: "旧活动变更" }, notifiedRecipients: 7 } },
    ]);
    await flushPromises();

    expect(wrapper.text()).not.toContain("已通知 7 位受影响成员");
    expect(
      wrapper.get<HTMLInputElement>('[data-test="activity-title"]').element.value,
    ).toBe("新的活动");
  });

  it("discards an old position-change rejection after the route changes", async () => {
    let rejectOld!: (value: [number, unknown]) => void;
    mock.onGet("/api/v1/activities/11/management").reply(200, {
      data: {
        activity: { ...activity.activity, id: "11", title: "新的活动" },
        positions: activity.positions.map((position) => ({ position, questions: [] })),
      },
    });
    mock.onPost("/api/v1/positions/20/changes").reply(
      () => new Promise((resolve) => { rejectOld = resolve; }),
    );
    const wrapper = await open();
    await wrapper.get('[data-test="position-capacity-20"]').setValue(21);
    await wrapper.get('[data-test="review-position-20"]').trigger("click");
    await wrapper.get('[data-test="change-reason"]').setValue("增加人手");
    await wrapper.get('[data-test="submit-activity-change"]').trigger("click");

    await wrapper.setProps({ id: "11" });
    await flushPromises();
    rejectOld([409, { code: "ACTIVITY_CHANGE_NOT_ALLOWED" }]);
    await flushPromises();

    expect(wrapper.text()).not.toContain("活动状态已变化，请刷新后重试");
    expect(wrapper.get('[data-test="review-activity-change"]').attributes("disabled")).toBeUndefined();
  });

  it("discards an old draft-creation response after the route changes", async () => {
    let finishOld!: (value: [number, unknown]) => void;
    mock.onGet("/api/v1/activities/11/management").reply(200, {
      data: {
        activity: { ...activity.activity, id: "11", title: "新的活动" },
        positions: activity.positions.map((position) => ({ position, questions: [] })),
      },
    });
    mock.onPost("/api/v1/activities/10/positions").reply(
      () => new Promise((resolve) => { finishOld = resolve; }),
    );
    const wrapper = await open();
    await wrapper.get('[data-test="new-position-name"]').setValue("旧草稿");
    await wrapper.get('[data-test="new-position-description"]').setValue("不应进入新活动");
    await wrapper.get('[data-test="save-draft-position"]').trigger("click");

    await wrapper.setProps({ id: "11" });
    await flushPromises();
    finishOld([
      201,
      { data: { ...activity.positions[0], id: "99", name: "旧草稿", status: "DRAFT" } },
    ]);
    await flushPromises();

    expect(wrapper.text()).not.toContain("旧草稿");
    expect(wrapper.get('[data-test="review-activity-change"]').attributes("disabled")).toBeUndefined();
  });

  it("discards an old draft-publication response after the route changes", async () => {
    let finishOld!: (value: [number, unknown]) => void;
    mock.reset();
    mock.onGet("/api/v1/activities/10/management").reply(200, {
      data: {
        activity: activity.activity,
        positions: [
          { position: { ...activity.positions[0], id: "99", name: "旧草稿", status: "DRAFT" }, questions: [] },
        ],
      },
    });
    mock.onGet("/api/v1/activities/11/management").reply(200, {
      data: {
        activity: { ...activity.activity, id: "11", title: "新的活动" },
        positions: activity.positions.map((position) => ({ position, questions: [] })),
      },
    });
    mock.onPost("/api/v1/positions/99/publication").reply(
      () => new Promise((resolve) => { finishOld = resolve; }),
    );
    const wrapper = await open();
    await wrapper.get('[data-test="publish-position-99"]').trigger("click");
    await wrapper.setProps({ id: "11" });
    await flushPromises();
    finishOld([
      200,
      { data: { position: { ...activity.positions[0], id: "99", status: "ACTIVE" }, notifiedRecipients: 7 } },
    ]);
    await flushPromises();

    expect(wrapper.text()).not.toContain("已通知 7 位受影响成员");
    expect(wrapper.get('[data-test="review-activity-change"]').attributes("disabled")).toBeUndefined();
  });

  it("loads persisted draft positions and questions from the protected management detail", async () => {
    mock.reset();
    mock.onGet("/api/v1/activities/10/management").reply(200, {
      data: {
        activity: activity.activity,
        positions: [
          ...activity.positions.map((position) => ({ position, questions: [] })),
          {
            position: {
              ...activity.positions[0],
              id: "99",
              name: "引导",
              status: "DRAFT",
            },
            questions: [
              {
                id: "100",
                questionType: "TEXT",
                title: "有接待经验吗？",
                requiredQuestion: true,
                optionsJson: null,
                sortOrder: 1,
              },
            ],
          },
        ],
      },
    });
    const wrapper = await open();

    expect(wrapper.text()).toContain("引导");
    expect(
      wrapper.get<HTMLInputElement>('[data-test="question-title-99-0"]').element.value,
    ).toBe("有接待经验吗？");
  });

  it("creates a valid persisted draft question before publishing the draft position", async () => {
    mock.reset();
    mock.onGet("/api/v1/activities/10/management").reply(200, {
      data: {
        activity: activity.activity,
        positions: [
          {
            position: { ...activity.positions[0], id: "99", name: "引导", status: "DRAFT" },
            questions: [],
          },
        ],
      },
    });
    mock.onPost("/api/v1/positions/99/questions").reply(201, {
      data: {
        id: "100",
        questionType: "TEXT",
        title: "接待经验",
        requiredQuestion: true,
        optionsJson: null,
        sortOrder: 1,
      },
    });
    mock.onPost("/api/v1/positions/99/publication").reply(200, {
      data: {
        position: { ...activity.positions[0], id: "99", name: "引导", status: "ACTIVE" },
        notifiedRecipients: 2,
      },
    });
    const wrapper = await open();

    await wrapper.get('[data-test="add-question-99"]').trigger("click");
    await wrapper.get('[data-test="question-title-99-0"]').setValue(" 接待经验 ");
    await wrapper.findAll("button").find((button) => button.text() === "保存问题")!.trigger("click");
    await flushPromises();
    expect(JSON.parse(mock.history.post[0].data)).toMatchObject({
      type: "TEXT",
      title: "接待经验",
      required: false,
      options: null,
      sortOrder: 1,
    });
    expect(wrapper.findAll('[data-test^="question-title-99-"]')).toHaveLength(1);
    expect(
      wrapper.get<HTMLInputElement>('[data-test="question-title-99-0"]').element.value,
    ).toBe("接待经验");
    await wrapper.get('[data-test="publish-position-99"]').trigger("click");
    await flushPromises();

    expect(wrapper.text()).toContain("已通知 2 位受影响成员");
  });
});
