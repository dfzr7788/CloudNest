<script setup lang="ts">
import { nextTick, onBeforeUnmount, ref, watch } from "vue";
import {
  getDocument,
  GlobalWorkerOptions,
  type PDFDocumentProxy,
  type RenderTask,
} from "pdfjs-dist";
import PdfWorker from "pdfjs-dist/build/pdf.worker.min.mjs?worker";
// Vite emits a JavaScript worker bundle; no production .mjs MIME mapping is required.
GlobalWorkerOptions.workerPort ||= new PdfWorker();
const props = defineProps<{ url: string }>();
const emit = defineEmits<{ loaded: [] }>(),
  scale = ref(1.5);
const canvas = ref<HTMLCanvasElement>(),
  page = ref(1),
  pages = ref(0),
  busy = ref(true),
  error = ref(""),
  rendered = ref(false);
let document: PDFDocumentProxy | undefined,
  loading: ReturnType<typeof getDocument> | undefined,
  rendering: RenderTask | undefined,
  generation = 0;
async function draw() {
  if (!document || !canvas.value) return;
  const mine = ++generation;
  rendering?.cancel();
  busy.value = true;
  rendered.value = false;
  try {
    const source = await document.getPage(page.value);
    if (mine !== generation) return;
    const viewport = source.getViewport({ scale: scale.value });
    const target = canvas.value;
    target.width = viewport.width;
    target.height = viewport.height;
    const context = target.getContext("2d");
    if (!context) throw new Error("无法创建预览画布");
    rendering = source.render({
      canvas: target,
      canvasContext: context,
      viewport,
    });
    await rendering.promise;
    if (mine === generation) {
      rendered.value = true;
      emit("loaded");
    }
  } catch (e) {
    if (mine === generation)
      error.value = e instanceof Error ? e.message : "PDF 渲染失败";
  } finally {
    if (mine === generation) busy.value = false;
  }
}
watch(
  () => props.url,
  async (url) => {
    generation++;
    rendering?.cancel();
    await loading?.destroy();
    document = undefined;
    error.value = "";
    busy.value = true;
    rendered.value = false;
    page.value = 1;
    pages.value = 0;
    try {
      loading = getDocument({
        url,
        withCredentials: true,
        disableAutoFetch: true,
        disableStream: true,
      });
      document = await loading.promise;
      pages.value = document.numPages;
      await nextTick();
      await draw();
    } catch (e) {
      busy.value = false;
      error.value =
        e instanceof Error
          ? `PDF 预览失败：${e.message}`
          : "PDF 预览失败，请下载后查看";
    }
  },
  { immediate: true },
);
onBeforeUnmount(() => {
  generation++;
  rendering?.cancel();
  void loading?.destroy();
});
async function turn(delta: number) {
  page.value += delta;
  await draw();
}
</script>
<template>
  <section class="pdf-preview" v-loading="busy">
    <el-alert
      v-if="error"
      :title="error"
      type="error"
      :closable="false"
    /><template v-else
      ><div class="pdf-controls">
        <el-button
          size="small"
          :disabled="busy"
          @click="
            scale = Math.max(0.5, scale - 0.25);
            draw();
          "
          >缩小</el-button
        ><el-button
          size="small"
          :disabled="busy"
          @click="
            scale = Math.min(2.5, scale + 0.25);
            draw();
          "
          >放大</el-button
        ><el-input-number
          v-model="page"
          :min="1"
          :max="pages || 1"
          :disabled="busy"
          aria-label="跳转页码"
          @change="draw"
        />
        <el-button size="small" :disabled="page <= 1 || busy" @click="turn(-1)"
          >上一页</el-button
        ><span>第 {{ page }} / {{ pages }} 页</span
        ><el-button
          size="small"
          :disabled="page >= pages || busy"
          @click="turn(1)"
          >下一页</el-button
        >
      </div>
      <canvas ref="canvas" aria-label="PDF 页面" :data-rendered="rendered"
    /></template>
  </section>
</template>
<style scoped>
.pdf-preview {
  width: 100%;
  padding: 16px;
  min-height: 360px;
  align-self: flex-start;
}
.pdf-controls {
  display: flex;
  justify-content: center;
  align-items: center;
  gap: 8px;
  flex-wrap: wrap;
  margin-bottom: 16px;
  font-size: 12px;
  color: var(--cn-muted);
}
.pdf-preview canvas {
  display: block;
  max-width: 100%;
  height: auto;
  margin: 0 auto;
  background: white;
  box-shadow: 0 3px 12px #27396b14;
}
</style>
