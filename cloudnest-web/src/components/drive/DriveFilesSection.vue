<script setup lang="ts">
import type { Entry } from "../../api";
import FileGrid from "../files/FileGrid.vue";
import FileList from "../files/FileList.vue";
import EmptyState from "../common/EmptyState.vue";
import type { FileAction } from "../files/actions";
defineProps<{
  items: Entry[];
  selected: Entry[];
  grid: boolean;
  busy: boolean;
  failure: string;
  emptyTitle: string;
  emptyDescription: string;
  trash?: boolean;
}>();
const emit = defineEmits<{
  toggle: [file: Entry];
  open: [file: Entry];
  action: [action: FileAction, file: Entry];
  retry: [];
  upload: [];
}>();
</script>
<template>
  <section class="file-content" :aria-busy="busy">
    <div
      v-if="busy && !items.length"
      class="file-skeleton"
      role="status"
      aria-label="正在加载文件"
    >
      <el-skeleton animated :rows="6" />
    </div>
    <EmptyState
      v-else-if="failure"
      title="文件暂时无法加载"
      :description="failure"
      ><el-button @click="emit('retry')">重新加载</el-button></EmptyState
    >
    <template v-else
      ><p v-if="busy" class="refresh-status" role="status">正在刷新…</p>
      <FileGrid
        v-if="grid && !trash && items.length"
        :items="items"
        :selected="selected"
        @toggle="emit('toggle', $event)"
        @open="emit('open', $event)"
        @action="(action, file) => emit('action', action, file)" /><FileList
        v-else-if="items.length"
        :items="items"
        :selected="selected"
        :trash="trash"
        @toggle="emit('toggle', $event)"
        @open="emit('open', $event)"
        @action="(action, file) => emit('action', action, file)" /><EmptyState
        v-else
        :title="emptyTitle"
        :description="emptyDescription"
        ><slot name="empty-action" /></EmptyState
    ></template>
  </section>
</template>
