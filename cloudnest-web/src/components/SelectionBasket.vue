<script setup lang="ts">
import { ref } from "vue";
import type { Entry } from "../api";
const selected = defineModel<Entry[]>({ required: true });
const open = ref(false);
</script>
<template>
  <el-button v-if="selected.length" size="small" @click="open = true"
    >查看已选 ({{ selected.length }})</el-button
  >
  <el-dialog class="cn-dialog"
    v-model="open"
    title="跨目录已选项目"
    width="min(640px,94vw)"
    append-to-body
  >
    <p>
      进入其他文件夹或翻页后仍保留选择。仅打包这里列出的项目；选中文件夹会包含其全部内容。
    </p>
    <div class="selection-basket">
      <div v-for="entry in selected" :key="entry.id" class="basket-entry">
        <span
          >{{ entry.kind === "FOLDER" ? "📁 " : ""
          }}{{ entry.path || entry.name }}</span
        >
        <el-button
          size="small"
          :aria-label="'移除已选 ' + entry.name"
          @click="selected = selected.filter((e) => e.id !== entry.id)"
          >移除</el-button
        >
      </div>
      <p v-if="!selected.length">尚未选择项目</p>
    </div>
    <template #footer
      ><el-button @click="selected = []">清空选择</el-button
      ><el-button @click="open = false">完成</el-button></template
    >
  </el-dialog>
</template>
<style scoped>
.selection-basket {
  max-height: 50vh;
  overflow: auto;
}
.basket-entry {
  display: flex;
  align-items: center;
  justify-content: space-between;
  gap: 16px;
  padding: 12px 0;
  border-bottom: 1px solid #e8edf8;
}
.basket-entry span {
  overflow-wrap: anywhere;
}
</style>
