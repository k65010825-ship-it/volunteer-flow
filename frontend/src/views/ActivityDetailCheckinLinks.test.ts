import { flushPromises, mount } from "@vue/test-utils";
import MockAdapter from "axios-mock-adapter";
import { afterEach, beforeEach, expect, it } from "vitest";
import { createMemoryHistory, createRouter } from "vue-router";
import { apiClient } from "../api/http";
import { activityDetail } from "../test/registrationFixtures";
import { attendance, session, summary } from "../test/checkinFixtures";
import ActivityDetailView from "./ActivityDetailView.vue";
const mock = new MockAdapter(apiClient);
const wrappers: ReturnType<typeof mount>[] = [];
beforeEach(() => {
  mock.onGet("/api/v1/activities/10").reply(200, { data: activityDetail });
  mock.onGet("/api/v1/activities/10/management").reply(403);
  mock.onGet("/api/v1/activities/10/check-in-sessions/current").reply(200, { data: { session, attendance } });
  mock.onGet("/api/v1/activities/10/attendance-summary").reply(403);
});
afterEach(() => { wrappers.splice(0).forEach(w => w.unmount()); mock.reset(); });
async function open() {
  const router = createRouter({ history: createMemoryHistory(), routes: [
    { path: "/activities/:id", component: ActivityDetailView },
    { path: "/activities/:id/check-in", component: { template: "<p />" } },
    { path: "/activities/:id/check-in-management", component: { template: "<p />" } },
  ] });
  await router.push("/activities/10"); const w = mount(ActivityDetailView, { props: { id: "10" }, global: { plugins: [router] } });
  wrappers.push(w); await flushPromises(); return w;
}
it("shows member entry only for confirmed own attendance and checks management independently", async () => {
  mock.onGet("/api/v1/activities/10/attendance-summary").reply(200, { data: summary });
  const w = await open();
  expect(w.get('[data-test="checkin-link"]').attributes("href")).toBe("/activities/10/check-in");
  expect(w.get('[data-test="checkin-management-link"]').attributes("href")).toBe("/activities/10/check-in-management");
  expect(w.find(".manage-link").exists()).toBe(false);
});
it("hides check-in links after denied/empty probes even when activity management is allowed", async () => {
  mock.onGet("/api/v1/activities/10/check-in-sessions/current").reply(200, { data: { session: null, attendance: null } });
  mock.onGet("/api/v1/activities/10/management").reply(200, { data: { activity: activityDetail.activity, positions: [] } });
  const w = await open(); expect(w.find('[data-test="checkin-link"]').exists()).toBe(false);
  expect(w.find('[data-test="checkin-management-link"]').exists()).toBe(false);
});
it("does not apply an old attendance permission probe to the next activity", async () => {
  let finish!: (value: [number, unknown]) => void;
  mock.onGet("/api/v1/activities/10/attendance-summary").reply(() => new Promise(resolve => { finish = resolve; }));
  const w = await open();
  mock.onGet("/api/v1/activities/11").reply(200, { data: { ...activityDetail, activity: { ...activityDetail.activity, id: "11" } } });
  mock.onGet("/api/v1/activities/11/check-in-sessions/current").reply(200, { data: { session: null, attendance: null } });
  await w.setProps({ id: "11" }); await flushPromises(); finish([200, { data: summary }]); await flushPromises();
  expect(w.find('[data-test="checkin-management-link"]').exists()).toBe(false);
});
