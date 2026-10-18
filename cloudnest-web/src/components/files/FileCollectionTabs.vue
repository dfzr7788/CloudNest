<script setup lang="ts">
defineProps<{ modelValue: string; nested: boolean }>();
const emit = defineEmits<{ "update:modelValue": [value: string] }>();
</script>
<template>
  <div class="collection-tabs" role="group" aria-label="文件集合">
    <button
      v-for="item in [
        { key: 'all', label: nested ? '当前目录' : '全部文件' },
        { key: 'favorites', label: '我的收藏' },
        { key: 'opened', label: '最近打开' },
        { key: 'uploaded', label: '最近上传' },
      ]"
      :key="item.key"
      :aria-pressed="modelValue === item.key"
      :class="{ active: modelValue === item.key }"
      @click="emit('update:modelValue', item.key)"
    >
      {{ item.label }}
    </button>
  </div>
</template>
