<script setup lang="ts">
import { computed, ref, watch, defineAsyncComponent } from "vue";
import { api, errorText, type Entry, bytes } from "../api";
import FileMark from "./FileMark.vue";
import DOMPurify from "dompurify";
import { renderMarkdown } from "../markdown";
const PdfPreview = defineAsyncComponent(() => import("./PdfPreview.vue"));
const props = defineProps<{
  file: Entry | null;
  base?: string;
  allowDownload?: boolean;
  siblings?: Entry[];
}>();
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
    append-to-body
    :model-value="!!file"
    :title="file?.name"
    width="min(1000px,94vw)"
    destroy-on-close
    @close="emit('close')"
    ><div class="preview-body" v-loading="loading">
      <el-alert
        v-if="error"
        :title="error"
        type="error"
        :closable="false"
      /><template v-else-if="file"
        ><img
          v-if="file.mime?.startsWith('image/') && supported"
          :src="url"
          :alt="file.name"
          :style="{
            transform: `scale(${zoom}) rotate(${rotation}deg)`,
            maxWidth: '100%',
          }"
          @load="opened"
          @error="error = '图片读取失败，文件可能已被删除或授权已失效'"
        /><PdfPreview
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
          style="max-width: 100%"
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
  padding: 24px;
}
.document-toolbar {
  display: flex;
  align-items: center;
  justify-content: space-between;
  gap: 16px;
  color: #7b88a1;
  font-size: 13px;
  margin-bottom: 24px;
}
.rendered-document {
  max-width: 860px;
  margin: auto;
  overflow-wrap: anywhere;
  line-height: 1.85;
  color: #243650;
}
.rendered-document :deep(h1),
.rendered-document :deep(h2) {
  border-bottom: 1px solid #e1e7f3;
  padding-bottom: 8px;
}
.rendered-document :deep(pre) {
  padding: 16px;
  background: #edf1f9;
  overflow: auto;
  white-space: pre;
}
.rendered-document :deep(code) {
  font-family: Consolas, monospace;
  background: #edf1f9;
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
  border: 1px solid #d5deed;
  padding: 8px 12px;
  min-width: 80px;
}
.rendered-document :deep(blockquote) {
  border-left: 4px solid #7d89f5;
  margin-left: 0;
  padding-left: 18px;
  color: #687896;
}
.rendered-document :deep(a) {
  color: #5264dd;
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
