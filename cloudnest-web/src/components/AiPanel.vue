<script setup lang="ts">
import { computed, ref, watch, onBeforeUnmount } from "vue";
import { ElMessage, ElMessageBox } from "element-plus";
import { marked } from "marked";
import DOMPurify from "dompurify";
import { api, date, errorText, type Entry } from "../api";
import {
  ai,
  aiFinished,
  aiStage,
  type AiConnection,
  type AiCoverage,
  type AiModel,
  type AiTask,
} from "../api/ai";
import AiConversation from "./AiConversation.vue";
import AiSettings from "./AiSettings.vue";
import AiVersionDiff from "./AiVersionDiff.vue";
const props = defineProps<{ file: Entry }>();
const models = ref<AiModel[]>([]),
  connections = ref<AiConnection[]>([]),
  modelId = ref(""),
  enabled = ref(false);
const settings = ref(false),
  busy = ref(false),
  error = ref(""),
  task = ref<AiTask | null>(null),
  history = ref<AiTask[]>([]),
  historyPage = ref(1);
const coverage = ref<AiCoverage | null>(null),
  rangeChars = ref(120000),
  allowPartial = ref(false),
  language = ref("zh"),
  selectedTags = ref<string[]>([]);
const connection = computed(() =>
  connections.value.find(
    (c) =>
      c.id === models.value.find((m) => m.id === modelId.value)?.connection_id,
  ),
);
const canStart = computed(
  () =>
    enabled.value &&
    !!modelId.value &&
    !!coverage.value &&
    (!coverage.value.partial || allowPartial.value) &&
    !busy.value &&
    (!task.value || aiFinished(task.value)),
);
// No model-supplied HTML, images or live links. The remaining Markdown is sanitized.
const answerHtml = computed(() => {
  const rendered = marked.parse(task.value?.result?.text || "", {
    async: false,
  });
  return DOMPurify.sanitize(rendered, {
    ALLOWED_TAGS: [
      "p",
      "br",
      "strong",
      "em",
      "h1",
      "h2",
      "h3",
      "h4",
      "ul",
      "ol",
      "li",
      "blockquote",
      "pre",
      "code",
      "table",
      "thead",
      "tbody",
      "tr",
      "td",
      "th",
      "hr",
    ],
    ALLOWED_ATTR: [],
  });
});
let timer: ReturnType<typeof setTimeout> | undefined,
  generation = 0,
  alive = true;
let pending: { key: string; signature: string } | undefined;
async function loadHistory() {
  history.value = await ai.tasks(props.file.id, historyPage.value, true);
}
async function load() {
  busy.value = true;
  try {
    const [caps, ms, cs, pref] = await Promise.all([
      ai.capabilities(),
      ai.models(),
      ai.connections(),
      ai.preferences(),
    ]);
    if (!alive) return;
    enabled.value = caps.enabled;
    models.value = ms.filter((m) => m.purpose !== "EMBEDDING");
    connections.value = cs;
    if (!models.value.some((m) => m.id === modelId.value))
      modelId.value =
        pref.default_chat_model_id ||
        models.value.find((m) => m.enabled)?.id ||
        "";
    await loadHistory();
    if (!task.value) {
      task.value = history.value[0] || null;
      selectedTags.value = [];
    }
    schedule();
  } catch (e) {
    if (alive) error.value = errorText(e);
  } finally {
    if (alive) busy.value = false;
  }
}
function schedule() {
  clearTimeout(timer);
  if (!alive || !task.value || aiFinished(task.value)) return;
  const mine = generation,
    id = task.value.taskId;
  timer = setTimeout(
    async () => {
      try {
        const t = await ai.task(id);
        if (!alive || mine !== generation) return;
        task.value = t;
        if (aiFinished(t)) {
          selectedTags.value = [];
          await loadHistory();
        }
      } catch (e) {
        if (alive && mine === generation) error.value = errorText(e);
        return;
      }
      schedule();
    },
    document.hidden ? 8000 : 2000,
  );
}
async function analyze() {
  busy.value = true;
  error.value = "";
  coverage.value = null;
  const mine = ++generation;
  try {
    const value = await ai.analysis(
      props.file.id,
      modelId.value,
      rangeChars.value,
    );
    if (alive && mine === generation) coverage.value = value;
  } catch (e) {
    if (alive) error.value = errorText(e);
  } finally {
    if (alive) {
      busy.value = false;
      schedule();
    }
  }
}
async function generate(
  kind: "summaries" | "tag-suggestions",
  forceRefresh = false,
) {
  if (!coverage.value) return;
  busy.value = true;
  error.value = "";
  try {
    await ElMessageBox.confirm(
      `将发送《${props.file.name}》本次选择范围内的文本与位置到 ${connection.value?.base_url}，使用 ${models.value.find((m) => m.id === modelId.value)?.name}。预计${kind === "summaries" ? coverage.value.summaryCalls : coverage.value.tagCalls}次调用，由该 API 账号付费。${coverage.value.partial ? "仅分析部分内容。" : ""}${forceRefresh ? "重新生成将再次调用。" : ""}`,
      "开始 AI 分析",
      { confirmButtonText: "确认并开始", cancelButtonText: "取消" },
    );
    const input = {
      modelId: modelId.value,
      expectedRevision: coverage.value.revision,
      rangeChars: rangeChars.value,
      allowPartial: allowPartial.value,
      language: language.value,
      forceRefresh,
    };
    const signature = kind + JSON.stringify(input);
    if (!pending || pending.signature !== signature)
      pending = { key: crypto.randomUUID(), signature };
    // Keep the same key after a lost response, so retrying cannot create a second paid task.
    task.value = await ai.generate(props.file.id, kind, input, pending.key);
    pending = undefined;
    generation++;
    selectedTags.value = [];
    schedule();
    await loadHistory();
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
    schedule();
  } catch (e) {
    error.value = errorText(e);
  }
}
async function applyTags() {
  busy.value = true;
  try {
    const { data: current } = await api.get<string[]>(
      `/entries/${props.file.id}/tags`,
    );
    const merged = [...new Set([...current, ...selectedTags.value])];
    if (merged.length > 20) throw new Error("合并后超过 20 个标签，请减少选择");
    await ElMessageBox.confirm(
      `当前标签：${current.join("、") || "无"}\n合并后：${merged.join("、")}`,
      "确认合并标签",
      { confirmButtonText: "保存合并结果", cancelButtonText: "取消" },
    );
    await api.put(`/entries/${props.file.id}/tags`, {
      tags: merged,
      expectedTags: current,
    });
    ElMessage.success("标签已合并保存");
    selectedTags.value = [];
  } catch (e) {
    if (e !== "cancel" && e !== "close") error.value = errorText(e);
  } finally {
    busy.value = false;
  }
}
async function remove() {
  if (!task.value) return;
  try {
    await ElMessageBox.confirm("删除此 AI 结果，源文件不受影响。", "删除结果");
    await api.delete(`/ai/artifacts/${task.value.taskId}`);
    task.value = null;
    await loadHistory();
  } catch (e) {
    if (e !== "cancel" && e !== "close") error.value = errorText(e);
  }
}
function select(t: AiTask) {
  generation++;
  clearTimeout(timer);
  task.value = t;
  selectedTags.value = [];
  schedule();
}
watch([modelId, rangeChars], () => {
  coverage.value = null;
  allowPartial.value = false;
});
watch(settings, (open) => {
  if (!open) void load();
});
onBeforeUnmount(() => {
  alive = false;
  generation++;
  clearTimeout(timer);
});
void load();
</script>
<template>
  <section class="ai-panel" v-loading="busy">
    <p>
      <strong>{{ file.name }}</strong>
    </p>
    <el-alert v-if="error" :title="error" type="error" :closable="false" />
    <el-alert
      v-if="!enabled"
      title="站点 AI 尚未开启，文件预览仍可正常使用。"
      type="info"
      :closable="false"
    />
    <el-button @click="settings = true">{{
      models.length ? "AI 设置与用量" : "配置自己的 API"
    }}</el-button>
    <el-collapse v-if="enabled && models.length" class="qa-entry">
      <el-collapse-item title="单文档问答与会话历史" name="qa">
        <AiConversation
          :file="file"
          :model-id="modelId"
          :models="models"
          :connections="connections"
        />
      </el-collapse-item>
    </el-collapse>
    <el-form v-if="enabled && models.length" label-position="top">
      <el-form-item label="聊天模型"
        ><el-select v-model="modelId"
          ><el-option
            v-for="m in models.filter((m) => m.enabled)"
            :key="m.id"
            :label="m.name"
            :value="m.id" /></el-select
      ></el-form-item>
      <p v-if="connection">
        发送至 {{ connection.base_url }} ·
        {{
          connection.consented
            ? "已确认数据发送"
            : "请先在 AI 设置中确认数据发送"
        }}
      </p>
      <el-form-item label="分析范围：从文档开头起，最多字符数（含来源标识）"
        ><el-input-number
          v-model="rangeChars"
          :min="100"
          :max="262144"
          :step="1000"
      /></el-form-item>
      <el-form-item label="结果语言"
        ><el-select v-model="language"
          ><el-option value="zh" label="中文" /><el-option
            value="original"
            label="跟随原文" /></el-select
      ></el-form-item>
      <el-button :disabled="!modelId || !connection?.consented" @click="analyze"
        >检查文档范围（不调用模型）</el-button
      >
      <div v-if="coverage" class="coverage">
        <p>
          文件修订 {{ coverage.revision }} ·
          {{
            coverage.partial
              ? "部分内容"
              : coverage.status === "NO_EXTRACTABLE_TEXT"
                ? "没有可提取文本"
                : "完整提取范围"
          }}
        </p>
        <p>
          提取 {{ coverage.processedCharacters }} 字符；本次选择
          {{ coverage.selectedCharacters }} 字符<span
            v-if="coverage.totalPages != null"
            >，已处理 {{ coverage.processedPages }}/{{
              coverage.totalPages
            }}
            页</span
          >。
        </p>
        <p>{{ coverage.reason }}</p>
        <p>
          摘要预计 {{ coverage.summaryCalls }} 次调用（最多 8
          次）；标签需要范围能放入单次上下文。
        </p>
        <el-checkbox v-if="coverage.partial" v-model="allowPartial"
          >我同意仅分析上述部分内容</el-checkbox
        >
      </div>
      <el-button
        type="primary"
        :disabled="
          !canStart ||
          coverage?.status === 'NO_EXTRACTABLE_TEXT' ||
          (coverage?.summaryCalls || 0) > 8
        "
        @click="generate('summaries')"
        >生成摘要</el-button
      >
      <el-button
        :disabled="
          !canStart ||
          coverage?.status === 'NO_EXTRACTABLE_TEXT' ||
          (coverage?.summaryCalls || 0) > 1
        "
        @click="generate('tag-suggestions')"
        >建议标签</el-button
      >
      <p v-if="coverage && coverage.summaryCalls > 1">
        标签建议需要单次上下文，请缩小范围后重新检查。
      </p>
      <el-button
        v-if="task?.status === 'SUCCEEDED'"
        :disabled="!canStart"
        @click="
          generate(task.kind === 'TAGS' ? 'tag-suggestions' : 'summaries', true)
        "
        >重新生成（再次调用）</el-button
      >
    </el-form>
    <article v-if="task" class="ai-result" aria-live="polite">
      <p>
        {{ aiStage(task) }} · {{ task.model_name }} ·
        {{ date(task.created_at) }}
      </p>
      <p>
        已完成 {{ task.completed_calls
        }}<span v-if="task.total_calls != null"> / {{ task.total_calls }}</span>
        次调用
      </p>
      <el-alert
        v-if="task.coverage?.partial"
        title="此结果仅覆盖部分内容"
        type="warning"
        :closable="false"
      />
      <p v-if="task.error_message">
        {{ task.error_message }}（{{ task.error_code }}）
      </p>
      <el-button v-if="!aiFinished(task)" @click="stop"
        >停止（可能已计费）</el-button
      >
      <div class="answer" v-html="answerHtml" />
      <template v-if="task.result?.tags">
        <el-checkbox-group v-model="selectedTags"
          ><el-checkbox
            v-for="tag in task.result.tags"
            :key="tag"
            :value="tag"
            >{{ tag }}</el-checkbox
          ></el-checkbox-group
        >
        <el-button :disabled="!selectedTags.length || busy" @click="applyTags"
          >确认合并所选标签</el-button
        >
      </template>
      <details v-if="task.coverage">
        <summary>处理范围与来源位置</summary>
        <p>
          {{ task.coverage.selectedCharacters }} 字符 ·
          {{ task.coverage.reason }}
        </p>
        <p v-for="source in task.coverage.sources" :key="source.sourceId">
          {{ source.sourceId }} · {{ source.locator }}
        </p>
      </details>
      <el-button v-if="aiFinished(task)" @click="remove">删除此结果</el-button>
    </article>
    <h3>此文件的任务</h3>
    <p v-if="!history.length">暂无任务，上传或打开文件不会自动调用模型。</p>
    <el-button
      v-for="t in history"
      :key="t.id"
      class="history-item"
      @click="select(t)"
      >{{ t.kind === "TAGS" ? "标签" : "摘要" }} · {{ aiStage(t) }} ·
      {{ date(t.created_at) }}</el-button
    >
    <el-button
      :disabled="historyPage === 1"
      @click="
        historyPage--;
        loadHistory().catch((e) => (error = errorText(e)));
      "
      >上一页</el-button
    >
    <el-button
      :disabled="history.length < 20"
      @click="
        historyPage++;
        loadHistory().catch((e) => (error = errorText(e)));
      "
      >下一页</el-button
    >
    <p class="notice">
      回答仅为辅助结果，请对照原文。停止不能收回已发送数据，也不保证供应商停止计费；网络失败不会自动重发。
    </p>
    <el-dialog
      v-model="settings"
      title="AI 设置"
      append-to-body
      width="min(850px,96vw)"
      destroy-on-close
      ><AiSettings :active="settings"
    /></el-dialog>
    <AiVersionDiff v-if="enabled" :file="file" :model-id="modelId" />
  </section>
</template>
<style scoped>
.ai-panel {
  line-height: 1.7;
  overflow-wrap: anywhere;
}
.ai-panel p {
  margin: 8px 0;
}
.ai-panel .el-button {
  margin: 6px 6px 6px 0;
  white-space: normal;
  height: auto;
  min-height: 32px;
}
.ai-panel .el-select {
  width: 100%;
}
.coverage,
.ai-result {
  margin: 16px 0;
  padding: 14px;
  background: #f4f6fb;
  border-radius: 10px;
}
.history-item {
  display: block;
  text-align: left;
}
.notice {
  font-size: 12px;
  color: #65758e;
}
.answer :deep(pre),
.answer :deep(table) {
  max-width: 100%;
  overflow: auto;
}
.answer :deep(pre) {
  padding: 10px;
  background: #e8ecf4;
}
.answer :deep(td),
.answer :deep(th) {
  border: 1px solid #ccd3df;
  padding: 5px;
}
</style>
