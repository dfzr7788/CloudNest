<script setup lang="ts">
import { t } from "../../i18n";
import { computed, ref, watch, onUnmounted } from "vue";
import { ElMessage } from "element-plus";
import { api, bytes, date, errorText } from "../../api";
const props = defineProps<{ active: boolean }>();
const rows = ref<any[]>([]),
  unknown = ref<any[]>([]),
  editing = ref<any>(null);
const events = ref<any[]>([]),
  busy = ref(false),
  budgetGiB = ref(0);
const management = computed<Record<string, string>>(() => ({
  ACTIVE: t("nodeSettings.active"),
  WRITE_PAUSED: t("nodeSettings.paused"),
  MAINTENANCE: t("nodeSettings.maintenance"),
}));
const health = computed<Record<string, string>>(() => ({
  UNKNOWN: t("nodeSettings.unverified"),
  ONLINE: t("nodeSettings.online"),
  DEGRADED: t("nodeSettings.degraded"),
  OFFLINE: t("nodeSettings.offline"),
}));
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
      ? ElMessage.success(t("nodeSettings.testPassed"))
      : ElMessage.error(t("nodeSettings.testFailed"));
  });
}
</script>
<template>
  <section class="node-settings" v-loading="busy">
    <h3>{{ t("nodes.count") }}</h3>
    <p>
      {{ t("nodeSettings.help") }}
    </p>
    <el-alert
      v-if="unknown.length"
      type="error"
      :closable="false"
      :title="
        t('nodeSettings.unknown') + unknown.map((n) => n.backend).join('、')
      "
    />
    <el-table class="node-desktop" :data="rows">
      <el-table-column prop="name" :label="t('nodes.node')" min-width="120" />
      <el-table-column :label="t('common.status')" min-width="140"
        ><template #default="{ row }"
          >{{ management[row.management] }} / {{ health[row.state]
          }}{{ row.stale ? t("nodeSettings.stale") : ""
          }}{{ !row.enabled ? t("nodeSettings.disabled") : "" }}</template
        ></el-table-column
      >
      <el-table-column :label="t('nodeSettings.usageReserved')" min-width="170"
        ><template #default="{ row }"
          >{{ bytes(row.usedBytes) }} / {{ bytes(row.reservedBytes) }}</template
        ></el-table-column
      >
      <el-table-column :label="t('nodeSettings.budget')" min-width="120"
        ><template #default="{ row }">{{
          row.budget_bytes
            ? bytes(row.budget_bytes)
            : t("nodeSettings.unlimited")
        }}</template></el-table-column
      >
      <el-table-column :label="t('common.actions')" min-width="210"
        ><template #default="{ row }">
          <el-button text @click="edit(row)">{{
            t("nodeSettings.details")
          }}</el-button>
          <el-button text @click="testNode(row)">{{
            t("nodeSettings.test")
          }}</el-button>
          <el-button
            v-if="!['local', 'minio'].includes(row.backend)"
            text
            :disabled="row.referenced"
            @click="
              action(() =>
                api.delete(`/admin/nodes/${encodeURIComponent(row.backend)}`),
              )
            "
            >{{ t("common.delete") }}</el-button
          >
        </template></el-table-column
      >
    </el-table>
    <div class="node-mobile">
      <article v-for="row in rows" :key="row.backend" class="node-card">
        <strong>{{ row.name }}</strong>
        <p>
          {{ management[row.management] }} / {{ health[row.state]
          }}{{ row.stale ? t("nodeSettings.stale") : ""
          }}{{ !row.enabled ? t("nodeSettings.disabled") : "" }}
        </p>
        <p>
          {{ t("nodeSettings.used") }} {{ bytes(row.usedBytes) }}
          {{ t("nodeSettings.reserved") }}
          {{ bytes(row.reservedBytes) }}
        </p>
        <p>
          {{ t("nodeSettings.budgetPrefix")
          }}{{
            row.budget_bytes
              ? bytes(row.budget_bytes)
              : t("nodeSettings.unlimited")
          }}
        </p>
        <el-button text @click="edit(row)">{{
          t("nodeSettings.details")
        }}</el-button
        ><el-button text @click="testNode(row)">{{
          t("nodeSettings.test")
        }}</el-button>
      </article>
    </div>
    <p>
      {{ t("nodeSettings.capacityHelp") }}
    </p>
    <el-dialog
      append-to-body
      class="cn-dialog"
      :model-value="!!editing"
      :title="t('nodeSettings.configuration')"
      width="min(620px, 94vw)"
      @close="editing = null"
    >
      <el-form v-if="editing" label-position="top">
        <el-form-item :label="t('files.name')"
          ><el-input v-model="editing.name" maxlength="80"
        /></el-form-item>
        <el-form-item :label="t('nodeSettings.management')"
          ><el-select v-model="editing.management"
            ><el-option
              v-for="(label, key) in management"
              :key="key"
              :label="label"
              :value="key" /></el-select
        ></el-form-item>
        <el-checkbox v-model="editing.enabled">{{
          t("nodeSettings.enabled")
        }}</el-checkbox
        ><el-checkbox v-model="editing.allow_upload">{{
          t("nodeSettings.upload")
        }}</el-checkbox
        ><el-checkbox v-model="editing.allow_migration">{{
          t("nodeSettings.migration")
        }}</el-checkbox>
        <el-form-item :label="t('nodeSettings.budgetGiB')"
          ><el-input-number
            v-model="budgetGiB"
            :min="0"
            :max="8388607"
            :precision="3"
        /></el-form-item>
        <el-form-item :label="t('nodeSettings.endpoint')"
          ><el-input
            v-model="editing.public_endpoint"
            placeholder="https://objects.example.com"
        /></el-form-item>
        <el-checkbox
          v-model="editing.direct_download"
          :disabled="editing.backend === 'local'"
          >{{ t("nodeSettings.direct") }}</el-checkbox
        >
        <p>
          {{ t("nodeSettings.checkedPrefix")
          }}{{ date(editing.checked_at) }}；{{ editing.latency_ms }} ms
        </p>
        <p>{{ t("nodeSettings.credentialsHelp") }}</p>
        <el-table :data="events"
          ><el-table-column :label="t('nodeSettings.history')"
            ><template #default="{ row }">{{
              health[row.state]
            }}</template></el-table-column
          ><el-table-column :label="t('common.time')"
            ><template #default="{ row }">{{
              date(row.created_at)
            }}</template></el-table-column
          ></el-table
        >
      </el-form>
      <template #footer
        ><el-button @click="editing = null">{{ t("common.cancel") }}</el-button
        ><el-button type="primary" @click="action(save)">{{
          t("common.save")
        }}</el-button></template
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
