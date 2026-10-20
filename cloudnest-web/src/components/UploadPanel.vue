<script setup lang="ts">
import { t as tr } from "../i18n";
import { computed, ref } from "vue";
import { useUploads, type UploadTask } from "../stores/uploads";
import FileMark from "./FileMark.vue";
import { bytes, errorText } from "../api";
import { ElMessage } from "element-plus";
import {
  UploadFilled,
  Close,
  VideoPause,
  VideoPlay,
} from "@element-plus/icons-vue";
const uploadStateKeys: Record<string, string> = {
  等待选择原文件: "transfers.state.choose",
  校验文件: "transfers.state.hashing",
  已跳过: "transfers.state.skipped",
  秒传完成: "transfers.state.instant",
  上传中: "transfers.state.uploading",
  合并校验中: "transfers.state.merging",
  已完成: "transfers.state.complete",
  已取消: "transfers.state.cancelled",
  已暂停: "transfers.state.paused",
  上传失败: "transfers.state.failed",
  排队中: "transfers.state.queued",
};
function uploadStateLabel(state: string) {
  return uploadStateKeys[state] ? tr(uploadStateKeys[state]) : state;
}
const store = useUploads(),
  expanded = ref(true),
  input = ref<HTMLInputElement>(),
  choosing = ref<UploadTask>();
const active = computed(() => store.tasks.filter((t) => t.active).length);
function choose(t: UploadTask) {
  choosing.value = t;
  input.value?.click();
}
async function selected(e: Event) {
  const file = (e.target as HTMLInputElement).files?.[0];
  try {
    if (file && choosing.value) await store.choose(choosing.value, file);
  } catch (e) {
    ElMessage.error(errorText(e));
  }
  (e.target as HTMLInputElement).value = "";
}
async function cancel(t: UploadTask) {
  try {
    await store.cancel(t);
  } catch (e) {
    ElMessage.error(errorText(e));
  }
}
</script>
<template>
  <aside v-if="store.tasks.length" class="upload-panel">
    <button
      class="upload-panel-head"
      :aria-expanded="expanded"
      aria-controls="cn-upload-list"
      @click="expanded = !expanded"
    >
      <el-icon><UploadFilled /></el-icon
      ><strong>{{ tr("transfers.title") }}</strong
      ><span>{{
        active
          ? tr("transfers.countActive", { active, total: store.tasks.length })
          : tr("transfers.count", { count: store.tasks.length })
      }}</span
      ><span>{{
        expanded ? tr("transfers.collapse") : tr("transfers.expand")
      }}</span>
    </button>
    <div v-if="expanded" id="cn-upload-list" class="upload-list">
      <div class="upload-tools">
        <el-button size="small" @click="store.pauseAll">{{
          tr("transfers.pauseAll")
        }}</el-button
        ><el-button size="small" @click="store.resumeAll">{{
          tr("transfers.resumeAll")
        }}</el-button
        ><el-button size="small" @click="store.clearCompleted">{{
          tr("transfers.clear")
        }}</el-button>
      </div>
      <div v-for="t in store.tasks" :key="t.key" class="upload-task">
        <div class="task-top">
          <FileMark kind="FILE" :name="t.name" /><strong :title="t.name">{{
            t.name
          }}</strong
          ><span>{{ bytes(t.size) }}</span>
        </div>
        <el-progress
          :aria-label="
            t.state === '校验文件'
              ? tr('transfers.hashProgress')
              : tr('transfers.uploadProgress')
          "
          :percentage="Math.round(t.progress)"
          :status="
            t.state.includes('完成')
              ? 'success'
              : t.error
                ? 'exception'
                : undefined
          "
          :stroke-width="5"
        />
        <div class="task-bottom">
          <span role="status"
            >{{ uploadStateLabel(t.state)
            }}{{ t.state === "校验文件" ? tr("transfers.local") : "" }}</span
          >
          <small v-if="t.state === '上传中' && t.speed">{{
            tr("transfers.speed", {
              sent: bytes(t.sent || 0),
              speed: bytes(t.speed),
              seconds: Math.ceil(t.remaining || 0),
            })
          }}</small>
          <div>
            <el-button
              v-if="t.active"
              text
              size="small"
              :icon="VideoPause"
              @click="store.pause(t)"
              >{{ tr("transfers.pause") }}</el-button
            ><el-button
              v-else-if="
                ['已暂停', '上传失败', '等待选择原文件'].includes(t.state)
              "
              text
              size="small"
              :icon="VideoPlay"
              @click="t.file ? store.run(t) : choose(t)"
              >{{
                t.file ? tr("transfers.resume") : tr("transfers.chooseOriginal")
              }}</el-button
            ><el-button
              v-if="
                !t.state.includes('完成') &&
                !['已取消', '已跳过'].includes(t.state)
              "
              text
              size="small"
              :icon="Close"
              @click="cancel(t)"
              >{{ tr("common.cancel") }}</el-button
            >
          </div>
        </div>
        <p v-if="t.error" class="task-error" role="alert">{{ t.error }}</p>
      </div>
    </div>
    <input ref="input" type="file" hidden @change="selected" />
  </aside>
</template>
