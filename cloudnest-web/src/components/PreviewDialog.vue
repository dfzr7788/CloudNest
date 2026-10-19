<script setup lang="ts">
import { computed, ref, watch, defineAsyncComponent } from "vue";
import { api, errorText, type Entry, bytes } from "../api";
import { ai } from "../api/ai";
import { nextTick } from "vue";
import FileMark from "./FileMark.vue";
import AiPanel from "./AiPanel.vue";
import DOMPurify from "dompurify";
import { renderMarkdown } from "../markdown";
const PdfPreview = defineAsyncComponent(() => import("./PdfPreview.vue"));
const props = withDefaults(
  defineProps<{
    file: Entry | null;
    base?: string;
    allowDownload?: boolean;
    siblings?: Entry[];
  }>(),
  { allowDownload: true },
);
const emit = defineEmits<{ close: []; select: [file: Entry] }>();
const markdown = computed(
  () =>
    !!props.file &&
    (/\.(md|markdown)$/i.test(props.file.name) ||
      ["text/markdown", "text/x-markdown"].includes(props.file.mime || "")),
);
const word = computed(
  () =>
    !!props.file &&
    (/\.(doc|docx|xls|xlsx|ppt|pptx)$/i.test(props.file.name) ||
      [
        "application/msword",
        "application/vnd.openxmlformats-officedocument.wordprocessingml.document",
      ].includes(props.file.mime || "")),
);
const officeEnabled = ref(false);
const aiOpen = ref(false),
  aiCaps = ref<{ enabled: boolean } | null>(null),
  aiError = ref("");
const aiTrigger = ref<{ $el: HTMLElement }>();
const eligibleAi = computed(
  () =>
    !!props.file &&
    !props.base &&
    /\.(txt|md|markdown|pdf|docx)$/i.test(props.file.name),
);
async function loadAiCapabilities() {
  const id = props.file?.id;
  aiError.value = "";
  try {
    const caps = await ai.capabilities();
    if (id === props.file?.id && !props.base) aiCaps.value = caps;
  } catch (e) {
    if (id === props.file?.id) aiError.value = errorText(e);
  }
}
async function closeAssistant() {
  aiOpen.value = false;
  await nextTick();
  aiTrigger.value?.$el.focus();
}
const rawMarkdown = ref(false),
  documentHtml = ref("");
const renderedHtml = computed(() =>
  word.value
    ? DOMPurify.sanitize(documentHtml.value, {
        ALLOWED_TAGS: [
          "p",
          "br",
          "hr",
          "h1",
          "h2",
          "h3",
          "h4",
          "h5",
          "h6",
          "strong",
          "em",
          "del",
          "blockquote",
          "ul",
          "ol",
          "li",
          "pre",
          "code",
          "table",
          "thead",
          "tbody",
          "tr",
          "th",
          "td",
          "a",
        ],
        ALLOWED_ATTR: ["href", "title", "start", "align"],
      })
    : renderMarkdown(
        text.value,
        (props.base || `/api/files/${props.file?.id}`) + "/attachment?path=",
      ),
);
let requestGeneration = 0;
const images = computed(
  () =>
    props.siblings?.filter(
      (e) => e.kind === "FILE" && e.mime?.startsWith("image/"),
    ) || [],
);
const imageIndex = computed(() =>
  images.value.findIndex((e) => e.id === props.file?.id),
);
const zoom = ref(1),
  rotation = ref(0);
function opened() {
  if (props.file && !props.base)
    api.post(`/entries/${props.file.id}/opened`).catch(() => {});
}
const text = ref(""),
  error = ref(""),
  loading = ref(false);
const url = computed(() =>
  props.file ? (props.base || `/api/files/${props.file.id}`) + "/preview" : "",
);
const supported = computed(
  () =>
    !!props.file &&
    [
      "image/png",
      "image/jpeg",
      "image/gif",
      "image/webp",
      "image/bmp",
      "application/pdf",
      "text/plain",
      "audio/mpeg",
      "audio/wav",
      "audio/x-wav",
      "audio/ogg",
      "video/mp4",
      "video/webm",
    ].includes(props.file.mime || ""),
);
watch(
  () => props.file,
  async (f) => {
    const mine = ++requestGeneration;
    aiOpen.value = false;
    aiCaps.value = null;
    aiError.value = "";
    if (f && eligibleAi.value) void loadAiCapabilities();
    officeEnabled.value = false;
    if (f && !props.base && /\.(doc|docx|xls|xlsx|ppt|pptx)$/i.test(f.name))
      void api
        .get("/office/status")
        .then((r) => {
          if (mine === requestGeneration) officeEnabled.value = r.data.enabled;
        })
        .catch(() => {});
    text.value = "";
    documentHtml.value = "";
    rawMarkdown.value = false;
    loading.value = false;
    zoom.value = 1;
    rotation.value = 0;
    error.value = "";
    if (f && (f.mime === "text/plain" || markdown.value || word.value)) {
      const isWord = word.value;
      if (f.size > (isWord ? 10 : 2) * 1024 * 1024) {
        error.value = isWord
          ? "Office 文档超过 10 MiB，请下载后查看"
          : "文本超过 2 MiB，请下载后查看";
        return;
      }
      loading.value = true;
      try {
        const result = await api.get(url.value, {
          responseType: isWord ? "json" : "text",
          baseURL: "",
        });
        if (mine !== requestGeneration) return;
        if (isWord) documentHtml.value = result.data.html;
        else text.value = result.data;
        opened();
      } catch (e) {
        if (mine === requestGeneration) error.value = errorText(e);
      } finally {
        if (mine === requestGeneration) loading.value = false;
      }
    }
  },
  { immediate: true },
);
</script>
<template>
  <el-dialog
    class="cn-dialog cn-preview-dialog"
    append-to-body
    :model-value="!!file"
    :title="file?.name"
    :width="aiOpen ? 'min(1440px,96vw)' : 'min(1100px,96vw)'"
    destroy-on-close
    @close="emit('close')"
  >
    <template #header
      ><div class="preview-heading">
        <FileMark
          v-if="file"
          :kind="file.kind"
          :mime="file.mime"
          :name="file.name"
        />
        <div>
          <h2 :title="file?.name">{{ file?.name }}</h2>
          <p>
            文件预览<span v-if="file"> · {{ bytes(file.size) }}</span>
          </p>
        </div>
        <a
          v-if="file && allowDownload !== false"
          :href="(base || `/api/files/${file.id}`) + '/download'"
          class="el-button preview-download"
          download
          >下载</a
        >
      </div></template
    >
    <div class="reading-workspace" :class="{ 'with-assistant': aiOpen }">
      <div
        class="preview-body"
        :class="{
          'preview-image': file?.mime?.startsWith('image/'),
          'mobile-document-hidden': aiOpen,
        }"
        v-loading="loading"
      >
        <el-alert
          v-if="error"
          :title="error"
          type="error"
          :closable="false"
        /><template v-else-if="file"
          ><div
            v-if="file.mime?.startsWith('image/') && supported"
            class="image-scroll"
          >
            <div
              class="image-stage"
              :style="{ width: zoom * 100 + '%', height: zoom * 100 + '%' }"
            >
              <img
                :src="url"
                :alt="file.name"
                :style="{
                  transform: `rotate(${rotation}deg)`,
                  maxWidth: '100%',
                }"
                @load="opened"
                @error="error = '图片读取失败，文件可能已被删除或授权已失效'"
              />
            </div>
          </div>
          <PdfPreview
            v-else-if="file.mime === 'application/pdf'"
            :url="url"
            @loaded="opened"
          />
          <audio
            v-else-if="file.mime?.startsWith('audio/') && supported"
            :src="url"
            controls
            @loadeddata="opened"
            @error="error = '浏览器无法解码此音频，请下载后查看'"
          />
          <video
            v-else-if="file.mime?.startsWith('video/') && supported"
            :src="url"
            controls
            @loadeddata="opened"
            @error="error = '浏览器无法解码此视频，请下载后查看'"
          />
          <section v-else-if="word || markdown" class="document-preview">
            <div class="document-toolbar">
              <span v-if="word"
                >Office 阅读预览 · 复杂排版、图片及批注请下载原文件查看</span
              >
              <template v-else
                ><span>Markdown</span
                ><el-switch
                  v-model="rawMarkdown"
                  active-text="源码"
                  inactive-text="阅读"
              /></template>
            </div>
            <div v-if="word && officeEnabled && file" class="office-actions">
              <a
                class="el-button"
                :href="`/api/office/${file.id}/editor?mode=view`"
                target="_blank"
                rel="noopener"
                >Office 原版式查看</a
              ><a
                class="el-button el-button--primary"
                :href="`/api/office/${file.id}/editor?mode=edit`"
                target="_blank"
                rel="noopener"
                >在线编辑并保留版本</a
              >
            </div>
            <pre v-if="markdown && rawMarkdown">{{ text }}</pre>
            <article v-else class="rendered-document" v-html="renderedHtml" />
          </section>
          <pre v-else-if="file.mime === 'text/plain'">{{ text }}</pre>
          <div v-else class="empty-state">
            <FileMark :kind="file.kind" :mime="file.mime" />
            <h3>暂不支持此文件的在线预览</h3>
            <p>{{ bytes(file.size) }} · {{ file.mime || "未知类型" }}</p>
          </div></template
        >
      </div>
      <aside
        v-if="aiOpen && file && !base"
        class="reading-assistant"
        role="region"
        aria-label="AI 阅读助手"
      >
        <header class="assistant-heading">
          <div>
            <strong>AI 阅读助手</strong>
            <p>结合原文，按需分析</p>
          </div>
          <el-button aria-label="关闭阅读助手" @click="closeAssistant"
            >返回文档</el-button
          >
        </header>
        <div class="assistant-content">
          <AiPanel
            :key="file.id"
            :file="file"
            :capabilities="aiCaps || undefined"
          />
        </div>
      </aside>
    </div>
    <template #footer
      ><template v-if="file?.mime?.startsWith('image/')"
        ><el-button
          v-if="imageIndex > 0"
          @click="emit('select', images[imageIndex - 1]!)"
          >上一张</el-button
        ><el-button
          v-if="imageIndex >= 0 && imageIndex < images.length - 1"
          @click="emit('select', images[imageIndex + 1]!)"
          >下一张</el-button
        ><span v-if="imageIndex >= 0"
          >当前页图片 {{ imageIndex + 1 }} / {{ images.length }}</span
        ><el-button @click="zoom = Math.max(0.25, zoom - 0.25)">缩小</el-button
        ><el-button @click="zoom = Math.min(3, zoom + 0.25)">放大</el-button
        ><el-button @click="rotation = (rotation + 90) % 360"
          >旋转</el-button
        ></template
      ><el-button
        ref="aiTrigger"
        v-if="eligibleAi && aiCaps?.enabled"
        @click="aiOpen = !aiOpen"
        >AI 阅读助手</el-button
      ><span
        v-if="eligibleAi && aiError"
        class="ai-capability-error"
        role="status"
        >AI 状态读取失败
        <el-button link @click="loadAiCapabilities">重试</el-button></span
      ><el-button @click="emit('close')">关闭</el-button
      ><a
        v-if="file && allowDownload !== false"
        :href="(base || `/api/files/${file.id}`) + '/download'"
        class="el-button el-button--primary"
        download
        >下载文件</a
      ></template
    ></el-dialog
  >
</template>
<style scoped>
.document-preview {
  width: 100%;
  align-self: flex-start;
  min-height: 360px;
  padding: 24px 32px 48px;
}
.document-toolbar {
  display: flex;
  align-items: center;
  justify-content: space-between;
  gap: 16px;
  color: var(--cn-muted);
  font-size: 13px;
  margin-bottom: 24px;
}
.rendered-document {
  max-width: 780px;
  margin: auto;
  padding: 40px 48px;
  background: var(--cn-surface);
  border: 1px solid var(--cn-border);
  border-radius: 4px;
  font-size: 15px;
  overflow-wrap: anywhere;
  line-height: 1.85;
  color: var(--cn-text);
}
.rendered-document :deep(p),
.rendered-document :deep(ul),
.rendered-document :deep(ol) {
  margin: 0 0 16px;
}
.rendered-document :deep(h1) {
  font-size: 28px;
}
.rendered-document :deep(h2) {
  font-size: 22px;
}
.rendered-document :deep(h3) {
  font-size: 18px;
}
.rendered-document :deep(h1),
.rendered-document :deep(h2),
.rendered-document :deep(h3) {
  margin: 24px 0 16px;
}
.rendered-document :deep(> :first-child) {
  margin-top: 0;
}
@media (max-width: 767px) {
  .rendered-document {
    padding: 24px 20px;
  }
}
.rendered-document :deep(h1),
.rendered-document :deep(h2) {
  border-bottom: 1px solid var(--cn-border);
  padding-bottom: 8px;
}
.rendered-document :deep(pre) {
  padding: 16px;
  background: var(--cn-subtle);
  overflow: auto;
  white-space: pre;
}
.rendered-document :deep(code) {
  font-family: Consolas, monospace;
  background: var(--cn-subtle);
  border-radius: 4px;
  padding: 2px 4px;
}
.rendered-document :deep(table) {
  border-collapse: collapse;
  display: block;
  max-width: 100%;
  overflow: auto;
  margin: 16px 0;
}
.rendered-document :deep(td),
.rendered-document :deep(th) {
  border: 1px solid var(--cn-border);
  padding: 8px 12px;
  min-width: 80px;
}
.rendered-document :deep(blockquote) {
  border-left: 4px solid var(--cn-accent);
  margin-left: 0;
  padding-left: 18px;
  color: var(--cn-muted);
}
.rendered-document :deep(a) {
  color: var(--cn-accent);
  text-decoration: underline;
}
</style>

<style scoped>
.rendered-document :deep(img) {
  max-width: 100%;
  height: auto;
}
.rendered-document :deep(.katex-display) {
  overflow-x: auto;
  overflow-y: hidden;
}
</style>
