import { defineStore } from "pinia";
import { ref } from "vue";
import * as notificationApi from "../api/notifications";

const POLLING_INTERVAL_MS = 30_000;

export const useNotificationStore = defineStore("notifications", () => {
  const unreadCount = ref(0);
  let timer: ReturnType<typeof setInterval> | undefined;
  let generation = 0;

  async function refreshUnreadCount() {
    const requestGeneration = generation;
    const count = await notificationApi.getUnreadCount();
    if (requestGeneration === generation) unreadCount.value = count;
  }

  function refreshWhenVisible() {
    if (!document.hidden) void refreshUnreadCount().catch(() => undefined);
  }

  function stopPolling() {
    generation += 1;
    if (timer !== undefined) clearInterval(timer);
    timer = undefined;
    document.removeEventListener("visibilitychange", refreshWhenVisible);
    window.removeEventListener("volunteerflow:session-expired", stopPolling);
  }

  function startPolling() {
    stopPolling();
    document.addEventListener("visibilitychange", refreshWhenVisible);
    window.addEventListener("volunteerflow:session-expired", stopPolling);
    refreshWhenVisible();
    timer = setInterval(refreshWhenVisible, POLLING_INTERVAL_MS);
  }

  return { unreadCount, refreshUnreadCount, startPolling, stopPolling };
});
