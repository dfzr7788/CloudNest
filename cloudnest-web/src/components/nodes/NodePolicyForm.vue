<script setup lang="ts">
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
    ElMessage.success("已保存，请执行完整检测");
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
    <h2>S3 节点接入</h2>
    <p>
      支持 Garage 与 S3
      兼容存储。新节点保存后先完整检测，再在调度策略中设置故障域、评级和预算。
    </p>
    <el-table :data="rows"
      ><el-table-column prop="name" label="节点" /><el-table-column
        prop="endpoint"
        label="内网地址"
        min-width="220"
      /><el-table-column prop="bucket" label="存储桶" /><el-table-column
        label="操作"
        ><template #default="{ row }"
          ><el-button text @click="edit(row)">编辑连接</el-button></template
        ></el-table-column
      ></el-table
    >
    <el-form label-position="top" class="connection-form">
      <el-form-item label="节点名称"
        ><el-input v-model="policy.name" maxlength="80"
      /></el-form-item>
      <el-form-item label="S3 Endpoint"
        ><el-input v-model="policy.endpoint" placeholder="http://私网地址:3900"
      /></el-form-item>
      <el-form-item label="存储桶"
        ><el-input v-model="policy.bucket"
      /></el-form-item>
      <el-form-item label="区域"
        ><el-input v-model="policy.region"
      /></el-form-item>
      <el-form-item label="Access Key（编辑时留空保留）"
        ><el-input v-model="policy.accessKey" autocomplete="off"
      /></el-form-item>
      <el-form-item label="Secret Key（编辑时留空保留）"
        ><el-input
          v-model="policy.secretKey"
          type="password"
          show-password
          autocomplete="new-password"
      /></el-form-item>
      <el-button type="primary" @click="act(save)">{{
        policy.id ? "更新连接" : "新增节点"
      }}</el-button
      ><el-button v-if="policy.id" @click="policy = blank()"
        >取消编辑</el-button
      >
    </el-form>
    <h3>手动模式默认节点</h3>
    <el-select v-model="defaultBackend"
      ><el-option label="A 本地存储" value="local" /><el-option
        label="旧 MinIO"
        value="minio" /><el-option
        v-for="n in rows"
        :key="n.id"
        :label="n.name"
        :value="'s3:' + n.id"
    /></el-select>
    <el-button
      @click="act(() => api.put('/admin/config/storage', { defaultBackend }))"
      >保存默认节点</el-button
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
