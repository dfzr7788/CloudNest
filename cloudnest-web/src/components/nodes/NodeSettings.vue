<script setup lang="ts">
import { ref, watch, onUnmounted } from "vue";
import { ElMessage } from "element-plus";
import { api, bytes, date, errorText } from "../../api";
const props = defineProps<{ active: boolean }>();
const rows = ref<any[]>([]),
  unknown = ref<any[]>([]),
  editing = ref<any>(null);
const events = ref<any[]>([]),
  busy = ref(false),
  budgetGiB = ref(0);
const management: Record<string, string> = {
  ACTIVE: "正常",
  WRITE_PAUSED: "暂停写入",
  MAINTENANCE: "维护",
};
const health: Record<string, string> = {
  UNKNOWN: "未验证",
  ONLINE: "在线",
  DEGRADED: "异常",
  OFFLINE: "离线",
};
let timer: ReturnType<typeof setTimeout> | undefined;
async function load() {
  const { data } = await api.get("/admin/nodes");
  rows.value = data.nodes;
  unknown.value = data.unknownBackends;
}
async function poll() {
  if (!props.active) return;
  try {
    await load();
  } catch (e) {
    ElMessage.error(errorText(e));
  }
  if (props.active) timer = setTimeout(poll, 10000);
}
watch(
  () => props.active,
  (active) => {
    clearTimeout(timer);
    if (active) void poll();
  },
  { immediate: true },
);
onUnmounted(() => clearTimeout(timer));
async function action(fn: () => Promise<unknown>) {
  busy.value = true;
  try {
    await fn();
    await load();
  } catch (e) {
    ElMessage.error(errorText(e));
  } finally {
    busy.value = false;
  }
}
async function edit(row: any) {
  editing.value = { ...row };
  budgetGiB.value = row.budget_bytes / 1024 ** 3;
  try {
    events.value = (
      await api.get(`/admin/nodes/${encodeURIComponent(row.backend)}/events`)
    ).data;
  } catch (e) {
    ElMessage.error(errorText(e));
  }
}
async function save() {
  const n = editing.value;
  await api.put(`/admin/nodes/${encodeURIComponent(n.backend)}`, {
    name: n.name,
    enabled: n.enabled,
    management: n.management,
    allowUpload: n.allow_upload,
    allowMigration: n.allow_migration,
    budgetBytes: Math.floor(budgetGiB.value * 1024 ** 3),
    publicEndpoint: n.public_endpoint || "",
    directDownload: n.direct_download,
  });
  editing.value = null;
}
async function testNode(row: any) {
  await action(async () => {
    const { data } = await api.post(
      `/admin/nodes/${encodeURIComponent(row.backend)}/test`,
    );
    data.ok
      ? ElMessage.success("读写校验通过")
      : ElMessage.error("检测失败，请检查私网连接和凭据");
  });
}
</script>
<template>
  <section class="node-settings" v-loading="busy">
    <h3>存储节点</h3>
    <p>
      在下方新建 S3
      策略即可接入节点。新节点先完整检测，再设为默认节点。暂停写入和维护阻止新任务，已有任务继续排空。
    </p>
    <el-alert
      v-if="unknown.length"
      type="error"
      :closable="false"
      :title="'发现未映射的旧后端：' + unknown.map((n) => n.backend).join('、')"
    />
    <el-table class="node-desktop" :data="rows">
      <el-table-column prop="name" label="节点" min-width="120" />
      <el-table-column label="状态" min-width="140"
        ><template #default="{ row }"
          >{{ management[row.management] }} / {{ health[row.state]
          }}{{ row.stale ? "（检测过期）" : ""
          }}{{ !row.enabled ? "（停用）" : "" }}</template
        ></el-table-column
      >
      <el-table-column label="CloudNest 已用 / 预留" min-width="170"
        ><template #default="{ row }"
          >{{ bytes(row.usedBytes) }} / {{ bytes(row.reservedBytes) }}</template
        ></el-table-column
      >
      <el-table-column label="管理员预算" min-width="120"
        ><template #default="{ row }">{{
          row.budget_bytes ? bytes(row.budget_bytes) : "未设上限"
        }}</template></el-table-column
      >
      <el-table-column label="操作" min-width="210"
        ><template #default="{ row }">
          <el-button text @click="edit(row)">详情与配置</el-button>
          <el-button text @click="testNode(row)">完整检测</el-button>
          <el-button
            v-if="!['local', 'minio'].includes(row.backend)"
            text
            :disabled="row.referenced"
            @click="
              action(() =>
                api.delete(`/admin/nodes/${encodeURIComponent(row.backend)}`),
              )
            "
            >删除</el-button
          >
        </template></el-table-column
      >
    </el-table>
    <div class="node-mobile">
      <article v-for="row in rows" :key="row.backend" class="node-card">
        <strong>{{ row.name }}</strong>
        <p>
          {{ management[row.management] }} / {{ health[row.state]
          }}{{ row.stale ? "（检测过期）" : ""
          }}{{ !row.enabled ? "（停用）" : "" }}
        </p>
        <p>
          CloudNest 已用 {{ bytes(row.usedBytes) }} · 预留
          {{ bytes(row.reservedBytes) }}
        </p>
        <p>
          管理员预算：{{
            row.budget_bytes ? bytes(row.budget_bytes) : "未设上限"
          }}
        </p>
        <el-button text @click="edit(row)">详情与配置</el-button
        ><el-button text @click="testNode(row)">完整检测</el-button>
      </article>
    </div>
    <p>
      远端容量来自管理员预算，并非 B
      的真实磁盘剩余；已用包含迁移保留副本。直连关闭后，新授权改走
      A，已发链接最长约 60 秒后失效。
    </p>
    <el-dialog
      append-to-body
      class="cn-dialog"
      :model-value="!!editing"
      title="节点配置与健康记录"
      width="min(620px, 94vw)"
      @close="editing = null"
    >
      <el-form v-if="editing" label-position="top">
        <el-form-item label="名称"
          ><el-input v-model="editing.name" maxlength="80"
        /></el-form-item>
        <el-form-item label="管理状态"
          ><el-select v-model="editing.management"
            ><el-option
              v-for="(label, key) in management"
              :key="key"
              :label="label"
              :value="key" /></el-select
        ></el-form-item>
        <el-checkbox v-model="editing.enabled">启用节点</el-checkbox
        ><el-checkbox v-model="editing.allow_upload">允许新上传</el-checkbox
        ><el-checkbox v-model="editing.allow_migration">允许迁入</el-checkbox>
        <el-form-item
          label="管理员预算（GiB，0 为不设硬上限，请自行扣除运行余量）"
          ><el-input-number
            v-model="budgetGiB"
            :min="0"
            :max="8388607"
            :precision="3"
        /></el-form-item>
        <el-form-item label="公网 S3 地址（含协议，不含路径）"
          ><el-input
            v-model="editing.public_endpoint"
            placeholder="https://objects.example.com"
        /></el-form-item>
        <el-checkbox
          v-model="editing.direct_download"
          :disabled="editing.backend === 'local'"
          >启用授权直连下载</el-checkbox
        >
        <p>
          最近检测：{{ date(editing.checked_at) }}；{{ editing.latency_ms }} ms
        </p>
        <p>凭据在原存储策略中管理；有引用时不能更改内部地址或桶。</p>
        <el-table :data="events"
          ><el-table-column label="健康变化"
            ><template #default="{ row }">{{
              health[row.state]
            }}</template></el-table-column
          ><el-table-column label="时间"
            ><template #default="{ row }">{{
              date(row.created_at)
            }}</template></el-table-column
          ></el-table
        >
      </el-form>
      <template #footer
        ><el-button @click="editing = null">取消</el-button
        ><el-button type="primary" @click="action(save)"
          >保存</el-button
        ></template
      >
    </el-dialog>
  </section>
</template>
<style scoped>
.node-mobile {
  display: none;
}
.node-card {
  padding: 16px;
  margin: 12px 0;
  border: 1px solid var(--cn-border-strong);
  border-radius: 10px;
  overflow-wrap: anywhere;
}
.node-card p {
  margin: 8px 0;
  color: var(--cn-muted);
}
@media (max-width: 640px) {
  .node-desktop {
    display: none;
  }
  .node-mobile {
    display: block;
  }
}
</style>
