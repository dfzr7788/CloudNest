<script setup lang="ts">
import { t } from "../../i18n";
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
  sort?: string;
  direction?: string;
  emptyVariant?: "folder" | "search" | "trash" | "favorite" | "recent";
}>();
const emit = defineEmits<{
  toggle: [file: Entry];
  open: [file: Entry];
  action: [action: FileAction, file: Entry];
  retry: [];
  upload: [];
  sort: [value: string];
}>();
</script>
<template>
  <section class="file-content" :aria-busy="busy">
    <div
      v-if="busy && !items.length"
      class="file-skeleton"
      role="status"
      :aria-label="t('files.loading')"
    >
      <div v-if="grid && !trash" class="skeleton-grid" aria-hidden="true">
        <el-skeleton v-for="n in 8" :key="n" animated
          ><template #template
            ><div class="skeleton-tile">
              <el-skeleton-item variant="image" /><el-skeleton-item
                variant="text"
              /><el-skeleton-item variant="text" /></div></template
        ></el-skeleton>
      </div>
      <el-skeleton v-else animated :count="6" aria-hidden="true"
        ><template #template
          ><div class="skeleton-row">
            <el-skeleton-item variant="rect" /><el-skeleton-item
              variant="text"
            /><el-skeleton-item variant="text" /></div></template
      ></el-skeleton>
    </div>
    <EmptyState
      v-else-if="failure"
      :title="t('files.loadFailed')"
      variant="error"
      :description="failure"
      ><el-button @click="emit('retry')">{{
        t("common.reload")
      }}</el-button></EmptyState
    >
    <template v-else
      ><p v-if="busy" class="refresh-status" role="status">
        {{ t("files.refreshing") }}
      </p>
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
        :sort="sort"
        :direction="direction"
        @toggle="emit('toggle', $event)"
        @open="emit('open', $event)"
        @action="(action, file) => emit('action', action, file)"
        @sort="emit('sort', $event)" /><EmptyState
        v-else
        :title="emptyTitle"
        :description="emptyDescription"
        :variant="emptyVariant"
        ><slot name="empty-action" /></EmptyState
    ></template>
  </section>
</template>
