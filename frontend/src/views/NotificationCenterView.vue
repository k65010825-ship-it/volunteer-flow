<script setup lang="ts">
import { computed, onMounted, ref } from "vue";
import { useRouter } from "vue-router";
import {
  listNotifications,
  markAllNotificationsRead,
  markNotificationRead,
  type UserNotification,
} from "../api/notifications";
import { useNotificationStore } from "../stores/notifications";

const PAGE_SIZE = 20;
const router = useRouter();
const notifications = ref<UserNotification[]>([]);
const nextCursor = ref<string | null>(null);
const hasMore = ref(false);
const loading = ref(true);
const loadingMore = ref(false);
const readingAll = ref(false);
const readingId = ref<string | null>(null);
const error = ref("");
const store = useNotificationStore();
let readAllCompletedAt: Date | null = null;

const canReadAll = computed(
  () => notifications.value.some((notification) => !notification.readAt) && !readingAll.value,
);

function applyReadAllState(items: UserNotification[]) {
  const completedAt = readAllCompletedAt;
  if (!completedAt) return items;
  return items.map((notification) => {
    const createdAt = Date.parse(notification.createTime);
    if (
      notification.readAt ||
      !Number.isFinite(createdAt) ||
      createdAt > completedAt.getTime()
    )
      return notification;
    return { ...notification, readAt: completedAt.toISOString() };
  });
}

async function load(reset = false) {
  if (reset) {
    loading.value = true;
    error.value = "";
  } else {
    if (!hasMore.value || !nextCursor.value || loadingMore.value) return;
    loadingMore.value = true;
  }
  try {
    const page = await listNotifications({
      cursor: reset ? undefined : nextCursor.value ?? undefined,
      size: PAGE_SIZE,
    });
    const items = applyReadAllState(page.items);
    notifications.value = reset ? items : [...notifications.value, ...items];
    nextCursor.value = page.nextCursor;
    hasMore.value = page.hasMore;
  } catch {
    error.value = "通知加载失败，请检查网络后重试。";
  } finally {
    loading.value = false;
    loadingMore.value = false;
  }
}

function destination(notification: UserNotification) {
  if (!notification.businessId) return null;
  if (notification.businessType === "REGISTRATION")
    return `/registrations/${notification.businessId}`;
  if (notification.businessType === "ACTIVITY")
    return `/activities/${notification.businessId}`;
  if (notification.businessType === "PROMOTION_OFFER") return "/registrations";
  return null;
}

async function openNotification(notification: UserNotification) {
  if (readingId.value || readingAll.value) return;
  readingId.value = notification.id;
  try {
    if (!notification.readAt) {
      await markNotificationRead(notification.id);
      notification.readAt = new Date().toISOString();
      void store.refreshUnreadCount().catch(() => undefined);
    }
    const target = destination(notification);
    if (target) await router.push(target);
  } catch {
    error.value = "标记通知已读失败，请稍后重试。";
  } finally {
    readingId.value = null;
  }
}

async function readAll() {
  if (!canReadAll.value) return;
  readingAll.value = true;
  try {
    await markAllNotificationsRead();
    readAllCompletedAt = new Date();
    const readAt = readAllCompletedAt.toISOString();
    notifications.value.forEach((notification) => {
      notification.readAt ??= readAt;
    });
    await store.refreshUnreadCount();
  } catch {
    error.value = "全部标记已读失败，请稍后重试。";
  } finally {
    readingAll.value = false;
  }
}

onMounted(() => {
  void load(true);
});
</script>

<template>
  <section class="page notification-page">
    <div class="page-heading">
      <div>
        <h1>通知</h1>
        <p>报名、候补和活动变更的最新消息。</p>
      </div>
      <button class="secondary" data-test="read-all" :disabled="!canReadAll" @click="readAll">
        {{ readingAll ? "处理中…" : "全部标记已读" }}
      </button>
    </div>
    <p v-if="loading" class="state" role="status">正在加载通知…</p>
    <div v-else-if="error && !notifications.length" class="state" role="alert">
      {{ error }} <button class="secondary" @click="load(true)">重试</button>
    </div>
    <template v-else>
      <p v-if="error" class="error" role="alert">{{ error }}</p>
      <div v-if="!notifications.length" class="state"><strong>暂无通知</strong>新的报名和活动消息会显示在这里。</div>
      <div v-else class="notification-list">
        <button
          v-for="notification in notifications"
          :key="notification.id"
          class="notification-item"
          :class="{ 'notification-read': notification.readAt, 'notification-priority': ['PROMOTION_OFFER', 'ACTIVITY_CANCELED'].includes(notification.type) }"
          :disabled="Boolean(readingId) || readingAll"
          data-test="notification-item"
          @click="openNotification(notification)"
        >
          <span class="notification-title">{{ notification.title }}</span>
          <span class="notification-content">{{ notification.content }}</span>
          <time :datetime="notification.createTime">{{ notification.createTime.replace("T", " ") }}</time>
          <span v-if="!notification.readAt" class="unread-dot" aria-label="未读"></span>
        </button>
      </div>
      <button v-if="hasMore" class="secondary load-more" data-test="load-more" :disabled="loadingMore" @click="load()">
        {{ loadingMore ? "正在加载…" : "加载更多" }}
      </button>
    </template>
  </section>
</template>
