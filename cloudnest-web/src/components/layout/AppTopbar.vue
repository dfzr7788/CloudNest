<script setup lang="ts">
import { Menu } from "@element-plus/icons-vue";
import CloudNestLogo from "../brand/CloudNestLogo.vue";
defineProps<{ title: string }>();
const emit = defineEmits<{ menu: [] }>();
</script>
<template>
  <header class="topbar">
    <div class="topbar-context">
      <el-button
        class="mobile-menu"
        :icon="Menu"
        aria-label="打开导航"
        @click="emit('menu')"
      /><CloudNestLogo class="mobile-brand" /><strong class="desktop-context">{{
        title
      }}</strong>
    </div>
    <div class="topbar-search"><slot name="search" /></div>
    <div class="topbar-right"><slot /></div>
  </header>
</template>
