import { mount, flushPromises } from "@vue/test-utils";
import MockAdapter from "axios-mock-adapter";
import { afterEach, expect, it } from "vitest";
import { apiClient } from "../api/http";
import DraftActivitiesView from "./DraftActivitiesView.vue";

const mock = new MockAdapter(apiClient);
const wrappers: ReturnType<typeof mount>[] = [];
afterEach(() => {
  wrappers.splice(0).forEach((w) => w.unmount());
  mock.reset();
});
async function open() {
  const wrapper = mount(DraftActivitiesView, {
    props: { organizationId: "1" },
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
it("pages server drafts and opens the persistent editor link", async () => {
  mock.onGet("/api/v1/organizations/1/activity-drafts").reply((config) => [
    200,
    {
      data:
        config.params.page === 1
          ? [
              {
                activity: { id: "10", title: "迎新服务", location: "礼堂" },
                positions: [],
              },
            ]
          : [],
      meta: { total: 21, page: config.params.page, size: 20 },
    },
  ]);
  const wrapper = await open();
  expect(wrapper.find('a[href="/activities/10/edit"]').exists()).toBe(true);
  await wrapper.find('[data-test="next-page"]').trigger("click");
  await flushPromises();
  expect(mock.history.get[1].params).toEqual({ page: 2, size: 20 });
  expect(wrapper.text()).toContain("暂无活动草稿");
});
it("shows a retryable error without presenting a successful empty state", async () => {
  mock.onGet("/api/v1/organizations/1/activity-drafts").reply(403);
  const wrapper = await open();
  expect(wrapper.find('[role="alert"]').exists()).toBe(true);
  expect(wrapper.text()).not.toContain("暂无活动草稿");
  mock
    .onGet("/api/v1/organizations/1/activity-drafts")
    .reply(200, { data: [], meta: { total: 0, page: 1, size: 20 } });
  await wrapper.find('[data-test="retry"]').trigger("click");
  await flushPromises();
  expect(wrapper.text()).toContain("暂无活动草稿");
});
