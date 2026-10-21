<script setup lang="ts">
import { requestKey } from "../utils/requestKey";
import { ref, watch, onUnmounted } from "vue";
import { ElMessage, ElMessageBox } from "element-plus";
import { api, bytes, date, errorText } from "../api";
import { t } from "../i18n";
const props = defineProps<{ folderId: string; base?: string }>();
const history = ref<any[]>([]),
  jobs = ref<any[]>([]),
  entries = ref<any[]>([]),
  changes = ref<any[]>([]);
const detail = ref<any>(null),
  preview = ref<any>(null),
  selected = ref("");
const busy = ref(false),
  failure = ref("");
const allHistory = ref(false);
const policy = ref({
  enabled: false,
  intervalHours: 24,
  keepCount: 30,
  protection: "NORMAL",
});
const presets = ["NORMAL", "IMPORTANT", "ARCHIVE"];
let timer: ReturnType<typeof setTimeout> | undefined;
let generation = 0,
  stopped = false;
const base = () => props.base || "/resilience";
async function load() {
  const token = ++generation;
  try {
    const [snapshots, tasks, policies] = await Promise.all([
      api.get(`${base()}/snapshots`, {
        params: allHistory.value ? {} : { folderId: props.folderId },
      }),
      api.get(`${base()}/tasks`),
      api.get(`${base()}/policies`),
    ]);
    if (token !== generation || stopped) return;
    history.value = snapshots.data;
    jobs.value = tasks.data;
    const found = policies.data.find(
      (p: any) => p.folder_id === props.folderId,
    );
    policy.value = found
      ? {
          enabled: found.enabled,
          intervalHours: found.interval_hours,
          keepCount: found.keep_count,
          protection: found.protection,
        }
      : {
          enabled: false,
          intervalHours: 24,
          keepCount: 30,
          protection: "NORMAL",
        };
    failure.value = "";
  } catch (e) {
    if (token === generation) failure.value = errorText(e);
  }
}
async function poll() {
  try {
    jobs.value = (await api.get(`${base()}/tasks`)).data;
  } catch (e) {
    failure.value = errorText(e);
  }
  if (!stopped) timer = setTimeout(poll, 5000);
}
watch(
  () => [props.folderId, props.base],
  () => {
    detail.value = null;
    preview.value = null;
    void load();
  },
  { immediate: true },
);
timer = setTimeout(poll, 5000);
onUnmounted(() => {
  stopped = true;
  generation++;
  clearTimeout(timer);
});
async function act(fn: () => Promise<unknown>) {
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
async function inspect(row: any) {
  await act(async () => {
    const [tree, diff] = await Promise.all([
      api.get(`${base()}/snapshots/${row.id}/entries`),
      api.get(`${base()}/snapshots/${row.id}/diff`),
    ]);
    detail.value = row;
    entries.value = tree.data;
    changes.value = diff.data.changes;
    selected.value = "";
    preview.value = null;
  });
}
async function restorePreview() {
  await act(async () => {
    preview.value = (
      await api.post(`${base()}/snapshots/${detail.value.id}/preview`, {
        targetFolder: props.folderId,
        selectedEntry: selected.value || null,
      })
    ).data;
  });
}
async function restore() {
  await act(async () => {
    await api.post(`${base()}/snapshots/${detail.value.id}/restore`, {
      targetFolder: props.folderId,
      selectedEntry: selected.value || null,
      requestKey: requestKey(),
    });
    preview.value = null;
    detail.value = null;
    ElMessage.success(t("next.restoreQueued"));
  });
}
async function remove(row: any) {
  try {
    await ElMessageBox.confirm(
      t("next.deleteSnapshotConfirm"),
      t("next.deleteSnapshot"),
    );
  } catch {
    return;
  }
  await act(() => api.delete(`${base()}/snapshots/${row.id}`));
}
function pathOf(entry: any) {
  const names = [entry.name];
  const seen = new Set([entry.entry_id]);
  let p = entry.parent_entry_id;
  while (p) {
    const parent = entries.value.find((e) => e.entry_id === p);
    if (!parent || seen.has(p)) break;
    seen.add(p);
    names.unshift(parent.name);
    p = parent.parent_entry_id;
  }
  return names.join(" / ");
}
</script>
<template>
  <section class="next-panel" v-loading="busy">
    <el-alert v-if="failure" :title="failure" type="error" :closable="false" />
    <el-alert :title="t('next.snapshotNotice')" type="info" :closable="false" />
    <div class="next-toolbar">
      <h2>{{ t("next.timeMachine") }}</h2>
      <el-checkbox v-model="allHistory" @change="load">{{
        t("next.allSnapshots")
      }}</el-checkbox
      ><el-button
        type="primary"
        @click="act(() => api.post(`${base()}/snapshots`, { folderId }))"
        >{{ t("next.createSnapshot") }}</el-button
      ><el-button @click="load">{{ t("next.refresh") }}</el-button>
    </div>
    <el-table :data="history" :empty-text="t('next.noSnapshots')">
      <el-table-column prop="name" :label="t('next.name')" min-width="180" />
      <el-table-column :label="t('next.created')" min-width="170"
        ><template #default="{ row }">{{
          date(row.created_at)
        }}</template></el-table-column
      >
      <el-table-column
        prop="entry_count"
        :label="t('next.entries')"
        width="100"
      />
      <el-table-column :label="t('next.size')" width="110"
        ><template #default="{ row }">{{
          bytes(row.total_bytes)
        }}</template></el-table-column
      >
      <el-table-column :label="t('next.actions')" width="180"
        ><template #default="{ row }"
          ><el-button link @click="inspect(row)">{{
            t("next.browse")
          }}</el-button
          ><el-button link type="danger" @click="remove(row)">{{
            t("next.delete")
          }}</el-button></template
        ></el-table-column
      >
    </el-table>
    <h3>{{ t("next.protection") }}</h3>
    <el-form label-position="top" class="next-form">
      <el-form-item :label="t('next.preset')"
        ><el-select v-model="policy.protection"
          ><el-option
            v-for="preset in presets"
            :key="preset"
            :value="preset"
            :label="t(`next.${preset}`)" /></el-select
      ></el-form-item>
      <el-form-item :label="t('next.autoSnapshot')"
        ><el-switch v-model="policy.enabled"
      /></el-form-item>
      <el-form-item :label="t('next.intervalHours')"
        ><el-input-number v-model="policy.intervalHours" :min="1" :max="8760"
      /></el-form-item>
      <el-form-item :label="t('next.keepCount')"
        ><el-input-number v-model="policy.keepCount" :min="1" :max="365"
      /></el-form-item>
      <el-form-item
        ><el-button
          @click="
            act(() => api.put(`${base()}/policies`, { ...policy, folderId }))
          "
          >{{ t("next.save") }}</el-button
        ></el-form-item
      >
    </el-form>
    <p class="muted">{{ t("next.protectionNotice") }}</p>
    <h3>{{ t("next.tasks") }}</h3>
    <el-table :data="jobs" :empty-text="t('next.noTasks')">
      <el-table-column :label="t('next.type')" width="130"
        ><template #default="{ row }">{{
          t(`next.${row.kind}`)
        }}</template></el-table-column
      >
      <el-table-column :label="t('next.status')" min-width="130"
        ><template #default="{ row }">{{
          t(`next.${row.status}`)
        }}</template></el-table-column
      >
      <el-table-column :label="t('next.progress')" width="140"
        ><template #default="{ row }"
          >{{ row.progress }} / {{ row.total }}</template
        ></el-table-column
      >
      <el-table-column
        prop="error_code"
        :label="t('next.result')"
        min-width="170"
      />
      <el-table-column :label="t('next.actions')" min-width="200"
        ><template #default="{ row }"
          ><el-button
            v-if="['QUEUED', 'RUNNING'].includes(row.status)"
            link
            @click="act(() => api.post(`${base()}/tasks/${row.id}/pause`))"
            >{{ t("next.pause") }}</el-button
          ><el-button
            v-if="row.status === 'PAUSED'"
            link
            @click="act(() => api.post(`${base()}/tasks/${row.id}/resume`))"
            >{{ t("next.resume") }}</el-button
          ><el-button
            v-if="row.status === 'FAILED'"
            link
            @click="act(() => api.post(`${base()}/tasks/${row.id}/retry`))"
            >{{ t("next.retry") }}</el-button
          ><el-button
            v-if="!['COMPLETE', 'CANCELLED'].includes(row.status)"
            link
            type="danger"
            @click="act(() => api.post(`${base()}/tasks/${row.id}/cancel`))"
            >{{ t("next.cancel") }}</el-button
          ></template
        ></el-table-column
      >
    </el-table>
    <el-dialog
      :model-value="!!detail"
      :title="t('next.history')"
      width="900px"
      class="cn-dialog"
      @close="detail = null"
    >
      <p>{{ t("next.restoreNotice") }}</p>
      <el-radio-group v-model="selected" @change="preview = null"
        ><el-radio value="">{{
          t("next.wholeFolder")
        }}</el-radio></el-radio-group
      >
      <el-table
        :data="entries"
        max-height="320"
        @row-click="
          (row: any) => {
            selected = row.entry_id;
            preview = null;
          }
        "
      >
        <el-table-column width="55"
          ><template #default="{ row }"
            ><el-radio
              v-model="selected"
              :value="row.entry_id"
              :aria-label="row.name"
              @change="preview = null"
              ><span /></el-radio></template
        ></el-table-column>
        <el-table-column :label="t('next.path')" min-width="240"
          ><template #default="{ row }">{{
            pathOf(row)
          }}</template></el-table-column
        >
        <el-table-column :label="t('next.size')" width="120"
          ><template #default="{ row }">{{
            bytes(row.size)
          }}</template></el-table-column
        >
      </el-table>
      <h3>{{ t("next.changes") }}</h3>
      <el-table :data="changes" max-height="180"
        ><el-table-column prop="name" :label="t('next.name')" /><el-table-column
          :label="t('next.type')"
          ><template #default="{ row }">{{
            t(`next.${row.kind}`)
          }}</template></el-table-column
        ></el-table
      >
      <el-alert
        v-if="preview"
        :title="
          t('next.previewSummary', {
            count: preview.entries.length,
            size: bytes(preview.totalBytes),
          })
        "
        type="warning"
        :closable="false"
      />
      <template #footer
        ><el-button @click="detail = null">{{ t("next.close") }}</el-button
        ><el-button :disabled="busy" @click="restorePreview">{{
          t("next.preview")
        }}</el-button
        ><el-button
          v-if="preview"
          type="primary"
          :disabled="busy"
          @click="restore"
          >{{ t("next.confirmRestore") }}</el-button
        ></template
      >
    </el-dialog>
  </section>
</template>
