<script setup lang="ts">
import { ref } from "vue";
import { useRoute, useRouter } from "vue-router";
import { useAuthStore } from "../stores/auth";
import { backNavigation } from "../router/backNavigation";

const route = useRoute();
const router = useRouter();
const auth = useAuthStore();
const pending = ref(false);

async function goBack() {
  if (pending.value) return;
  const target = backNavigation(
    router.options.history.state.back,
    route.fullPath,
    auth.user?.platformRole === "PLATFORM_ADMIN",
    Boolean(route.meta.public),
  );
  if (target.useHistory) {
    router.back();
    return;
  }
  pending.value = true;
  try {
    await router.replace(target.destination);
  } finally {
    pending.value = false;
  }
}
</script>

<template>
  <div class="page-back">
    <button
      type="button"
      class="page-back-button"
      :disabled="pending"
      @click="goBack"
    >
      <svg viewBox="0 0 24 24" fill="none" aria-hidden="true">
        <path d="m14 6-6 6 6 6M8 12h12" />
      </svg>
      <span>返回上一页</span>
    </button>
  </div>
</template>
