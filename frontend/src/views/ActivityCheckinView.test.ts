import { flushPromises, mount } from "@vue/test-utils";
import MockAdapter from "axios-mock-adapter";
import { afterEach, beforeEach, describe, expect, it, vi } from "vitest";
import { apiClient } from "../api/http";
import { attendance, session } from "../test/checkinFixtures";
import ActivityCheckinView from "./ActivityCheckinView.vue";

const mock = new MockAdapter(apiClient);
const wrappers: ReturnType<typeof mount>[] = [];
beforeEach(() => {
  vi.useFakeTimers({ toFake: ["Date", "setInterval", "clearInterval"] });
  vi.setSystemTime(new Date("2026-10-11T02:00:00Z"));
  mock.onGet("/api/v1/activities/10/check-in-sessions/current").reply(200, { data: { session, attendance } });
});
afterEach(() => {
  wrappers.splice(0).forEach(w => w.unmount()); mock.reset(); vi.useRealTimers();
});
async function open() {
  const w = mount(ActivityCheckinView, { props: { id: "10" }, global: { stubs: { RouterLink: true } } });
  wrappers.push(w); await flushPromises(); return w;
}
describe("ActivityCheckinView", () => {
  it("derives remaining time from the server UTC closesAt and disables at exact expiry", async () => {
    const w = await open();
    await w.get('[data-test="code"]').setValue("abc");
    expect(w.get('[data-test="countdown"]').text()).toContain("01:00");
    await vi.advanceTimersByTimeAsync(59000);
    expect(w.get('[data-test="countdown"]').text()).toContain("00:01");
    await vi.advanceTimersByTimeAsync(1000);
    expect(w.get('[data-test="submit-checkin"]').attributes("disabled")).toBeDefined();
    expect(w.text()).toContain("签到已关闭");
  });
  it("normalizes code, blocks concurrent submissions, and reloads persisted attendance", async () => {
    let finish!: (value: [number, unknown]) => void;
    mock.onPost("/api/v1/check-in-sessions/70/check-ins").reply(() => new Promise(resolve => { finish = resolve; }));
    const w = await open();
    await w.get('[data-test="code"]').setValue("  aBc123  ");
    await w.get("form").trigger("submit"); await flushPromises();
    await w.get("form").trigger("submit");
    expect(mock.history.post).toHaveLength(1);
    expect(JSON.parse(mock.history.post[0]!.data)).toEqual({ code: "ABC123" });
    expect(w.get('[data-test="submit-checkin"]').attributes("disabled")).toBeDefined();
    mock.onGet("/api/v1/activities/10/check-in-sessions/current").reply(200, { data: {
      session: null, attendance: { ...attendance, attendanceStatus: "CHECKED_IN", recordId: "90", checkedInAt: "2026-10-11T02:00:10" },
    } });
    finish([201, { data: {} }]); await flushPromises();
    expect(w.text()).toContain("已签到");
    expect(w.find('[data-test="code"]').exists()).toBe(false);
    w.unmount();
    const fresh = await open(); expect(fresh.text()).toContain("已签到");
  });
  it.each([
    ["INVALID_CHECKIN_CODE", "签到码无效"], ["CHECKIN_SESSION_NOT_OPEN", "签到已关闭"],
    ["REGISTRATION_NOT_CONFIRMED", "尚未录取"], ["ALREADY_CHECKED_IN", "已签到，请勿重复"],
  ])("renders distinct %s errors without echoing the code", async (code, message) => {
    mock.onPost("/api/v1/check-in-sessions/70/check-ins").reply(422, { code, detail: "secret-echo" });
    const w = await open(); await w.get('[data-test="code"]').setValue("secret-echo");
    await w.get("form").trigger("submit"); await flushPromises();
    expect(w.get('[role="alert"]').text()).toContain(message);
    expect(w.get('[role="alert"]').text()).not.toContain("secret-echo");
  });
  it("does not offer self check-in to an ineligible member", async () => {
    mock.onGet("/api/v1/activities/10/check-in-sessions/current").reply(200, { data: { session, attendance: null } });
    const w = await open(); expect(w.text()).toContain("尚未录取"); expect(w.find("form").exists()).toBe(false);
  });
  it("isolates an old load after the activity changes", async () => {
    let finish!: (value: [number, unknown]) => void;
    mock.onGet("/api/v1/activities/10/check-in-sessions/current").reply(() => new Promise(resolve => { finish = resolve; }));
    const w = mount(ActivityCheckinView, { props: { id: "10" }, global: { stubs: { RouterLink: true } } }); wrappers.push(w);
    await flushPromises();
    mock.onGet("/api/v1/activities/11/check-in-sessions/current").reply(200, { data: { session: null, attendance: null } });
    await w.setProps({ id: "11" }); await flushPromises();
    finish([200, { data: { session, attendance } }]); await flushPromises();
    expect(w.find("form").exists()).toBe(false); expect(w.text()).toContain("尚未录取");
  });
  it("ignores a late submission completion and clears typed code on route change", async () => {
    let finish!: (value: [number, unknown]) => void;
    mock.onPost("/api/v1/check-in-sessions/70/check-ins").reply(() => new Promise(resolve => { finish = resolve; }));
    const w = await open(); await w.get('[data-test="code"]').setValue("oldcode"); await w.get("form").trigger("submit"); await flushPromises();
    mock.onGet("/api/v1/activities/11/check-in-sessions/current").reply(200, { data: { session: null, attendance: null } });
    await w.setProps({ id: "11" }); await flushPromises(); finish([201, { data: {} }]); await flushPromises();
    expect(w.text()).not.toContain("签到成功"); expect(mock.history.get.filter(r => r.url?.includes("/11/"))).toHaveLength(1);
  });
  it("a late old submit cannot unlock a new activity's pending submission", async () => {
    let finishOld!: (value: [number, unknown]) => void;
    let finishNew!: (value: [number, unknown]) => void;
    mock.onPost("/api/v1/check-in-sessions/70/check-ins").reply(() => new Promise(resolve => { finishOld = resolve; }));
    const w = await open(); await w.get('[data-test="code"]').setValue("first"); await w.get("form").trigger("submit"); await flushPromises();
    mock.onGet("/api/v1/activities/11/check-in-sessions/current").reply(200, { data: { session: { ...session, sessionId: "80", activityId: "11" }, attendance } });
    mock.onPost("/api/v1/check-in-sessions/80/check-ins").reply(() => new Promise(resolve => { finishNew = resolve; }));
    await w.setProps({ id: "11" }); await flushPromises(); await w.get('[data-test="code"]').setValue("second"); await w.get("form").trigger("submit"); await flushPromises();
    finishOld([201, { data: {} }]); await flushPromises();
    expect(w.get('[data-test="submit-checkin"]').attributes("disabled")).toBeDefined();
    await w.get("form").trigger("submit"); expect(mock.history.post).toHaveLength(2);
    finishNew([201, { data: {} }]); await flushPromises();
  });
});
