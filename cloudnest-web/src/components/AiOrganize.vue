<script setup lang="ts">
import { onBeforeUnmount, ref, watch, computed } from "vue";
import { ElMessageBox } from "element-plus";
import { api, errorText } from "../api";
import { ai, aiFinished, aiStage, type AiModel } from "../api/ai";
import { workspace, type WorkspaceTask } from "../api/ai-workspace";
import AiFilePicker from "./AiFilePicker.vue";
const props = defineProps<{ active: boolean }>();
const files = ref<string[]>([]),
  folders = ref<string[]>([]),
  models = ref<AiModel[]>([]),
  model = ref(""),
  enabled = ref(false),
  busy = ref(false),
  error = ref("");
interface Preview {
  digest: string;
  notice: string;
  sources: {
    fileId: string;
    name: string;
    revision: number;
    text: string;
    partial: boolean;
  }[];
  folders: { id: string; name: string }[];
}
const preview = ref<Preview | null>(null),
  task = ref<WorkspaceTask | null>(null),
  outcomes = ref<{ fileId: string; status: string; message?: string }[]>([]);
const choices = ref<
  Record<string, { applyName: boolean; applyMove: boolean; applyTags: boolean }>
>({});
const running = computed(() => !!task.value && !aiFinished(task.value));
let timer: ReturnType<typeof setTimeout> | undefined,
  epoch = 0,
  pending: { body: object; key: string } | undefined;
function outcomeLabel(status: string) {
  return (
    (
      {
        APPLIED: "已应用",
        ALREADY_APPLIED: "此前已应用",
        FAILED: "未应用",
        SKIPPED: "已跳过",
      } as Record<string, string>
    )[status] || status
  );
}
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
  await guard(async () => {
    const [caps, ms, pref, jobs] = await Promise.all([
      ai.capabilities(),
      ai.models(),
      ai.preferences(),
      ai.tasks(),
    ]);
    enabled.value = caps.enabled;
    models.value = ms.filter((m) => m.purpose !== "EMBEDDING" && m.enabled);
    model.value = pref.default_chat_model_id || models.value[0]?.id || "";
    task.value =
      (jobs.find((t) => t.kind === "ORGANIZE") as WorkspaceTask) || null;
    if (task.value && !aiFinished(task.value)) schedule();
    initChoices();
  });
}
function initChoices() {
  choices.value = {};
  for (const s of task.value?.result?.suggestions || [])
    choices.value[s.fileId] = {
      applyName: false,
      applyTags: false,
      applyMove: false,
    };
}
async function inspect() {
  await guard(async () => {
    preview.value = (
      await api.post<Preview>("/ai/organization-preview", {
        fileIds: files.value,
        targetFolderIds: folders.value,
      })
    ).data;
  });
}
async function generate() {
  if (!preview.value) return;
  await guard(async () => {
    const cs = await ai.connections(),
      m = models.value.find((m) => m.id === model.value),
      destination =
        cs.find((c) => c.id === m?.connection_id)?.base_url || "所选供应商";
    await ElMessageBox.confirm(
      `将已列文件名、原标签、开头最多 1000 字符及目标目录名发送至 ${destination}，可能收费。仅生成建议。`,
      "确认整理建议外发",
      { confirmButtonText: "确认并生成建议", type: "warning" },
    );
    const body = {
      modelId: model.value,
      fileIds: files.value,
      targetFolderIds: folders.value,
      expectedDigest: preview.value!.digest,
      allowPartial: true,
    };
    if (!pending || JSON.stringify(pending.body) !== JSON.stringify(body))
      pending = { body, key: crypto.randomUUID() };
    task.value = await workspace.job("organization-jobs", body, pending.key);
    pending = undefined;
    outcomes.value = [];
    schedule();
  });
}
function schedule() {
  clearTimeout(timer);
  const token = epoch;
  timer = setTimeout(() => void poll(token), document.hidden ? 7000 : 2000);
}
async function poll(token: number) {
  if (token !== epoch || !props.active || !task.value) return;
  try {
    const fresh = await workspace.task(task.value.id);
    if (token !== epoch) return;
    task.value = fresh;
    if (aiFinished(fresh)) initChoices();
    else schedule();
  } catch (e) {
    error.value = errorText(e) + "；可刷新任务状态，不会自动重发付费请求。";
  }
}
async function apply() {
  if (!task.value) return;
  await guard(async () => {
    const selections = Object.entries(choices.value)
      .filter(([, c]) => c.applyName || c.applyMove || c.applyTags)
      .map(([fileId, c]) => ({ fileId, ...c }));
    if (!selections.length) return;
    await ElMessageBox.confirm(
      `将对 ${selections.length} 个文件应用勾选的改名、标签合并或移动。冲突项会单独报告，不覆盖同名文件。`,
      "确认应用整理建议",
      { confirmButtonText: "确认应用", type: "warning" },
    );
    outcomes.value = (
      await api.post(`/ai/tasks/${task.value!.id}/apply-organization`, {
        selections,
      })
    ).data;
  });
}
watch(
  () => props.active,
  (active) => {
    stop();
    if (active) void load();
  },
  { immediate: true },
);
onBeforeUnmount(stop);
watch(
  [files, folders],
  () => {
    preview.value = null;
  },
  { deep: true },
);
</script>
<template>
  <section class="organize">
    <h3>智能整理</h3>
    <p>
      只提出文件名、标签和目标目录建议。每次最多 8 个文件，使用开头最多 1000
      字符；请检查理由和覆盖范围后逐项勾选。
    </p>
    <p v-if="error" role="alert">{{ error }}</p>
    <el-form label-position="top"
      ><el-form-item label="需要整理的文件"
        ><AiFilePicker v-model="files" files-only :max="8" /></el-form-item
      ><el-form-item label="允许建议的目标目录"
        ><AiFilePicker v-model="folders" folders-only :max="20" /></el-form-item
      ><el-form-item label="整理聊天模型"
        ><el-select v-model="model"
          ><el-option
            v-for="m in models"
            :key="m.id"
            :label="m.name"
            :value="m.id" /></el-select></el-form-item
    ></el-form>
    <el-button
      :disabled="
        !enabled || !files.length || !folders.length || busy || running
      "
      @click="inspect"
      >预览整理范围（不调用模型）</el-button
    >
    <div v-if="preview" class="card">
      <p>{{ preview.notice }}</p>
      <p v-for="file in preview.sources" :key="file.fileId">
        {{ file.name }} · 修订 {{ file.revision }} ·
        {{ file.partial ? "部分正文" : "完整提取正文" }}
      </p>
      <p>目标：{{ preview.folders.map((f) => f.name).join("、") }}</p>
      <el-button
        type="primary"
        :disabled="busy || running || !model"
        @click="generate"
        >生成整理建议（可能收费）</el-button
      >
    </div>
    <div v-if="task" class="card" aria-live="polite">
      <p>{{ aiStage(task) }} · {{ task.result?.text || task.error_message }}</p>
      <p>{{ task.result?.notice }}</p>
      <el-button
        v-if="running"
        @click="
          guard(async () => {
            task = (await ai.cancel(task!.id)) as WorkspaceTask;
            schedule();
          })
        "
        >停止整理任务</el-button
      ><el-button
        @click="
          guard(async () => {
            task = await workspace.task(task!.id);
            initChoices();
            if (!aiFinished(task!)) schedule();
          })
        "
        >刷新整理任务状态</el-button
      >
      <div
        v-for="suggestion in task.result?.suggestions"
        :key="suggestion.fileId"
        class="suggestion"
      >
        <h4>{{ suggestion.originalName }}</h4>
        <p>{{ suggestion.reason }}</p>
        <template v-if="choices[suggestion.fileId]"
          ><el-checkbox v-model="choices[suggestion.fileId]!.applyName"
            >改名为 {{ suggestion.name }}</el-checkbox
          ><el-checkbox v-model="choices[suggestion.fileId]!.applyTags"
            >合并标签：{{
              suggestion.tags.join("、") || "无新标签"
            }}</el-checkbox
          ><el-checkbox v-model="choices[suggestion.fileId]!.applyMove"
            >移动到
            {{
              preview?.folders.find((f) => f.id === suggestion.targetFolderId)
                ?.name || "生成时选定的目标目录"
            }}</el-checkbox
          ></template
        >
        <p
          v-for="outcome in outcomes.filter(
            (o) => o.fileId === suggestion.fileId,
          )"
          :key="outcome.fileId"
          role="status"
        >
          {{ outcomeLabel(outcome.status) }} {{ outcome.message }}
        </p>
      </div>
      <el-button
        v-if="task.result?.suggestions?.length"
        type="primary"
        :disabled="busy || running"
        @click="apply"
        >确认应用勾选项</el-button
      >
    </div>
  </section>
</template>
<style scoped>
.organize {
  line-height: 1.7;
  overflow-wrap: anywhere;
}
.card {
  border: 1px solid var(--cn-border);
  padding: 14px;
  margin: 14px 0;
  border-radius: 10px;
}
.suggestion {
  padding: 12px 0;
  border-top: 1px solid var(--cn-border);
}
.suggestion :deep(.el-checkbox) {
  display: flex;
  white-space: normal;
  height: auto;
  margin: 8px 0;
}
.suggestion :deep(.el-checkbox__label) {
  white-space: normal;
}
.organize :deep(.el-button) {
  margin: 4px;
}
</style>
