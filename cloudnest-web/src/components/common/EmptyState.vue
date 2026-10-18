<script setup lang="ts">
defineProps<{ title: string; description?: string }>();
</script>
<template>
  <section
    class="cn-empty"
    role="region"
    :aria-label="title"
    aria-live="polite"
  >
    <svg viewBox="0 0 96 72" fill="none" aria-hidden="true">
      <path
        d="M12 26a6 6 0 0 1 6-6h22l8 8h30a6 6 0 0 1 6 6v24a6 6 0 0 1-6 6H18a6 6 0 0 1-6-6V26Z"
        fill="#EEF1FF"
        stroke="#A9B3F8"
        stroke-width="2"
      />
      <path
        d="M30 44h36M30 52h22"
        stroke="#8593F5"
        stroke-width="2"
        stroke-linecap="round"
      />
      <circle cx="74" cy="14" r="9" fill="#F1F4F9" />
    </svg>
    <h3>{{ title }}</h3>
    <p v-if="description">{{ description }}</p>
    <slot />
  </section>
</template>
<style scoped>
.cn-empty {
  padding: 48px 20px;
  text-align: center;
  color: var(--cn-muted);
}
svg {
  width: 96px;
  height: 72px;
  margin-bottom: 16px;
}
h3 {
  font-size: 16px;
  font-weight: 600;
  margin-bottom: 8px;
}
p {
  font-size: 14px;
  margin-bottom: 20px;
}
</style>
