<script setup lang="ts">
import { onMounted, ref } from "vue";
import { useRouter } from "vue-router";
import { listOrganizations } from "../api/organizations";
import type { Organization } from "../api/types";
const organizations = ref<Organization[]>([]);
const loading = ref(true);
const failed = ref(false);
const router = useRouter();
onMounted(async () => {
  try {
    organizations.value = await listOrganizations();
    if (organizations.value.length === 1)
      await router.replace(
        `/organizations/${organizations.value[0].id}/activities`,
      );
  } catch {
    failed.value = true;
  } finally {
    loading.value = false;
  }
});
</script>
<template>
  <section class="page">
    <h1>选择组织</h1>
    <p v-if="loading" class="state">正在加载组织…</p>
    <div v-else-if="failed" class="state">
      <strong>组织加载失败</strong>
      <p>请检查网络后刷新页面。</p>
    </div>
    <div v-else-if="!organizations.length" class="state">
      <strong>你还没有加入组织</strong>
      <p>获得负责人分享的邀请码后即可加入。</p>
      <RouterLink class="primary link-button" to="/join"
        >使用邀请码加入</RouterLink
      >
    </div>
    <div v-else class="organization-list">
      <RouterLink
        v-for="org in organizations"
        :key="org.id"
        :to="`/organizations/${org.id}/activities`"
        ><strong>{{ org.name }}</strong
        ><span>{{ org.description || "查看组织活动" }}</span></RouterLink
      >
    </div>
  </section>
</template>
