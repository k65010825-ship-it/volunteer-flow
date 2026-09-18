<script setup lang="ts">
import { computed } from "vue";
import { useRoute, useRouter } from "vue-router";
import { useAuthStore } from "../stores/auth";
const route = useRoute();
const router = useRouter();
const auth = useAuthStore();
const publicPage = computed(() => Boolean(route.meta.public));
async function logout() {
  await auth.logout();
  await router.replace("/login");
}
</script>
<template>
  <div class="app-shell">
    <header class="topbar">
      <RouterLink class="brand" to="/activities">VolunteerFlow</RouterLink
      ><button v-if="!publicPage" class="logout-button" @click="logout">
        退出
      </button>
    </header>
    <main class="main"><slot /></main>
    <nav v-if="!publicPage" class="bottom-nav" aria-label="主导航">
      <RouterLink to="/activities"
        ><svg viewBox="0 0 24 24" aria-hidden="true">
          <path d="M4 5.5h16v13H4zM8 3v5M16 3v5M4 10h16" /></svg
        >活动</RouterLink
      ><RouterLink to="/registrations"
        ><svg viewBox="0 0 24 24" aria-hidden="true">
          <circle cx="12" cy="8" r="4" />
          <path d="M4.5 21a7.5 7.5 0 0 1 15 0" /></svg
        >我的报名</RouterLink
      ><a aria-disabled="true"
        ><svg viewBox="0 0 24 24" aria-hidden="true">
          <path
            d="M18 9a6 6 0 0 0-12 0c0 7-3 7-3 9h18c0-2-3-2-3-9M10 21h4"
          /></svg
        >通知</a
      ><a aria-disabled="true"
        ><svg viewBox="0 0 24 24" aria-hidden="true">
          <path d="M5 4h14v16H5zM8 8h8M8 12h8M8 16h5" /></svg
        >我的资料</a
      >
    </nav>
  </div>
</template>
