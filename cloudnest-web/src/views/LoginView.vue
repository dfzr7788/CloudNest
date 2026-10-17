<script setup lang="ts">
import { ref, onMounted } from "vue";
import { ElMessage } from "element-plus";
import { useRouter, useRoute } from "vue-router";
import { api, errorText } from "../api";
import { useUser } from "../stores/user";
import { Cloudy, FolderOpened, Lock, Share } from "@element-plus/icons-vue";
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
      <div class="brand light">
        <el-icon><Cloudy /></el-icon> CloudNest
      </div>
      <span class="eyebrow">YOUR PERSONAL CLOUD</span>
      <h1>让每一份珍贵，<br />都有自己的归处。</h1>
      <p>
        从灵感到成果，从日常到回忆。<br />在你的私人空间里，安心收藏，自由分享。
      </p>
      <div class="orbit">
        <div class="orbit-inner">
          <el-icon><Cloudy /></el-icon>
        </div>
        <span class="floating folder-float"
          ><el-icon><FolderOpened /></el-icon> 工作与灵感</span
        ><span class="floating secure-float"
          ><el-icon><Lock /></el-icon> 只属于你的空间</span
        ><span class="floating share-float"
          ><el-icon><Share /></el-icon> 轻松分享</span
        >
      </div>
      <small>CloudNest · 每一份文件，都值得好好安放</small>
    </section>
    <section class="login-form">
      <div class="login-card">
        <span class="eyebrow">WELCOME TO CLOUDNEST</span>
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
          /><el-input
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
    <el-dialog v-model="forgot" title="找回密码" width="min(480px,94vw)"
      ><p>使用账号或已验证的邮箱接收重置链接。</p>
      <el-input
        v-model="recoveryAccount"
        placeholder="账号或邮箱"
        aria-label="找回账号"
      />
      <p>{{ recoveryMessage }}</p>
      <template #footer
        ><el-button :loading="busy" @click="recover"
          >发送重置链接</el-button
        ></template
      ></el-dialog
    >
    <el-dialog
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
      <p>{{ recoveryMessage }}</p>
      <template #footer
        ><el-button :loading="busy" @click="reset"
          >保存新密码</el-button
        ></template
      >
    </el-dialog>
  </main>
</template>
