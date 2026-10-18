<script setup lang="ts">
import { computed, ref } from "vue";
import { useUploads, type UploadTask } from "../stores/uploads";
import { bytes, errorText } from "../api";
import { ElMessage } from "element-plus";
import {
  UploadFilled,
  Close,
  VideoPause,
  VideoPlay,
} from "@element-plus/icons-vue";
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
      <el-icon><UploadFilled /></el-icon><strong>传输列表</strong
      ><span>{{
        active
          ? active + " 正在传输 / " + store.tasks.length
          : store.tasks.length + " 个任务"
      }}</span
      ><span>{{ expanded ? "收起" : "展开" }}</span>
    </button>
    <div v-if="expanded" id="cn-upload-list" class="upload-list">
      <div class="upload-tools">
        <el-button size="small" @click="store.pauseAll">全部暂停</el-button
        ><el-button size="small" @click="store.resumeAll"
          >继续 / 重试全部</el-button
        ><el-button size="small" @click="store.clearCompleted"
          >清除已结束</el-button
        >
      </div>
      <div v-for="t in store.tasks" :key="t.key" class="upload-task">
        <div class="task-top">
          <strong :title="t.name">{{ t.name }}</strong
          ><span>{{ bytes(t.size) }}</span>
        </div>
        <el-progress
          :aria-label="t.state === '校验文件' ? '本地文件校验进度' : '上传进度'"
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
            >{{ t.state
            }}{{ t.state === "校验文件" ? "（本地计算）" : "" }}</span
          >
          <small v-if="t.state === '上传中' && t.speed"
            >{{ bytes(t.sent || 0) }} · {{ bytes(t.speed) }}/s · 约
            {{ Math.ceil(t.remaining || 0) }} 秒</small
          >
          <div>
            <el-button
              v-if="t.active"
              text
              size="small"
              :icon="VideoPause"
              @click="store.pause(t)"
              >暂停</el-button
            ><el-button
              v-else-if="
                ['已暂停', '上传失败', '等待选择原文件'].includes(t.state)
              "
              text
              size="small"
              :icon="VideoPlay"
              @click="t.file ? store.run(t) : choose(t)"
              >{{ t.file ? "继续 / 重试" : "选择原文件" }}</el-button
            ><el-button
              v-if="
                !t.state.includes('完成') &&
                !['已取消', '已跳过'].includes(t.state)
              "
              text
              size="small"
              :icon="Close"
              @click="cancel(t)"
              >取消</el-button
            >
          </div>
        </div>
        <p v-if="t.error" class="task-error" role="alert">{{ t.error }}</p>
      </div>
    </div>
    <input ref="input" type="file" hidden @change="selected" />
  </aside>
</template>
