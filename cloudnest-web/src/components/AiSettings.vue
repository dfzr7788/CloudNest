<script setup lang="ts">
import { reactive, ref, watch, onBeforeUnmount } from "vue";
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
  connectionId: "",
  name: "",
  modelId: "",
  contextLimit: 32768,
  maxOutput: 1024,
  outputParamMode: "max_tokens",
  enabled: true,
});
const testTask = ref<AiTask | null>(null);
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
function editModel(m: AiModel | null) {
  currentModel.value = m;
  Object.assign(modelForm, {
    connectionId: m?.connection_id || connections.value[0]?.id || "",
    name: m?.name || "",
    modelId: m?.model_id || "",
    contextLimit: m?.context_limit || 32768,
    maxOutput: m?.max_output || 1024,
    outputParamMode: m?.output_param_mode || "max_tokens",
    enabled: m?.enabled ?? true,
  });
  modelEditor.value = true;
}
async function saveModel() {
  await run(async () => {
    if (currentModel.value)
      await api.patch(`/ai/models/${currentModel.value.id}`, {
        ...modelForm,
        configVersion: currentModel.value.config_version,
      });
    else await api.post("/ai/models", modelForm);
    modelEditor.value = false;
  });
}
async function consent(c: AiConnection) {
  await ElMessageBox.confirm(
    `调用时将把你主动选择的文件文本、文件内位置或固定测试提示发送到 ${c.base_url}，费用由该 API 账号承担。CloudNest 服务器可以解密你的密钥，运维者具有技术访问能力；这不是端到端加密。上传文件不会自动调用模型。目的地改变后需要重新同意。`,
    "确认数据发送与费用",
    { confirmButtonText: "同意此目的地", cancelButtonText: "取消" },
  );
  await api.post("/ai/consents", {
    connectionId: c.id,
    destinationFingerprint: c.destinationFingerprint,
  });
}
async function remove(c: AiConnection, credentialOnly: boolean) {
  await run(async () => {
    await ElMessageBox.confirm(
      "将停止关联任务并清除可用凭据。旧备份可能仍有密文；如需彻底撤销访问，请在供应商处撤销 Key。",
      credentialOnly ? "撤销密钥" : "删除连接",
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
      "只发送固定的极短提示，不发送文件；可能产生少量费用。超时或停止仍可能计费。",
      "测试聊天模型",
    );
    if (!pendingTest || pendingTest.model !== m.id)
      pendingTest = { model: m.id, key: crypto.randomUUID() };
    testTask.value = await ai.test(m.id, pendingTest.key);
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
        testTask.value = tasks.find((t) => t.kind === "TEST") || null;
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
    <el-alert
      v-if="!enabled"
      title="站点 AI 尚未开启。管理员可设置 AI_ENABLED=true 后重启服务；已有凭据仍可撤销。"
      type="info"
      :closable="false"
    />
    <p>
      使用自己的 Chat Completions 兼容
      API。文件仅在你点击生成后发送，回答仅供辅助阅读。支持 HTTPS 公网 443
      端口；服务器的 localhost 指服务器自身，无法直接访问你电脑上的本地模型。
    </p>
    <el-button
      type="primary"
      :disabled="!enabled || connections.length >= 5"
      @click="edit(null)"
      >添加 API 连接</el-button
    >
    <article v-for="c in connections" :key="c.id" class="connection-card">
      <strong>{{ c.name }}</strong> · {{ c.enabled ? "启用" : "停用" }} · 密钥{{
        c.keyConfigured ? "已设置" : "未设置"
      }}
      <p class="destination">{{ c.base_url }}</p>
      <p>
        测试状态：{{ c.last_test_status }} · 数据发送同意：{{
          c.consented ? "已确认" : "待确认"
        }}
      </p>
      <el-button :disabled="!enabled" @click="edit(c)">编辑</el-button>
      <el-button :disabled="!enabled" @click="run(() => consent(c))"
        >查看并确认数据发送</el-button
      >
      <el-button @click="remove(c, true)">撤销密钥</el-button>
      <el-button type="danger" plain @click="remove(c, false)">删除</el-button>
    </article>
    <h3>聊天模型</h3>
    <el-button
      :disabled="!enabled || !connections.length"
      @click="editModel(null)"
      >添加模型</el-button
    >
    <article v-for="m in models" :key="m.id" class="connection-card">
      <strong>{{ m.name }}</strong> · {{ m.model_id }} ·
      {{ m.enabled ? "启用" : "停用" }}
      <p>
        连接：{{ connections.find((c) => c.id === m.connection_id)?.name }} ·
        上下文 {{ m.context_limit }} · 输出上限 {{ m.max_output }}
      </p>
      <el-button :disabled="!enabled" @click="editModel(m)">编辑</el-button>
      <el-button
        :disabled="
          !enabled || !m.enabled || (!!testTask && !aiFinished(testTask))
        "
        @click="test(m)"
        >测试聊天模型（可能收费）</el-button
      >
      <el-button
        @click="
          run(async () => {
            await ElMessageBox.confirm(
              '删除此模型并停止关联任务？',
              '删除模型',
            );
            await api.delete('/ai/models/' + m.id);
          })
        "
        >删除</el-button
      >
    </article>
    <p>默认聊天模型</p>
    <el-select
      v-model="defaultModel"
      clearable
      placeholder="请选择"
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
        v-for="m in models.filter((m) => m.enabled)"
        :key="m.id"
        :value="m.id"
        :label="m.name"
      />
    </el-select>
    <p v-if="testTask" role="status">
      测试：{{ aiStage(testTask) }} {{ testTask.result?.text }}
      {{ testTask.error_message }}
    </p>
    <el-button
      v-if="testTask && !aiFinished(testTask)"
      @click="
        run(async () => {
          testTask = await ai.cancel(testTask!.taskId);
          schedulePoll();
        })
      "
      >停止测试</el-button
    >
    <h3>使用记录</h3>
    <AiUsage :active="active && !busy" />
    <el-dialog
      v-model="connectionEditor"
      append-to-body
      title="API 连接"
      width="min(520px,94vw)"
      @closed="form.apiKey = ''"
    >
      <el-form label-position="top" @submit.prevent="saveConnection">
        <el-form-item label="显示名称"
          ><el-input v-model="form.name" maxlength="60"
        /></el-form-item>
        <el-form-item
          label="API 根地址（含版本路径，如 https://provider.example/v1）"
          ><el-input
            v-model="form.baseUrl"
            maxlength="2048"
            placeholder="https://provider.example/v1"
        /></el-form-item>
        <el-form-item
          :label="
            current ? 'API Key（留空保留；更换地址必须重新输入）' : 'API Key'
          "
          ><el-input
            v-model="form.apiKey"
            type="password"
            autocomplete="new-password"
            maxlength="4096"
        /></el-form-item>
        <el-checkbox v-model="form.enabled">启用连接</el-checkbox>
        <p>
          保存不会请求供应商；密钥以带版本的加密格式保存在服务器，浏览器不持久保存。撤销供应商密钥可阻止旧备份继续使用。
        </p>
        <el-button @click="connectionEditor = false">取消</el-button
        ><el-button type="primary" :loading="busy" native-type="submit"
          >保存连接</el-button
        >
      </el-form>
    </el-dialog>
    <el-dialog
      v-model="modelEditor"
      append-to-body
      title="聊天模型"
      width="min(520px,94vw)"
    >
      <el-form label-position="top" @submit.prevent="saveModel">
        <el-form-item label="所属连接"
          ><el-select
            v-model="modelForm.connectionId"
            :disabled="!!currentModel"
            ><el-option
              v-for="c in connections"
              :key="c.id"
              :value="c.id"
              :label="c.name" /></el-select
        ></el-form-item>
        <el-form-item label="显示名称"
          ><el-input v-model="modelForm.name" maxlength="60"
        /></el-form-item>
        <el-form-item label="供应商模型 ID（手动输入）"
          ><el-input v-model="modelForm.modelId" maxlength="200"
        /></el-form-item>
        <el-form-item label="上下文窗口（token，请按供应商规格填写）"
          ><el-input-number
            v-model="modelForm.contextLimit"
            :min="1024"
            :max="2000000"
        /></el-form-item>
        <el-form-item label="最大输出 token"
          ><el-input-number v-model="modelForm.maxOutput" :min="64" :max="2048"
        /></el-form-item>
        <el-form-item label="输出上限兼容参数"
          ><el-select v-model="modelForm.outputParamMode"
            ><el-option value="max_tokens" label="max_tokens" /><el-option
              value="max_completion_tokens"
              label="max_completion_tokens" /></el-select
        ></el-form-item>
        <el-checkbox v-model="modelForm.enabled">启用模型</el-checkbox>
        <p>
          不发送
          temperature，不自动切换参数或模型。上下文使用保守估算，仍可能被供应商拒绝。
        </p>
        <el-button @click="modelEditor = false">取消</el-button
        ><el-button type="primary" :loading="busy" native-type="submit"
          >保存模型</el-button
        >
      </el-form>
    </el-dialog>
  </section>
</template>
<style scoped>
.connection-card {
  padding: 14px;
  margin: 12px 0;
  border: 1px solid #dce3ef;
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
