<script setup lang="ts">
import { useAuthStore } from "../stores/auth";
const auth = useAuthStore();
</script>
<template>
  <section class="page profile-page">
    <h1>个人资料</h1>
    <p class="muted">账号信息仅本人可见。组织管理员权限由各组织独立授予。</p>
    <dl v-if="auth.user" class="profile-fields">
      <div>
        <dt>用户 ID</dt>
        <dd>{{ auth.user.id }}</dd>
      </div>
      <div>
        <dt>用户名</dt>
        <dd>{{ auth.user.username }}</dd>
      </div>
      <div>
        <dt>姓名</dt>
        <dd>{{ auth.user.realName }}</dd>
      </div>
      <div>
        <dt>学号</dt>
        <dd>{{ auth.user.studentNumber }}</dd>
      </div>
      <div>
        <dt>联系方式</dt>
        <dd>{{ auth.user.contact }}</dd>
      </div>
      <div>
        <dt>平台身份</dt>
        <dd>
          {{
            auth.user.platformRole === "PLATFORM_ADMIN" ? "平台管理员" : "用户"
          }}
        </dd>
      </div>
    </dl>
    <p v-else class="state">暂时无法读取账号资料，请重新登录。</p>
    <p class="muted">当前后端不提供资料修改接口。</p>
  </section>
</template>
<style scoped>
.profile-fields {
  max-width: 680px;
  margin-top: 32px;
}
.profile-fields div {
  display: grid;
  grid-template-columns: 140px 1fr;
  gap: 16px;
  padding: 18px 0;
  border-bottom: 1px solid var(--line);
}
dt {
  color: var(--muted);
}
dd {
  margin: 0;
  overflow-wrap: anywhere;
}
@media (max-width: 600px) {
  .profile-fields div {
    grid-template-columns: 100px 1fr;
  }
}
</style>
