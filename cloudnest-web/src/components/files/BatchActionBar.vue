<script setup lang="ts">
defineProps<{ count: number }>();
</script>
<template>
  <section v-if="count" class="batch-action-bar" aria-label="批量操作">
    <slot />
  </section>
</template>
