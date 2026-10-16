import { flushPromises, mount } from "@vue/test-utils";
import MockAdapter from "axios-mock-adapter";
import { afterEach, beforeEach, describe, expect, it, vi } from "vitest";
import { apiClient } from "../api/http";
import { attendance, member, session, summary } from "../test/checkinFixtures";
import CheckinManagementView from "./CheckinManagementView.vue";

const mock = new MockAdapter(apiClient);
const wrappers: ReturnType<typeof mount>[] = [];
const opened = { sessionId: "71", activityId: "10", opensAt: session.opensAt, closesAt: "2026-10-11T02:15:00", code: "ONE-TIME" };
beforeEach(() => {
  vi.useFakeTimers({ toFake: ["Date", "setInterval", "clearInterval"] }); vi.setSystemTime(new Date("2026-10-11T02:00:00Z"));
  mock.onGet("/api/v1/activities/10/attendance-summary").reply(200, { data: summary });
  mock.onGet("/api/v1/activities/10/check-in-sessions/current").reply(200, { data: { session: null, attendance: null } });
  mock.onGet("/api/v1/positions/20/registrations").reply(200, { data: { items: [member], total: "1", page: "1", size: "100" } });
  mock.onPost("/api/v1/activities/10/check-in-sessions").reply(201, { data: opened });
});
afterEach(() => { wrappers.splice(0).forEach(w => w.unmount()); mock.reset(); vi.useRealTimers(); vi.unstubAllGlobals(); });
async function open() {
  const w = mount(CheckinManagementView, { props: { id: "10" }, global: { stubs: { RouterLink: true } } });
  wrappers.push(w); await flushPromises(); return w;
}
describe("CheckinManagementView", () => {
  it("defaults duration to 15 and rejects outside integer bounds before any request", async () => {
    const w = await open(); expect((w.get('[data-test="duration"]').element as HTMLInputElement).value).toBe("15");
    for (const value of ["4", "121", "5.5", ""]) {
      await w.get('[data-test="duration"]').setValue(value); await w.get('[data-test="open-session"]').trigger("click");
      expect(w.text()).toContain("5–120");
    }
    expect(mock.history.post).toHaveLength(0);
  });
  it.each([5, 120])("accepts duration boundary %s", async duration => {
    const w = await open(); await w.get('[data-test="duration"]').setValue(String(duration));
    await w.get('[data-test="open-session"]').trigger("click"); await flushPromises();
    expect(JSON.parse(mock.history.post[0]!.data)).toEqual({
      durationMinutes: duration,
      sessionType: "DYNAMIC_QR",
    });
    expect(w.get('[data-test="plaintext-code"]').text()).toBe("ONE-TIME");
  });
  it("warns once, copies on explicit action, clears the code immediately on early closure", async () => {
    const copy = vi.fn().mockResolvedValue(undefined); vi.stubGlobal("navigator", { clipboard: { writeText: copy } });
    const storage = vi.spyOn(Storage.prototype, "setItem");
    const w = await open(); await w.get('[data-test="open-session"]').trigger("click"); await flushPromises();
    expect(w.text()).toContain("仅本次显示");
    await w.get('[data-test="copy-code"]').trigger("click"); await flushPromises();
    expect(copy).toHaveBeenCalledWith("ONE-TIME"); expect(w.text()).toContain("已复制"); expect(storage).not.toHaveBeenCalled(); storage.mockRestore();
    let finish!: (value: [number, unknown]) => void;
    mock.onPost("/api/v1/check-in-sessions/71/closure").reply(() => new Promise(resolve => { finish = resolve; }));
    await w.get('[data-test="close-session"]').trigger("click"); await flushPromises();
    expect(w.find('[data-test="plaintext-code"]').exists()).toBe(false);
    finish([200, { data: { ...session, sessionId: "71", status: "CLOSED" } }]); await flushPromises();
    expect(w.text()).toContain("已关闭");
  });
  it("never reconstructs a code from current or summary responses", async () => {
    mock.onGet("/api/v1/activities/10/check-in-sessions/current").reply(200, { data: { session: { ...session, code: "LEAK" }, attendance } });
    mock.onGet("/api/v1/activities/10/attendance-summary").reply(200, { data: { ...summary, correctionSession: { ...session, code: "LEAK" } } });
    const w = await open(); expect(w.find('[data-test="plaintext-code"]').exists()).toBe(false); expect(w.text()).not.toContain("LEAK");
    expect(w.find('[data-test="close-session"]').exists()).toBe(true);
  });
  it("clears a prior one-time code when a new open attempt fails", async () => {
    const w = await open(); await w.get('[data-test="open-session"]').trigger("click"); await flushPromises();
    await vi.advanceTimersByTimeAsync(15 * 60000);
    mock.onPost("/api/v1/activities/10/check-in-sessions").reply(409, { code: "CHECKIN_SESSION_ALREADY_OPEN" });
    await w.get('[data-test="open-session"]').trigger("click"); await flushPromises();
    expect(w.find('[data-test="plaintext-code"]').exists()).toBe(false); expect(w.get('[role="alert"]').text()).toContain("已开启");
  });
  it("searches members and sends a required trimmed reason using historical correctionSession for NO_SHOW", async () => {
    mock.onGet("/api/v1/activities/10/attendance-summary").reply(200, { data: { ...summary, activityStatus: "FINISHED", finalStatistics: true,
      correctionSession: { ...session, status: "EXPIRED" } } });
    mock.onPost("/api/v1/check-in-sessions/70/manual-check-ins").reply(201, { data: {} });
    const w = await open(); expect(w.text()).toContain("未到场");
    await w.get('[data-test="member-search"]').setValue("不存在"); expect(w.find('[data-test="member-30"]').exists()).toBe(false);
    await w.get('[data-test="member-search"]').setValue("2026001"); expect(w.find('[data-test="member-30"]').exists()).toBe(true);
    await w.get('[data-test="manual-30"]').trigger("click"); expect(w.text()).toContain("补签原因"); expect(mock.history.post).toHaveLength(0);
    await w.get('[data-test="reason-30"]').setValue("   已核实到场   "); await w.get('[data-test="manual-30"]').trigger("click"); await flushPromises();
    expect(JSON.parse(mock.history.post[0]!.data)).toEqual({ registrationId: "30", reason: "已核实到场" });
    expect(w.text()).toContain("补签成功");
  });
  it("disables manual correction with an explanation when correctionSession is null", async () => {
    mock.onGet("/api/v1/activities/10/attendance-summary").reply(200, { data: { ...summary, correctionSession: null } });
    const w = await open(); expect(w.get('[data-test="manual-30"]').attributes("disabled")).toBeDefined();
    expect(w.text()).toContain("尚无签到会话，无法补签");
  });
  it("shows aggregate totals, position totals, fractional percentage and final/preliminary status", async () => {
    const w = await open(); const totals = w.get('[data-test="attendance-totals"]').text();
    expect(totals).toContain("已录取 3"); expect(totals).toContain("已签到 1"); expect(totals).toContain("未到场 1"); expect(totals).toContain("待签到 1"); expect(totals).toContain("33.33%");
    expect(w.get('[data-test="position-20"]').text()).toContain("迎新"); expect(w.text()).toContain("实时统计");
  });
  it("searches subsequent pages instead of silently limiting search to the first 100", async () => {
    mock.onGet("/api/v1/positions/20/registrations").reply(config => [200, { data: { items: config.params.page === 1 ? Array.from({ length: 100 }, (_, i) => ({ ...member, registrationId: String(100 + i), studentNumber: "other" })) : [member], total: "101", page: String(config.params.page), size: "100" } }]);
    const w = await open(); await w.get('[data-test="member-search"]').setValue("2026001");
    expect(w.find('[data-test="member-30"]').exists()).toBe(true); expect(mock.history.get.filter(r => r.url?.includes("positions"))).toHaveLength(2);
  });
  it("explains additional review permission without losing summary or session controls", async () => {
    mock.onGet("/api/v1/positions/20/registrations").reply(403, { code: "PERMISSION_DENIED" });
    const w = await open(); expect(w.text()).toContain("registration:review"); expect(w.find('[data-test="attendance-totals"]').exists()).toBe(true);
  });
  it("isolates old summary/current/member responses after route changes", async () => {
    let finish!: (value: [number, unknown]) => void;
    mock.onGet("/api/v1/activities/10/attendance-summary").reply(() => new Promise(resolve => { finish = resolve; }));
    const w = mount(CheckinManagementView, { props: { id: "10" }, global: { stubs: { RouterLink: true } } }); wrappers.push(w); await flushPromises();
    mock.onGet("/api/v1/activities/11/attendance-summary").reply(200, { data: { ...summary, activityId: "11", positions: [], correctionSession: null, confirmedCount: "0" } });
    mock.onGet("/api/v1/activities/11/check-in-sessions/current").reply(200, { data: { session: null, attendance: null } });
    await w.setProps({ id: "11" }); await flushPromises(); finish([200, { data: summary }]); await flushPromises();
    expect(w.get('[data-test="attendance-totals"]').text()).toContain("已录取 0"); expect(w.find('[data-test="member-30"]').exists()).toBe(false);
    expect(mock.history.get.some(r => r.url?.includes("positions"))).toBe(false);
  });
  it("discards a late open result on navigation and unmount", async () => {
    let finish!: (value: [number, unknown]) => void;
    mock.onPost("/api/v1/activities/10/check-in-sessions").reply(() => new Promise(resolve => { finish = resolve; }));
    const w = await open(); await w.get('[data-test="open-session"]').trigger("click"); await flushPromises();
    mock.onGet("/api/v1/activities/11/attendance-summary").reply(200, { data: { ...summary, activityId: "11", positions: [] } });
    mock.onGet("/api/v1/activities/11/check-in-sessions/current").reply(200, { data: { session: null, attendance: null } });
    await w.setProps({ id: "11" }); await flushPromises(); finish([201, { data: opened }]); await flushPromises();
    expect(w.text()).not.toContain("ONE-TIME"); expect(w.find('[data-test="plaintext-code"]').exists()).toBe(false);
    w.unmount();
  });
  it("a late old close cannot unlock a new activity's pending open", async () => {
    let finishOld!: (value: [number, unknown]) => void;
    let finishNew!: (value: [number, unknown]) => void;
    mock.onGet("/api/v1/activities/10/check-in-sessions/current").reply(200, { data: { session, attendance: null } });
    mock.onPost("/api/v1/check-in-sessions/70/closure").reply(() => new Promise(resolve => { finishOld = resolve; }));
    const w = await open(); await w.get('[data-test="close-session"]').trigger("click"); await flushPromises();
    mock.onGet("/api/v1/activities/11/attendance-summary").reply(200, { data: { ...summary, activityId: "11", positions: [] } });
    mock.onGet("/api/v1/activities/11/check-in-sessions/current").reply(200, { data: { session: null, attendance: null } });
    mock.onPost("/api/v1/activities/11/check-in-sessions").reply(() => new Promise(resolve => { finishNew = resolve; }));
    await w.setProps({ id: "11" }); await flushPromises(); await w.get('[data-test="open-session"]').trigger("click"); await flushPromises();
    finishOld([200, { data: { ...session, status: "CLOSED" } }]); await flushPromises();
    expect(w.get('[data-test="open-session"]').attributes("disabled")).toBeDefined();
    await w.get('[data-test="open-session"]').trigger("click"); expect(mock.history.post).toHaveLength(2);
    finishNew([201, { data: { ...opened, activityId: "11" } }]); await flushPromises();
  });
  it("clears a displayed code on route change and releases it on unmount", async () => {
    const w = await open(); await w.get('[data-test="open-session"]').trigger("click"); await flushPromises();
    const state = w.vm as unknown as { plaintextCode: string };
    expect(state.plaintextCode).toBe("ONE-TIME");
    mock.onGet("/api/v1/activities/11/attendance-summary").reply(200, { data: { ...summary, activityId: "11", positions: [] } });
    mock.onGet("/api/v1/activities/11/check-in-sessions/current").reply(200, { data: { session: null, attendance: null } });
    await w.setProps({ id: "11" }); await flushPromises(); expect(state.plaintextCode).toBe("");
    mock.onPost("/api/v1/activities/11/check-in-sessions").reply(201, { data: { ...opened, activityId: "11" } });
    await w.get('[data-test="open-session"]').trigger("click"); await flushPromises(); expect(state.plaintextCode).toBe("ONE-TIME");
    w.unmount(); expect(state.plaintextCode).toBe("");
  });
  it("does not accept a pending open result after unmount", async () => {
    let finish!: (value: [number, unknown]) => void;
    mock.onPost("/api/v1/activities/10/check-in-sessions").reply(() => new Promise(resolve => { finish = resolve; }));
    const w = await open(); const state = w.vm as unknown as { plaintextCode: string };
    await w.get('[data-test="open-session"]').trigger("click"); await flushPromises(); w.unmount();
    finish([201, { data: opened }]); await flushPromises(); expect(state.plaintextCode).toBe("");
  });
  it.each(["current", "members"])("ignores an old %s read after route change", async target => {
    let finish!: (value: [number, unknown]) => void;
    const url = target === "current" ? "/api/v1/activities/10/check-in-sessions/current" : "/api/v1/positions/20/registrations";
    mock.onGet(url).reply(() => new Promise(resolve => { finish = resolve; }));
    const w = await open();
    mock.onGet("/api/v1/activities/11/attendance-summary").reply(200, { data: { ...summary, activityId: "11", positions: [], correctionSession: null } });
    mock.onGet("/api/v1/activities/11/check-in-sessions/current").reply(200, { data: { session: null, attendance: null } });
    await w.setProps({ id: "11" }); await flushPromises();
    finish([200, { data: target === "current" ? { session, attendance: null } : { items: [member], total: "1" } }]); await flushPromises();
    expect(w.find('[data-test="close-session"]').exists()).toBe(false); expect(w.find('[data-test="member-30"]').exists()).toBe(false);
  });
  it("blocks duplicate manual corrections and ignores their late completion on another activity", async () => {
    let finish!: (value: [number, unknown]) => void;
    mock.onPost("/api/v1/check-in-sessions/70/manual-check-ins").reply(() => new Promise(resolve => { finish = resolve; }));
    const w = await open(); await w.get('[data-test="reason-30"]').setValue("到场确认");
    await w.get('[data-test="manual-30"]').trigger("click"); await flushPromises(); await w.get('[data-test="manual-30"]').trigger("click");
    expect(mock.history.post).toHaveLength(1);
    mock.onGet("/api/v1/activities/11/attendance-summary").reply(200, { data: { ...summary, activityId: "11", positions: [] } });
    mock.onGet("/api/v1/activities/11/check-in-sessions/current").reply(200, { data: { session: null, attendance: null } });
    await w.setProps({ id: "11" }); await flushPromises(); finish([201, { data: {} }]); await flushPromises();
    expect(w.text()).not.toContain("补签成功"); expect(mock.history.get.filter(r => r.url === "/api/v1/activities/11/attendance-summary")).toHaveLength(1);
  });
  it("disables correction for already checked-in members", async () => {
    mock.onGet("/api/v1/positions/20/registrations").reply(200, { data: { items: [{ ...member, attendanceStatus: "CHECKED_IN" }], total: "1" } });
    const w = await open(); expect(w.get('[data-test="manual-30"]').attributes("disabled")).toBeDefined();
  });
  it("does not expose controls when the protected summary is forbidden", async () => {
    mock.onGet("/api/v1/activities/10/attendance-summary").reply(403, { code: "PERMISSION_DENIED" });
    const w = await open(); expect(w.get('[role="alert"]').text()).toContain("权限"); expect(w.find('[data-test="open-session"]').exists()).toBe(false);
    expect(mock.history.get.some(r => r.url?.includes("positions"))).toBe(false);
  });
  it.each([403, 500])("keeps refresh usable after navigation while an old member read finishes and destination summary fails with %s", async status => {
    let finishOld!: (value: [number, unknown]) => void;
    mock.onGet("/api/v1/positions/20/registrations").reply(() => new Promise(resolve => { finishOld = resolve; }));
    const w = await open();
    const refresh = () => w.findAll("button").find(button => button.text() === "刷新管理数据")!;
    expect(refresh().attributes("disabled")).toBeDefined();
    mock.onGet("/api/v1/activities/11/attendance-summary").reply(status, { code: "PERMISSION_DENIED" });
    await w.setProps({ id: "11" }); await flushPromises();
    finishOld([200, { data: { items: [member], total: "1" } }]); await flushPromises();
    expect(w.find('[data-test="member-30"]').exists()).toBe(false);
    expect(refresh().attributes("disabled")).toBeUndefined();
    mock.onGet("/api/v1/activities/11/attendance-summary").reply(200, { data: { ...summary, activityId: "11", positions: [], correctionSession: null, confirmedCount: "0" } });
    mock.onGet("/api/v1/activities/11/check-in-sessions/current").reply(200, { data: { session: null, attendance: null } });
    await refresh().trigger("click"); await flushPromises();
    expect(w.get('[data-test="attendance-totals"]').text()).toContain("已录取 0");
    expect(mock.history.get.filter(request => request.url === "/api/v1/activities/11/attendance-summary")).toHaveLength(2);
  });
  it("reports clipboard denial without losing the one-time code", async () => {
    vi.stubGlobal("navigator", { clipboard: { writeText: vi.fn().mockRejectedValue(new Error("denied")) } });
    const w = await open(); await w.get('[data-test="open-session"]').trigger("click"); await flushPromises();
    await w.get('[data-test="copy-code"]').trigger("click"); await flushPromises();
    expect(w.text()).toContain("复制失败"); expect(w.get('[data-test="plaintext-code"]').text()).toBe("ONE-TIME");
  });
});
