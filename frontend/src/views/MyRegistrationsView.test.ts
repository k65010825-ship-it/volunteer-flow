import { flushPromises, mount } from "@vue/test-utils";
import MockAdapter from "axios-mock-adapter";
import { afterEach, describe, expect, it } from "vitest";
import { apiClient } from "../api/http";
import { activityDetail, registration } from "../test/registrationFixtures";
import MyRegistrationsView from "./MyRegistrationsView.vue";

const mock = new MockAdapter(apiClient);
const wrappers: ReturnType<typeof mount>[] = [];
afterEach(() => {
  wrappers.splice(0).forEach((wrapper) => wrapper.unmount());
  mock.reset();
});
async function open() {
  const wrapper = mount(MyRegistrationsView, {
    global: {
      stubs: {
        RouterLink: { props: ["to"], template: '<a :href="to"><slot /></a>' },
      },
    },
  });
  wrappers.push(wrapper);
  await flushPromises();
  return wrapper;
}
describe("MyRegistrationsView", () => {
  it("shows own status and links, enriching each distinct activity once", async () => {
    mock
      .onGet("/api/v1/users/me/registrations")
      .reply(200, {
        data: [
          registration(),
          registration({
            registrationId: "31",
            positionId: "21",
            status: "PENDING_REVIEW",
            waitlistSequence: null,
          }),
        ],
      });
    mock.onGet("/api/v1/activities/10").reply(200, { data: activityDetail });
    const wrapper = await open();
    expect(wrapper.text()).toContain("开学典礼");
    expect(wrapper.text()).toContain("礼仪");
    expect(wrapper.text()).toContain("负责人审核");
    expect(wrapper.get('a[href="/registrations/30"]').text()).toContain(
      "当前位次：2",
    );
    expect(
      mock.history.get.filter((r) => r.url === "/api/v1/activities/10"),
    ).toHaveLength(1);
  });
  it("keeps registration state visible if enrichment is unavailable", async () => {
    mock
      .onGet("/api/v1/users/me/registrations")
      .reply(200, { data: [registration()] });
    mock.onGet("/api/v1/activities/10").reply(404);
    const wrapper = await open();
    expect(wrapper.text()).toContain("活动 #10");
    expect(wrapper.text()).toContain("岗位 #20");
    expect(wrapper.text()).toContain("候补中");
  });
  it("retries failed list loads and shows an empty state", async () => {
    mock.onGet("/api/v1/users/me/registrations").replyOnce(503);
    mock.onGet("/api/v1/users/me/registrations").reply(200, { data: [] });
    const wrapper = await open();
    expect(wrapper.get('[role="alert"]').text()).toContain("加载失败");
    await wrapper.get("button").trigger("click");
    await flushPromises();
    expect(wrapper.text()).toContain("暂无报名");
  });
});
