<script setup lang="ts">
import { requestKey } from "../../utils/requestKey";
import { ref, watch, onBeforeUnmount } from "vue";
import { t } from "../../i18n";
import { api, date, errorText } from "../../api";
interface Approval {
  id: string;
  proposal_hash: string;
  status: string;
  expires_at: number;
  plan: Record<string, unknown>;
  jobIds: string[];
}
const props = defineProps<{ active: boolean }>();
const enabled = ref(false),
  loaded = ref(false),
  busy = ref(false),
  failure = ref("");
const fileIds = ref(""),
  target = ref(""),
  kind = ref("MIGRATE");
const preview = ref<Record<string, unknown> | null>(null),
  approvals = ref<Approval[]>([]);
const targets = ref<{ backend: string; name: string }[]>([]);
let disposed = false,
  pending: { signature: string; key: string } | undefined;
async function load() {
  busy.value = true;
  failure.value = "";
  try {
    const caps = (
      await api.get<{ enabled: boolean }>("/admin/agent/approvals/available")
    ).data;
    if (disposed) return;
    enabled.value = caps.enabled;
    if (caps.enabled) {
      const [rows, nodes] = await Promise.all([
        api.get<Approval[]>("/admin/agent/approvals"),
        api.get<{ backend: string; name: string }[]>("/storage/targets"),
      ]);
      if (disposed) return;
      approvals.value = rows.data;
      targets.value = nodes.data;
    }
    loaded.value = true;
  } catch (e) {
    if (!disposed) failure.value = errorText(e);
  } finally {
    if (!disposed) busy.value = false;
  }
}
function input() {
  const ids = [
    ...new Set(fileIds.value.split(/[\s,，]+/).filter(Boolean)),
  ].sort();
  if (
    !ids.length ||
    ids.length > 20 ||
    !ids.every((id) => /^[0-9a-f-]{36}$/i.test(id)) ||
    !target.value
  )
    throw new Error(t("approvals.invalid"));
  return { kind: kind.value, target: target.value, fileIds: ids };
}
async function simulate() {
  busy.value = true;
  failure.value = "";
  preview.value = null;
  try {
    const result = (
      await api.post<Record<string, unknown>>(
        "/admin/agent/approvals/simulate",
        input(),
      )
    ).data;
    if (!disposed) preview.value = result;
  } catch (e) {
    if (!disposed) failure.value = errorText(e);
  } finally {
    if (!disposed) busy.value = false;
  }
}
async function propose() {
  busy.value = true;
  failure.value = "";
  try {
    const value = input(),
      signature = JSON.stringify(value);
    if (pending?.signature !== signature)
      pending = { signature, key: requestKey() };
    await api.post("/admin/agent/approvals", value, {
      headers: { "Idempotency-Key": pending.key },
    });
    pending = undefined;
    await load();
  } catch (e) {
    if (!disposed) failure.value = errorText(e);
  } finally {
    if (!disposed) busy.value = false;
  }
}
async function decide(row: Approval, approve: boolean) {
  busy.value = true;
  failure.value = "";
  try {
    await api.post(
      `/admin/agent/approvals/${row.id}/${approve ? "approve" : "reject"}`,
      { proposalHash: row.proposal_hash },
    );
    await load();
  } catch (e) {
    if (!disposed) failure.value = errorText(e);
  } finally {
    if (!disposed) busy.value = false;
  }
}
watch([fileIds, target, kind], () => {
  preview.value = null;
});
watch(
  () => props.active,
  (active) => {
    if (active) void load();
  },
  { immediate: true },
);
onBeforeUnmount(() => {
  disposed = true;
});
</script>
<template>
  <section class="agent-approvals">
    <el-alert :title="t('approvals.notice')" type="warning" :closable="false" />
    <el-button :loading="busy" @click="load">{{
      t("approvals.refresh")
    }}</el-button>
    <p v-if="failure" role="alert">{{ failure }}</p>
    <p v-if="loaded && !enabled">{{ t("approvals.disabled") }}</p>
    <template v-if="enabled">
      <el-form label-position="top" @submit.prevent="simulate">
        <el-form-item :label="t('approvals.files')"
          ><el-input
            v-model="fileIds"
            type="textarea"
            :rows="3"
            :maxlength="800"
        /></el-form-item>
        <el-form-item :label="t('approvals.target')"
          ><el-select v-model="target"
            ><el-option
              v-for="node in targets"
              :key="node.backend"
              :value="node.backend"
              :label="`${node.name} (${node.backend})`" /></el-select
        ></el-form-item>
        <el-form-item :label="t('approvals.kind')"
          ><el-select v-model="kind"
            ><el-option
              value="MIGRATE"
              :label="t('approvals.migrate')" /><el-option
              value="REPLICATE"
              :label="t('approvals.replicate')" /></el-select
        ></el-form-item>
        <el-button :loading="busy" @click="simulate">{{
          t("approvals.simulate")
        }}</el-button
        ><el-button :disabled="!preview" :loading="busy" @click="propose">{{
          t("approvals.propose")
        }}</el-button>
      </el-form>
      <pre v-if="preview">{{ JSON.stringify(preview, null, 2) }}</pre>
      <article v-for="row in approvals" :key="row.id">
        <h3>{{ row.id }} · {{ row.status }}</h3>
        <p>{{ t("approvals.expires") }} {{ date(row.expires_at) }}</p>
        <p>{{ row.proposal_hash }}</p>
        <pre>{{ JSON.stringify(row.plan, null, 2) }}</pre>
        <template v-if="row.status === 'PENDING'"
          ><el-button
            type="danger"
            :disabled="busy"
            @click="decide(row, true)"
            >{{ t("approvals.approve") }}</el-button
          ><el-button :disabled="busy" @click="decide(row, false)">{{
            t("approvals.reject")
          }}</el-button></template
        >
        <p v-if="row.jobIds.length">
          {{ t("approvals.submitted") }} {{ row.jobIds.join(", ") }}
        </p>
      </article>
    </template>
  </section>
</template>
<style scoped>
.agent-approvals {
  display: grid;
  gap: 16px;
  min-width: 0;
}
pre {
  white-space: pre-wrap;
  overflow-wrap: anywhere;
  max-height: 420px;
  overflow: auto;
  background: var(--el-fill-color-light);
  padding: 12px;
}
article {
  border: 1px solid var(--el-border-color);
  border-radius: 8px;
  padding: 16px;
  overflow-wrap: anywhere;
}
.el-select {
  width: 100%;
}
</style>
