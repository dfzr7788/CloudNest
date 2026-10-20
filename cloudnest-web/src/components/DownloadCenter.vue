<script setup lang="ts">
import { t } from "../i18n";
import { computed, ref, onMounted, onBeforeUnmount, watch } from "vue";
import { api, date, errorText, bytes } from "../api";
import { useUser } from "../stores/user";
import { ElMessage } from "element-plus";
const props = defineProps<{ revision: number }>(),
  store = useUser(),
  jobs = ref<
    {
      id: string;
      status: string;
      error: string | null;
      createdAt: number;
      expiresAt: number;
      fileIds: string[];
      fileCount?: number;
      completedBytes?: number;
      totalBytes?: number;
      folderCount?: number;
    }[]
  >([]),
  error = ref("");
const labels = computed<Record<string, string>>(() => ({
  QUEUED: t("nodes.queued"),
  BUILDING: t("downloads.packing"),
  READY: t("downloads.ready"),
  FAILED: t("downloads.failed"),
  CANCELLED: t("nodes.cancelled"),
}));
let timer: ReturnType<typeof setTimeout> | undefined,
  disposed = false,
  loading = false;
async function load() {
  if (disposed || !store.user || loading) return;
  loading = true;
  try {
    jobs.value = (await api.get("/downloads")).data;
    error.value = "";
  } catch (e) {
    error.value = errorText(e);
  } finally {
    loading = false;
  }
}
async function poll() {
  await load();
  if (!disposed)
    timer = setTimeout(
      poll,
      jobs.value.some((j) => ["QUEUED", "BUILDING"].includes(j.status))
        ? 2000
        : 10000,
    );
}
async function cancel(id: string) {
  try {
    await api.delete(`/downloads/${id}`);
    await load();
  } catch (e) {
    ElMessage.error(errorText(e));
  }
}
async function retry(ids: string[]) {
  try {
    await api.post("/downloads", { fileIds: ids });
    await load();
    ElMessage.success(t("downloads.recreated"));
  } catch (e) {
    ElMessage.error(errorText(e));
  }
}
watch(() => props.revision, load);
watch(() => store.user?.id, load);
onMounted(poll);
onBeforeUnmount(() => {
  disposed = true;
  clearTimeout(timer);
});
</script>
<template>
  <section
    id="download-tasks"
    class="download-center"
    :class="{ 'is-empty': !jobs.length && !error }"
  >
    <div class="download-heading">
      <strong>{{ t("downloads.title") }}</strong
      ><el-button size="small" @click="load">{{
        t("downloads.refresh")
      }}</el-button>
    </div>
    <p class="muted" v-if="jobs.length">
      {{ t("downloads.retention") }}
    </p>
    <el-alert
      v-if="error"
      :title="error"
      type="error"
      :closable="false"
    /><el-table v-if="jobs.length" :data="jobs"
      ><el-table-column :label="t('downloads.created')" min-width="170"
        ><template #default="{ row }">{{
          date(row.createdAt)
        }}</template></el-table-column
      ><el-table-column :label="t('downloads.content')" min-width="155"
        ><template #default="{ row }"
          ><span v-if="row.fileCount !== undefined"
            >{{ row.fileCount }} {{ t("downloads.files")
            }}<span v-if="row.folderCount">
              · {{ row.folderCount }} {{ t("downloads.folders") }}</span
            ></span
          ><span v-else
            >{{ row.fileIds.length }} {{ t("downloads.files") }}</span
          ></template
        ></el-table-column
      ><el-table-column :label="t('common.status')" min-width="150"
        ><template #default="{ row }"
          >{{
            row.expiresAt <= Date.now()
              ? t("downloads.expired")
              : labels[row.status] || row.status
          }}<small v-if="row.error"> · {{ row.error }}</small></template
        ></el-table-column
      ><el-table-column :label="t('common.actions')" width="180"
        ><template #default="{ row }"
          ><a
            v-if="row.status === 'READY' && row.expiresAt > Date.now()"
            :href="`/api/downloads/${row.id}/file`"
            >{{ t("downloads.zip") }}</a
          ><span v-if="row.totalBytes"
            >{{ bytes(row.completedBytes || 0) }} /
            {{ bytes(row.totalBytes) }}</span
          ><el-button
            v-if="['QUEUED', 'BUILDING'].includes(row.status)"
            text
            @click="cancel(row.id)"
            >{{ t("downloads.cancel") }}</el-button
          ><el-button text @click="retry(row.fileIds)">{{
            t("downloads.retry")
          }}</el-button></template
        ></el-table-column
      ></el-table
    >
    <p v-else class="muted">{{ t("downloads.empty") }}</p>
  </section>
</template>
