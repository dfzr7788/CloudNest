<script setup lang="ts">
import { t } from "../i18n";
import { ref, watch } from "vue";
import { api, date, errorText } from "../api";
import { ElMessage } from "element-plus";
const props = defineProps<{ active: boolean }>();
interface Usage {
  id: string;
  model_name: string;
  purpose?: string;
  status: string;
  input_tokens: number | null;
  output_tokens: number | null;
  total_tokens: number | null;
  latency: number | null;
  created_at: number;
  error_code: string | null;
}
const items = ref<Usage[]>([]),
  page = ref(1),
  days = ref(30),
  busy = ref(false);
const summary = ref<{
  calls: number;
  known_usage_calls: number;
  total_tokens: number | null;
}>();
async function load() {
  busy.value = true;
  try {
    const { data } = await api.get("/ai/usage", {
      params: { page: page.value, days: days.value },
    });
    items.value = data.items;
    summary.value = data.summary;
  } catch (e) {
    ElMessage.error(errorText(e));
  } finally {
    busy.value = false;
  }
}
watch(
  () => props.active,
  (active) => {
    if (active) void load();
  },
  { immediate: true },
);
</script>
<template>
  <section v-loading="busy" class="ai-usage">
    <p>{{ t("usage.limit") }}</p>
    <el-input-number
      v-model="days"
      :min="1"
      :max="90"
      :aria-label="t('usage.days')"
    />
    <el-button
      @click="
        page = 1;
        load();
      "
      >{{ t("usage.refresh") }}</el-button
    >
    <p v-if="summary">
      {{
        t("usage.summary", {
          calls: summary.calls,
          known: summary.known_usage_calls,
          tokens: summary.total_tokens ?? t("usage.unknown"),
        })
      }}
    </p>
    <el-table :data="items" max-height="350" :empty-text="t('usage.empty')">
      <el-table-column :label="t('common.time')" min-width="160"
        ><template #default="{ row }">{{
          date(row.created_at)
        }}</template></el-table-column
      >
      <el-table-column
        prop="model_name"
        :label="t('usage.model')"
        min-width="100"
      />
      <el-table-column :label="t('usage.purpose')" min-width="110"
        ><template #default="{ row }">{{
          row.purpose === "EMBEDDING" ? t("usage.embedding") : t("usage.chat")
        }}</template></el-table-column
      >
      <el-table-column
        prop="status"
        :label="t('common.status')"
        min-width="110"
      />
      <el-table-column :label="t('usage.tokens')" min-width="150"
        ><template #default="{ row }"
          >{{ row.input_tokens ?? t("usage.unknown") }} /
          {{
            row.purpose === "EMBEDDING"
              ? t("usage.na")
              : (row.output_tokens ?? t("usage.unknown"))
          }}</template
        ></el-table-column
      >
      <el-table-column :label="t('usage.latency')" min-width="110"
        ><template #default="{ row }">{{
          row.latency == null ? t("usage.unknown") : row.latency + " ms"
        }}</template></el-table-column
      >
      <el-table-column
        prop="error_code"
        :label="t('usage.error')"
        min-width="150"
      />
    </el-table>
    <div class="usage-pagination">
      <el-button
        :disabled="page === 1"
        @click="
          page--;
          load();
        "
        >{{ t("usage.previous") }}</el-button
      >
      <span>{{ t("account.page", { page }) }}</span>
      <el-button
        :disabled="items.length < 50"
        @click="
          page++;
          load();
        "
        >{{ t("usage.next") }}</el-button
      >
    </div>
  </section>
</template>
<style scoped>
.ai-usage {
  min-width: 0;
}
.usage-pagination {
  display: flex;
  align-items: center;
  flex-wrap: wrap;
  gap: 10px;
  margin-top: 12px;
}
</style>
