<script setup lang="ts">
import { ref, watch, onUnmounted } from "vue";
import { api, date, errorText, type Entry } from "../../api";
import { ElMessage } from "element-plus";
const props = defineProps<{ files: Entry[] }>();
const emit = defineEmits<{ close: [] }>();
const targets = ref<any[]>([]),
  jobs = ref<any[]>([]),
  protection = ref<any>(null);
const target = ref(""),
  kind = ref("REPLICATE"),
  busy = ref(false);
let timer: ReturnType<typeof setTimeout> | undefined;
let generation = 0;
let stopped = false;
async function load() {
  const token = ++generation;
  try {
    const [nodes, tasks] = await Promise.all([
      api.get("/storage/targets"),
      api.get("/storage/jobs"),
    ]);
    if (token !== generation) return;
    targets.value = nodes.data;
    jobs.value = tasks.data;
    if (props.files.length === 1 && props.files[0]?.kind === "FILE") {
      const result = await api.get(
        `/storage/files/${props.files[0].id}/replicas`,
      );
      if (token === generation) protection.value = result.data;
    }
  } catch (e) {
    ElMessage.error(errorText(e));
  }
}
async function poll() {
  await load();
  if (!stopped && props.files.length) timer = setTimeout(poll, 5000);
}
watch(
  () => props.files,
  (files) => {
    clearTimeout(timer);
    generation++;
    protection.value = null;
    if (files.length) void poll();
  },
  { immediate: true },
);
onUnmounted(() => {
  stopped = true;
  generation++;
  clearTimeout(timer);
});
async function stopProtection() {
  try {
    await api.delete(`/storage/files/${props.files[0]!.id}/protection`);
    await load();
    ElMessage.success("已停止自动修复，已有副本保留");
  } catch (e) {
    ElMessage.error(errorText(e));
  }
}
async function submit() {
  busy.value = true;
  try {
    const fileIds = props.files
      .filter((f) => f.kind === "FILE")
      .map((f) => f.id);
    if (fileIds.length)
      await api.post("/storage/jobs", {
        kind: kind.value,
        target: target.value,
        fileIds,
      });
    for (const file of props.files.filter((f) => f.kind === "FOLDER"))
      await api.post("/storage/jobs", {
        kind: kind.value,
        target: target.value,
        folderId: file.id,
      });
    ElMessage.success("任务已提交；完成校验后生效");
    await load();
  } catch (e) {
    ElMessage.error(errorText(e));
  } finally {
    busy.value = false;
  }
}
async function action(job: any, retry: boolean) {
  try {
    if (retry) await api.post("/storage/jobs/" + job.id + "/retry");
    else await api.delete("/storage/jobs/" + job.id);
    await load();
  } catch (e) {
    ElMessage.error(errorText(e));
  }
}
</script>
<template>
  <el-dialog
    :model-value="!!files.length"
    title="存储与副本"
    width="min(840px,94vw)"
    class="cn-dialog"
    append-to-body
    @close="emit('close')"
  >
    <p>
      已选
      {{ files.length }}
      项。目录包含所有未删除的子文件；共享同一内容的文件会一起改变物理存储位置。
    </p>
    <el-alert
      v-if="protection"
      :closable="false"
      :type="protection.protected ? 'success' : 'warning'"
      :title="
        protection.protected
          ? '已有两个不同故障域的在线已验证副本'
          : '尚未达到异域双副本保护；请查看副本状态和任务结果'
      "
    />
    <el-table v-if="protection" :data="protection.replicas"
      ><el-table-column prop="backend" label="副本节点" /><el-table-column
        prop="state"
        label="完整性" /><el-table-column prop="health" label="健康"
    /></el-table>
    <el-button v-if="protection?.desiredReplicas === 2" @click="stopProtection"
      >停止自动副本保护（保留已有数据）</el-button
    >
    <el-form label-position="top">
      <el-form-item label="操作"
        ><el-radio-group v-model="kind"
          ><el-radio value="REPLICATE">创建异域保护副本</el-radio
          ><el-radio value="MIGRATE">迁移主存储</el-radio></el-radio-group
        ></el-form-item
      >
      <el-form-item label="目标节点"
        ><el-select v-model="target" placeholder="选择目标节点"
          ><el-option
            v-for="n in targets"
            :key="n.backend"
            :label="n.name + ' · ' + n.failure_domain"
            :value="n.backend" /></el-select
      ></el-form-item>
      <el-button
        type="primary"
        :disabled="!target"
        :loading="busy"
        @click="submit"
        >提交后台任务</el-button
      >
    </el-form>
    <h3>我的存储任务</h3>
    <el-table :data="jobs"
      ><el-table-column
        prop="file_name"
        label="文件"
        min-width="140"
      /><el-table-column
        prop="target_backend"
        label="目标"
        min-width="140"
      /><el-table-column
        prop="status"
        label="状态"
        width="120"
      /><el-table-column
        prop="error"
        label="详情"
        min-width="180"
      /><el-table-column label="提交时间" min-width="165"
        ><template #default="{ row }">{{
          date(row.created_at)
        }}</template></el-table-column
      ><el-table-column label="操作" width="90"
        ><template #default="{ row }"
          ><el-button
            v-if="['FAILED', 'INTERRUPTED', 'CANCELLED'].includes(row.status)"
            text
            @click="action(row, true)"
            >重试</el-button
          ><el-button
            v-if="['QUEUED', 'RUNNING'].includes(row.status)"
            text
            @click="action(row, false)"
            >取消</el-button
          ></template
        ></el-table-column
      ></el-table
    >
    <p>
      迁移会保留来源数据。双副本不能替代独立备份；副本全部不可用时，系统会明确报告失败。
    </p>
  </el-dialog>
</template>
