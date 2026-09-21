import { flushPromises } from "@vue/test-utils";
import MockAdapter from "axios-mock-adapter";
import { createPinia, setActivePinia } from "pinia";
import { afterEach, beforeEach, describe, expect, it, vi } from "vitest";
import { apiClient } from "../api/http";
import { useNotificationStore } from "./notifications";

const mock = new MockAdapter(apiClient);

function deferredReply() {
  let resolve!: (response: [number, unknown]) => void;
  return {
    reply: new Promise<[number, unknown]>((done) => {
      resolve = done;
    }),
    resolve,
  };
}

beforeEach(() => {
  vi.useFakeTimers();
  setActivePinia(createPinia());
  Object.defineProperty(document, "hidden", { configurable: true, value: false });
});

afterEach(() => {
  useNotificationStore().stopPolling();
  mock.reset();
  vi.useRealTimers();
});

describe("notification polling request lifecycle", () => {
  it("does not write an unread response that resolves after polling stops", async () => {
    const pending = deferredReply();
    mock.onGet("/api/v1/users/me/notifications/unread-count").reply(() => pending.reply);
    const store = useNotificationStore();

    store.startPolling();
    store.stopPolling();
    pending.resolve([200, { data: { unreadCount: 9 } }]);
    await flushPromises();

    expect(store.unreadCount).toBe(0);
  });

  it("keeps a new polling session result when an older request resolves last", async () => {
    const oldRequest = deferredReply();
    const newRequest = deferredReply();
    let calls = 0;
    mock.onGet("/api/v1/users/me/notifications/unread-count").reply(() => {
      calls += 1;
      return calls === 1 ? oldRequest.reply : newRequest.reply;
    });
    const store = useNotificationStore();

    store.startPolling();
    store.stopPolling();
    store.startPolling();
    newRequest.resolve([200, { data: { unreadCount: 7 } }]);
    await flushPromises();
    oldRequest.resolve([200, { data: { unreadCount: 2 } }]);
    await flushPromises();

    expect(store.unreadCount).toBe(7);
  });

  it("stops polling on session expiry and ignores its outstanding response", async () => {
    const pending = deferredReply();
    mock.onGet("/api/v1/users/me/notifications/unread-count").reply(() => pending.reply);
    const store = useNotificationStore();

    store.startPolling();
    window.dispatchEvent(new Event("volunteerflow:session-expired"));
    pending.resolve([200, { data: { unreadCount: 4 } }]);
    await flushPromises();
    document.dispatchEvent(new Event("visibilitychange"));
    await vi.advanceTimersByTimeAsync(30_000);

    expect(store.unreadCount).toBe(0);
    expect(mock.history.get).toHaveLength(1);
  });
});
