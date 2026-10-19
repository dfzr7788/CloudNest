<script setup lang="ts">
import { bytes, type Entry } from "../../api";
import Thumbnail from "../Thumbnail.vue";
import FileActionMenu from "./FileActionMenu.vue";
import type { FileAction } from "./actions";
defineProps<{ items: Entry[]; selected: Entry[] }>();
const emit = defineEmits<{
  toggle: [file: Entry];
  open: [file: Entry];
  action: [action: FileAction, file: Entry];
}>();
</script>
<template>
  <div class="file-grid">
    <article
      v-for="file in items"
      :key="file.id"
      class="file-tile"
      :class="{ selected: selected.some((e) => e.id === file.id) }"
      @click="emit('toggle', file)"
      @dblclick="emit('open', file)"
    >
      <div class="tile-selection" @click.stop @dblclick.stop>
        <el-checkbox
          :model-value="selected.some((e) => e.id === file.id)"
          :aria-label="'选择 ' + file.name"
          @change="emit('toggle', file)"
        />
      </div>
      <div class="tile-more" @click.stop @dblclick.stop>
        <FileActionMenu :file="file" @action="emit('action', $event, file)" />
      </div>
      <button
        class="tile-open"
        :aria-label="(file.favorite ? '★ ' : '') + file.name"
        :title="file.name"
        @click.stop="
          $event.ctrlKey || $event.metaKey || $event.shiftKey
            ? emit('toggle', file)
            : emit('open', file)
        "
        @dblclick.stop
      >
        <span class="tile-thumbnail"><Thumbnail :file="file" /></span
        ><strong>{{ file.favorite ? "★ " : "" }}{{ file.name }}</strong
        ><small>{{
          file.kind === "FOLDER" ? "文件夹" : bytes(file.size)
        }}</small>
      </button>
    </article>
  </div>
</template>
