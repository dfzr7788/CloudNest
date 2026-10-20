<script setup lang="ts">
withDefaults(defineProps<{ compact?: boolean; light?: boolean }>(), {
  compact: false,
  light: false,
});
</script>
<template>
  <span class="cn-logo" :class="{ 'cn-logo--light': light }">
    <svg viewBox="0 0 40 40" fill="none" aria-hidden="true">
      <rect width="40" height="40" rx="11" fill="var(--cn-brand)" />
      <rect
        x="0.5"
        y="0.5"
        width="39"
        height="39"
        rx="10.5"
        stroke="white"
        stroke-opacity="0.22"
      />
      <path
        d="M28.5 25.5a6.2 6.2 0 0 0-1.6-12.1 8.2 8.2 0 0 0-15.1 1.9A5.1 5.1 0 0 0 13 25.5h4"
        stroke="white"
        stroke-width="2.1"
        stroke-linecap="round"
        stroke-linejoin="round"
      />
      <path
        d="M13 29.5h13.5l3-9.5H17l-4 9.5Z"
        fill="var(--cn-brand-pressed)"
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
  gap: 9px;
  color: var(--cn-brand);
  white-space: nowrap;
}
svg {
  width: 26px;
  height: 26px;
  flex-shrink: 0;
  box-shadow: 0 2px 6px var(--cn-brand-glow);
  border-radius: 7px;
}
.cn-logo-name {
  color: var(--cn-heading);
  font-size: 16px;
  font-weight: 600;
  letter-spacing: -0.03em;
}
</style>
