<script setup lang="ts">
withDefaults(defineProps<{ compact?: boolean; light?: boolean }>(), {
  compact: false,
  light: false,
});
</script>
<template>
  <span class="cn-logo" :class="{ 'cn-logo--light': light }">
    <svg viewBox="0 0 40 40" fill="none" aria-hidden="true">
      <rect width="40" height="40" rx="12" fill="currentColor" />
      <path
        d="M28.5 25.5a6.2 6.2 0 0 0-1.6-12.1 8.2 8.2 0 0 0-15.1 1.9A5.1 5.1 0 0 0 13 25.5h4"
        stroke="white"
        stroke-width="2.1"
        stroke-linecap="round"
        stroke-linejoin="round"
      />
      <path
        d="M13 29.5h13.5l3-9.5H17l-4 9.5Z"
        fill="currentColor"
        stroke="white"
        stroke-width="2"
        stroke-linejoin="round"
      />
    </svg>
    <span v-if="!compact" class="cn-logo-name">CloudNest</span>
  </span>
</template>
<style scoped>
.cn-logo {
  display: inline-flex;
  align-items: center;
  gap: 10px;
  color: #75798b;
  white-space: nowrap;
}
svg {
  width: 28px;
  height: 28px;
  flex-shrink: 0;
}
.cn-logo-name {
  color: var(--cn-heading);
  font-size: 18px;
  font-weight: 600;
  letter-spacing: -0.7px;
}
.cn-logo--light,
.cn-logo--light .cn-logo-name {
  color: white;
}
.cn-logo--light svg {
  color: #75798b;
}
</style>
