<script setup lang="ts">
import { ref, onMounted } from "vue";
import { ElMessage } from "element-plus";
import { useRouter, useRoute } from "vue-router";
import CloudNestLogo from "../components/brand/CloudNestLogo.vue";
import { api, errorText } from "../api";
import { useUser } from "../stores/user";
import { Lock } from "@element-plus/icons-vue";
const registering = ref(false),
  otp = ref(""),
  forgot = ref(false),
  recoveryAccount = ref(""),
  resetToken = ref(String(useRoute().query.reset || "")),
  resetPassword = ref(""),
  resetConfirmation = ref(""),
  recoveryMessage = ref(""),
  username = ref(""),
  password = ref(""),
  busy = ref(false),
  error = ref("");
const router = useRouter(),
  store = useUser();
const invitation = ref(String(useRoute().query.invitation || ""));
if (invitation.value) registering.value = true;
async function submit() {
  if (!/^[A-Za-z0-9_]{3,40}$/.test(username.value)) {
    error.value = "账号需要 3～40 个字母、数字或下划线";
    return;
  }
  if (password.value.length < 8 || password.value.length > 64) {
    error.value = "密码长度需要 8～64 个字符";
    return;
  }
  busy.value = true;
  error.value = "";
  try {
    store.user = (
      await api.post(`/auth/${registering.value ? "register" : "login"}`, {
        username: username.value,
        password: password.value,
        invitation: invitation.value || undefined,
        otp: otp.value || undefined,
      })
    ).data;
    await router.push("/");
  } catch (e) {
    error.value = errorText(e);
  } finally {
    busy.value = false;
  }
}
onMounted(async () => {
  const token = String(useRoute().query.verifyEmail || "");
  if (token) {
    try {
      await api.post("/public/auth/verify-email", { token });
      ElMessage.success("邮箱验证成功，可以登录了");
      await router.replace("/login");
    } catch (e) {
      error.value = errorText(e);
    }
  }
});
async function recover() {
  busy.value = true;
  try {
    recoveryMessage.value = (
      await api.post("/public/auth/forgot", { account: recoveryAccount.value })
    ).data.message;
  } catch (e) {
    recoveryMessage.value = errorText(e);
  } finally {
    busy.value = false;
  }
}
async function reset() {
  if (resetPassword.value !== resetConfirmation.value) {
    recoveryMessage.value = "两次密码不同";
    return;
  }
  busy.value = true;
  try {
    await api.post("/public/auth/reset", {
      token: resetToken.value,
      password: resetPassword.value,
    });
    resetToken.value = "";
    await router.replace("/login");
    ElMessage.success("密码已重置，请重新登录；双重验证保持启用");
  } catch (e) {
    recoveryMessage.value = errorText(e);
  } finally {
    busy.value = false;
  }
}
</script>
<template>
  <main class="login-page">
    <section class="login-story">
      <CloudNestLogo light />
      <div class="login-story-copy">
        <span class="eyebrow">你的私人云端工作空间</span>
        <h1>重要的文件，<br />安心放在这里。</h1>
        <p>从日常记录到长期项目。<br />有序收纳，随时取用。</p>
      </div>
      <div class="auth-geometry" aria-hidden="true">
        <div class="geometry-folder">
          <svg viewBox="0 0 420 252" fill="none">
            <rect
              x="1"
              y="1"
              width="418"
              height="250"
              rx="10"
              fill="#222327"
              stroke="#3A3B41"
            />
            <path d="M1 40h418M112 40v211" stroke="#34353A" />
            <circle cx="18" cy="21" r="3" fill="#6C6E78" />
            <circle cx="30" cy="21" r="3" fill="#52545D" />
            <circle cx="42" cy="21" r="3" fill="#45474F" />
            <rect x="16" y="60" width="80" height="25" rx="4" fill="#33343B" />
            <path
              d="M28 72h54M28 105h45M28 137h51M28 169h37"
              stroke="#696B76"
              stroke-width="3"
              stroke-linecap="round"
            />
            <path
              d="M128 87h275M128 128h275M128 169h275M128 210h275"
              stroke="#2E3035"
            />
            <g
              stroke="#8A8D9C"
              stroke-width="1.5"
              stroke-linecap="round"
              stroke-linejoin="round"
            >
              <path
                d="M132 58h6l3 3h11v13h-20V58ZM132 99h6l3 3h11v13h-20V99Z"
              />
              <path
                d="M135 140h11l5 5v13h-16v-18ZM146 140v5h5M135 181h11l5 5v13h-16v-18ZM146 181v5h5"
              />
            </g>
            <g
              fill="#B9BBC6"
              font-size="12"
              font-family="Inter, Segoe UI, Microsoft YaHei, sans-serif"
            >
              <text x="166" y="71">工作与项目</text>
              <text x="166" y="112">灵感与收藏</text>
              <text x="166" y="153">阅读笔记.md</text>
              <text x="166" y="194">下一段旅程.pdf</text>
            </g>
            <path
              d="M343 68h42M355 109h30M343 150h42M355 191h30"
              stroke="#4A4C55"
              stroke-width="3"
              stroke-linecap="round"
            />
            <circle cx="136" cy="231" r="3" fill="#83BFA4" />
            <path
              d="M148 231h77"
              stroke="#555762"
              stroke-width="3"
              stroke-linecap="round"
            />
          </svg>
        </div>
      </div>
      <small>CloudNest · 文件由你掌握</small>
    </section>
    <section class="login-form">
      <div class="login-card">
        <CloudNestLogo class="login-mobile-logo" />
        <span class="eyebrow">CLOUDNEST</span>
        <h2>{{ registering ? "创建你的云盘" : "欢迎回来" }}</h2>
        <p class="muted">
          {{
            registering
              ? "从这里开始，整理你的数字生活。"
              : "登录，回到属于你的私人空间。"
          }}
        </p>
        <form @submit.prevent="submit">
          <template v-if="registering"
            ><label for="invitation">邀请码（开放注册时可留空）</label
            ><el-input
              id="invitation"
              v-model="invitation"
              autocomplete="off"
              maxlength="100"
          /></template>
          <label for="username">账号</label
          ><el-input
            id="username"
            v-model="username"
            placeholder="字母、数字或下划线"
            autocomplete="username"
            size="large"
            maxlength="40"
          /><label for="password">密码</label
          ><el-input
            id="password"
            v-model="password"
            type="password"
            show-password
            placeholder="至少 8 个字符"
            :autocomplete="registering ? 'new-password' : 'current-password'"
            size="large"
            maxlength="64"
          /><label v-if="!registering" for="login-otp"
            >双重验证 <span class="muted">（未启用可留空）</span></label
          ><el-input
            id="login-otp"
            v-if="!registering"
            v-model="otp"
            placeholder="双重验证代码 / 恢复码（未启用可留空）"
            aria-label="登录双重验证代码"
            autocomplete="one-time-code"
          /><el-alert
            v-if="error"
            :title="error"
            type="error"
            :closable="false"
            show-icon
          /><el-button
            type="primary"
            native-type="submit"
            size="large"
            :loading="busy"
            class="full"
            >{{ registering ? "注册并进入云盘" : "登录云盘" }}</el-button
          >
        </form>
        <p class="switch-auth">
          {{ registering ? "已有账号？" : "还没有账号？" }}
          <button
            @click="
              registering = !registering;
              error = '';
            "
          >
            {{ registering ? "立即登录" : "创建账号" }}
          </button>
        </p>
        <el-button
          v-if="!registering"
          text
          @click="
            forgot = true;
            recoveryMessage = '';
          "
          >忘记密码</el-button
        >
        <div class="login-note">
          <el-icon><Lock /></el-icon> 文件独立存储，访问始终经过授权
        </div>
      </div>
    </section>
    <el-dialog
      class="cn-dialog"
      v-model="forgot"
      title="找回密码"
      width="min(480px,94vw)"
      ><p>使用账号或已验证的邮箱接收重置链接。</p>
      <el-input
        v-model="recoveryAccount"
        placeholder="账号或邮箱"
        aria-label="找回账号"
      />
      <p role="status" aria-live="polite">{{ recoveryMessage }}</p>
      <template #footer
        ><el-button :loading="busy" @click="recover"
          >发送重置链接</el-button
        ></template
      ></el-dialog
    >
    <el-dialog
      class="cn-dialog"
      :model-value="!!resetToken"
      title="设置新密码"
      width="min(480px,94vw)"
      :close-on-click-modal="false"
      @close="resetToken = ''"
    >
      <el-input
        v-model="resetPassword"
        type="password"
        show-password
        placeholder="新密码，8～64 字符"
        aria-label="重置新密码"
      /><el-input
        v-model="resetConfirmation"
        type="password"
        placeholder="再次输入新密码"
        aria-label="确认重置密码"
      />
      <p role="status" aria-live="polite">{{ recoveryMessage }}</p>
      <template #footer
        ><el-button :loading="busy" @click="reset"
          >保存新密码</el-button
        ></template
      >
    </el-dialog>
  </main>
</template>
