<script setup lang="ts">
withDefaults(
  defineProps<{
    title: string;
    description?: string;
    variant?:
      | "folder"
      | "search"
      | "trash"
      | "favorite"
      | "recent"
      | "share"
      | "error";
  }>(),
  { variant: "folder" },
);
</script>
<template>
  <section
    class="cn-empty"
    role="region"
    :aria-label="title"
    aria-live="polite"
  >
    <svg
      class="empty-illustration"
      viewBox="0 0 160 112"
      fill="none"
      aria-hidden="true"
    >
      <ellipse cx="80" cy="98" rx="57" ry="5" fill="#191A1D" />
      <circle cx="80" cy="52" r="44" fill="#25262B" />
      <path
        d="M111 26h15a8 8 0 0 0-1-16 11 11 0 0 0-21 3 7 7 0 0 0 1 13h6"
        stroke="#454853"
        stroke-width="1.5"
      />
      <path
        d="M32 41a6 6 0 0 1 6-6h30l9 9h44a6 6 0 0 1 6 6v38a6 6 0 0 1-6 6H38a6 6 0 0 1-6-6V41Z"
        fill="#343742"
      />
      <path
        d="M58 29h40a4 4 0 0 1 4 4v44H54V33a4 4 0 0 1 4-4Z"
        fill="#30323A"
        stroke="#565A68"
        transform="rotate(8 78 53)"
      />
      <path
        d="M65 41h25M65 49h19"
        stroke="#626675"
        stroke-width="2"
        stroke-linecap="round"
      />
      <path
        d="M29 58a6 6 0 0 1 6-6h91a6 6 0 0 1 6 7l-6 30a6 6 0 0 1-6 5H41a6 6 0 0 1-6-5l-6-30Z"
        fill="#2C2E36"
        stroke="#5C6173"
        stroke-width="1.5"
      />
      <g
        stroke="#999DB8"
        stroke-width="2"
        stroke-linecap="round"
        stroke-linejoin="round"
      >
        <template v-if="variant === 'search'">
          <circle cx="79" cy="70" r="9" />
          <path d="m86 77 7 7" />
        </template>
        <template v-else-if="variant === 'trash'">
          <path d="M70 64h22M74 64l2 19h10l2-19M77 60h8M79 69v9M84 69v9" />
        </template>
        <path
          v-else-if="variant === 'favorite'"
          d="m81 60 4 8 9 1-6 6 1 9-8-4-8 4 1-9-6-6 9-1 4-8Z"
        />
        <template v-else-if="variant === 'recent'">
          <circle cx="81" cy="72" r="11" />
          <path d="M81 65v7l5 3" />
        </template>
        <template v-else-if="variant === 'share'">
          <path
            d="m77 75-3 3a5 5 0 0 1-7-7l6-6a5 5 0 0 1 7 0M84 69l3-3a5 5 0 0 1 7 7l-6 6a5 5 0 0 1-7 0M76 76l10-10"
          />
        </template>
        <template v-else-if="variant === 'error'">
          <circle cx="81" cy="72" r="11" />
          <path d="M81 65v8M81 78v1" />
        </template>
        <path v-else d="M68 74h26M74 81h14" />
      </g>
      <path
        d="M22 30v6M19 33h6M139 70v6M136 73h6"
        stroke="#484B57"
        stroke-width="1.5"
        stroke-linecap="round"
      />
    </svg>
    <h3>{{ title }}</h3>
    <p v-if="description">{{ description }}</p>
    <slot />
  </section>
</template>
<style scoped>
.cn-empty {
  display: flex;
  flex-direction: column;
  align-items: center;
  justify-content: center;
  min-height: 340px;
  padding: 48px 24px;
  text-align: center;
  color: var(--cn-muted);
}
.empty-illustration {
  width: 128px;
  height: 90px;
  margin-bottom: 24px;
}
h3 {
  font-size: 16px;
  font-weight: 600;
  margin-bottom: 8px;
}
p {
  font-size: 14px;
  max-width: 380px;
  margin-bottom: 24px;
}
@media (max-width: 767px) {
  .cn-empty {
    min-height: 300px;
    padding: 32px 16px;
  }
}
</style>
