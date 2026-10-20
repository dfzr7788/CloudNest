<script setup lang="ts">
import { t } from "../../i18n";
import { ref, onMounted } from "vue";
import { api, errorText } from "../../api";
import { ElMessage } from "element-plus";
const rows = ref<any[]>([]),
  defaultBackend = ref("local"),
  busy = ref(false);
const blank = () => ({
  id: undefined as string | undefined,
  name: "",
  endpoint: "",
  bucket: "",
  region: "garage",
  accessKey: "",
  secretKey: "",
});
const policy = ref(blank());
async function load() {
  const { data } = await api.get("/admin/storage");
  rows.value = data.policies;
  defaultBackend.value = data.defaultBackend;
}
onMounted(() => load().catch((e) => ElMessage.error(errorText(e))));
async function act(fn: () => Promise<unknown>) {
  busy.value = true;
  try {
    await fn();
    await load();
    ElMessage.success(t("nodePolicy.saved"));
  } catch (e) {
    ElMessage.error(errorText(e));
  } finally {
    busy.value = false;
  }
}
function edit(row: any) {
  policy.value = { ...row, accessKey: "", secretKey: "" };
}
async function save() {
  await api.post("/admin/storage/policies", policy.value);
  policy.value = blank();
}
</script>
<template>
  <section v-loading="busy">
    <h2>{{ t("nodePolicy.title") }}</h2>
    <p>
      {{ t("nodePolicy.help") }}
    </p>
    <el-table :data="rows"
      ><el-table-column prop="name" :label="t('nodes.node')" /><el-table-column
        prop="endpoint"
        :label="t('nodePolicy.endpoint')"
        min-width="220"
      /><el-table-column
        prop="bucket"
        :label="t('nodePolicy.bucket')"
      /><el-table-column :label="t('common.actions')"
        ><template #default="{ row }"
          ><el-button text @click="edit(row)">{{
            t("nodePolicy.edit")
          }}</el-button></template
        ></el-table-column
      ></el-table
    >
    <el-form label-position="top" class="connection-form">
      <el-form-item :label="t('nodePolicy.name')"
        ><el-input v-model="policy.name" maxlength="80"
      /></el-form-item>
      <el-form-item label="S3 Endpoint"
        ><el-input
          v-model="policy.endpoint"
          :placeholder="t('nodePolicy.endpointHint')"
      /></el-form-item>
      <el-form-item :label="t('nodePolicy.bucket')"
        ><el-input v-model="policy.bucket"
      /></el-form-item>
      <el-form-item :label="t('nodePolicy.region')"
        ><el-input v-model="policy.region"
      /></el-form-item>
      <el-form-item :label="t('nodePolicy.accessKey')"
        ><el-input v-model="policy.accessKey" autocomplete="off"
      /></el-form-item>
      <el-form-item :label="t('nodePolicy.secretKey')"
        ><el-input
          v-model="policy.secretKey"
          type="password"
          show-password
          autocomplete="new-password"
      /></el-form-item>
      <el-button type="primary" @click="act(save)">{{
        policy.id ? t("nodePolicy.update") : t("nodePolicy.add")
      }}</el-button
      ><el-button v-if="policy.id" @click="policy = blank()">{{
        t("nodePolicy.cancel")
      }}</el-button>
    </el-form>
    <h3>{{ t("nodePolicy.default") }}</h3>
    <el-select v-model="defaultBackend"
      ><el-option :label="t('nodePolicy.local')" value="local" /><el-option
        :label="t('nodePolicy.legacy')"
        value="minio" /><el-option
        v-for="n in rows"
        :key="n.id"
        :label="n.name"
        :value="'s3:' + n.id"
    /></el-select>
    <el-button
      @click="act(() => api.put('/admin/config/storage', { defaultBackend }))"
      >{{ t("nodePolicy.saveDefault") }}</el-button
    >
  </section>
</template>
<style scoped>
.connection-form {
  max-width: 560px;
  margin: 24px 0;
}
h2 {
  font-size: 19px;
}
p {
  color: var(--cn-muted);
  line-height: 1.7;
}
</style>
