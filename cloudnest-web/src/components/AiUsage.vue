<script setup lang="ts">
import { ref, watch } from "vue";
import { api, date, errorText } from "../api";
import { ElMessage } from "element-plus";
const props = defineProps<{ active: boolean }>();
interface Usage {
  id: string;
  model_name: string;
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
    <p>
      每日最多 100 次上游调用，按 UTC 00:00（北京时间
      08:00）重置；测试及分段调用均计数。
    </p>
    <el-input-number v-model="days" :min="1" :max="90" aria-label="统计天数" />
    天
    <el-button
      @click="
        page = 1;
        load();
      "
      >刷新用量</el-button
    >
    <p v-if="summary">
      共 {{ summary.calls }} 次调用，{{ summary.known_usage_calls }} 次返回总
      token；已知总量
      {{
        summary.total_tokens ?? "未知"
      }}。未返回用量显示“未知”，费用以供应商账单为准。
    </p>
    <el-table :data="items" max-height="350" empty-text="暂无调用记录">
      <el-table-column label="时间" min-width="160"
        ><template #default="s">{{
          date(s.row.created_at)
        }}</template></el-table-column
      >
      <el-table-column prop="model_name" label="模型" min-width="100" />
      <el-table-column prop="status" label="状态" min-width="110" />
      <el-table-column label="输入/输出 token" min-width="150"
        ><template #default="s"
          >{{ s.row.input_tokens ?? "未知" }} /
          {{ s.row.output_tokens ?? "未知" }}</template
        ></el-table-column
      >
      <el-table-column label="耗时" min-width="90"
        ><template #default="s">{{
          s.row.latency == null ? "未知" : s.row.latency + " ms"
        }}</template></el-table-column
      >
      <el-table-column prop="error_code" label="错误码" min-width="150" />
    </el-table>
    <el-button
      :disabled="page === 1"
      @click="
        page--;
        load();
      "
      >上一页</el-button
    >
    <span>第 {{ page }} 页</span>
    <el-button
      :disabled="items.length < 50"
      @click="
        page++;
        load();
      "
      >下一页</el-button
    >
  </section>
</template>
