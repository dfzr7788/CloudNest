<script setup lang="ts">
import { t } from "../../i18n";
import { Menu } from "@element-plus/icons-vue";
import CloudNestLogo from "../brand/CloudNestLogo.vue";
import LanguageSwitcher from "../common/LanguageSwitcher.vue";
defineProps<{ title: string }>();
const emit = defineEmits<{ menu: [] }>();
</script>
<template>
  <header class="topbar">
    <div class="topbar-context">
      <el-button
        class="mobile-menu"
        :icon="Menu"
        :aria-label="t('nav.openNavigation')"
        @click="emit('menu')"
      /><CloudNestLogo class="mobile-brand" /><strong class="desktop-context">{{
        title
      }}</strong>
    </div>
    <div class="topbar-search"><slot name="search" /></div>
    <div class="topbar-right"><LanguageSwitcher /><slot /></div>
  </header>
</template>
