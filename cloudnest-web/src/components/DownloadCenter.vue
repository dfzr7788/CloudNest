<script setup lang="ts">
import { ref, onMounted, onBeforeUnmount, watch } from "vue";
import { api, date, errorText } from "../api";
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
      folderCount?: number;
    }[]
  >([]),
  error = ref("");
const labels: Record<string, string> = {
  QUEUED: "排队中",
  BUILDING: "正在打包",
  READY: "可下载",
  FAILED: "打包失败",
};
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
async function retry(ids: string[]) {
  try {
    await api.post("/downloads", { fileIds: ids });
    await load();
    ElMessage.success("已重新创建打包任务");
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
    style="
      margin-top: 18px;
      padding: 18px;
      border: 1px solid #e5eaf6;
      border-radius: 12px;
      background: white;
    "
  >
    <div
      style="display: flex; justify-content: space-between; align-items: center"
    >
      <strong>下载任务</strong
      ><el-button size="small" @click="load">刷新任务</el-button>
    </div>
    <p style="margin: 10px 0; color: #8991a5">
      ZIP
      任务保留一小时，可在刷新页面后继续查看；下载到本机的完成状态由浏览器管理。
    </p>
    <el-alert
      v-if="error"
      :title="error"
      type="error"
      :closable="false"
    /><el-table v-if="jobs.length" :data="jobs"
      ><el-table-column label="创建时间" min-width="170"
        ><template #default="{ row }">{{
          date(row.createdAt)
        }}</template></el-table-column
      ><el-table-column label="内容" min-width="155"
        ><template #default="{ row }"
          ><span v-if="row.fileCount !== undefined"
            >{{ row.fileCount }} 个文件<span v-if="row.folderCount">
              · {{ row.folderCount }} 个目录</span
            ></span
          ><span v-else>{{ row.fileIds.length }} 个文件</span></template
        ></el-table-column
      ><el-table-column label="状态" min-width="150"
        ><template #default="{ row }"
          >{{
            row.expiresAt <= Date.now()
              ? "已过期"
              : labels[row.status] || row.status
          }}<small v-if="row.error"> · {{ row.error }}</small></template
        ></el-table-column
      ><el-table-column label="操作" width="180"
        ><template #default="{ row }"
          ><a
            v-if="row.status === 'READY' && row.expiresAt > Date.now()"
            :href="`/api/downloads/${row.id}/file`"
            >下载 ZIP</a
          ><el-button text @click="retry(row.fileIds)"
            >重新打包</el-button
          ></template
        ></el-table-column
      ></el-table
    >
    <p v-else>暂无下载任务。</p>
  </section>
</template>
