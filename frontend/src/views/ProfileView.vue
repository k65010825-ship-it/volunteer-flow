<script setup lang="ts">
import { reactive, ref, watch } from "vue";
import { useRouter } from "vue-router";
import { isAxiosError } from "axios";
import { useAuthStore } from "../stores/auth";
const auth = useAuthStore();
const router = useRouter();
const profile = reactive({ realName: "", contact: "" });
const password = reactive({
  currentPassword: "",
  newPassword: "",
  confirmPassword: "",
});
const saving = ref(false);
const changing = ref(false);
const profileError = ref("");
const passwordError = ref("");
const success = ref("");
watch(
  () => auth.user,
  (user) => {
    profile.realName = user?.realName ?? "";
    profile.contact = user?.contact ?? "";
  },
  { immediate: true },
);
function errorMessage(error: unknown, fallback: string) {
  const code = isAxiosError(error) ? error.response?.data?.code : "";
  if (code === "INVALID_CURRENT_PASSWORD")
    return "当前密码不正确，请重新输入。";
  if (code === "PASSWORD_UNCHANGED") return "新密码不能与当前密码相同。";
  if (code === "INVALID_NEW_PASSWORD")
    return "新密码须为 12–72 个字符，且 UTF-8 编码不超过 72 字节。";
  return fallback;
}
async function saveProfile() {
  if (saving.value || changing.value) return;
  profileError.value = "";
  success.value = "";
  const realName = profile.realName.trim();
  const contact = profile.contact.trim();
  if (!realName || realName.length > 64 || !contact || contact.length > 128) {
    profileError.value =
      "请填写姓名（不超过 64 字）和联系方式（不超过 128 字）。";
    return;
  }
  saving.value = true;
  try {
    await auth.updateProfile({ realName, contact });
    success.value = "资料已保存。";
  } catch (error) {
    profileError.value = errorMessage(error, "资料保存失败，请稍后重试。");
  } finally {
    saving.value = false;
  }
}
async function changePassword() {
  if (changing.value || saving.value) return;
  passwordError.value = "";
  if (password.newPassword !== password.confirmPassword) {
    passwordError.value = "两次输入的新密码不一致。";
    return;
  }
  if (
    password.newPassword.length < 12 ||
    password.newPassword.length > 72 ||
    new TextEncoder().encode(password.newPassword).length > 72
  ) {
    passwordError.value =
      "新密码须为 12–72 个字符，且 UTF-8 编码不超过 72 字节。";
    return;
  }
  if (
    !password.currentPassword ||
    password.currentPassword === password.newPassword
  ) {
    passwordError.value = "请填写当前密码，并使用不同的新密码。";
    return;
  }
  changing.value = true;
  try {
    await auth.changePassword({
      currentPassword: password.currentPassword,
      newPassword: password.newPassword,
    });
    await router.replace({ path: "/login", query: { passwordChanged: "1" } });
  } catch (error) {
    passwordError.value = errorMessage(error, "修改密码失败，请稍后重试。");
  } finally {
    // Clear sensitive page state after every server response.
    password.currentPassword = "";
    password.newPassword = "";
    password.confirmPassword = "";
    changing.value = false;
  }
}
</script>
<template>
  <section class="page profile-page">
    <h1>个人资料</h1>
    <p class="muted">维护个人信息和账号安全。组织权限由各组织独立授予。</p>
    <template v-if="auth.user">
      <dl class="identity-card">
        <div>
          <dt>用户 ID</dt>
          <dd>{{ auth.user.id }}</dd>
        </div>
        <div>
          <dt>用户名</dt>
          <dd>{{ auth.user.username }}</dd>
        </div>
        <div>
          <dt>学号</dt>
          <dd>{{ auth.user.studentNumber }}</dd>
        </div>
        <div>
          <dt>平台身份</dt>
          <dd>
            {{
              auth.user.platformRole === "PLATFORM_ADMIN"
                ? "平台管理员"
                : "用户"
            }}
          </dd>
        </div>
      </dl>
      <div class="profile-grid">
        <form
          class="profile-panel"
          data-test="profile-form"
          @submit.prevent="saveProfile"
        >
          <h2>基本资料</h2>
          <p class="muted">用户名和学号作为身份标识，不支持在此修改。</p>
          <label
            >姓名<input
              v-model="profile.realName"
              name="realName"
              required
              maxlength="64"
              autocomplete="name"
              :disabled="saving || changing"
          /></label>
          <label
            >联系方式<input
              v-model="profile.contact"
              name="contact"
              required
              maxlength="128"
              :disabled="saving || changing"
          /></label>
          <p v-if="profileError" class="error" role="alert">
            {{ profileError }}
          </p>
          <p v-if="success" class="success" role="status">{{ success }}</p>
          <button class="primary" :disabled="saving || changing">
            {{ saving ? "正在保存…" : "保存资料" }}
          </button>
        </form>
        <form
          class="profile-panel"
          data-test="password-form"
          @submit.prevent="changePassword"
        >
          <h2>修改密码</h2>
          <p class="muted">修改后所有设备将退出登录，需要使用新密码登录。</p>
          <label
            >当前密码<input
              v-model="password.currentPassword"
              name="currentPassword"
              type="password"
              required
              autocomplete="current-password"
              :disabled="saving || changing"
          /></label>
          <label
            >新密码<input
              v-model="password.newPassword"
              name="newPassword"
              type="password"
              required
              minlength="12"
              maxlength="72"
              autocomplete="new-password"
              aria-describedby="password-help"
              :disabled="saving || changing"
          /></label>
          <small id="password-help" class="muted"
            >12–72 个字符，UTF-8 编码不超过 72 字节；中文通常每字占 3
            字节。</small
          >
          <label
            >确认新密码<input
              v-model="password.confirmPassword"
              name="confirmPassword"
              type="password"
              required
              autocomplete="new-password"
              :disabled="saving || changing"
          /></label>
          <p v-if="passwordError" class="error" role="alert">
            {{ passwordError }}
          </p>
          <button class="primary" :disabled="saving || changing">
            {{ changing ? "正在修改…" : "修改密码并重新登录" }}
          </button>
        </form>
      </div>
    </template>
    <p v-else class="state">暂时无法读取账号资料，请重新登录。</p>
  </section>
</template>
<style scoped>
.identity-card {
  display: grid;
  grid-template-columns: repeat(4, minmax(0, 1fr));
  gap: 24px;
  padding: 24px;
  margin: 28px 0;
  border: 1px solid var(--line);
  border-radius: 16px;
}
dt {
  color: var(--muted);
  font-size: 14px;
  margin-bottom: 8px;
}
dd {
  margin: 0;
  overflow-wrap: anywhere;
}
.profile-grid {
  display: grid;
  grid-template-columns: repeat(2, minmax(0, 1fr));
  gap: 24px;
  align-items: start;
}
.profile-panel {
  padding: 28px;
  border: 1px solid var(--line);
  border-radius: 16px;
  display: grid;
  gap: 18px;
}
.profile-panel h2,
.profile-panel p {
  margin: 0;
}
.profile-panel label {
  display: grid;
  gap: 8px;
}
.profile-panel input {
  box-sizing: border-box;
  width: 100%;
  min-width: 0;
  border: 1px solid var(--line);
  border-radius: 10px;
  padding: 12px 14px;
  background: #fff;
  color: var(--ink);
  font: inherit;
}
.profile-panel input:focus-visible {
  outline: 2px solid var(--green);
  outline-offset: 2px;
}
.profile-panel button {
  justify-self: start;
}
.success {
  color: #087a47;
}
@media (max-width: 720px) {
  .profile-grid {
    grid-template-columns: 1fr;
  }
  .identity-card {
    grid-template-columns: repeat(2, minmax(0, 1fr));
    gap: 20px;
  }
  .profile-panel {
    padding: 20px;
  }
}
</style>
