import { flushPromises, mount } from "@vue/test-utils";
import { afterEach, beforeEach, describe, expect, it } from "vitest";
import MockAdapter from "axios-mock-adapter";
import { apiClient } from "../api/http";
import ActivityListView from "./ActivityListView.vue";

const mock = new MockAdapter(apiClient);
const wrappers: ReturnType<typeof mount>[] = [];
beforeEach(() => {
  mock
    .onGet(/\/access$/)
    .reply(200, { data: { permissionCodes: ["activity:create"] } });
});
afterEach(() => {
  wrappers.splice(0).forEach((w) => w.unmount());
  mock.reset();
  localStorage.clear();
});
async function open() {
  const w = mount(ActivityListView, {
    props: { organizationId: "100" },
    global: { stubs: { RouterLink: { template: "<a><slot /></a>" } } },
  });
  wrappers.push(w);
  await flushPromises();
  return w;
}
describe("ActivityListView", () => {
  it("exposes organization and audit navigation independently of activity creation", async () => {
    mock
      .onGet(/\/access$/)
      .reply(200, { data: { permissionCodes: ["member:read", "audit:read"] } });
    mock
      .onGet(/\/activities$/)
      .reply(200, { data: [], meta: { page: 1, size: 20, total: 0 } });
    const wrapper = await open();
    expect(wrapper.get(".workspace-links").text()).toContain("组织管理");
    expect(wrapper.get(".workspace-links").text()).toContain("审计日志");
    expect(wrapper.text()).not.toContain("创建活动");
  });
  it("loads backend pagination and advances with page/size params", async () => {
    mock
      .onGet("/api/v1/organizations/100/activities")
      .reply((config) => [
        200,
        {
          data: [
            {
              activity: {
                id: "1",
                title: "新生开学典礼",
                location: "图书馆前广场",
                activityStartAt: "2026-09-20T08:00:00",
              },
              positions: [],
            },
          ],
          meta: { page: config.params.page, size: 20, total: 21 },
        },
      ]);
    const w = await open();
    expect(w.text()).toContain("新生开学典礼");
    expect(w.text()).toContain("创建活动");
    await w
      .findAll("button")
      .find((b) => b.text() === "下一页")!
      .trigger("click");
    await flushPromises();
    const calls = mock.history.get.filter((c) =>
      c.url?.endsWith("/activities"),
    );
    expect(calls[calls.length - 1].params).toEqual({ page: 2, size: 20 });
    expect(w.text()).toContain("第 2 页");
  });
  it("shows an empty state", async () => {
    mock
      .onGet(/\/activities$/)
      .reply(200, { data: [], meta: { page: 1, size: 20, total: 0 } });
    const w = await open();
    expect(w.text()).toContain("暂时没有已发布活动");
    expect(
      w
        .findAll("button")
        .find((b) => b.text() === "下一页")!
        .attributes("disabled"),
    ).toBeDefined();
  });
  it("ignores an older organization response", async () => {
    let resolveOld!: (value: [number, unknown]) => void;
    mock.onGet("/api/v1/organizations/100/activities").reply(
      () =>
        new Promise((resolve) => {
          resolveOld = resolve;
        }),
    );
    mock
      .onGet("/api/v1/organizations/200/activities")
      .reply(200, { data: [], meta: { page: 1, size: 20, total: 0 } });
    const w = await open();
    await w.setProps({ organizationId: "200" });
    await flushPromises();
    resolveOld([
      200,
      {
        data: [{ activity: { id: "old", title: "旧组织活动" }, positions: [] }],
        meta: { page: 1, size: 20, total: 1 },
      },
    ]);
    await flushPromises();
    expect(w.text()).not.toContain("旧组织活动");
    expect(w.text()).toContain("暂时没有已发布活动");
  });
  it("offers retry after loading failure", async () => {
    mock
      .onGet(/\/activities$/)
      .replyOnce(500)
      .onGet(/\/activities$/)
      .reply(200, { data: [], meta: { page: 1, size: 20, total: 0 } });
    const w = await open();
    expect(w.text()).toContain("活动加载失败");
    await w.find("button").trigger("click");
    await flushPromises();
    expect(w.text()).toContain("暂时没有已发布活动");
  });
});
