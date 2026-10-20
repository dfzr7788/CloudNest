<script setup lang="ts">
import type { Component } from "vue";
defineProps<{ label: string; icon: Component }>();
const emit = defineEmits<{ click: [event: MouseEvent] }>();
</script>
<template>
  <el-tooltip :content="label"
    ><el-button
      class="cn-icon-button"
      :icon="icon"
      :aria-label="label"
      @click="emit('click', $event)"
  /></el-tooltip>
</template>
<style scoped>
.cn-icon-button {
  width: 32px;
  height: 32px;
  padding: 0;
  flex-shrink: 0;
}
@media (max-width: 767px) {
  .cn-icon-button {
    width: 36px;
    height: 36px;
  }
}
</style>
