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
        d="M29 26H13.5a6 6 0 0 1-.9-11.9 8 8 0 0 1 15.1 1.5A5.3 5.3 0 0 1 30 25M12 27l3-7h14"
        stroke="white"
        stroke-width="2"
        stroke-linecap="round"
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
  color: var(--cn-brand);
  white-space: nowrap;
}
svg {
  width: 36px;
  height: 36px;
  flex-shrink: 0;
}
.cn-logo-name {
  color: var(--cn-heading);
  font-size: 21px;
  font-weight: 700;
  letter-spacing: -0.7px;
}
.cn-logo--light,
.cn-logo--light .cn-logo-name {
  color: white;
}
.cn-logo--light svg {
  color: #6374da;
}
</style>
