<script setup lang="ts">
import { computed } from "vue";
import { locale } from "./i18n";
import zhCN from "element-plus/es/locale/lang/zh-cn";
import enUS from "element-plus/es/locale/lang/en";
const elementLocale = computed(() => (locale.value === "en-US" ? enUS : zhCN));
</script>
<template>
  <el-config-provider :locale="elementLocale"
    ><router-view
  /></el-config-provider>
</template>
