<script setup lang="ts">
import { computed, ref, watch, onBeforeUnmount } from "vue";
import { t } from "../../i18n";
import { date, errorText } from "../../api";
import {
  agentTools,
  type AgentToolAvailability,
  type AgentToolName,
  type AgentToolResult,
} from "../../api/agent";

const props = defineProps<{ active: boolean }>();
const availability = ref<AgentToolAvailability | null>(null);
const selected = ref<AgentToolName>("get_node_status");
const backend = ref("");
const limit = ref(20);
const loading = ref(false),
  executing = ref(false),
  failure = ref("");
const result = ref<AgentToolResult | null>(null);
let generation = 0,
  disposed = false;
const requiresBackend = computed(() => selected.value === "get_node_events");
const requiresLimit = computed(
  () => selected.value !== "explain_storage_policy",
);
const evidence = computed(() =>
  result.value ? JSON.stringify(result.value.data, null, 2) : "",
);
const description = computed(() =>
  t(`agentTools.description.${selected.value}`),
);

async function load() {
  const ticket = ++generation;
  loading.value = true;
  failure.value = "";
  try {
    const data = await agentTools.available();
    if (disposed || ticket !== generation) return;
    availability.value = data;
    if (
      !data.tools.some((tool) => tool.name === selected.value) &&
      data.tools.length
    )
      selected.value = data.tools[0].name;
  } catch (e) {
    if (!disposed && ticket === generation) failure.value = errorText(e);
  } finally {
    if (!disposed && ticket === generation) loading.value = false;
  }
}
async function execute() {
  if (executing.value || !availability.value?.enabled) return;
  if (requiresBackend.value && !backend.value.trim()) {
    failure.value = t("agentTools.backendRequired");
    return;
  }
  if (
    requiresLimit.value &&
    (!Number.isInteger(limit.value) || limit.value < 1 || limit.value > 100)
  ) {
    failure.value = t("agentTools.limitInvalid");
    return;
  }
  executing.value = true;
  failure.value = "";
  // Remove previous evidence before sending so a failed request cannot look successful.
  result.value = null;
  const name = selected.value;
  const args: Record<string, unknown> = {};
  if (requiresLimit.value) args.limit = limit.value;
  if (requiresBackend.value) args.backend = backend.value.trim();
  try {
    const data = await agentTools.invoke(name, args);
    if (!disposed) result.value = data;
  } catch (e) {
    if (!disposed) failure.value = errorText(e);
  } finally {
    if (!disposed) executing.value = false;
  }
}
watch(
  () => props.active,
  (active) => {
    if (active && !availability.value && !loading.value) void load();
  },
  { immediate: true },
);
onBeforeUnmount(() => {
  disposed = true;
  generation++;
});
</script>
<template>
  <section
    class="agent-diagnostics"
    :aria-label="t('agentTools.title')"
    v-loading="loading"
  >
    <h2>{{ t("agentTools.title") }}</h2>
    <p>{{ t("agentTools.intro") }}</p>
    <el-alert
      v-if="failure"
      :title="failure"
      type="error"
      :closable="false"
      show-icon
    />
    <el-button v-if="!availability" :loading="loading" @click="load">{{
      t("common.reload")
    }}</el-button>
    <el-alert
      v-else-if="!availability.enabled"
      :title="t('agentTools.disabled')"
      type="info"
      :closable="false"
      show-icon
    />
    <template v-else>
      <el-form label-position="top" @submit.prevent="execute">
        <el-form-item :label="t('agentTools.choose')">
          <el-select
            v-model="selected"
            :disabled="executing"
            :aria-label="t('agentTools.choose')"
          >
            <el-option
              v-for="tool in availability.tools"
              :key="tool.name"
              :value="tool.name"
              :label="t(`agentTools.name.${tool.name}`)"
            />
          </el-select>
        </el-form-item>
        <p class="tool-description">{{ description }}</p>
        <el-form-item v-if="requiresBackend" :label="t('agentTools.backend')">
          <el-input
            v-model="backend"
            maxlength="80"
            :disabled="executing"
            :aria-label="t('agentTools.backend')"
            :placeholder="t('agentTools.backendHint')"
          />
        </el-form-item>
        <el-form-item v-if="requiresLimit" :label="t('agentTools.limit')">
          <el-input-number
            v-model="limit"
            :min="1"
            :max="100"
            :step="1"
            :precision="0"
            :disabled="executing"
            :aria-label="t('agentTools.limit')"
          />
        </el-form-item>
        <el-button
          type="primary"
          native-type="submit"
          :loading="executing"
          :disabled="!availability.tools.length"
          >{{ t("agentTools.execute") }}</el-button
        >
      </el-form>
      <article v-if="result" class="tool-result" aria-live="polite">
        <h3>{{ t(`agentTools.name.${result.tool}`) }}</h3>
        <dl>
          <dt>{{ t("agentTools.observedAt") }}</dt>
          <dd>{{ date(result.observedAt) }}</dd>
          <dt>{{ t("agentTools.source") }}</dt>
          <dd>{{ t("agentTools.metadataSource") }}</dd>
          <dt>{{ t("agentTools.invocation") }}</dt>
          <dd>{{ result.invocationId }}</dd>
        </dl>
        <p>{{ t("agentTools.evidenceNote") }}</p>
        <pre :aria-label="t('agentTools.evidence')">{{ evidence }}</pre>
      </article>
    </template>
  </section>
</template>
<style scoped>
.agent-diagnostics {
  max-width: 960px;
}
form {
  max-width: 520px;
  margin: 24px 0;
}
.tool-description {
  line-height: 1.7;
}
.tool-result {
  border: 1px solid var(--cn-border-strong);
  border-radius: 10px;
  padding: 20px;
  margin-top: 24px;
}
dl {
  display: grid;
  grid-template-columns: auto 1fr;
  gap: 8px 16px;
}
dt {
  color: var(--cn-muted);
}
dd {
  margin: 0;
  overflow-wrap: anywhere;
}
pre {
  max-height: 480px;
  overflow: auto;
  padding: 16px;
  background: var(--cn-surface);
  white-space: pre-wrap;
  overflow-wrap: anywhere;
  font-size: 13px;
}
@media (max-width: 640px) {
  dl {
    grid-template-columns: 1fr;
  }
  .tool-result {
    padding: 12px;
  }
}
</style>
