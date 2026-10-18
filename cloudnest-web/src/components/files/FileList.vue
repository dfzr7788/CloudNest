<script setup lang="ts">
import { Download, Share } from "@element-plus/icons-vue";
import { bytes, date, type Entry } from "../../api";
import FileMark from "../FileMark.vue";
import FileActionMenu from "./FileActionMenu.vue";
import type { FileAction } from "./actions";
defineProps<{ items: Entry[]; selected: Entry[]; trash?: boolean }>();
const emit = defineEmits<{
  toggle: [file: Entry];
  open: [file: Entry];
  action: [action: FileAction, file: Entry];
}>();
</script>
<template>
  <table class="cn-file-list" aria-label="文件列表">
    <thead>
      <tr>
        <th class="selection-cell"><span class="sr-only">选择</span></th>
        <th>名称</th>
        <th class="size-cell">大小</th>
        <th class="date-cell">{{ trash ? "删除时间" : "修改时间" }}</th>
        <th class="actions-cell">操作</th>
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
            :aria-label="'选择 ' + file.name"
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
            <FileMark :kind="file.kind" :mime="file.mime" /><span
              ><strong>{{ file.favorite ? "★ " : "" }}{{ file.name }}</strong
              ><small v-if="file.path">{{ file.path }}</small
              ><small class="mobile-file-meta"
                >{{ file.kind === "FOLDER" ? "文件夹" : bytes(file.size) }} ·
                {{ date(trash ? file.deletedAt : file.updatedAt) }}</small
              ></span
            ></button
          ><el-button
            v-if="file.path && !trash"
            link
            @click.stop="emit('action', 'locate', file)"
            >打开所在目录</el-button
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
                >恢复</el-button
              ><el-button
                text
                type="danger"
                @click="emit('action', 'purge', file)"
                >彻底删除</el-button
              ></template
            ><template v-else
              ><a
                v-if="file.kind === 'FILE'"
                :href="`/api/files/${file.id}/download`"
                class="row-icon"
                :aria-label="'下载 ' + file.name"
                ><el-icon><Download /></el-icon></a
              ><el-button
                class="row-share"
                text
                :icon="Share"
                :aria-label="'分享 ' + file.name"
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
