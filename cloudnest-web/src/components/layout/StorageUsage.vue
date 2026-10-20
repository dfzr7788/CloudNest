<script setup lang="ts">
import { t } from "../../i18n";
import { computed } from "vue";
import { bytes, type User } from "../../api";
const props = defineProps<{ user: User | null }>();
const percent = computed(() =>
  props.user?.quota
    ? Math.min(
        100,
        Math.round(
          ((props.user.used + props.user.reserved) / props.user.quota) * 100,
        ),
      )
    : 0,
);
</script>
<template>
  <section class="storage-card" :aria-label="t('storage.space')">
    <div>
      <span>{{ t("storage.space") }}</span
      ><strong>{{ percent }}%</strong>
    </div>
    <el-progress
      :percentage="percent"
      :show-text="false"
      :stroke-width="5"
      :color="percent > 90 ? 'var(--cn-warning)' : 'var(--cn-brand)'"
    />
    <p>{{ bytes(user?.used || 0) }} / {{ bytes(user?.quota || 0) }}</p>
    <small v-if="user?.reserved"
      >{{ t("storage.reserved") }} {{ bytes(user.reserved)
      }}{{ t("storage.counted") }}</small
    ><small v-else>{{ t("storage.quotaNote") }}</small>
  </section>
</template>
