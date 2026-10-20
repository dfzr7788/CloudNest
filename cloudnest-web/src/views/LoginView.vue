<script setup lang="ts">
import { t } from "../i18n";
import { ref, onMounted } from "vue";
import { ElMessage } from "element-plus";
import { useRouter, useRoute } from "vue-router";
import CloudNestLogo from "../components/brand/CloudNestLogo.vue";
import LanguageSwitcher from "../components/common/LanguageSwitcher.vue";
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
    error.value = t("login.accountRule");
    return;
  }
  if (password.value.length < 8 || password.value.length > 64) {
    error.value = t("login.passwordRule");
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
      ElMessage.success(t("login.emailVerified"));
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
    recoveryMessage.value = t("login.passwordMismatch");
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
    ElMessage.success(t("login.passwordReset"));
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
        <span class="eyebrow">{{ t("login.workspace") }}</span>
        <h1>{{ t("login.headlineOne") }}<br />{{ t("login.headlineTwo") }}</h1>
        <p>{{ t("login.storyOne") }}<br />{{ t("login.storyTwo") }}</p>
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
              <text x="166" y="71">{{ t("login.sampleProjects") }}</text>
              <text x="166" y="112">{{ t("login.sampleIdeas") }}</text>
              <text x="166" y="153">{{ t("login.sampleNotes") }}</text>
              <text x="166" y="194">{{ t("login.sampleTrip") }}</text>
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
      <small>{{ t("login.ownership") }}</small>
    </section>
    <section class="login-form">
      <div class="login-card">
        <LanguageSwitcher />
        <CloudNestLogo class="login-mobile-logo" />
        <span class="eyebrow">CLOUDNEST</span>
        <h2>{{ registering ? t("login.createDrive") : t("login.welcome") }}</h2>
        <p class="muted">
          {{ registering ? t("login.registerIntro") : t("login.signInIntro") }}
        </p>
        <form @submit.prevent="submit">
          <template v-if="registering"
            ><label for="invitation">{{ t("login.invitation") }}</label
            ><el-input
              id="invitation"
              v-model="invitation"
              autocomplete="off"
              maxlength="100"
          /></template>
          <label for="username">{{ t("common.account") }}</label
          ><el-input
            id="username"
            v-model="username"
            :placeholder="t('login.usernameHint')"
            autocomplete="username"
            size="large"
            maxlength="40"
          /><label for="password">{{ t("common.password") }}</label
          ><el-input
            id="password"
            v-model="password"
            type="password"
            show-password
            :placeholder="t('login.passwordHint')"
            :autocomplete="registering ? 'new-password' : 'current-password'"
            size="large"
            maxlength="64"
          /><label v-if="!registering" for="login-otp"
            >{{ t("login.twoFactor") }}
            <span class="muted">{{ t("login.optionalOtp") }}</span></label
          ><el-input
            id="login-otp"
            v-if="!registering"
            v-model="otp"
            :placeholder="t('login.otpHint')"
            :aria-label="t('login.otpLabel')"
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
            >{{
              registering ? t("login.register") : t("login.signIn")
            }}</el-button
          >
        </form>
        <p class="switch-auth">
          {{ registering ? t("login.existingAccount") : t("login.noAccount") }}
          <button
            @click="
              registering = !registering;
              error = '';
            "
          >
            {{ registering ? t("login.signInNow") : t("login.createAccount") }}
          </button>
        </p>
        <el-button
          v-if="!registering"
          text
          @click="
            forgot = true;
            recoveryMessage = '';
          "
          >{{ t("login.forgot") }}</el-button
        >
        <div class="login-note">
          <el-icon><Lock /></el-icon> {{ t("login.securityNote") }}
        </div>
      </div>
    </section>
    <el-dialog
      class="cn-dialog"
      v-model="forgot"
      :title="t('login.recoveryTitle')"
      width="min(480px,94vw)"
      ><p>{{ t("login.recoveryHelp") }}</p>
      <el-input
        v-model="recoveryAccount"
        :placeholder="t('login.accountOrEmail')"
        :aria-label="t('login.recoveryAccount')"
      />
      <p role="status" aria-live="polite">{{ recoveryMessage }}</p>
      <template #footer
        ><el-button :loading="busy" @click="recover">{{
          t("login.sendReset")
        }}</el-button></template
      ></el-dialog
    >
    <el-dialog
      class="cn-dialog"
      :model-value="!!resetToken"
      :title="t('login.newPasswordTitle')"
      width="min(480px,94vw)"
      :close-on-click-modal="false"
      @close="resetToken = ''"
    >
      <el-input
        v-model="resetPassword"
        type="password"
        show-password
        :placeholder="t('login.newPasswordHint')"
        :aria-label="t('login.resetPasswordLabel')"
      /><el-input
        v-model="resetConfirmation"
        type="password"
        :placeholder="t('login.confirmPasswordHint')"
        :aria-label="t('login.confirmPasswordLabel')"
      />
      <p role="status" aria-live="polite">{{ recoveryMessage }}</p>
      <template #footer
        ><el-button :loading="busy" @click="reset">{{
          t("login.savePassword")
        }}</el-button></template
      >
    </el-dialog>
  </main>
</template>
