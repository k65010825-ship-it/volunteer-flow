<script setup lang="ts">
import { computed, onBeforeUnmount, watch } from "vue";
import { useRoute, useRouter } from "vue-router";
import { useAuthStore } from "../stores/auth";
import { useNotificationStore } from "../stores/notifications";
import PageBack from "./PageBack.vue";
import { isNavigationHome } from "../router/backNavigation";
const route = useRoute();
const router = useRouter();
const auth = useAuthStore();
const notifications = useNotificationStore();
const publicPage = computed(() => Boolean(route.meta.public));
const administratorPage = computed(() => route.path.startsWith("/admin"));
const isAdministrator = computed(
  () => auth.user?.platformRole === "PLATFORM_ADMIN",
);
const showBack = computed(() => !isNavigationHome(route.path));
async function logout() {
  notifications.stopPolling();
  await auth.logout();
  await router.replace("/login");
}
watch(
  () => !publicPage.value && !!auth.user,
  (authenticatedPage) => {
    // During initial navigation route metadata is not ready; do not race session restoration.
    if (authenticatedPage) notifications.startPolling();
    else notifications.stopPolling();
  },
  { immediate: true },
);
onBeforeUnmount(notifications.stopPolling);
</script>
<template>
  <div
    class="app-shell"
    :class="{ 'admin-shell': administratorPage, 'public-shell': publicPage }"
  >
    <aside
      v-if="administratorPage"
      class="admin-sidebar"
      aria-label="平台管理员导航"
    >
      <RouterLink class="sidebar-brand" to="/admin/organizations"
        >VolunteerFlow</RouterLink
      >
      <p class="sidebar-label">平台管理</p>
      <RouterLink to="/admin/organizations">组织管理</RouterLink>
      <RouterLink to="/profile">个人资料</RouterLink>
      <RouterLink to="/activities?userView=1">进入用户端</RouterLink>
      <p class="sidebar-note">平台身份与组织角色独立管理。</p>
    </aside>
    <header class="topbar">
      <RouterLink
        class="brand"
        :to="
          administratorPage ? '/admin/organizations' : '/activities?userView=1'
        "
        >VolunteerFlow</RouterLink
      >
      <span v-if="!publicPage" class="account-name">{{
        auth.user?.realName || auth.user?.username
      }}</span>
      <RouterLink
        v-if="!publicPage && isAdministrator && !administratorPage"
        class="admin-entry"
        to="/admin/organizations"
        >平台管理</RouterLink
      >
      <button v-if="!publicPage" class="logout-button" @click="logout">
        退出
      </button>
    </header>
    <main class="main" :class="{ 'has-page-back': showBack }">
      <PageBack v-if="showBack" /><slot />
    </main>
    <nav
      v-if="!publicPage && !administratorPage"
      class="bottom-nav"
      aria-label="主导航"
    >
      <RouterLink to="/activities?userView=1"
        ><svg viewBox="0 0 24 24" aria-hidden="true">
          <path d="M4 5.5h16v13H4zM8 3v5M16 3v5M4 10h16" /></svg
        >活动</RouterLink
      ><RouterLink to="/registrations"
        ><svg viewBox="0 0 24 24" aria-hidden="true">
          <circle cx="12" cy="8" r="4" />
          <path d="M4.5 21a7.5 7.5 0 0 1 15 0" /></svg
        >我的报名</RouterLink
      ><RouterLink to="/notifications" class="notification-nav"
        ><svg viewBox="0 0 24 24" aria-hidden="true">
          <path
            d="M18 9a6 6 0 0 0-12 0c0 7-3 7-3 9h18c0-2-3-2-3-9M10 21h4"
          /></svg
        >通知<span
          v-if="notifications.unreadCount"
          class="notification-badge"
          data-test="notification-badge"
          >{{ notifications.unreadCount }}</span
        ></RouterLink
      ><RouterLink to="/profile"
        ><svg viewBox="0 0 24 24" aria-hidden="true">
          <path d="M5 4h14v16H5zM8 8h8M8 12h8M8 16h5" /></svg
        >我的资料</RouterLink
      >
    </nav>
  </div>
</template>
