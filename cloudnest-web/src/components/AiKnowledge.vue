<script setup lang="ts">
import { computed, onBeforeUnmount, ref, watch } from "vue";
import { ElMessageBox } from "element-plus";
import { api, date, errorText } from "../api";
import { ai, aiFinished, aiStage, type AiModel } from "../api/ai";
import {
  workspace,
  type KnowledgeBase,
  type WorkspaceTask,
  type IndexPreview,
  type KbSession,
  type Source,
} from "../api/ai-workspace";
import AiFilePicker from "./AiFilePicker.vue";
const props = defineProps<{ active: boolean }>();
const enabled = ref(false),
  models = ref<AiModel[]>([]),
  bases = ref<KnowledgeBase[]>([]),
  selected = ref(""),
  busy = ref(false),
  error = ref("");
const kb = computed(() => bases.value.find((x) => x.id === selected.value));
const embeddingModels = computed(() =>
  models.value.filter((m) => m.purpose === "EMBEDDING" && m.enabled),
);
const chatModels = computed(() =>
  models.value.filter((m) => m.purpose !== "EMBEDDING" && m.enabled),
);
const editor = ref(false),
  editId = ref<string | null>(null),
  name = ref(""),
  model = ref(""),
  scopes = ref<string[]>([]);
const preview = ref<IndexPreview | null>(null),
  partial = ref(false),
  question = ref(""),
  chatModel = ref("");
const task = ref<WorkspaceTask | null>(null),
  search = ref<WorkspaceTask | null>(null),
  history = ref<WorkspaceTask[]>([]),
  sessions = ref<KbSession[]>([]),
  sessionId = ref("");
const citation = ref<Source | null>(null);
let timer: ReturnType<typeof setTimeout> | undefined,
  epoch = 0;
let pending: { path: string; body: object; key: string } | undefined;
const running = computed(() => !!task.value && !aiFinished(task.value));
function stop() {
  epoch++;
  clearTimeout(timer);
}
async function refresh() {
  bases.value = await workspace.list();
}
async function load() {
  try {
    const [caps, ms, list, pref] = await Promise.all([
      ai.capabilities(),
      ai.models(),
      workspace.list(),
      ai.preferences(),
    ]);
    enabled.value = caps.enabled;
    models.value = ms;
    bases.value = list;
    chatModel.value =
      pref.default_chat_model_id || chatModels.value[0]?.id || "";
    if (!bases.value.some((x) => x.id === selected.value))
      selected.value = bases.value[0]?.id || "";
    if (selected.value) await choose();
  } catch (e) {
    error.value = errorText(e);
  }
}
async function choose() {
  stop();
  preview.value = null;
  task.value = null;
  search.value = null;
  history.value = [];
  sessionId.value = "";
  error.value = "";
  if (!selected.value) return;
  try {
    sessions.value = await workspace.sessions(selected.value);
    const jobs = await ai.tasks();
    const found = jobs.find(
      (t) =>
        (t as WorkspaceTask).workspace_id === selected.value && !aiFinished(t),
    );
    if (found) {
      task.value = found as WorkspaceTask;
      schedule();
    }
  } catch (e) {
    error.value = errorText(e);
  }
}
function edit(existing?: KnowledgeBase) {
  editId.value = existing?.id || null;
  name.value = existing?.name || "";
  model.value = existing?.model_id || embeddingModels.value[0]?.id || "";
  scopes.value = existing?.scopeIds.slice() || [];
  editor.value = true;
}
async function guarded(action: () => Promise<void>) {
  busy.value = true;
  error.value = "";
  try {
    await action();
  } catch (e) {
    if (e !== "cancel" && e !== "close") error.value = errorText(e);
  } finally {
    busy.value = false;
  }
}
async function save() {
  await guarded(async () => {
    const saved = await workspace.save(editId.value, {
      name: name.value,
      embeddingModelId: model.value,
      scopeIds: scopes.value,
    });
    editor.value = false;
    selected.value = saved.id;
    await refresh();
    await choose();
  });
}
async function remove() {
  if (!kb.value) return;
  await guarded(async () => {
    await ElMessageBox.confirm(
      "删除此知识库、索引和问答记录，源文件保留。",
      "删除知识库",
      { type: "warning" },
    );
    await workspace.remove(selected.value);
    selected.value = "";
    await load();
  });
}
async function inspect() {
  await guarded(async () => {
    preview.value = await workspace.preview(selected.value);
    partial.value = false;
  });
}
async function submit(path: string, body: object) {
  if (
    !pending ||
    pending.path !== path ||
    JSON.stringify(pending.body) !== JSON.stringify(body)
  )
    pending = { path, body, key: crypto.randomUUID() };
  task.value = await workspace.job(path, body, pending.key);
  pending = undefined;
  schedule();
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
    if (aiFinished(fresh)) {
      if (fresh.kind === "KB_SEARCH" && fresh.status === "SUCCEEDED")
        search.value = fresh;
      await refresh();
      if (sessionId.value)
        history.value = await workspace.history(sessionId.value);
    } else schedule();
  } catch (e) {
    error.value = errorText(e) + "；可刷新任务状态，系统不会自动重发付费请求。";
  }
}
async function index() {
  if (!kb.value || !preview.value) return;
  await guarded(async () => {
    await ElMessageBox.confirm(
      `将已列出的文件正文发送至 ${preview.value!.destination} 生成向量；本次最多 8 批并可能收费，失败批次可能已计费。${preview.value!.files.some((f) => f.partial) ? "包含部分提取文件。" : ""}`,
      "确认索引外发",
      { confirmButtonText: "确认并开始索引", type: "warning" },
    );
    await submit(`knowledge-bases/${selected.value}/index-jobs`, {
      modelId: kb.value!.model_id,
      expectedDigest: preview.value!.digest,
      allowPartial: partial.value,
    });
  });
}
async function find() {
  if (!kb.value) return;
  await guarded(async () => {
    const connections = await ai.connections();
    const m = models.value.find((m) => m.id === kb.value!.model_id);
    const destination =
      connections.find((c) => c.id === m?.connection_id)?.base_url ||
      "所选向量供应商";
    await ElMessageBox.confirm(
      `将问题发送至 ${destination}，产生 1 次向量调用；不会自动调用聊天模型。`,
      "确认语义检索",
      { confirmButtonText: "确认并检索", type: "warning" },
    );
    search.value = null;
    await submit(`knowledge-bases/${selected.value}/semantic-search`, {
      modelId: kb.value!.model_id,
      question: question.value,
    });
  });
}
async function answer() {
  if (!search.value || !chatModel.value) return;
  await guarded(async () => {
    const connections = await ai.connections();
    const m = models.value.find((m) => m.id === chatModel.value);
    const destination =
      connections.find((c) => c.id === m?.connection_id)?.base_url ||
      "所选聊天供应商";
    await ElMessageBox.confirm(
      `将问题、所选来源片段和最多 6 轮历史发送至 ${destination}，产生 1 次聊天调用，可能收费。`,
      "确认知识库问答",
      { confirmButtonText: "确认并生成回答", type: "warning" },
    );
    if (
      !sessionId.value ||
      sessions.value.find((s) => s.id === sessionId.value)?.model_id !==
        chatModel.value ||
      sessions.value.find((s) => s.id === sessionId.value)?.readOnly
    ) {
      const s = await workspace.createSession(selected.value, chatModel.value);
      sessionId.value = s.id;
      sessions.value = await workspace.sessions(selected.value);
    }
    await submit(`knowledge-bases/${selected.value}/answers`, {
      modelId: chatModel.value,
      question: question.value,
      searchTaskId: search.value!.id,
      sessionId: sessionId.value,
    });
  });
}
async function readSession() {
  await guarded(async () => {
    history.value = sessionId.value
      ? await workspace.history(sessionId.value)
      : [];
  });
}
async function removeSession() {
  if (!sessionId.value) return;
  await guarded(async () => {
    await ElMessageBox.confirm("清除本会话全部问题和回答？", "删除会话");
    await workspace.deleteSession(sessionId.value);
    sessionId.value = "";
    history.value = [];
    sessions.value = await workspace.sessions(selected.value);
  });
}
async function showSource(source: Source, tid: string) {
  await guarded(async () => {
    const fresh = await workspace.task(tid);
    citation.value =
      fresh.result?.sources?.find((s) => s.sourceId === source.sourceId) ||
      null;
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
</script>
<template>
  <section class="knowledge">
    <h3>AI 知识库</h3>
    <p>
      选择文件快照后手动建立索引。新上传文件不会自动加入；内容变化后需确认更新。每库最多
      100 个文件、1000 块，不会自动产生向量费用。
    </p>
    <p v-if="!enabled">站点 AI 未开启。</p>
    <p v-else-if="!embeddingModels.length">
      请先在 AI 设置中添加并测试向量模型；聊天模型仍可用于摘要和单文档问答。
    </p>
    <p v-if="error" role="alert">{{ error }}</p>
    <el-button
      :disabled="!enabled || !embeddingModels.length || busy"
      @click="edit()"
      >新建知识库</el-button
    >
    <el-select
      v-model="selected"
      aria-label="选择知识库"
      placeholder="选择知识库"
      @change="choose"
      ><el-option
        v-for="base in bases"
        :key="base.id"
        :value="base.id"
        :label="base.name"
    /></el-select>
    <template v-if="kb">
      <p>
        {{ kb.name }} · {{ kb.chunks }} 块 ·
        {{ kb.ready ? "可检索" : "尚无可用索引"
        }}<span v-if="kb.needsUpdate"> · 需要更新</span>
      </p>
      <el-button :disabled="busy || running" @click="edit(kb)"
        >修改范围 / 刷新文件快照</el-button
      ><el-button :disabled="busy || running" @click="inspect"
        >预览索引范围</el-button
      ><el-button
        type="danger"
        plain
        :disabled="busy || running"
        @click="remove"
        >删除知识库</el-button
      >
      <div v-if="preview" class="card">
        <p>
          {{ preview.chunks }} 块；保守估计最多
          {{ preview.maxCalls }} 次向量请求（复用及合批后会减少），本次任务最多
          8 批。
        </p>
        <p>发送至 {{ preview.destination }}</p>
        <p>{{ preview.notice }}</p>
        <ul>
          <li v-for="file in preview.files" :key="file.fileId">
            {{ file.name }} · 修订 {{ file.revision }} ·
            {{ file.partial ? "部分提取" : "完整提取" }} {{ file.reason }}
          </li>
        </ul>
        <el-checkbox
          v-if="preview.files.some((f) => f.partial)"
          v-model="partial"
          >同意仅索引已提取部分</el-checkbox
        >
        <el-button
          type="primary"
          :disabled="
            busy ||
            running ||
            (preview.files.some((f) => f.partial) && !partial)
          "
          @click="index"
          >确认 / 继续索引（可能收费）</el-button
        >
      </div>
      <el-form label-position="top" @submit.prevent="find"
        ><el-form-item label="语义搜索问题"
          ><el-input
            v-model="question"
            type="textarea"
            :maxlength="2000"
            :rows="3" /></el-form-item
        ><el-button
          :disabled="busy || running || !kb.ready || !question.trim()"
          @click="find"
          >检索相关片段（可能收费）</el-button
        ></el-form
      >
      <p>
        语义搜索只返回索引中仍有权限的当前修订片段，相关度不代表事实正确；原有文件名和正文搜索继续可用。
      </p>
      <el-select
        v-model="chatModel"
        aria-label="知识库聊天模型"
        placeholder="选择聊天模型"
        ><el-option
          v-for="m in chatModels"
          :key="m.id"
          :value="m.id"
          :label="m.name"
      /></el-select>
      <el-button
        :disabled="
          busy ||
          running ||
          !chatModel ||
          !search?.result?.sources?.length ||
          search?.question !== question.trim()
        "
        @click="answer"
        >基于这些片段回答（可能收费）</el-button
      >
      <el-select
        v-model="sessionId"
        aria-label="知识库问答历史"
        clearable
        placeholder="新会话 / 历史会话"
        @change="readSession"
        ><el-option
          v-for="s in sessions"
          :key="s.id"
          :value="s.id"
          :label="
            date(s.updated_at) + (s.readOnly ? ' · 只读' : '')
          " /></el-select
      ><el-button
        :disabled="!sessionId || busy || running"
        @click="removeSession"
        >删除会话</el-button
      >
      <div v-if="task" class="card" aria-live="polite">
        <p>{{ aiStage(task) }} · 已完成 {{ task.completed_calls }} 次调用</p>
        <p v-if="task.error_message" role="alert">
          {{ task.error_message }}（{{ task.error_code }}）
        </p>
        <p v-if="task.question">问题：{{ task.question }}</p>
        <p class="answer">{{ task.result?.text }}</p>
        <p v-if="task.result?.invalidCitations">
          部分来源标识无效，已过滤，请核对原文。
        </p>
        <el-button
          v-if="running"
          @click="
            guarded(async () => {
              task = (await ai.cancel(task!.id)) as WorkspaceTask;
              schedule();
            })
          "
          >停止任务</el-button
        ><el-button
          @click="
            guarded(async () => {
              task = await workspace.task(task!.id);
              if (!aiFinished(task!)) schedule();
            })
          "
          >刷新任务状态</el-button
        >
        <div
          v-for="source in task.result?.sources"
          :key="source.sourceId"
          class="source"
        >
          <el-button link @click="showSource(source, task!.id)"
            >{{ source.name }} · {{ source.locator }}</el-button
          >
          <p>{{ source.text }}</p>
        </div>
      </div>
      <div
        v-for="turn in history.filter((t) => t.id !== task?.id)"
        :key="turn.id"
        class="card"
      >
        <small>{{ date(turn.created_at) }} · {{ aiStage(turn) }}</small>
        <p v-if="turn.question">问题：{{ turn.question }}</p>
        <p class="answer">{{ turn.result?.text || turn.error_message }}</p>
        <el-button
          v-for="source in turn.result?.sources"
          :key="source.sourceId"
          link
          @click="showSource(source, turn.id)"
          >{{ source.name }} · {{ source.locator }}</el-button
        >
      </div>
    </template>
    <el-dialog class="cn-dialog"
      v-model="editor"
      :title="editId ? '修改知识库' : '新建知识库'"
      width="680px"
      append-to-body
      ><el-form label-position="top" @submit.prevent="save"
        ><el-form-item label="知识库名称"
          ><el-input v-model="name" maxlength="100" /></el-form-item
        ><el-form-item label="知识库向量模型"
          ><el-select v-model="model"
            ><el-option
              v-for="m in embeddingModels"
              :key="m.id"
              :value="m.id"
              :label="m.name" /></el-select></el-form-item
        ><el-form-item label="文件或文件夹快照"
          ><AiFilePicker v-model="scopes" :max="100"
        /></el-form-item>
        <p>保存仅更新文件快照，不调用供应商。修改模型后需建立完整新代次。</p>
        <el-button
          type="primary"
          :disabled="busy || !name.trim() || !model || !scopes.length"
          @click="save"
          >保存知识库</el-button
        ></el-form
      ></el-dialog
    >
    <el-dialog class="cn-dialog"
      :model-value="!!citation"
      title="知识库来源原文"
      width="650px"
      append-to-body
      @close="citation = null"
      ><template v-if="citation"
        ><p>
          {{ citation.name }} · 修订 {{ citation.revision }} ·
          {{ citation.locator }}
        </p>
        <pre>{{ citation.text }}</pre>
        <a
          :href="`/api/files/${citation.file_id}/download`"
          target="_blank"
          rel="noopener"
          >打开来源文件</a
        ></template
      ></el-dialog
    >
  </section>
</template>
<style scoped>
.knowledge {
  line-height: 1.7;
  overflow-wrap: anywhere;
}
.knowledge :deep(.el-select) {
  max-width: 100%;
  width: 280px;
  margin: 8px 8px 8px 0;
}
.knowledge :deep(.el-button) {
  margin: 4px;
}
.card {
  padding: 14px;
  margin: 14px 0;
  border: 1px solid var(--cn-border);
  border-radius: 10px;
}
.answer,
pre {
  white-space: pre-wrap;
  overflow-wrap: anywhere;
}
.source {
  background: var(--cn-subtle);
  padding: 8px;
  margin: 8px 0;
}
.source p {
  white-space: pre-wrap;
  margin: 4px 0;
}
pre {
  max-height: 400px;
  overflow: auto;
}
</style>
