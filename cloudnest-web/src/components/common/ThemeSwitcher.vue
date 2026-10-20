<script setup lang="ts">
import { computed } from "vue";
import { Sunny, Moon } from "@element-plus/icons-vue";
import { t } from "../../i18n";
import { theme, toggleTheme } from "../../theme";

const label = computed(() =>
  theme.value === "dark" ? t("theme.switchToLight") : t("theme.switchToDark"),
);
</script>
<template>
  <el-tooltip :content="label">
    <el-button
      class="cn-icon-button theme-switcher"
      :icon="theme === 'dark' ? Sunny : Moon"
      :aria-label="label"
      @click="toggleTheme"
    />
  </el-tooltip>
</template>
<style scoped>
.theme-switcher {
  width: 32px;
  height: 32px;
  padding: 0;
  flex-shrink: 0;
}
@media (max-width: 767px) {
  .theme-switcher {
    width: 36px;
    height: 36px;
  }
}
</style>
