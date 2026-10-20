<script setup lang="ts">
import { t } from "../../i18n";
defineProps<{ count: number }>();
</script>
<template>
  <section v-if="count" class="batch-action-bar" :aria-label="t('files.batch')">
    <slot />
  </section>
</template>
