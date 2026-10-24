import MockAdapter from "axios-mock-adapter";
import { afterEach, expect, it } from "vitest";
import { apiClient } from "./http";
import { getUnreadCount } from "./notifications";
const mock = new MockAdapter(apiClient);
afterEach(() => mock.reset());
it("normalizes serialized Long zero so the empty unread badge stays hidden", async () => {
  mock
    .onGet("/api/v1/users/me/notifications/unread-count")
    .reply(200, { data: { unreadCount: "0" } });
  expect(await getUnreadCount()).toBe(0);
});
