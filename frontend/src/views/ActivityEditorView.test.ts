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
  mock
    .onGet("/api/v1/organizations/1/access")
    .reply(200, {
      data: { permissionCodes: ["activity:create", "activity:publish"] },
    });
  mock
    .onGet("/api/v1/activities/10")
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
    expect(localStorage.getItem("activity-draft:1")).toBe("10");
    expect(
      JSON.parse(mock.history.post[0].data).freeCancelDeadlineAt,
    ).toBeNull();
  });
  it("reopens a create-only draft and loads real common and position questions", async () => {
    mock
      .onGet("/api/v1/activities/10")
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
    mock
      .onGet("/api/v1/activities/10/questions")
      .reply(200, {
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
  it("explains the read permission on draft 403 and keeps the ID", async () => {
    mock.onGet("/api/v1/activities/10").reply(403);
    const w = await open({ id: "10" });
    expect(w.text()).toContain("activity:read");
    expect(w.text()).toContain("活动 ID：10");
    expect(w.text()).not.toContain("活动不存在");
  });
  it("requires confirmation before publication and keeps ID", async () => {
    mock
      .onGet("/api/v1/activities/10")
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
