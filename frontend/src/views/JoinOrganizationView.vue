<script setup lang="ts">
import { ref } from "vue";
import { useRouter } from "vue-router";
import { joinOrganization } from "../api/organizations";
const code = ref("");
const error = ref("");
const router = useRouter();
async function submit() {
  try {
    const member = await joinOrganization(code.value);
    await router.replace(`/organizations/${member.organizationId}/activities`);
  } catch {
    error.value = "邀请码无效、已过期或已用完";
  }
}
</script>
<template>
  <section class="narrow">
    <button class="back" @click="$router.back()">← 返回</button>
    <h1>加入组织</h1>
    <p class="muted">
      输入负责人分享给你的邀请码。邀请码只会在创建时展示一次。
    </p>
    <form @submit.prevent="submit">
      <label>邀请码<input v-model.trim="code" required autofocus /></label>
      <p v-if="error" class="error">{{ error }}</p>
      <button class="primary">加入组织</button>
    </form>
  </section>
</template>
