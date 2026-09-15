import { mount } from "@vue/test-utils";
import { describe, expect, it, vi } from "vitest";
import ActivityListView from "./ActivityListView.vue";

vi.mock("../api/activities", () => ({
  listActivities: vi.fn().mockResolvedValue([
    {
      activity: {
        id: 1,
        title: "新生开学典礼",
        location: "图书馆前广场",
        activityStartAt: "2026-09-20T08:00:00",
      },
      positions: [],
    },
  ]),
}));

describe("ActivityListView", () => {
  it("renders activities returned by the backend", async () => {
    const wrapper = mount(ActivityListView, {
      props: { organizationId: "100" },
    });
    await new Promise((resolve) => setTimeout(resolve, 0));
    expect(wrapper.text()).toContain("新生开学典礼");
    expect(wrapper.text()).toContain("图书馆前广场");
  });
});
