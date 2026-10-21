<script setup lang="ts">
import { ref, watch, onUnmounted } from "vue";
import { api, errorText } from "../../api";
import { t } from "../../i18n";
import { ElMessage, ElMessageBox } from "element-plus";
const props = defineProps<{ active: boolean }>();
const data = ref<any>({ policies: [], jobs: [] }),
  source = ref("local"),
  target = ref("local"),
  busy = ref(false);
let timer: ReturnType<typeof setTimeout> | undefined,
  generation = 0;
async function load() {
  data.value = (await api.get("/admin/storage")).data;
}
async function poll(mine: number) {
  try {
    const result = await api.get("/admin/storage");
    if (mine === generation) data.value = result.data;
  } catch (e) {
    if (mine === generation) ElMessage.error(errorText(e));
  }
  if (mine === generation && props.active)
    timer = setTimeout(() => poll(mine), 5000);
}
watch(
  () => props.active,
  (active) => {
    clearTimeout(timer);
    const mine = ++generation;
    if (active) void poll(mine);
  },
  { immediate: true },
);
onUnmounted(() => {
  generation++;
  clearTimeout(timer);
});
async function act(fn: () => Promise<unknown>) {
  busy.value = true;
  try {
    await fn();
    await load();
  } catch (e) {
    if (e !== "cancel" && e !== "close") ElMessage.error(errorText(e));
  } finally {
    busy.value = false;
  }
}
</script>
<template>
  <section v-loading="busy">
    <h2>{{ t("storageOps.title") }}</h2>
    <p>{{ t("storageOps.help") }}</p>
    <el-form inline label-position="top">
      <el-form-item
        v-for="side in ['source', 'target']"
        :key="side"
        :label="
          t(side === 'source' ? 'storageOps.source' : 'storageOps.target')
        "
      >
        <el-select
          :model-value="side === 'source' ? source : target"
          @update:model-value="
            side === 'source' ? (source = $event) : (target = $event)
          "
          style="width: 240px; max-width: 100%"
        >
          <el-option value="local" :label="t('nodePolicy.local')" /><el-option
            value="minio"
            :label="t('nodePolicy.legacy')"
          />
          <el-option
            v-for="p in data.policies"
            :key="p.id"
            :value="'s3:' + p.id"
            :label="p.name"
          />
        </el-select>
      </el-form-item>
      <el-form-item
        ><el-button
          :disabled="source === target || busy"
          @click="
            act(async () => {
              await ElMessageBox.confirm(
                t('storageOps.help'),
                t('storageOps.start'),
              );
              await api.post('/admin/storage/migrations', { source, target });
            })
          "
          >{{ t("storageOps.start") }}</el-button
        ></el-form-item
      >
    </el-form>
    <el-table :data="data.jobs">
      <el-table-column
        prop="status"
        :label="t('common.status')"
        min-width="130"
      />
      <el-table-column :label="t('storageOps.progress')" min-width="110"
        ><template #default="{ row }"
          >{{ row.completed }} / {{ row.total }}</template
        ></el-table-column
      >
      <el-table-column
        prop="error"
        :label="t('storageOps.details')"
        min-width="180"
      />
      <el-table-column :label="t('common.actions')" min-width="110"
        ><template #default="{ row }">
          <el-button
            v-if="['QUEUED', 'RUNNING'].includes(row.status)"
            text
            @click="
              act(() => api.delete('/admin/storage/migrations/' + row.id))
            "
            >{{ t("common.cancel") }}</el-button
          >
        </template></el-table-column
      >
    </el-table>
  </section>
</template>
