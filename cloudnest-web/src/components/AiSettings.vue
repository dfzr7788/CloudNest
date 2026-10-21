<script setup lang="ts">
import { t } from "../i18n";
import { computed, reactive, ref, watch, onBeforeUnmount } from "vue";
import { ElMessage, ElMessageBox } from "element-plus";
import { api, errorText } from "../api";
import {
  ai,
  aiFinished,
  aiStage,
  type AiConnection,
  type AiModel,
  type AiTask,
} from "../api/ai";
import AiUsage from "./AiUsage.vue";
import { useUser } from "../stores/user";
const account = useUser();
const props = defineProps<{ active: boolean }>();
const enabled = ref(false),
  busy = ref(false),
  connections = ref<AiConnection[]>([]),
  models = ref<AiModel[]>([]),
  defaultModel = ref<string | null>(null);
const connectionEditor = ref(false),
  modelEditor = ref(false),
  current = ref<AiConnection | null>(null),
  currentModel = ref<AiModel | null>(null);
const form = reactive({ name: "", baseUrl: "", apiKey: "", enabled: true });
const modelForm = reactive({
  purpose: "CHAT" as "CHAT" | "EMBEDDING",
  embeddingDimensions: 1536,
  maxBatchSize: 16,
  requestDimensions: false,
  connectionId: "",
  name: "",
  modelId: "",
  contextLimit: 32768,
  maxOutput: 1024,
  outputParamMode: "max_tokens",
  enabled: true,
});
const testTask = ref<AiTask | null>(null);
const chatModels = computed(() =>
  models.value.filter((m) => m.purpose !== "EMBEDDING"),
);
const embeddingModels = computed(() =>
  models.value.filter((m) => m.purpose === "EMBEDDING"),
);
let timer: ReturnType<typeof setTimeout> | undefined,
  generation = 0;
let pendingTest: { model: string; key: string } | undefined;
async function load() {
  const [caps, cs, ms, pref] = await Promise.all([
    ai.capabilities(),
    ai.connections(),
    ai.models(),
    ai.preferences(),
  ]);
  enabled.value = caps.enabled;
  connections.value = cs;
  models.value = ms;
  defaultModel.value = pref.default_chat_model_id;
}
async function run(action: () => Promise<unknown>) {
  busy.value = true;
  try {
    await action();
    await load();
  } catch (e) {
    if (e !== "cancel" && e !== "close") ElMessage.error(errorText(e));
  } finally {
    busy.value = false;
  }
}
function edit(c: AiConnection | null) {
  current.value = c;
  Object.assign(form, {
    name: c?.name || "",
    baseUrl: c?.base_url || "",
    apiKey: "",
    enabled: c?.enabled ?? true,
  });
  connectionEditor.value = true;
}
async function saveConnection() {
  await run(async () => {
    const input = {
      name: form.name,
      baseUrl: form.baseUrl,
      enabled: form.enabled,
      ...(!current.value || form.apiKey ? { apiKey: form.apiKey } : {}),
      ...(current.value ? { configVersion: current.value.config_version } : {}),
    };
    try {
      if (current.value)
        await api.patch(`/ai/connections/${current.value.id}`, input);
      else await api.post("/ai/connections", input);
      connectionEditor.value = false;
    } finally {
      form.apiKey = "";
    }
  });
}
function editModel(m: AiModel | null, purpose: "CHAT" | "EMBEDDING" = "CHAT") {
  currentModel.value = m;
  Object.assign(modelForm, {
    purpose: m?.purpose || purpose,
    embeddingDimensions: m?.embedding_dimensions || 1536,
    maxBatchSize: m?.max_batch_size || 16,
    requestDimensions: m?.request_dimensions ?? false,
    connectionId: m?.connection_id || connections.value[0]?.id || "",
    name: m?.name || "",
    modelId: m?.model_id || "",
    contextLimit: m?.context_limit || (purpose === "EMBEDDING" ? 8192 : 32768),
    maxOutput: m?.max_output || 1024,
    outputParamMode: m?.output_param_mode || "max_tokens",
    enabled: m?.enabled ?? true,
  });
  modelEditor.value = true;
}
async function saveModel() {
  await run(async () => {
    const input = {
      connectionId: modelForm.connectionId,
      name: modelForm.name,
      modelId: modelForm.modelId,
      purpose: modelForm.purpose,
      contextLimit: modelForm.contextLimit,
      enabled: modelForm.enabled,
      ...(modelForm.purpose === "EMBEDDING"
        ? {
            embeddingDimensions: modelForm.embeddingDimensions,
            maxBatchSize: modelForm.maxBatchSize,
            requestDimensions: modelForm.requestDimensions,
          }
        : {
            maxOutput: modelForm.maxOutput,
            outputParamMode: modelForm.outputParamMode,
          }),
    };
    if (currentModel.value)
      await api.patch(`/ai/models/${currentModel.value.id}`, {
        ...input,
        configVersion: currentModel.value.config_version,
      });
    else await api.post("/ai/models", input);
    modelEditor.value = false;
  });
}
async function consent(c: AiConnection) {
  await ElMessageBox.confirm(
    t("ai.consentHelp", { destination: c.base_url }),
    t("ai.consentTitle"),
    { confirmButtonText: t("ai.agree"), cancelButtonText: t("common.cancel") },
  );
  await api.post("/ai/consents", {
    connectionId: c.id,
    destinationFingerprint: c.destinationFingerprint,
  });
}
async function remove(c: AiConnection, credentialOnly: boolean) {
  await run(async () => {
    await ElMessageBox.confirm(
      t("ai.revokeHelp"),
      credentialOnly ? t("ai.revokeKey") : t("ai.deleteConnection"),
      { type: "warning" },
    );
    await api.delete(
      `/ai/connections/${c.id}${credentialOnly ? "/credential" : ""}`,
    );
  });
}
function schedulePoll() {
  clearTimeout(timer);
  if (!props.active || !testTask.value || aiFinished(testTask.value)) return;
  const mine = generation,
    id = testTask.value.taskId;
  timer = setTimeout(
    async () => {
      try {
        const t = await ai.task(id);
        if (mine !== generation) return;
        testTask.value = t;
        if (aiFinished(t)) await load();
      } catch (e) {
        if (mine === generation) ElMessage.error(errorText(e));
        return;
      }
      schedulePoll();
    },
    document.hidden ? 8000 : 2000,
  );
}
async function test(m: AiModel) {
  await run(async () => {
    await ElMessageBox.confirm(
      m.purpose === "EMBEDDING"
        ? t("ai.embeddingTestHelp")
        : t("ai.chatTestHelp"),
      m.purpose === "EMBEDDING" ? t("ai.testEmbedding") : t("ai.testChat"),
    );
    if (!pendingTest || pendingTest.model !== m.id)
      pendingTest = { model: m.id, key: crypto.randomUUID() };
    testTask.value =
      m.purpose === "EMBEDDING"
        ? await ai.embeddingTest(m.id, pendingTest.key)
        : await ai.test(m.id, pendingTest.key);
    pendingTest = undefined;
    schedulePoll();
  });
}
watch(
  () => props.active,
  (active) => {
    generation++;
    clearTimeout(timer);
    form.apiKey = "";
    if (active) {
      void run(async () => {
        const tasks = await ai.tasks();
        testTask.value =
          tasks.find((t) => ["TEST", "EMBEDDING_TEST"].includes(t.kind)) ||
          null;
        schedulePoll();
      });
    } else {
      connectionEditor.value = false;
      modelEditor.value = false;
    }
  },
  { immediate: true },
);
watch(connectionEditor, (open) => {
  if (!open) form.apiKey = "";
});
onBeforeUnmount(() => {
  generation++;
  clearTimeout(timer);
  form.apiKey = "";
});
</script>
<template>
  <section v-loading="busy" class="ai-settings">
    <div v-if="account.user?.administrator" class="site-ai-control">
      <div>
        <strong>{{ t("ai.siteControl") }}</strong>
        <p>{{ t("ai.siteControlHelp") }}</p>
      </div>
      <el-button
        :type="enabled ? 'danger' : 'primary'"
        :disabled="busy"
        @click="run(() => api.put('/ai/site-settings', { enabled: !enabled }))"
      >
        {{ enabled ? t("ai.disableSite") : t("ai.enableSite") }}
      </el-button>
    </div>
    <el-alert
      v-if="!enabled"
      :title="t('ai.disabledHelp')"
      type="info"
      :closable="false"
    />
    <p>
      {{ t("ai.providerHelp") }}
    </p>
    <el-button
      type="primary"
      :disabled="!enabled || connections.length >= 5"
      @click="edit(null)"
      >{{ t("ai.addConnection") }}</el-button
    >
    <article v-for="c in connections" :key="c.id" class="connection-card">
      <strong>{{ c.name }}</strong> ·
      {{ c.enabled ? t("common.enable") : t("common.disable") }}
      {{ t("ai.keyPrefix")
      }}{{ c.keyConfigured ? t("ai.configured") : t("ai.notConfigured") }}
      <p class="destination">{{ c.base_url }}</p>
      <p>
        {{ t("ai.connectionTestPrefix") }}{{ c.last_test_status }}
        {{ t("ai.consentPrefix")
        }}{{ c.consented ? t("ai.confirmed") : t("ai.pending") }}
      </p>
      <el-button :disabled="!enabled" @click="edit(c)">{{
        t("common.edit")
      }}</el-button>
      <el-button :disabled="!enabled" @click="run(() => consent(c))">{{
        t("ai.reviewConsent")
      }}</el-button>
      <el-button @click="remove(c, true)">{{ t("ai.revokeKey") }}</el-button>
      <el-button type="danger" plain @click="remove(c, false)">{{
        t("common.delete")
      }}</el-button>
    </article>
    <h3>{{ t("ai.chatModels") }}</h3>
    <el-button
      :disabled="!enabled || !connections.length"
      @click="editModel(null)"
      >{{ t("ai.addModel") }}</el-button
    >
    <article v-for="m in chatModels" :key="m.id" class="connection-card">
      <strong>{{ m.name }}</strong> · {{ m.model_id }} ·
      {{ m.enabled ? t("common.enable") : t("common.disable") }}
      <p>
        {{ t("ai.connectionPrefix")
        }}{{ connections.find((c) => c.id === m.connection_id)?.name }}
        {{ t("ai.contextPrefix") }} {{ m.context_limit }}
        {{ t("ai.outputPrefix") }} {{ m.max_output }}
      </p>
      <p>{{ t("ai.modelTestPrefix") }}{{ m.last_test_status || "UNTESTED" }}</p>
      <el-button :disabled="!enabled" @click="editModel(m)">{{
        t("common.edit")
      }}</el-button>
      <el-button
        :disabled="
          !enabled || !m.enabled || (!!testTask && !aiFinished(testTask))
        "
        @click="test(m)"
        >{{ t("ai.testChatCost") }}</el-button
      >
      <el-button
        @click="
          run(async () => {
            await ElMessageBox.confirm(
              t('ai.deleteModelConfirm'),
              t('ai.deleteModel'),
            );
            await api.delete('/ai/models/' + m.id);
          })
        "
        >{{ t("common.delete") }}</el-button
      >
    </article>
    <h3>{{ t("ai.embeddingModels") }}</h3>
    <p>
      {{ t("ai.embeddingHelp") }}
    </p>
    <el-button
      :disabled="!enabled || !connections.length"
      @click="editModel(null, 'EMBEDDING')"
      >{{ t("ai.addEmbedding") }}</el-button
    >
    <article
      v-for="m in embeddingModels"
      :key="m.id"
      class="connection-card embedding-card"
    >
      <strong>{{ m.name }}</strong> · {{ m.model_id }} ·
      {{ m.enabled ? t("common.enable") : t("common.disable") }}
      <p>
        {{ t("ai.connectionPrefix")
        }}{{ connections.find((c) => c.id === m.connection_id)?.name }}
        {{ t("ai.dimensionsPrefix") }} {{ m.embedding_dimensions }}
        {{ t("ai.batchPrefix") }} {{ m.max_batch_size }}
        {{ t("ai.budgetPrefix") }} {{ m.context_limit }}
      </p>
      <p>
        {{ t("ai.modelTestPrefix") }}{{ m.last_test_status || "UNTESTED"
        }}{{ t("ai.chatNotEmbedding") }}
      </p>
      <el-button :disabled="!enabled" @click="editModel(m, 'EMBEDDING')">{{
        t("common.edit")
      }}</el-button>
      <el-button
        :disabled="
          !enabled || !m.enabled || (!!testTask && !aiFinished(testTask))
        "
        @click="test(m)"
        >{{ t("ai.testEmbeddingCost") }}</el-button
      >
      <el-button
        @click="
          run(async () => {
            await ElMessageBox.confirm(
              t('ai.deleteEmbeddingConfirm'),
              t('ai.deleteEmbedding'),
            );
            await api.delete('/ai/models/' + m.id);
          })
        "
        >{{ t("common.delete") }}</el-button
      >
    </article>
    <p>{{ t("ai.defaultChat") }}</p>
    <el-select
      v-model="defaultModel"
      :aria-label="t('ai.defaultChat')"
      clearable
      :placeholder="t('common.selectPlaceholder')"
      style="width: 100%; max-width: 360px"
      @change="
        run(() =>
          api.put('/ai/preferences', {
            defaultChatModelId: defaultModel || null,
          }),
        )
      "
    >
      <el-option
        v-for="m in chatModels.filter((m) => m.enabled)"
        :key="m.id"
        :value="m.id"
        :label="m.name"
      />
    </el-select>
    <p v-if="testTask" role="status">
      {{ t("ai.testPrefix") }}{{ aiStage(testTask) }}
      {{ testTask.result?.text }}
      {{ testTask.error_message }}
      <span
        v-if="
          testTask.kind === 'EMBEDDING_TEST' && testTask.status === 'SUCCEEDED'
        "
        >{{ testTask.result?.vectorCount }} {{ t("ai.vectorPrefix") }}
        {{ testTask.result?.dimensions }} {{ t("ai.dimensionSuffix")
        }}{{ testTask.result?.notice }}</span
      >
    </p>
    <el-button
      v-if="testTask && !aiFinished(testTask)"
      @click="
        run(async () => {
          testTask = await ai.cancel(testTask!.taskId);
          schedulePoll();
        })
      "
      >{{ t("ai.stopTest") }}</el-button
    >
    <h3>{{ t("ai.usage") }}</h3>
    <AiUsage :active="active && !busy" />
    <el-dialog
      class="cn-dialog"
      v-model="connectionEditor"
      append-to-body
      :title="t('ai.connection')"
      width="min(520px,94vw)"
      @closed="form.apiKey = ''"
    >
      <el-form label-position="top" @submit.prevent="saveConnection">
        <el-form-item :label="t('drive.displayName')"
          ><el-input v-model="form.name" maxlength="60"
        /></el-form-item>
        <el-form-item :label="t('ai.baseUrl')"
          ><el-input
            v-model="form.baseUrl"
            maxlength="2048"
            placeholder="https://provider.example/v1"
        /></el-form-item>
        <el-form-item :label="current ? t('ai.apiKey') : 'API Key'"
          ><el-input
            v-model="form.apiKey"
            type="password"
            autocomplete="new-password"
            maxlength="4096"
        /></el-form-item>
        <el-checkbox v-model="form.enabled">{{
          t("ai.enableConnection")
        }}</el-checkbox>
        <p>
          {{ t("ai.saveConnectionHelp") }}
        </p>
        <el-button @click="connectionEditor = false">{{
          t("common.cancel")
        }}</el-button
        ><el-button type="primary" :loading="busy" native-type="submit">{{
          t("ai.saveConnection")
        }}</el-button>
      </el-form>
    </el-dialog>
    <el-dialog
      class="cn-dialog"
      v-model="modelEditor"
      append-to-body
      :title="
        modelForm.purpose === 'EMBEDDING'
          ? t('ai.embeddingModels')
          : t('ai.chatModels')
      "
      width="min(520px,94vw)"
    >
      <el-form label-position="top" @submit.prevent="saveModel">
        <el-form-item :label="t('ai.parentConnection')"
          ><el-select
            v-model="modelForm.connectionId"
            :disabled="!!currentModel"
            ><el-option
              v-for="c in connections"
              :key="c.id"
              :value="c.id"
              :label="c.name" /></el-select
        ></el-form-item>
        <el-form-item :label="t('drive.displayName')"
          ><el-input v-model="modelForm.name" maxlength="60"
        /></el-form-item>
        <el-form-item :label="t('ai.providerModelId')"
          ><el-input v-model="modelForm.modelId" maxlength="200"
        /></el-form-item>
        <el-form-item
          :label="
            modelForm.purpose === 'EMBEDDING'
              ? t('ai.inputBudget')
              : t('ai.contextWindow')
          "
          ><el-input-number
            v-model="modelForm.contextLimit"
            :min="modelForm.purpose === 'EMBEDDING' ? 512 : 1024"
            :max="modelForm.purpose === 'EMBEDDING' ? 32768 : 2000000"
        /></el-form-item>
        <el-form-item
          v-if="modelForm.purpose === 'CHAT'"
          :label="t('ai.maxOutput')"
          ><el-input-number v-model="modelForm.maxOutput" :min="64" :max="2048"
        /></el-form-item>
        <el-form-item
          v-if="modelForm.purpose === 'CHAT'"
          :label="t('ai.outputParameter')"
          ><el-select v-model="modelForm.outputParamMode"
            ><el-option value="max_tokens" label="max_tokens" /><el-option
              value="max_completion_tokens"
              label="max_completion_tokens" /></el-select
        ></el-form-item>
        <template v-if="modelForm.purpose === 'EMBEDDING'">
          <el-form-item :label="t('ai.expectedDimensions')"
            ><el-input-number
              v-model="modelForm.embeddingDimensions"
              :min="1"
              :max="4096"
          /></el-form-item>
          <el-form-item :label="t('ai.maxBatch')"
            ><el-input-number
              v-model="modelForm.maxBatchSize"
              :min="1"
              :max="32"
          /></el-form-item>
          <el-checkbox v-model="modelForm.requestDimensions">{{
            t("ai.requestDimensions")
          }}</el-checkbox>
          <p>
            {{ t("ai.dimensionHelp") }}
          </p>
        </template>
        <el-checkbox v-model="modelForm.enabled">{{
          t("ai.enableModel")
        }}</el-checkbox>
        <p v-if="modelForm.purpose === 'CHAT'">
          {{ t("ai.parameterHelp") }}
        </p>
        <el-button @click="modelEditor = false">{{
          t("common.cancel")
        }}</el-button
        ><el-button type="primary" :loading="busy" native-type="submit">{{
          t("ai.saveModel")
        }}</el-button>
      </el-form>
    </el-dialog>
  </section>
</template>
<style scoped>
.site-ai-control {
  display: flex;
  flex-wrap: wrap;
  align-items: center;
  justify-content: space-between;
  gap: 16px;
  padding: 16px;
  margin-bottom: 16px;
  border: 1px solid var(--cn-border);
  border-radius: 8px;
}
.site-ai-control > div {
  flex: 1;
  min-width: min(240px, 100%);
}
.connection-card {
  padding: 14px;
  margin: 12px 0;
  border: 1px solid var(--cn-border);
  border-radius: 10px;
  overflow-wrap: anywhere;
}
.connection-card .el-button {
  margin: 4px;
}
.destination {
  overflow-wrap: anywhere;
}
.ai-settings {
  line-height: 1.7;
}
.ai-settings p {
  margin: 8px 0;
}
</style>
