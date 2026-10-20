<script setup lang="ts">
import { t } from "../../i18n";
defineProps<{ modelValue: string; nested: boolean }>();
const emit = defineEmits<{ "update:modelValue": [value: string] }>();
</script>
<template>
  <div class="collection-tabs" role="group" :aria-label="t('files.collection')">
    <button
      v-for="item in [
        {
          key: 'all',
          label: nested ? t('files.currentFolder') : t('files.all'),
        },
        { key: 'favorites', label: t('nav.favorites') },
        { key: 'opened', label: t('files.opened') },
        { key: 'uploaded', label: t('nav.uploaded') },
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
