import { mount, flushPromises } from "@vue/test-utils";
import MockAdapter from "axios-mock-adapter";
import { afterEach, beforeEach, describe, expect, it, vi } from "vitest";
import { apiClient } from "../api/http";
import ActivityEditorView from "./ActivityEditorView.vue";
const mock = new MockAdapter(apiClient);
const wrappers: ReturnType<typeof mount>[] = [];
const activity = {
  id: "10",
  organizationId: "1",
  title: "校园服务",
  description: "服务同学",
  location: "礼堂",
  registrationStartAt: "2026-11-01T08:00",
  registrationEndAt: "2026-11-02T08:00",
  freeCancelDeadlineAt: null,
  activityStartAt: "2026-11-03T08:00",
  activityEndAt: "2026-11-03T12:00",
  status: "DRAFT",
};
beforeEach(() => {
  mock.onGet("/api/v1/organizations/1/access").reply(200, {
    data: { permissionCodes: ["activity:create", "activity:publish"] },
  });
  mock
    .onGet("/api/v1/activities/10/draft")
    .reply(200, { data: { activity, positions: [] } });
  mock.onGet("/api/v1/activities/10/questions").reply(200, { data: [] });
});
afterEach(() => {
  wrappers.splice(0).forEach((w) => w.unmount());
  mock.reset();
  vi.restoreAllMocks();
  localStorage.clear();
});
async function open(props: { id?: string; organizationId?: string }) {
  const w = mount(ActivityEditorView, {
    props,
    global: { stubs: { RouterLink: { template: "<a><slot /></a>" } } },
  });
  wrappers.push(w);
  await flushPromises();
  return w;
}
describe("ActivityEditorView", () => {
  it("edits and confirms deletion of a saved position and resets its question scope", async () => {
    const savedPosition = {
      id: "20",
      activityId: "10",
      organizationId: "1",
      name: "引导",
      description: "指路",
      capacity: 2,
      registrationMode: "FIRST_COME",
      promotionTimeoutMinutes: 120,
      serviceStartAt: null,
      serviceEndAt: null,
      meetingLocation: null,
      status: "ACTIVE",
    };
    mock
      .onGet("/api/v1/activities/10/draft")
      .reply(200, { data: { activity, positions: [savedPosition] } });
    mock.onGet("/api/v1/positions/20/questions").reply(200, { data: [] });
    mock
      .onPut("/api/v1/positions/20/draft")
      .reply(200, { data: { ...savedPosition, name: "新引导" } });
    mock.onDelete("/api/v1/positions/20/draft").reply(204);
    const wrapper = await open({ id: "10" });
    await wrapper.find('[data-test="edit-position-20"]').trigger("click");
    await wrapper.find('[data-test="position-name"]').setValue("新引导");
    expect(
      wrapper.find('[data-test="publish-activity"]').attributes("disabled"),
    ).toBeDefined();
    await wrapper.find('[data-test="save-position"]').trigger("submit");
    await flushPromises();
    expect(wrapper.text()).toContain("新引导");
    expect(mock.history.put[0].url).toBe("/api/v1/positions/20/draft");
    await wrapper.find('[data-test="position-questions-20"]').trigger("click");
    const confirm = vi.spyOn(window, "confirm").mockReturnValue(false);
    await wrapper.find('[data-test="delete-position-20"]').trigger("click");
    expect(mock.history.delete).toHaveLength(0);
    confirm.mockReturnValue(true);
    await wrapper.find('[data-test="delete-position-20"]').trigger("click");
    await flushPromises();
    expect(mock.history.delete).toHaveLength(1);
    expect(wrapper.find('[data-test="edit-position-20"]').exists()).toBe(false);
    expect(wrapper.text()).toContain("公共问题管理");
  });
  it("creates a draft and keeps its real ID for the next step", async () => {
    mock
      .onPost("/api/v1/organizations/1/activities")
      .reply(201, { data: activity });
    const w = await open({ organizationId: "1" });
    const inputs = w.findAll("input");
    for (const [i, value] of [
      [0, "校园服务"],
      [1, "礼堂"],
      [2, activity.registrationStartAt],
      [3, activity.registrationEndAt],
      [5, activity.activityStartAt],
      [6, activity.activityEndAt],
    ] as [number, string][])
      await inputs[i].setValue(value);
    await w.find("textarea").setValue("服务同学");
    await w.find("form").trigger("submit");
    await flushPromises();
    expect(w.text()).toContain("活动 ID：10");
    expect(localStorage.getItem("activity-draft:1")).toBeNull();
    expect(
      JSON.parse(mock.history.post[0].data).freeCancelDeadlineAt,
    ).toBeNull();
  });
  it("ignores an old draft response after switching to a new activity route", async () => {
    let resolveOld!: (value: [number, unknown]) => void;
    mock.onGet("/api/v1/activities/10/draft").reply(
      () =>
        new Promise((resolve) => {
          resolveOld = resolve;
        }),
    );
    mock
      .onGet("/api/v1/activities/11/draft")
      .reply(200, {
        data: {
          activity: { ...activity, id: "11", title: "新草稿" },
          positions: [],
        },
      });
    mock.onGet("/api/v1/activities/11/questions").reply(200, { data: [] });
    const wrapper = await open({ id: "10" });
    await wrapper.setProps({ id: "11" });
    await flushPromises();
    resolveOld([200, { data: { activity, positions: [] } }]);
    await flushPromises();
    expect(
      (wrapper.find('[data-test="draft-title"]').element as HTMLInputElement)
        .value,
    ).toBe("新草稿");
    expect(wrapper.text()).toContain("活动 ID：11");
  });
  it("blocks publication when only an unfinished new position capacity was changed", async () => {
    mock
      .onGet("/api/v1/activities/10/draft")
      .reply(200, {
        data: {
          activity,
          positions: [
            {
              id: "20",
              name: "引导",
              description: "指路",
              capacity: 2,
              status: "ACTIVE",
            },
          ],
        },
      });
    mock.onGet("/api/v1/positions/20/questions").reply(200, { data: [] });
    const wrapper = await open({ id: "10" });
    expect(
      wrapper.find('[data-test="publish-activity"]').attributes("disabled"),
    ).toBeUndefined();
    await wrapper.findAll('input[type="number"]')[0].setValue(4);
    expect(
      wrapper.find('[data-test="publish-activity"]').attributes("disabled"),
    ).toBeDefined();
  });
  it("reopens a create-only draft and loads real common and position questions", async () => {
    mock.onGet("/api/v1/activities/10/draft").reply(200, {
      data: {
        activity,
        positions: [
          {
            id: "20",
            name: "引导",
            description: "指路",
            capacity: 2,
            status: "ACTIVE",
          },
        ],
      },
    });
    mock.onGet("/api/v1/activities/10/questions").reply(200, {
      data: [
        {
          id: "30",
          title: "姓名",
          questionType: "TEXT",
          requiredQuestion: true,
          optionsJson: null,
          sortOrder: 1,
        },
      ],
    });
    mock.onGet("/api/v1/positions/20/questions").reply(200, { data: [] });
    const w = await open({ id: "10" });
    expect(w.text()).toContain("姓名");
    expect(w.text()).toContain("引导");
    expect(mock.history.get.some((c) => c.url?.endsWith("/management"))).toBe(
      false,
    );
  });
  it("updates an existing draft instead of creating another one", async () => {
    mock
      .onPut("/api/v1/activities/10")
      .reply(200, { data: { ...activity, title: "更新名称" } });
    const w = await open({ id: "10" });
    await w.find('[data-test="draft-title"]').setValue("更新名称");
    await w.find("form").trigger("submit");
    await flushPromises();
    expect(mock.history.put).toHaveLength(1);
    expect(mock.history.post).toHaveLength(0);
  });
  it("does not expose publication without permission", async () => {
    mock
      .onGet("/api/v1/organizations/1/access")
      .reply(200, { data: { permissionCodes: ["activity:create"] } });
    const w = await open({ id: "10" });
    expect(w.find('[data-test="publish-activity"]').exists()).toBe(false);
  });
  it("explains create permission on draft 403 and keeps the ID", async () => {
    mock.onGet("/api/v1/activities/10/draft").reply(403);
    const w = await open({ id: "10" });
    expect(w.text()).toContain("activity:create");
    expect(w.text()).toContain("活动 ID：10");
    expect(w.text()).not.toContain("活动不存在");
  });
  it("requires confirmation before publication and keeps ID", async () => {
    mock.onGet("/api/v1/activities/10/draft").reply(200, {
      data: {
        activity,
        positions: [
          {
            id: "20",
            name: "引导",
            description: "指路",
            capacity: 2,
            status: "ACTIVE",
          },
        ],
      },
    });
    mock.onGet("/api/v1/positions/20/questions").reply(200, { data: [] });
    mock
      .onPost("/api/v1/activities/10/publication")
      .reply(200, { data: { ...activity, status: "PUBLISHED" } });
    const confirm = vi.spyOn(window, "confirm").mockReturnValue(false);
    const w = await open({ id: "10" });
    await w.find('[data-test="publish-activity"]').trigger("click");
    expect(mock.history.post).toHaveLength(0);
    confirm.mockReturnValue(true);
    await w.find('[data-test="publish-activity"]').trigger("click");
    await flushPromises();
    expect(w.text()).toContain("活动已发布");
    expect(w.find('[data-test="publish-activity"]').exists()).toBe(false);
  });
});
