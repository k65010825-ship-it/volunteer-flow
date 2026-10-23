import { mount, flushPromises } from "@vue/test-utils";
import MockAdapter from "axios-mock-adapter";
import { afterEach, beforeEach, describe, expect, it } from "vitest";
import { apiClient } from "../api/http";
import PublishedActivityManagementView from "./PublishedActivityManagementView.vue";
const mock = new MockAdapter(apiClient);
const wrappers: ReturnType<typeof mount>[] = [];
const activity = {
  id: "10",
  organizationId: "1",
  title: "服务",
  description: "说明",
  location: "礼堂",
  registrationStartAt: "2026-11-01T08:00",
  registrationEndAt: "2026-11-02T08:00",
  activityStartAt: "2026-11-03T08:00",
  activityEndAt: "2026-11-03T12:00",
  status: "PUBLISHED",
};
beforeEach(() => {
  mock
    .onGet("/api/v1/activities/10/management")
    .reply(200, { data: { activity, positions: [], commonQuestionCount: 0 } });
  mock
    .onGet("/api/v1/organizations/1/access")
    .reply(200, {
      data: { permissionCodes: ["activity:change", "activity:cancel"] },
    });
});
afterEach(() => {
  wrappers.splice(0).forEach((w) => w.unmount());
  mock.reset();
});
async function open() {
  const w = mount(PublishedActivityManagementView, {
    props: { id: "10" },
    global: { stubs: { RouterLink: { template: "<a><slot /></a>" } } },
  });
  wrappers.push(w);
  await flushPromises();
  return w;
}
describe("activity lifecycle management", () => {
  it.each([
    ["cancel", "cancellation", "CANCELED"],
    ["complete", "completion", "FINISHED"],
  ])(
    "confirms %s with a reason and disables terminal changes",
    async (action, path, status) => {
      mock
        .onPost(`/api/v1/activities/10/${path}`)
        .reply(200, { data: { ...activity, status } });
      const w = await open();
      await w.find(`[data-test="${action}-activity"]`).trigger("click");
      expect(mock.history.post).toHaveLength(0);
      expect(
        w.find('[data-test="submit-lifecycle"]').attributes("disabled"),
      ).toBeDefined();
      await w.find('[data-test="lifecycle-reason"]').setValue("天气原因");
      await w.find('[data-test="submit-lifecycle"]').trigger("click");
      await flushPromises();
      expect(JSON.parse(mock.history.post[0].data)).toEqual({
        reason: "天气原因",
      });
      expect(w.find('[data-test="complete-activity"]').exists()).toBe(false);
      expect(w.find("fieldset").attributes("disabled")).toBeDefined();
    },
  );
  it("does not offer cancel without cancel permission", async () => {
    mock
      .onGet("/api/v1/organizations/1/access")
      .reply(200, { data: { permissionCodes: ["activity:change"] } });
    const w = await open();
    expect(w.find('[data-test="cancel-activity"]').exists()).toBe(false);
  });
});
