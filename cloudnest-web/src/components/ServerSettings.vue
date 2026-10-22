<script setup lang="ts">
import { ref, watch } from "vue";
import FeatureSettings from "./FeatureSettings.vue";
import { t } from "../i18n";
import { ElMessage } from "element-plus";
import { api, errorText } from "../api";
const props = defineProps<{ active: boolean }>();
const busy = ref(false),
  testEmail = ref("");
const mail = ref<Record<string, string>>({
  host: "",
  port: "587",
  username: "",
  password: "",
  from: "",
  tls: "true",
  ssl: "false",
  baseUrl: location.origin,
});
const office = ref<Record<string, string>>({
  url: "",
  secret: "",
  baseUrl: location.origin,
});
let generation = 0;
watch(
  () => props.active,
  async (active) => {
    const mine = ++generation;
    mail.value.password = "";
    office.value.secret = "";
    if (!active) return;
    try {
      const [m, o] = await Promise.all([
        api.get("/admin/config/mail"),
        api.get("/admin/config/office"),
      ]);
      if (mine !== generation) return;
      mail.value = { ...mail.value, ...m.data, password: "" };
      office.value = { ...office.value, ...o.data, secret: "" };
    } catch (e) {
      if (mine === generation) ElMessage.error(errorText(e));
    }
  },
  { immediate: true },
);
async function action(fn: () => Promise<unknown>) {
  busy.value = true;
  try {
    await fn();
    ElMessage.success(t("common.saved"));
  } catch (e) {
    ElMessage.error(errorText(e));
  } finally {
    busy.value = false;
    mail.value.password = "";
    office.value.secret = "";
  }
}
function clean(config: Record<string, string>) {
  return Object.fromEntries(
    Object.entries(config).filter(([k]) => !k.endsWith("Configured")),
  );
}
</script>
<template>
  <div class="server-settings" v-loading="busy">
    <FeatureSettings :active="active" />
    <h3>{{ t("services.mailTitle") }}</h3>
    <el-form label-position="top" class="settings-form">
      <el-form-item :label="t('services.smtpHost')"
        ><el-input v-model="mail.host" /></el-form-item
      ><el-form-item :label="t('services.port')"
        ><el-input v-model="mail.port" /></el-form-item
      ><el-form-item :label="t('services.smtpUser')"
        ><el-input v-model="mail.username" /></el-form-item
      ><el-form-item :label="t('services.smtpPassword')"
        ><el-input v-model="mail.password" type="password" /></el-form-item
      ><el-form-item :label="t('services.mailFrom')"
        ><el-input v-model="mail.from" /></el-form-item
      ><el-form-item :label="t('services.publicUrl')"
        ><el-input v-model="mail.baseUrl"
      /></el-form-item>
    </el-form>
    <el-checkbox v-model="mail.tls" true-value="true" false-value="false"
      >STARTTLS</el-checkbox
    ><el-checkbox v-model="mail.ssl" true-value="true" false-value="false"
      >SSL</el-checkbox
    ><el-button
      @click="action(() => api.put('/admin/config/mail', clean(mail)))"
      >{{ t("services.saveMail") }}</el-button
    >
    <el-form label-position="top">
      <el-form-item :label="t('services.testRecipient')"
        ><el-input v-model="testEmail"
      /></el-form-item>
    </el-form>
    <el-button
      @click="
        action(() => api.post('/admin/config/mail/test', { email: testEmail }))
      "
      >{{ t("services.sendMail") }}</el-button
    >
    <h3>{{ t("services.officeTitle") }}</h3>
    <p>
      {{ t("services.officeHelp") }}
    </p>
    <el-form label-position="top" class="settings-form">
      <el-form-item :label="t('services.officeUrl')"
        ><el-input v-model="office.url" /></el-form-item
      ><el-form-item :label="t('services.officeSiteUrl')"
        ><el-input v-model="office.baseUrl" /></el-form-item
      ><el-form-item :label="t('services.officeSecret')"
        ><el-input v-model="office.secret" type="password"
      /></el-form-item>
    </el-form>
    <el-button
      @click="action(() => api.put('/admin/config/office', clean(office)))"
      >{{ t("services.saveOffice") }}</el-button
    ><el-button
      @click="action(() => api.post('/admin/config/office/test', {}))"
      >{{ t("services.testOffice") }}</el-button
    >
  </div>
</template>
<style scoped>
.settings-form {
  display: grid;
  grid-template-columns: repeat(auto-fit, minmax(min(100%, 220px), 1fr));
  gap: 12px;
  margin: 16px 0;
}
.server-settings .el-button {
  margin: 6px 6px 6px 0;
}
.server-settings > .el-input {
  display: block;
  max-width: 100%;
  width: 360px;
  margin: 12px 0;
}
</style>
