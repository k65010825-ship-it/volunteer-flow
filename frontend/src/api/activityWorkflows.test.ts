import MockAdapter from "axios-mock-adapter";
import { afterEach, describe, expect, it } from "vitest";
import { apiClient } from "./http";
import {
  listActivities,
  listActivitiesPage,
  createActivity,
  updateActivity,
  publishActivity,
  cancelActivity,
  completeActivity,
  listActivityQuestions,
  listPositionQuestions,
  createActivityQuestion,
  updateActivityQuestion,
  deleteActivityQuestion,
} from "./activities";
const mock = new MockAdapter(apiClient);
afterEach(() => mock.reset());
describe("activity workflow API contracts", () => {
  it("preserves the array reader while exposing pagination metadata", async () => {
    mock
      .onGet("/api/v1/organizations/1/activities")
      .reply(200, { data: [], meta: { total: 41, page: 2, size: 20 } });
    expect(await listActivities("1")).toEqual([]);
    expect(await listActivitiesPage("1", 2, 20)).toEqual({
      data: [],
      meta: { total: 41, page: 2, size: 20 },
    });
    expect(mock.history.get[1].params).toEqual({ page: 2, size: 20 });
  });
  it("uses real draft and lifecycle endpoints", async () => {
    const body = {
      title: "服务",
      description: "说明",
      location: "礼堂",
      registrationStartAt: "2026-11-01T08:00",
      registrationEndAt: "2026-11-02T08:00",
      activityStartAt: "2026-11-03T08:00",
      activityEndAt: "2026-11-03T12:00",
    };
    mock.onAny().reply(200, { data: { id: "10" } });
    await createActivity("1", body);
    await updateActivity("10", body);
    await publishActivity("10");
    await cancelActivity("10", "天气");
    await completeActivity("10", "任务完成");
    expect(mock.history.post.map((v) => v.url)).toEqual([
      "/api/v1/organizations/1/activities",
      "/api/v1/activities/10/publication",
      "/api/v1/activities/10/cancellation",
      "/api/v1/activities/10/completion",
    ]);
    expect(JSON.parse(mock.history.post[2].data)).toEqual({ reason: "天气" });
    expect(mock.history.put[0].url).toBe("/api/v1/activities/10");
  });
  it("reads complete questions and manages common questions via persisted IDs", async () => {
    mock.onGet(/\/questions$/).reply(200, { data: [] });
    mock.onPost(/\/questions$/).reply(201, { data: { id: "30" } });
    mock.onPut(/\/questions\/30$/).reply(200, { data: { id: "30" } });
    mock.onDelete(/\/questions\/30$/).reply(204);
    await listActivityQuestions("10");
    await listPositionQuestions("20");
    const body = {
      type: "TEXT" as const,
      title: "姓名",
      required: true,
      options: null,
      sortOrder: 1,
    };
    expect(await createActivityQuestion("10", body)).toEqual({ id: "30" });
    await updateActivityQuestion("10", "30", body);
    await deleteActivityQuestion("10", "30");
    expect(mock.history.get.map((v) => v.url)).toEqual([
      "/api/v1/activities/10/questions",
      "/api/v1/positions/20/questions",
    ]);
    expect(mock.history.delete[0].url).toBe(
      "/api/v1/activities/10/questions/30",
    );
  });
});
