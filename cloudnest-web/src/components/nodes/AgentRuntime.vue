<script setup lang="ts">
import { requestKey } from "../../utils/requestKey";
import { ref, watch, onBeforeUnmount, computed } from "vue";
import { t } from "../../i18n";
import { errorText, date } from "../../api";
import { ai, type AiModel } from "../../api/ai";
import { agentRuns, type AgentRun } from "../../api/agent";
const props = defineProps<{ active: boolean }>();
const enabled = ref(false),
  loaded = ref(false),
  busy = ref(false),
  consent = ref(false);
const failure = ref(""),
  objective = ref(""),
  model = ref("");
const models = ref<AiModel[]>([]),
  history = ref<AgentRun[]>([]),
  run = ref<AgentRun | null>(null);
let disposed = false,
  timer: ReturnType<typeof setTimeout> | undefined;
// Reuse an uncertain request's key. Editing its input starts a distinct request.
let pending: { signature: string; key: string } | undefined;
const running = computed(
  () => run.value && ["QUEUED", "RUNNING"].includes(run.value.status),
);
async function load() {
  busy.value = true;
  failure.value = "";
  try {
    const caps = await agentRuns.available();
    if (disposed) return;
    enabled.value = caps.enabled;
    if (caps.enabled) {
      const [m, h] = await Promise.all([ai.models(), agentRuns.list()]);
      if (disposed) return;
      models.value = m.filter((x) => x.enabled && x.purpose !== "EMBEDDING");
      history.value = h;
      model.value ||= models.value[0]?.id || "";
      if (!run.value && h[0]) select(h[0]);
    }
    loaded.value = true;
  } catch (e) {
    if (!disposed) failure.value = errorText(e);
  } finally {
    if (!disposed) busy.value = false;
  }
}
function schedule() {
  clearTimeout(timer);
  if (!disposed && props.active && running.value)
    timer = setTimeout(() => void poll(), 2000);
}
async function poll() {
  const id = run.value?.id;
  if (!id) return;
  try {
    const next = await agentRuns.get(id);
    if (disposed || run.value?.id !== id) return;
    run.value = next;
    history.value = [next, ...history.value.filter((x) => x.id !== id)];
    schedule();
  } catch (e) {
    if (!disposed) failure.value = errorText(e);
  }
}
function select(value: AgentRun) {
  run.value = value;
  failure.value = "";
  schedule();
}
async function start() {
  if (
    busy.value ||
    !enabled.value ||
    !consent.value ||
    !model.value ||
    !objective.value.trim()
  )
    return;
  busy.value = true;
  failure.value = "";
  const signature = JSON.stringify([model.value, objective.value]);
  if (pending?.signature !== signature)
    pending = { signature, key: requestKey() };
  try {
    const created = await agentRuns.create(
      model.value,
      objective.value,
      pending.key,
    );
    if (disposed) return;
    pending = undefined;
    select(created);
    history.value = [
      created,
      ...history.value.filter((x) => x.id !== created.id),
    ].slice(0, 30);
  } catch (e) {
    if (!disposed) failure.value = errorText(e);
  } finally {
    if (!disposed) busy.value = false;
  }
}
async function cancel() {
  if (!run.value || busy.value) return;
  busy.value = true;
  try {
    select(await agentRuns.cancel(run.value.id));
  } catch (e) {
    if (!disposed) failure.value = errorText(e);
  } finally {
    if (!disposed) busy.value = false;
  }
}
watch(
  () => props.active,
  (active) => {
    if (active) {
      if (!loaded.value) void load();
      else schedule();
    } else clearTimeout(timer);
  },
  { immediate: true },
);
onBeforeUnmount(() => {
  disposed = true;
  clearTimeout(timer);
});
</script>
<template>
  <section class="agent-runtime">
    <el-alert :title="t('agentRuntime.notice')" type="info" :closable="false" />
    <p v-if="failure" role="alert">{{ failure }}</p>
    <el-button :loading="busy" @click="load">{{
      t("agentRuntime.refresh")
    }}</el-button>
    <p v-if="loaded && !enabled">{{ t("agentRuntime.disabled") }}</p>
    <el-form v-if="enabled" label-position="top" @submit.prevent="start">
      <el-form-item :label="t('agentRuntime.model')">
        <el-select v-model="model"
          ><el-option
            v-for="m in models"
            :key="m.id"
            :value="m.id"
            :label="m.name"
        /></el-select>
      </el-form-item>
      <el-form-item :label="t('agentRuntime.objective')"
        ><el-input
          v-model="objective"
          type="textarea"
          :rows="3"
          :maxlength="2000"
          show-word-limit
      /></el-form-item>
      <el-checkbox v-model="consent">{{
        t("agentRuntime.consent")
      }}</el-checkbox>
      <div>
        <el-button
          type="primary"
          :disabled="!consent || !objective.trim() || !model"
          :loading="busy"
          @click="start"
          >{{ t("agentRuntime.start") }}</el-button
        >
      </div>
    </el-form>
    <div v-if="history.length" class="history">
      <span>{{ t("agentRuntime.history") }}</span>
      <el-button v-for="item in history" :key="item.id" @click="select(item)"
        >{{ date(item.created_at) }} · {{ item.status }}</el-button
      >
    </div>
    <article v-if="run">
      <p>{{ run.id }} · {{ run.status }} · {{ run.completed_calls }} / 6</p>
      <el-button v-if="running" :loading="busy" @click="cancel">{{
        t("agentRuntime.cancel")
      }}</el-button>
      <p v-if="run.error_code" role="alert">
        {{ run.error_code }}: {{ run.error_message }}
      </p>
      <template v-if="run.result">
        <p>
          {{ run.result.outcome }} · {{ t("agentRuntime.budget") }}:
          {{ run.result.reservedTokens }} / 32768
        </p>
        <h3>{{ t("agentRuntime.inference") }}</h3>
        <pre>{{ run.result.text }}</pre>
        <h3>{{ t("agentRuntime.evidence") }}</h3>
        <pre>{{ JSON.stringify(run.result.steps, null, 2) }}</pre>
      </template>
    </article>
  </section>
</template>
<style scoped>
.agent-runtime {
  display: grid;
  gap: 16px;
  min-width: 0;
}
pre {
  white-space: pre-wrap;
  overflow-wrap: anywhere;
  max-height: 520px;
  overflow: auto;
  background: var(--el-fill-color-light);
  padding: 12px;
}
.history {
  display: flex;
  flex-wrap: wrap;
  gap: 8px;
}
.el-select {
  width: 100%;
}
.el-checkbox {
  height: auto;
  margin-bottom: 12px;
}
:deep(.el-checkbox__label) {
  white-space: normal;
}
</style>
