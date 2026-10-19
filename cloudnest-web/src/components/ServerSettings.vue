<script setup lang="ts">
import { ref, watch, onUnmounted } from "vue";
import NodeSettings from "./nodes/NodeSettings.vue";
import { ElMessage } from "element-plus";
import { api, errorText, bytes } from "../api";
const props = defineProps<{ active: boolean }>();
const storage = ref<any>({ policies: [], jobs: [], usage: [] }),
  selected = ref("local"),
  source = ref("local"),
  target = ref("local"),
  warning = ref(80),
  busy = ref(false);
const policy = ref({
  id: undefined as string | undefined,
  name: "",
  endpoint: "",
  bucket: "",
  accessKey: "",
  secretKey: "",
  region: "",
});
const mail = ref<Record<string, string>>({
    host: "",
    port: "587",
    username: "",
    password: "",
    from: "",
    tls: "true",
    ssl: "false",
    baseUrl: location.origin,
  }),
  office = ref<Record<string, string>>({
    url: "",
    secret: "",
    baseUrl: location.origin,
  }),
  testEmail = ref("");
let timer: ReturnType<typeof setTimeout> | undefined;
async function load() {
  try {
    storage.value = (await api.get("/admin/storage")).data;
  } catch (e) {
    ElMessage.error(errorText(e));
  }
}
async function poll() {
  if (!props.active) return;
  await load();
  timer = setTimeout(poll, 5000);
}
watch(
  () => props.active,
  async (active) => {
    clearTimeout(timer);
    if (!active) {
      mail.value.password = "";
      office.value.secret = "";
      policy.value.accessKey = "";
      policy.value.secretKey = "";
      return;
    }
    try {
      const [m, o, s] = await Promise.all([
        api.get("/admin/config/mail"),
        api.get("/admin/config/office"),
        api.get("/admin/config/storage"),
      ]);
      mail.value = { ...mail.value, ...m.data, password: "" };
      office.value = { ...office.value, ...o.data, secret: "" };
      warning.value = Number(s.data.warningPercent || "80");
    } catch (e) {
      ElMessage.error(errorText(e));
    }
    await poll();
    selected.value = storage.value.defaultBackend;
  },
  { immediate: true },
);
onUnmounted(() => clearTimeout(timer));
async function action(fn: () => Promise<unknown>) {
  busy.value = true;
  try {
    await fn();
    await load();
    ElMessage.success("操作完成");
  } catch (e) {
    ElMessage.error(errorText(e));
  } finally {
    busy.value = false;
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
    <el-alert
      v-if="storage.warning"
      title="服务器磁盘使用量已达到告警阈值，请清理或扩容"
      type="warning"
      :closable="false"
    />
    <p>
      磁盘剩余 {{ bytes(storage.freeBytes || 0) }} /
      {{ bytes(storage.totalBytes || 0) }}
    </p>
    <NodeSettings :active="active" />
    <h3>存储策略</h3>
    <p>
      切换默认策略只影响新上传。旧对象迁移需显式创建任务，迁移校验成功后才更新引用；来源副本保留。
    </p>
    <el-select v-model="selected"
      ><el-option value="local" label="本地磁盘" /><el-option
        value="minio"
        label="环境变量中的 MinIO" /><el-option
        v-for="p in storage.policies"
        :key="p.id"
        :value="'s3:' + p.id"
        :label="p.name" /></el-select
    ><el-input-number v-model="warning" :min="50" :max="95" /><el-button
      @click="
        action(() =>
          api.put('/admin/config/storage', {
            defaultBackend: selected,
            warningPercent: String(warning),
          }),
        )
      "
      >保存默认策略与告警阈值</el-button
    ><el-button
      @click="
        action(async () => {
          const { data } = await api.post('/admin/storage/test', {
            backend: selected,
          });
          if (!data.ok) throw new Error('读写检测失败，请检查连接与凭据');
        })
      "
      >读写连接测试</el-button
    >
    <el-table :data="storage.policies"
      ><el-table-column prop="name" label="名称" /><el-table-column
        prop="endpoint"
        label="地址"
      /><el-table-column prop="bucket" label="桶" /><el-table-column
        label="操作"
        ><template #default="{ row }"
          ><el-button
            text
            @click="policy = { ...row, accessKey: '', secretKey: '' }"
            >编辑</el-button
          ><el-button
            text
            @click="
              action(() => api.delete('/admin/storage/policies/' + row.id))
            "
            >删除</el-button
          ></template
        ></el-table-column
      ></el-table
    >
    <div class="settings-form">
      <el-input v-model="policy.name" placeholder="策略名称" /><el-input
        v-model="policy.endpoint"
        placeholder="S3 Endpoint，例如 https://s3.example.com"
      /><el-input v-model="policy.bucket" placeholder="私有桶名称" /><el-input
        v-model="policy.region"
        placeholder="Region（可选）"
      /><el-input
        v-model="policy.accessKey"
        placeholder="Access Key（编辑时留空保留）"
      /><el-input
        v-model="policy.secretKey"
        type="password"
        show-password
        placeholder="Secret Key（编辑时留空保留）"
      />
    </div>
    <el-button
      @click="
        action(async () => {
          await api.post('/admin/storage/policies', policy);
          policy = {
            id: undefined,
            name: '',
            endpoint: '',
            bucket: '',
            accessKey: '',
            secretKey: '',
            region: '',
          };
        })
      "
      >保存 S3 策略</el-button
    >
    <h3>对象迁移</h3>
    <el-select v-model="source"
      ><el-option value="local" label="本地磁盘" /><el-option
        value="minio"
        label="环境 MinIO" /><el-option
        v-for="p in storage.policies"
        :key="p.id"
        :value="'s3:' + p.id"
        :label="p.name" /></el-select
    ><span> → </span
    ><el-select v-model="target"
      ><el-option value="local" label="本地磁盘" /><el-option
        value="minio"
        label="环境 MinIO" /><el-option
        v-for="p in storage.policies"
        :key="p.id"
        :value="'s3:' + p.id"
        :label="p.name" /></el-select
    ><el-button
      @click="
        action(() => api.post('/admin/storage/migrations', { source, target }))
      "
      >开始校验迁移</el-button
    >
    <el-table :data="storage.jobs"
      ><el-table-column prop="status" label="状态" /><el-table-column
        label="进度"
        ><template #default="{ row }"
          >{{ row.completed }} / {{ row.total }}</template
        ></el-table-column
      ><el-table-column prop="error" label="详情" /><el-table-column
        label="操作"
        ><template #default="{ row }"
          ><el-button
            v-if="['QUEUED', 'RUNNING'].includes(row.status)"
            text
            @click="
              action(() => api.delete('/admin/storage/migrations/' + row.id))
            "
            >取消</el-button
          ></template
        ></el-table-column
      ></el-table
    >
    <h3>邮件与账号找回</h3>
    <div class="settings-form">
      <el-input v-model="mail.host" placeholder="SMTP 主机" /><el-input
        v-model="mail.port"
        placeholder="端口"
      /><el-input v-model="mail.username" placeholder="SMTP 用户名" /><el-input
        v-model="mail.password"
        type="password"
        placeholder="SMTP 密码/授权码（留空保留）"
      /><el-input v-model="mail.from" placeholder="发件地址" /><el-input
        v-model="mail.baseUrl"
        placeholder="本站公网地址（用于验证/恢复链接）"
      />
    </div>
    <el-checkbox v-model="mail.tls" true-value="true" false-value="false"
      >STARTTLS</el-checkbox
    ><el-checkbox v-model="mail.ssl" true-value="true" false-value="false"
      >SSL</el-checkbox
    ><el-button
      @click="action(() => api.put('/admin/config/mail', clean(mail)))"
      >保存邮件配置</el-button
    ><el-input v-model="testEmail" placeholder="测试收件邮箱" /><el-button
      @click="
        action(() => api.post('/admin/config/mail/test', { email: testEmail }))
      "
      >发送测试邮件</el-button
    >
    <h3>OnlyOffice 文档服务</h3>
    <p>
      用于高保真 Office 阅读和编辑。需要自行部署 OnlyOffice Docs，并配置相同 JWT
      密钥；基础预览无需此服务。
    </p>
    <div class="settings-form">
      <el-input v-model="office.url" placeholder="文档服务地址" /><el-input
        v-model="office.baseUrl"
        placeholder="文档服务可访问的本站公网地址"
      /><el-input
        v-model="office.secret"
        type="password"
        placeholder="JWT 密钥，至少 32 字符（留空保留）"
      />
    </div>
    <el-button
      @click="action(() => api.put('/admin/config/office', clean(office)))"
      >保存 Office 配置</el-button
    ><el-button @click="action(() => api.post('/admin/config/office/test', {}))"
      >检查 Office 连接</el-button
    >
  </div>
</template>
<style scoped>
.server-settings h3 {
  margin-top: 28px;
}
.server-settings p {
  line-height: 1.7;
  color: var(--cn-muted);
}
.settings-form {
  display: grid;
  grid-template-columns: repeat(auto-fit, minmax(220px, 1fr));
  gap: 10px;
  margin: 12px 0;
}
.server-settings > .el-input {
  width: 300px;
  margin: 8px;
}
.server-settings > .el-select {
  width: 200px;
  margin: 6px;
}
.server-settings .el-button {
  margin: 5px;
}
</style>
