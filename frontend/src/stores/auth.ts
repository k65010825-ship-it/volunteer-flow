import { defineStore } from "pinia";
import { ref } from "vue";
import * as authApi from "../api/auth";
import { clearAccessToken } from "../api/http";
import type { CurrentUser } from "../api/types";
export const useAuthStore = defineStore("auth", () => {
  const user = ref<CurrentUser | null>(null);
  const ready = ref(false);
  async function restore() {
    try {
      user.value = await authApi.me();
    } catch {
      user.value = null;
    } finally {
      ready.value = true;
    }
  }
  async function login(username: string, password: string) {
    await authApi.login(username, password);
    user.value = await authApi.me();
  }
  async function register(payload: Parameters<typeof authApi.register>[0]) {
    await authApi.register(payload);
    user.value = await authApi.me();
  }
  async function logout() {
    try {
      await authApi.logout();
    } finally {
      clearAccessToken();
      user.value = null;
    }
  }
  return { user, ready, restore, login, register, logout };
});
