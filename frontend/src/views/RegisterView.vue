<script setup lang="ts">
import { reactive, ref } from "vue";
import { useRouter } from "vue-router";
import { useAuthStore } from "../stores/auth";
const form = reactive({
  username: "",
  password: "",
  realName: "",
  studentNumber: "",
  contact: "",
});
const error = ref("");
const loading = ref(false);
const auth = useAuthStore();
const router = useRouter();
async function submit() {
  loading.value = true;
  error.value = "";
  try {
    await auth.register(form);
    await router.replace("/activities");
  } catch {
    error.value = "注册失败，请检查用户名或学号是否已使用";
  } finally {
    loading.value = false;
  }
}
</script>
<template>
  <section class="auth-page">
    <div class="auth-panel">
      <h1>创建账号</h1>
      <p>使用校内资料加入志愿组织。</p>
      <form @submit.prevent="submit">
        <label
          >用户名<input
            v-model.trim="form.username"
            minlength="4"
            maxlength="64"
            required /></label
        ><label
          >密码<input
            v-model="form.password"
            type="password"
            minlength="12"
            maxlength="72"
            required /></label
        ><label
          >姓名<input
            v-model.trim="form.realName"
            maxlength="64"
            required /></label
        ><label
          >学号<input
            v-model.trim="form.studentNumber"
            maxlength="64"
            required /></label
        ><label
          >联系方式<input v-model.trim="form.contact" maxlength="128" required
        /></label>
        <p v-if="error" class="error">{{ error }}</p>
        <button class="primary" :disabled="loading">
          {{ loading ? "正在创建…" : "创建账号" }}
        </button>
      </form>
      <RouterLink to="/login">已有账号？返回登录</RouterLink>
    </div>
  </section>
</template>
