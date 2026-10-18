<script setup lang="ts">
import { MoreFilled } from "@element-plus/icons-vue";
import type { Entry } from "../../api";
import type { FileAction } from "./actions";
defineProps<{ file: Entry }>();
const emit = defineEmits<{ action: [action: FileAction] }>();
</script>
<template>
  <el-dropdown trigger="click" @command="emit('action', $event)"
    ><el-button
      text
      :icon="MoreFilled"
      :aria-label="file.name + '的操作'"
    /><template #dropdown
      ><el-dropdown-menu>
        <el-dropdown-item v-if="file.kind === 'FILE'" command="download"
          >下载文件</el-dropdown-item
        >
        <el-dropdown-item command="share">分享</el-dropdown-item>
        <el-dropdown-item command="favorite">{{
          file.favorite ? "取消收藏" : "收藏"
        }}</el-dropdown-item>
        <el-dropdown-item command="rename">重命名</el-dropdown-item>
        <el-dropdown-item command="move">移动</el-dropdown-item>
        <el-dropdown-item command="tags">编辑标签</el-dropdown-item>
        <el-dropdown-item v-if="file.kind === 'FILE'" command="versions"
          >版本历史</el-dropdown-item
        >
        <el-dropdown-item v-if="file.kind === 'FOLDER'" command="collect"
          >收集文件</el-dropdown-item
        >
        <el-dropdown-item command="zip">压缩下载</el-dropdown-item>
        <el-dropdown-item command="delete" divided class="cn-danger-action"
          >移入回收站</el-dropdown-item
        >
      </el-dropdown-menu></template
    ></el-dropdown
  >
</template>
