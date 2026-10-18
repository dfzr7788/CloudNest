<script setup lang="ts">
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
  <section class="storage-card" aria-label="存储空间">
    <div>
      <span>存储空间</span><strong>{{ percent }}%</strong>
    </div>
    <el-progress
      :percentage="percent"
      :show-text="false"
      :stroke-width="5"
      :color="percent > 90 ? '#bd690a' : '#5266f1'"
    />
    <p>{{ bytes(user?.used || 0) }} / {{ bytes(user?.quota || 0) }}</p>
    <small v-if="user?.reserved"
      >上传预留 {{ bytes(user.reserved) }}（已计入进度）</small
    ><small v-else>回收站与历史版本占用配额</small>
  </section>
</template>
