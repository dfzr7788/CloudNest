<script setup lang="ts">
import { computed } from "vue";
import {
  Document,
  Picture,
  VideoPlay,
  Files,
  Headset,
  Box,
} from "@element-plus/icons-vue";
const props = defineProps<{
  kind: string;
  mime?: string | null;
  name?: string;
}>();
const category = computed(() => {
  const mime = props.mime || "";
  const ext = props.name?.split(".").pop()?.toLowerCase() || "";
  if (props.kind === "FOLDER") return "folder";
  if (
    mime.startsWith("image/") ||
    /^(png|jpe?g|gif|webp|svg|avif|heic)$/.test(ext)
  )
    return "image";
  if (mime === "application/pdf" || ext === "pdf") return "pdf";
  if (mime.startsWith("video/") || /^(mp4|mov|webm|mkv)$/.test(ext))
    return "video";
  if (mime.startsWith("audio/") || /^(mp3|wav|ogg|flac|m4a)$/.test(ext))
    return "audio";
  if (
    /zip|compressed|tar|archive/.test(mime) ||
    /^(zip|rar|7z|gz|tar)$/.test(ext)
  )
    return "archive";
  if (
    mime.startsWith("text/") ||
    /word|document|sheet|presentation/.test(mime) ||
    /^(md|txt|docx?|xlsx?|pptx?|json|ts|js|vue|py)$/.test(ext)
  )
    return "text";
  return "file";
});
const icon = computed(
  () =>
    ({
      image: Picture,
      pdf: Document,
      video: VideoPlay,
      audio: Headset,
      text: Document,
      archive: Box,
      file: Files,
      folder: Files,
    })[category.value],
);
</script>
<template>
  <span class="file-mark" :class="category" aria-hidden="true">
    <svg
      v-if="category === 'folder'"
      class="folder-symbol"
      viewBox="0 0 64 52"
      fill="none"
    >
      <path
        d="M5 11a6 6 0 0 1 6-6h15l7 7h20a6 6 0 0 1 6 6v25H5V11Z"
        fill="#647991"
      />
      <path
        d="M5 23a6 6 0 0 1 6-6h42a6 6 0 0 1 6 6v20a6 6 0 0 1-6 6H11a6 6 0 0 1-6-6V23Z"
        fill="#859BB8"
      />
      <path
        d="M11 18h42"
        stroke="#A6BAD2"
        stroke-width="2"
        stroke-linecap="round"
      />
      <path
        d="M14 39h13"
        stroke="#C0CEDC"
        stroke-width="2"
        stroke-linecap="round"
      />
    </svg>
    <el-icon v-else><component :is="icon" /></el-icon>
  </span>
</template>
