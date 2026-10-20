<script setup lang="ts">
import { t } from "../../i18n";
import { Download, Share, ArrowDown, ArrowUp } from "@element-plus/icons-vue";
import { bytes, date, type Entry } from "../../api";
import FileMark from "../FileMark.vue";
import FileActionMenu from "./FileActionMenu.vue";
import type { FileAction } from "./actions";
defineProps<{
  items: Entry[];
  selected: Entry[];
  trash?: boolean;
  sort?: string;
  direction?: string;
}>();
const emit = defineEmits<{
  toggle: [file: Entry];
  open: [file: Entry];
  action: [action: FileAction, file: Entry];
  sort: [value: string];
}>();
</script>
<template>
  <table class="cn-file-list" :aria-label="t('files.list')">
    <thead>
      <tr>
        <th class="selection-cell">
          <span class="sr-only">{{ t("files.select") }}</span>
        </th>
        <th
          :aria-sort="
            sort === 'name'
              ? direction === 'asc'
                ? 'ascending'
                : 'descending'
              : 'none'
          "
        >
          <button
            v-if="!trash"
            class="column-sort"
            @click="emit('sort', 'name')"
          >
            {{ t("files.name")
            }}<el-icon v-if="sort === 'name'"
              ><component :is="direction === 'asc' ? ArrowUp : ArrowDown"
            /></el-icon></button
          ><span v-else>{{ t("files.name") }}</span>
        </th>
        <th
          class="size-cell"
          :aria-sort="
            sort === 'size'
              ? direction === 'asc'
                ? 'ascending'
                : 'descending'
              : 'none'
          "
        >
          <button
            v-if="!trash"
            class="column-sort"
            @click="emit('sort', 'size')"
          >
            {{ t("files.size")
            }}<el-icon v-if="sort === 'size'"
              ><component :is="direction === 'asc' ? ArrowUp : ArrowDown"
            /></el-icon></button
          ><span v-else>{{ t("files.size") }}</span>
        </th>
        <th
          class="date-cell"
          :aria-sort="
            sort === 'updatedAt'
              ? direction === 'asc'
                ? 'ascending'
                : 'descending'
              : 'none'
          "
        >
          <button
            v-if="!trash"
            class="column-sort"
            @click="emit('sort', 'updatedAt')"
          >
            {{ t("files.modified")
            }}<el-icon v-if="sort === 'updatedAt'"
              ><component :is="direction === 'asc' ? ArrowUp : ArrowDown"
            /></el-icon></button
          ><span v-else>{{ t("files.deleted") }}</span>
        </th>
        <th class="actions-cell">{{ t("common.actions") }}</th>
      </tr>
    </thead>
    <tbody>
      <tr
        v-for="file in items"
        :key="file.id"
        :class="{ selected: selected.some((e) => e.id === file.id) }"
        @click="emit('toggle', file)"
        @dblclick="!trash && emit('open', file)"
      >
        <td class="selection-cell" @click.stop @dblclick.stop>
          <el-checkbox
            :model-value="selected.some((e) => e.id === file.id)"
            :aria-label="t('files.selectPrefix') + file.name"
            @change="emit('toggle', file)"
          />
        </td>
        <td class="name-cell">
          <button
            class="entry-name"
            :aria-label="(file.favorite ? '★ ' : '') + file.name"
            :title="file.name"
            :disabled="trash"
            @click.stop="emit('open', file)"
            @dblclick.stop
          >
            <FileMark
              :kind="file.kind"
              :mime="file.mime"
              :name="file.name"
            /><span
              ><strong>{{ file.favorite ? "★ " : "" }}{{ file.name }}</strong
              ><small v-if="file.path">{{ file.path }}</small
              ><small class="mobile-file-meta"
                >{{
                  file.kind === "FOLDER" ? t("files.folder") : bytes(file.size)
                }}
                · {{ date(trash ? file.deletedAt : file.updatedAt) }}</small
              ></span
            ></button
          ><el-button
            v-if="file.path && !trash"
            link
            @click.stop="emit('action', 'locate', file)"
            >{{ t("files.locate") }}</el-button
          >
        </td>
        <td class="size-cell">
          {{ file.kind === "FOLDER" ? "—" : bytes(file.size) }}
        </td>
        <td class="date-cell">
          {{ date(trash ? file.deletedAt : file.updatedAt) }}
        </td>
        <td class="actions-cell" @click.stop @dblclick.stop>
          <div class="row-actions">
            <template v-if="trash"
              ><el-button
                text
                type="primary"
                @click="emit('action', 'restore', file)"
                >{{ t("common.restore") }}</el-button
              ><el-button
                text
                type="danger"
                @click="emit('action', 'purge', file)"
                >{{ t("common.purge") }}</el-button
              ></template
            ><template v-else
              ><a
                v-if="file.kind === 'FILE'"
                :href="`/api/files/${file.id}/download`"
                @click.prevent="emit('action', 'download', file)"
                class="row-icon"
                :aria-label="t('files.downloadPrefix') + file.name"
                ><el-icon><Download /></el-icon></a
              ><el-button
                class="row-share"
                text
                :icon="Share"
                :aria-label="t('files.sharePrefix') + file.name"
                @click="emit('action', 'share', file)" /><FileActionMenu
                :file="file"
                @action="emit('action', $event, file)"
            /></template>
          </div>
        </td>
      </tr>
    </tbody>
  </table>
</template>
