<script setup lang="ts">
import { ref, watch } from "vue";
import { ElMessage, ElMessageBox } from "element-plus";
import { api, errorText, date } from "../api";
const props = defineProps<{ active: boolean }>();
const status = ref<any>({ credentials: [] }),
  password = ref(""),
  otp = ref(""),
  email = ref(""),
  secret = ref(""),
  uri = ref(""),
  recovery = ref<string[]>([]),
  label = ref(""),
  readOnly = ref(true),
  davPassword = ref(""),
  days = ref(90),
  busy = ref(false);
const davUrl = location.origin + "/dav/";
async function load() {
  try {
    status.value = (await api.get("/user/access")).data;
  } catch (e) {
    ElMessage.error(errorText(e));
  }
}
async function perform(fn: () => Promise<void>) {
  busy.value = true;
  try {
    await fn();
    await load();
  } catch (e) {
    if (e !== "cancel") ElMessage.error(errorText(e));
  } finally {
    busy.value = false;
  }
}
async function bind() {
  await perform(async () => {
    await api.post("/user/access/email", {
      email: email.value,
      password: password.value,
    });
    ElMessage.success("验证链接已发送，请到邮箱确认");
    password.value = "";
  });
}
async function setup() {
  await perform(async () => {
    const { data } = await api.post("/user/access/mfa/setup", {
      password: password.value,
    });
    secret.value = data.secret;
    uri.value = data.uri;
    password.value = "";
  });
}
async function confirm() {
  await perform(async () => {
    recovery.value = (
      await api.post("/user/access/mfa/confirm", { code: otp.value })
    ).data.recoveryCodes;
    secret.value = "";
    uri.value = "";
    otp.value = "";
    ElMessage.success("双重验证已启用，请保存恢复码");
  });
}
async function disable() {
  await perform(async () => {
    await ElMessageBox.confirm(
      "关闭后将只需密码登录。是否继续？",
      "关闭双重验证",
    );
    await api.delete("/user/access/mfa", {
      data: { password: password.value, otp: otp.value },
    });
    password.value = "";
    otp.value = "";
  });
}
async function credential() {
  await perform(async () => {
    davPassword.value = (
      await api.post("/user/access/dav", {
        label: label.value,
        days: days.value,
        readOnly: readOnly.value,
        password: password.value,
        otp: otp.value,
      })
    ).data.password;
    password.value = "";
    otp.value = "";
  });
}
async function revoke(id: string) {
  await perform(async () => {
    await api.delete(`/user/access/dav/${id}`);
  });
}
watch(
  () => props.active,
  (v) => {
    password.value = "";
    otp.value = "";
    secret.value = "";
    uri.value = "";
    recovery.value = [];
    davPassword.value = "";
    if (v) load();
  },
  { immediate: true },
);
</script>
<template>
  <div class="access-settings" v-loading="busy">
    <section>
      <h3>邮箱与账号恢复</h3>
      <p>
        当前邮箱：{{ status.email || "未绑定" }}。验证后可从登录页找回密码。
      </p>
      <el-input
        v-model="email"
        placeholder="新邮箱"
        aria-label="绑定邮箱"
      /><el-input
        v-model="password"
        type="password"
        show-password
        placeholder="当前登录密码（用于敏感操作）"
        aria-label="验证当前密码"
      /><el-button @click="bind">发送验证邮件</el-button>
    </section>
    <section>
      <h3>双重验证</h3>
      <p>
        {{
          status.mfa ? "已启用身份验证器验证" : "尚未启用"
        }}。请保管身份验证器及恢复码。
      </p>
      <el-input
        v-model="otp"
        placeholder="身份验证器代码或恢复码"
        aria-label="双重验证代码"
      /><el-button v-if="!status.mfa && !secret" @click="setup"
        >设置双重验证</el-button
      ><el-button v-if="status.mfa" @click="disable">关闭双重验证</el-button>
      <div v-if="secret">
        <p>在身份验证器中手动添加 CloudNest，密钥如下（仅本次显示）：</p>
        <el-input
          :model-value="secret"
          readonly
          aria-label="验证器密钥"
        /><el-button @click="confirm">验证代码并启用</el-button>
      </div>
      <el-alert
        v-if="recovery.length"
        title="请立即保存以下一次性恢复码；关闭页面后不再显示"
        type="warning"
        :closable="false"
      />
      <pre v-if="recovery.length">{{ recovery.join("\n") }}</pre>
    </section>
    <section>
      <h3>WebDAV 应用密码</h3>
      <p>
        地址：{{ davUrl }} · 用户名：{{
          status.username
        }}。客户端使用独立应用密码，可随时撤销。
      </p>
      <el-input
        v-model="label"
        placeholder="设备名称，例如 Windows / rclone"
        aria-label="应用密码名称"
      /><el-checkbox v-model="readOnly">只读访问</el-checkbox
      ><el-input-number
        v-model="days"
        :min="1"
        :max="365"
        aria-label="应用密码有效天数"
      /><span>天</span><el-button @click="credential">创建应用密码</el-button
      ><el-input
        v-if="davPassword"
        :model-value="davPassword"
        readonly
        aria-label="新应用密码"
      />
      <p v-if="davPassword">应用密码仅本次显示，请保存到客户端。</p>
      <el-table :data="status.credentials"
        ><el-table-column prop="label" label="设备" /><el-table-column
          label="权限"
          ><template #default="{ row }">{{
            row.read_only ? "只读" : "读写"
          }}</template></el-table-column
        ><el-table-column label="到期"
          ><template #default="{ row }">{{
            date(row.expires_at)
          }}</template></el-table-column
        ><el-table-column label="操作"
          ><template #default="{ row }"
            ><el-button text @click="revoke(row.id)">撤销</el-button></template
          ></el-table-column
        ></el-table
      >
    </section>
  </div>
</template>
<style scoped>
.access-settings section {
  padding: 16px 0;
  border-bottom: 1px solid var(--cn-border);
}
.access-settings .el-input {
  max-width: 460px;
  margin: 6px 8px 6px 0;
}
.access-settings p {
  line-height: 1.8;
  overflow-wrap: anywhere;
}
.access-settings pre {
  user-select: all;
  white-space: pre-wrap;
}
</style>
