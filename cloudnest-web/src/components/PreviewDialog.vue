<script setup lang="ts">
import { computed, ref, watch, defineAsyncComponent } from "vue";
import { api, errorText, type Entry, bytes } from "../api";
import FileMark from "./FileMark.vue";
const PdfPreview = defineAsyncComponent(() => import("./PdfPreview.vue"));
const props = defineProps<{
  file: Entry | null;
  base?: string;
  allowDownload?: boolean;
  siblings?: Entry[];
}>();
const emit = defineEmits<{ close: []; select: [file: Entry] }>();
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
    text.value = "";
    zoom.value = 1;
    rotation.value = 0;
    error.value = "";
    if (f?.mime === "text/plain") {
      if (f.size > 2 * 1024 * 1024) {
        error.value = "文本超过 2 MiB，请下载后查看";
        return;
      }
      loading.value = true;
      try {
        text.value = (
          await api.get(url.value, { responseType: "text", baseURL: "" })
        ).data;
        opened();
      } catch (e) {
        error.value = errorText(e);
      } finally {
        loading.value = false;
      }
    }
  },
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
