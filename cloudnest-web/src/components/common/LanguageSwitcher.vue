<script setup lang="ts">
import { locale, setLocale } from "../../i18n";
</script>
<template>
  <select
    class="language-switcher"
    aria-label="Language / 语言"
    :value="locale"
    @change="setLocale(($event.target as HTMLSelectElement).value)"
  >
    <option value="zh-CN">简体中文</option>
    <option value="en-US">English</option>
  </select>
</template>
<style scoped>
.language-switcher {
  color: var(--cn-text);
  background: var(--cn-surface, #222327);
  border: 1px solid var(--cn-border-strong);
  border-radius: 6px;
  padding: 6px 8px;
  max-width: 120px;
  min-height: 32px;
}
</style>
