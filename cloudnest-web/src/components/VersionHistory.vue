<script setup lang="ts">
import { ref, watch } from "vue";
import { ElMessage, ElMessageBox } from "element-plus";
import { api, bytes, date, errorText, type Entry } from "../api";
import { useUploads } from "../stores/uploads";
const props = defineProps<{ file: Entry | null; folderId: string }>(),
  emit = defineEmits<{ close: []; changed: [] }>();
const versions = ref<
    { id: string; size: number; mime: string; created_at: number }[]
  >([]),
  revision = ref(0),
  busy = ref(false),
  input = ref<HTMLInputElement>();
const uploads = useUploads();
async function load() {
  if (!props.file) return;
  try {
    const { data } = await api.get(`/files/${props.file.id}/versions`);
    versions.value = data.items;
    revision.value = data.revision;
  } catch (e) {
    ElMessage.error(errorText(e));
  }
}
watch(() => props.file, load);
async function restore(id: string) {
  try {
    await api.post(`/files/${props.file!.id}/versions/${id}/restore`, {
      expectedRevision: revision.value,
    });
    await load();
    emit("changed");
    ElMessage.success("已切换当前版本，原当前内容保留在历史中");
  } catch (e) {
    ElMessage.error(errorText(e));
  }
}
async function remove(id: string) {
  try {
    await ElMessageBox.confirm(
      "此历史版本将永久删除，无法恢复。",
      "清理历史版本",
      { confirmButtonText: "删除", cancelButtonText: "取消" },
    );
    await api.delete(`/files/${props.file!.id}/versions/${id}`);
    await load();
    emit("changed");
  } catch (e) {
    if (e !== "cancel" && e !== "close") ElMessage.error(errorText(e));
  }
}
async function selected(e: Event) {
  const element = e.target as HTMLInputElement,
    file = element.files?.[0],
    target = props.file,
    expected = revision.value;
  element.value = "";
  if (!file || !target) return;
  busy.value = true;
  try {
    await uploads.enqueue([file], target.parentId || props.folderId, "keep");
    const task = uploads.tasks[uploads.tasks.length - 1]!;
    await uploads.run(task);
    if (!task.sessionId || !task.state.includes("完成"))
      throw new Error(
        task.error ||
          "上传未完成，可在传输列表继续，完成后重新选择文件提交版本",
      );
    const session = (await api.get(`/uploads/${task.sessionId}`)).data;
    await api.post(`/files/${target.id}/versions`, {
      sourceId: session.fileId,
      expectedRevision: expected,
    });
    await load();
    emit("changed");
    ElMessage.success("新版本已保存");
  } catch (err) {
    ElMessage.error(
      errorText(err) + "；已上传成功的副本会保留，可在文件列表查看。",
    );
  } finally {
    busy.value = false;
  }
}
</script>
<template>
  <el-dialog
    class="cn-dialog"
    :model-value="!!file"
    title="文件版本历史"
    width="min(720px,94vw)"
    @close="emit('close')"
    :close-on-click-modal="!busy"
    :show-close="!busy"
  >
    <p>{{ file?.name }} · 当前修订 {{ revision }}</p>
    <p>
      历史版本占用配额，最多保留 20
      份。恢复会交换当前与历史内容，不重复计费。单文件分享固定到创建时的内容。
    </p>
    <el-button type="primary" :loading="busy" @click="input?.click()"
      >上传并保存新版本</el-button
    ><input ref="input" type="file" hidden @change="selected" />
    <el-table :data="versions" empty-text="暂无历史版本"
      ><el-table-column label="保存时间" min-width="160"
        ><template #default="{ row }">{{
          date(row.created_at)
        }}</template></el-table-column
      ><el-table-column label="大小" width="110"
        ><template #default="{ row }">{{
          bytes(row.size)
        }}</template></el-table-column
      ><el-table-column label="操作" width="210"
        ><template #default="{ row }"
          ><a :href="`/api/files/${file!.id}/versions/${row.id}/download`"
            >下载</a
          ><el-button text :disabled="busy" @click="restore(row.id)"
            >恢复</el-button
          ><el-button
            text
            type="danger"
            :disabled="busy"
            @click="remove(row.id)"
            >删除</el-button
          ></template
        ></el-table-column
      ></el-table
    >
  </el-dialog>
</template>
