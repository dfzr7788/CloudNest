<script setup lang="ts">
import { onBeforeUnmount, ref, watch } from "vue";
import { ElMessageBox } from "element-plus";
import { api, date, errorText, type Entry } from "../api";
import { ai, aiFinished, aiStage } from "../api/ai";
import { workspace, type WorkspaceTask } from "../api/ai-workspace";
const props = defineProps<{ file: Entry; modelId: string }>();
const versions = ref<{ id: string; created_at: number }[]>([]),
  revision = ref(0),
  from = ref(""),
  to = ref("current"),
  error = ref(""),
  busy = ref(false),
  task = ref<WorkspaceTask | null>(null);
const preview = ref<{
  digest: string;
  revision: number;
  notice: string;
  changes: { type: string; line: number; text: string }[];
} | null>(null);
let timer: ReturnType<typeof setTimeout> | undefined,
  epoch = 0,
  pending: { body: object; key: string } | undefined;
function stop() {
  epoch++;
  clearTimeout(timer);
}
async function guard(fn: () => Promise<void>) {
  busy.value = true;
  error.value = "";
  try {
    await fn();
  } catch (e) {
    if (e !== "cancel" && e !== "close") error.value = errorText(e);
  } finally {
    busy.value = false;
  }
}
async function load() {
  stop();
  preview.value = null;
  task.value = null;
  await guard(async () => {
    const v = (await api.get(`/files/${props.file.id}/versions`)).data;
    versions.value = v.items;
    revision.value = v.revision;
    from.value = v.items[0]?.id || "";
    const jobs = await ai.tasks(props.file.id);
    task.value =
      (jobs.find((t) => t.kind === "VERSION_DIFF") as WorkspaceTask) || null;
    if (task.value && !aiFinished(task.value)) schedule();
  });
}
async function inspect() {
  await guard(async () => {
    preview.value = (
      await api.get(`/ai/files/${props.file.id}/change-preview`, {
        params: { fromVersion: from.value, toVersion: to.value },
      })
    ).data;
  });
}
async function generate() {
  if (!preview.value) return;
  await guard(async () => {
    const ms = await ai.models(),
      cs = await ai.connections(),
      m = ms.find((m) => m.id === props.modelId);
    const dest =
      cs.find((c) => c.id === m?.connection_id)?.base_url || "所选聊天供应商";
    await ElMessageBox.confirm(
      `将这两个版本的已列差异发送至 ${dest}，可能收费。行号为提取文本位置。`,
      "确认版本差异外发",
      { confirmButtonText: "确认并解释差异", type: "warning" },
    );
    const body = {
      modelId: props.modelId,
      expectedRevision: preview.value!.revision,
      fromVersion: from.value,
      toVersion: to.value,
      expectedDigest: preview.value!.digest,
    };
    if (!pending || JSON.stringify(pending.body) !== JSON.stringify(body))
      pending = { body, key: crypto.randomUUID() };
    task.value = await workspace.job(
      `files/${props.file.id}/change-summaries`,
      body,
      pending.key,
    );
    pending = undefined;
    schedule();
  });
}
function schedule() {
  const token = epoch;
  clearTimeout(timer);
  timer = setTimeout(() => void poll(token), document.hidden ? 7000 : 2000);
}
async function poll(token: number) {
  if (token !== epoch || !task.value) return;
  try {
    const t = await workspace.task(task.value.id);
    if (token !== epoch) return;
    task.value = t;
    if (!aiFinished(t)) schedule();
  } catch (e) {
    error.value = errorText(e);
  }
}
watch(
  () => props.file.id,
  () => void load(),
  { immediate: true },
);
watch([from, to], () => {
  preview.value = null;
});
onBeforeUnmount(stop);
</script>
<template>
  <section class="version-diff">
    <h3>版本变化摘要</h3>
    <p v-if="!versions.length">
      此文件还没有历史版本；可先通过文件的历史版本功能添加新版本。
    </p>
    <template v-else
      ><p>
        当前修订 {{ revision }}。先查看确定性文本差异，确认后再让模型解释。
      </p>
      <el-select v-model="from" aria-label="比较起始版本"
        ><el-option value="current" label="当前版本" /><el-option
          v-for="v in versions"
          :key="v.id"
          :value="v.id"
          :label="date(v.created_at)" /></el-select
      ><span> → </span
      ><el-select v-model="to" aria-label="比较结束版本"
        ><el-option value="current" label="当前版本" /><el-option
          v-for="v in versions"
          :key="v.id"
          :value="v.id"
          :label="date(v.created_at)" /></el-select
      ><el-button :disabled="busy || from === to" @click="inspect"
        >查看版本文本差异</el-button
      ></template
    >
    <p v-if="error" role="alert">{{ error }}</p>
    <div v-if="preview">
      <p>{{ preview.notice }}</p>
      <pre>{{
        preview.changes
          .map((c) => `${c.type === "ADDED" ? "+" : "−"} ${c.line}: ${c.text}`)
          .join("\n") || "可提取正文没有变化"
      }}</pre>
      <el-button
        :disabled="busy || !modelId || (!!task && !aiFinished(task))"
        @click="generate"
        >生成版本变化摘要（可能收费）</el-button
      >
    </div>
    <div v-if="task" aria-live="polite">
      <p>{{ aiStage(task) }} · {{ task.error_message }}</p>
      <pre>{{ task.result?.text }}</pre>
      <el-button
        v-if="!aiFinished(task)"
        @click="
          guard(async () => {
            task = (await ai.cancel(task!.id)) as WorkspaceTask;
            schedule();
          })
        "
        >停止差异摘要</el-button
      ><el-button
        @click="
          guard(async () => {
            task = await workspace.task(task!.id);
            if (!aiFinished(task!)) schedule();
          })
        "
        >刷新差异任务状态</el-button
      >
    </div>
  </section>
</template>
<style scoped>
.version-diff {
  margin-top: 24px;
  border-top: 1px solid #dce3ef;
  padding-top: 12px;
  overflow-wrap: anywhere;
}
.version-diff :deep(.el-select) {
  width: 220px;
  max-width: 100%;
  margin: 6px 0;
}
.version-diff :deep(.el-button) {
  margin: 4px;
}
pre {
  white-space: pre-wrap;
  max-height: 350px;
  overflow: auto;
  overflow-wrap: anywhere;
  background: #f6f7fc;
  padding: 12px;
}
</style>
