<script setup lang="ts">
import { requestKey } from "../utils/requestKey";
import { ref, watch, onBeforeUnmount, computed } from "vue";
import { ElMessageBox } from "element-plus";
import { date, errorText, type Entry } from "../api";
import {
  ai,
  aiFinished,
  aiStage,
  type AiConversation,
  type AiModel,
  type AiConnection,
  type AiTask,
  type AiCitation,
} from "../api/ai";
const props = defineProps<{
  file: Entry;
  modelId: string;
  models: AiModel[];
  connections: AiConnection[];
}>();
const items = ref<AiConversation[]>([]),
  current = ref<AiConversation | null>(null),
  question = ref(""),
  error = ref(""),
  busy = ref(false),
  page = ref(1),
  allowPartial = ref(false),
  citation = ref<AiCitation | null>(null),
  task = ref<AiTask | null>(null);
const selectedModel = ref(props.modelId);
const destination = computed(() =>
  props.connections.find(
    (c) =>
      c.id ===
      props.models.find((m) => m.id === selectedModel.value)?.connection_id,
  ),
);
let alive = true,
  epoch = 0,
  timer: ReturnType<typeof setTimeout> | undefined,
  pending: { key: string; signature: string } | undefined;
watch(
  () => props.modelId,
  (id) => {
    if (!selectedModel.value) selectedModel.value = id;
  },
);
async function list() {
  items.value = await ai.conversations(props.file.id, page.value);
}
async function open(id: string) {
  clearTimeout(timer);
  const mine = ++epoch;
  error.value = "";
  try {
    const c = await ai.conversation(id);
    if (!alive || mine !== epoch) return;
    const active = c.activeTaskId ? await ai.task(c.activeTaskId) : null;
    if (!alive || mine !== epoch) return;
    current.value = c;
    task.value = active;
    schedule();
  } catch (e) {
    if (alive && mine === epoch) error.value = errorText(e);
  }
}
function schedule() {
  clearTimeout(timer);
  if (!alive || !current.value || !task.value || aiFinished(task.value)) return;
  const mine = epoch,
    id = task.value.taskId;
  timer = setTimeout(
    async () => {
      try {
        const t = await ai.task(id);
        if (!alive || mine !== epoch) return;
        task.value = t;
        if (aiFinished(t)) {
          const c = await ai.conversation(current.value!.id);
          if (!alive || mine !== epoch) return;
          current.value = c;
          await list();
        }
      } catch (e) {
        if (alive && mine === epoch) error.value = errorText(e);
        return;
      }
      if (alive && mine === epoch) schedule();
    },
    document.hidden ? 8000 : 2000,
  );
}
async function create() {
  busy.value = true;
  error.value = "";
  try {
    const coverage = await ai.analysis(
      props.file.id,
      selectedModel.value,
      262144,
    );
    if (coverage.status === "NO_EXTRACTABLE_TEXT")
      throw new Error("文件没有可提取文本");
    current.value = await ai.createConversation({
      fileId: props.file.id,
      modelId: selectedModel.value,
      expectedRevision: coverage.revision,
    });
    ++epoch;
    clearTimeout(timer);
    task.value = null;
    pending = undefined;
    question.value = "";
    await list();
  } catch (e) {
    error.value = errorText(e);
  } finally {
    busy.value = false;
  }
}
async function send() {
  if (!current.value || !question.value.trim()) return;
  busy.value = true;
  error.value = "";
  try {
    await ElMessageBox.confirm(
      `将问题、最多 6 轮历史和本次检索的文档片段发送至 ${current.value.destination}，使用 ${current.value.model_name}。每次提问最多调用模型 1 次，可能计费。长文档仅覆盖关键词命中的片段。`,
      "发送文档问题",
      { confirmButtonText: "确认发送", cancelButtonText: "取消" },
    );
    const input = {
      question: question.value.trim(),
      allowPartial: allowPartial.value,
    };
    const signature = JSON.stringify({ id: current.value.id, input });
    if (!pending || pending.signature !== signature)
      pending = { key: requestKey(), signature };
    task.value = await ai.question(current.value.id, input, pending.key);
    pending = undefined;
    question.value = "";
    current.value = await ai.conversation(current.value.id);
    schedule();
  } catch (e) {
    if (e !== "cancel" && e !== "close") error.value = errorText(e);
  } finally {
    busy.value = false;
  }
}
async function stop() {
  if (!task.value) return;
  try {
    task.value = await ai.cancel(task.value.taskId);
    if (current.value) current.value = await ai.conversation(current.value.id);
    schedule();
  } catch (e) {
    error.value = errorText(e);
  }
}
async function remove() {
  if (!current.value) return;
  try {
    await ElMessageBox.confirm(
      "删除本会话的全部问题、答案和引用，并停止正在生成的任务。源文件会保留。",
      "删除会话",
      { type: "warning" },
    );
    await ai.deleteConversation(current.value.id);
    ++epoch;
    clearTimeout(timer);
    current.value = null;
    task.value = null;
    pending = undefined;
    citation.value = null;
    await list();
  } catch (e) {
    if (e !== "cancel" && e !== "close") error.value = errorText(e);
  }
}
async function showCitation(id: string) {
  if (!current.value) return;
  try {
    citation.value = await ai.citation(current.value.id, id);
  } catch (e) {
    error.value = errorText(e);
  }
}
async function load() {
  try {
    await list();
    if (items.value[0]) await open(items.value[0].id);
  } catch (e) {
    error.value = errorText(e);
  }
}
async function changePage(delta: number) {
  page.value += delta;
  try {
    await list();
  } catch (e) {
    error.value = errorText(e);
  }
}
onBeforeUnmount(() => {
  alive = false;
  ++epoch;
  clearTimeout(timer);
});
void load();
</script>
<template>
  <section class="document-qa" v-loading="busy">
    <p>
      仅依据当前文档回答。长文档使用本地关键词检索，不保证覆盖全文；历史只作为背景。会话在最近活动
      30 天后清除。
    </p>
    <el-alert v-if="error" :title="error" type="error" :closable="false" />
    <el-select v-model="selectedModel" aria-label="新会话模型"
      ><el-option
        v-for="m in models.filter((m) => m.enabled)"
        :key="m.id"
        :value="m.id"
        :label="m.name"
    /></el-select>
    <p v-if="destination">新会话发送至 {{ destination.base_url }}</p>
    <el-button
      :disabled="busy || !selectedModel || !destination?.consented"
      @click="create"
      >新建文档会话（不调用模型）</el-button
    >
    <el-select
      :model-value="current?.id"
      aria-label="历史会话"
      placeholder="选择历史会话"
      @change="open"
      ><el-option
        v-for="item in items"
        :key="item.id"
        :value="item.id"
        :label="`${date(item.created_at)} · 修订 ${item.revision} · ${item.model_name}${item.readOnly ? ' · 只读' : ''}`"
    /></el-select>
    <div class="qa-pages">
      <el-button :disabled="page === 1" @click="changePage(-1)"
        >上一页会话</el-button
      ><span>第 {{ page }} 页</span
      ><el-button :disabled="items.length < 20" @click="changePage(1)"
        >下一页会话</el-button
      >
    </div>
    <template v-if="current">
      <p>
        修订 {{ current.revision }} · {{ current.model_name }} ·
        {{ current.destination }}
      </p>
      <el-alert
        v-if="current.readOnly"
        title="文件或模型配置已变化，此会话仅供阅读。请新建会话继续提问。"
        type="warning"
        :closable="false"
      />
      <div class="qa-transcript">
        <article
          v-for="message in current.messages"
          :key="message.id"
          class="qa-message"
          :class="
            message.role === 'user' ? 'qa-message-user' : 'qa-message-assistant'
          "
        >
          <strong>{{ message.role === "user" ? "你" : "文档助手" }}</strong>
          <p class="qa-text">{{ message.content }}</p>
          <el-button
            v-if="
              message.role === 'user' &&
              ['FAILED', 'CANCELLED', 'STALE'].includes(message.status) &&
              !current.readOnly
            "
            link
            type="primary"
            :disabled="!!current.activeTaskId"
            @click="question = message.content"
            >重新填写此问题</el-button
          >
          <small v-if="message.role === 'assistant' && message.scope"
            >{{ message.scope.partial ? "仅本次检索片段" : "完整提取范围" }} ·
            {{ message.scope.selectedCharacters }} 字符</small
          >
          <small
            v-if="message.role === 'user' && message.status !== 'SUCCEEDED'"
            >{{
              message.error_message ||
              (["QUEUED", "RUNNING"].includes(message.status)
                ? "等待回答"
                : `本次未完成（${message.status}），可以重新提问`)
            }}</small
          >
          <el-button
            v-for="source in message.citations"
            :key="source.id"
            link
            type="primary"
            @click="showCitation(source.id)"
            >[{{ source.source_id }}] {{ source.locator }}</el-button
          >
        </article>
      </div>
      <template v-if="task"
        ><p>
          {{ aiStage(task) }} · {{ task.completed_calls }}/{{
            task.total_calls ?? 1
          }}
          次调用
        </p>
        <p v-if="task.error_message" role="alert">{{ task.error_message }}</p>
        <p v-if="task.coverage">
          {{
            task.coverage.partial
              ? "本次仅覆盖检索片段"
              : "本次覆盖完整提取范围"
          }}
          · {{ task.coverage.selectedCharacters }} 字符
        </p>
        <el-button v-if="!aiFinished(task)" @click="stop"
          >停止回答</el-button
        ></template
      >
      <el-input
        v-model="question"
        type="textarea"
        :rows="3"
        :maxlength="2000"
        show-word-limit
        aria-label="文档问题"
        placeholder="询问这份文档，最多 2000 字符"
        :disabled="current.readOnly"
      />
      <el-checkbox v-model="allowPartial" :disabled="current.readOnly"
        >若提取不完整，同意仅使用已提取部分</el-checkbox
      >
      <el-button
        type="primary"
        :disabled="
          busy || current.readOnly || !question.trim() || !!current.activeTaskId
        "
        @click="send"
        >发送问题（可能收费）</el-button
      >
      <el-button @click="open(current.id)">刷新会话</el-button
      ><el-button type="danger" plain @click="remove">删除会话</el-button>
    </template>
    <el-dialog
      class="cn-dialog"
      :model-value="!!citation"
      title="引用原文"
      width="min(600px, 94vw)"
      append-to-body
      @close="citation = null"
    >
      <template v-if="citation"
        ><p>
          {{ file.name }} · 修订 {{ citation.revision }} ·
          {{ citation.locator }}
        </p>
        <el-alert
          v-if="citation.readOnly"
          title="这是会话绑定版本的历史摘录，当前文件或配置已经变化。"
          type="warning"
          :closable="false"
        />
        <pre class="qa-excerpt">{{ citation.excerpt }}</pre>
        <p>
          此处显示当时发送给模型的原文片段。关闭窗口可返回文件预览，暂不支持精确跳转。
        </p></template
      >
    </el-dialog>
  </section>
</template>
<style scoped>
.document-qa {
  line-height: 1.6;
  overflow-wrap: anywhere;
}
.document-qa > .el-select {
  width: 100%;
  margin: 10px 0;
}
.qa-pages {
  display: flex;
  align-items: center;
  gap: 8px;
  flex-wrap: wrap;
}
.qa-message {
  padding: 12px;
  background: var(--el-fill-color-light);
  border-radius: 8px;
  margin: 12px 0;
}
.qa-text,
.qa-excerpt {
  white-space: pre-wrap;
  overflow-wrap: anywhere;
  font-family: inherit;
}
.qa-excerpt {
  max-height: 50vh;
  overflow: auto;
}
.qa-message .el-button {
  white-space: normal;
  height: auto;
  text-align: left;
}
.qa-transcript {
  max-height: 60vh;
  overflow: auto;
}
.document-qa > .el-button {
  margin: 6px 6px 6px 0;
}
.document-qa > .el-checkbox {
  height: auto;
  white-space: normal;
  margin: 8px 0;
}
</style>
