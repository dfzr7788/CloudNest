<script setup lang="ts">
import { computed } from "vue";
import {
  Folder,
  Document,
  Picture,
  VideoPlay,
  Files,
  Headset,
} from "@element-plus/icons-vue";
const props = defineProps<{
  kind: string;
  mime?: string | null;
  name?: string;
}>();
const category = computed(() =>
  props.kind === "FOLDER"
    ? "folder"
    : props.mime?.startsWith("image/")
      ? "image"
      : props.mime === "application/pdf"
        ? "pdf"
        : props.mime?.startsWith("video/")
          ? "video"
          : props.mime?.startsWith("audio/")
            ? "audio"
            : props.mime?.startsWith("text/")
              ? "text"
              : "file",
);
const icon = computed(
  () =>
    ({
      folder: Folder,
      image: Picture,
      pdf: Document,
      video: VideoPlay,
      audio: Headset,
      text: Document,
      file: Files,
    })[category.value],
);
</script>
<template>
  <span class="file-mark" :class="category"
    ><el-icon><component :is="icon" /></el-icon
  ></span>
</template>
