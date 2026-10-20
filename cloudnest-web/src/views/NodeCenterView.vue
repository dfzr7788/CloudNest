<script setup lang="ts">
import { t } from "../i18n";
import { computed, ref, onMounted, onUnmounted } from "vue";
import LanguageSwitcher from "../components/common/LanguageSwitcher.vue";
import ThemeSwitcher from "../components/common/ThemeSwitcher.vue";
import { api, bytes, date, errorText } from "../api";
import { ElMessage } from "element-plus";
import NodePolicyForm from "../components/nodes/NodePolicyForm.vue";
import NodeSettings from "../components/nodes/NodeSettings.vue";
import AgentDiagnostics from "../components/nodes/AgentDiagnostics.vue";
import AgentRuntime from "../components/nodes/AgentRuntime.vue";
import AgentApprovals from "../components/nodes/AgentApprovals.vue";
import StorageTopology from "../components/nodes/StorageTopology.vue";
import { useUser } from "../stores/user";
const user = useUser();
const tab = ref("overview"),
  loading = ref(false),
  failure = ref("");
const data = ref<any>({
  placement: {},
  decisions: [],
  jobs: [],
  alerts: [],
  protection: [],
  events: [],
});
const nodes = ref<any[]>([]);
const policy = ref({ mode: "MANUAL", minReliability: 0 });
const modes = computed<Record<string, string>>(() => ({
  MANUAL: t("nodes.manual"),
  CAPACITY: t("nodes.capacity"),
  STABILITY: t("nodes.stability"),
  HIGH_AVAILABILITY: t("nodes.highAvailability"),
}));
const statuses = computed<Record<string, string>>(() => ({
  QUEUED: t("nodes.queued"),
  RUNNING: t("nodes.running"),
  COMPLETE: t("nodes.complete"),
  FAILED: t("nodes.failed"),
  INTERRUPTED: t("nodes.interrupted"),
  CANCELLED: t("nodes.cancelled"),
}));
let timer: ReturnType<typeof setTimeout> | undefined;
let stopped = false;
async function load(initial = false) {
  try {
    const [overview, pool] = await Promise.all([
      api.get("/admin/horizon"),
      api.get("/admin/nodes"),
    ]);
    data.value = overview.data;
    if (initial || tab.value !== "placement") nodes.value = pool.data.nodes;
    failure.value = "";
    if (initial)
      policy.value = {
        mode: data.value.placement.mode,
        minReliability: data.value.placement.min_reliability,
      };
  } catch (e) {
    failure.value = errorText(e);
  }
}
async function poll() {
  await load();
  if (!stopped) timer = setTimeout(poll, 10000);
}
onMounted(async () => {
  try {
    await user.load();
    if (user.user?.administrator) {
      await load(true);
      if (!stopped) timer = setTimeout(poll, 10000);
    }
  } catch (e) {
    failure.value = errorText(e);
  }
});
onUnmounted(() => {
  stopped = true;
  clearTimeout(timer);
});
async function act(fn: () => Promise<unknown>) {
  loading.value = true;
  try {
    await fn();
    await load();
    ElMessage.success(t("nodes.actionComplete"));
  } catch (e) {
    ElMessage.error(errorText(e));
  } finally {
    loading.value = false;
  }
}
async function saveNode(n: any) {
  await api.put(`/admin/horizon/nodes/${encodeURIComponent(n.backend)}`, {
    failureDomain: n.failure_domain,
    reliability: n.reliability,
    priority: n.priority,
    cost: n.cost,
  });
}
</script>
<template>
  <main class="node-center" v-loading="loading">
    <header>
      <div>
        <p class="eyebrow">CLOUDNEST HORIZON · 2.0</p>
        <h1>{{ t("nav.nodes") }}</h1>
        <p>{{ t("nodes.intro") }}</p>
      </div>
      <div class="node-header-actions">
        <ThemeSwitcher />
        <LanguageSwitcher />
        <router-link to="/" class="node-back-link">{{
          t("nodes.back")
        }}</router-link>
      </div>
    </header>
    <el-alert v-if="failure" type="error" :title="failure" :closable="false" />
    <el-alert
      v-if="user.user && !user.user.administrator"
      type="error"
      :title="t('nodes.adminRequired')"
      :closable="false"
    />
    <template v-if="user.user?.administrator">
      <section class="metrics" :aria-label="t('nodes.summary')">
        <article>
          <span>{{ t("nodes.count") }}</span
          ><strong>{{ nodes.length }}</strong>
        </article>
        <article>
          <span>{{ t("nodes.healthy") }}</span
          ><strong>{{
            nodes.filter((n) => n.enabled && n.state === "ONLINE" && !n.stale)
              .length
          }}</strong>
        </article>
        <article>
          <span>{{ t("nodes.alertCount") }}</span
          ><strong>{{ data.alerts.length }}</strong>
        </article>
        <article>
          <span>{{ t("nodes.logical") }}</span
          ><strong>{{ bytes(data.logicalBytes || 0) }}</strong>
        </article>
        <article>
          <span>{{ t("nodes.physical") }}</span
          ><strong>{{
            bytes(nodes.reduce((sum, n) => sum + n.usedBytes, 0))
          }}</strong>
        </article>
      </section>
      <el-tabs v-model="tab">
        <el-tab-pane :label="t('topology.tab')" name="topology"
          ><StorageTopology :active="tab === 'topology'"
        /></el-tab-pane>
        <el-tab-pane :label="t('approvals.tab')" name="approvals"
          ><AgentApprovals :active="tab === 'approvals'"
        /></el-tab-pane>
        <el-tab-pane :label="t('agentRuntime.tab')" name="agent-runtime">
          <AgentRuntime :active="tab === 'agent-runtime'" />
        </el-tab-pane>
        <el-tab-pane :label="t('agentTools.tab')" name="diagnostics">
          <AgentDiagnostics :active="tab === 'diagnostics'" />
        </el-tab-pane>
        <el-tab-pane :label="t('nodes.overview')" name="overview"
          ><NodeSettings :active="tab === 'overview'" /><NodePolicyForm
        /></el-tab-pane>
        <el-tab-pane :label="t('nodes.placement')" name="placement">
          <el-form label-position="top" class="policy-form">
            <el-form-item :label="t('nodes.uploadPolicy')"
              ><el-select v-model="policy.mode"
                ><el-option
                  v-for="(label, key) in modes"
                  :key="key"
                  :label="label"
                  :value="key" /></el-select
            ></el-form-item>
            <el-form-item :label="t('nodes.minReliability')"
              ><el-input-number
                v-model="policy.minReliability"
                :min="0"
                :max="100"
            /></el-form-item>
            <el-button
              type="primary"
              @click="act(() => api.put('/admin/horizon/placement', policy))"
              >{{ t("nodes.savePolicy") }}</el-button
            >
          </el-form>
          <p>
            {{ t("nodes.capacityHelp") }}
          </p>
          <div class="attributes">
            <article v-for="n in nodes" :key="n.backend">
              <h3>{{ n.name }}</h3>
              <el-form label-position="top"
                ><el-form-item :label="t('nodes.domainHelp')"
                  ><el-input v-model="n.failure_domain" maxlength="80"
                /></el-form-item>
                <el-form-item :label="t('nodes.reliability')"
                  ><el-input-number v-model="n.reliability" :min="0" :max="100"
                /></el-form-item>
                <el-form-item :label="t('nodes.priority')"
                  ><el-input-number v-model="n.priority" :min="0" :max="100"
                /></el-form-item>
                <el-form-item :label="t('nodes.cost')"
                  ><el-input-number v-model="n.cost" :min="0" :max="100"
                /></el-form-item>
                <el-button @click="act(() => saveNode(n))">{{
                  t("nodes.saveAttributes")
                }}</el-button></el-form
              >
            </article>
          </div>
          <h2>{{ t("nodes.decisions") }}</h2>
          <el-table :data="data.decisions"
            ><el-table-column
              prop="reason"
              :label="t('nodes.reason')"
              min-width="350"
            /><el-table-column :label="t('common.time')" width="180"
              ><template #default="{ row }">{{
                date(row.created_at)
              }}</template></el-table-column
            ></el-table
          >
        </el-tab-pane>
        <el-tab-pane :label="t('nodes.jobs')" name="jobs"
          ><p>
            {{ t("nodes.jobsHelp") }}
          </p>
          <el-table :data="data.jobs"
            ><el-table-column
              prop="file_name"
              :label="t('common.file')"
              min-width="220"
            /><el-table-column :label="t('nodes.job')" width="100"
              ><template #default="{ row }">{{
                row.kind === "MIGRATE"
                  ? t("nodes.migration")
                  : t("nodes.replica")
              }}</template></el-table-column
            ><el-table-column
              prop="target_backend"
              :label="t('nodes.target')"
              min-width="160"
            /><el-table-column :label="t('common.status')" width="130"
              ><template #default="{ row }">{{
                statuses[row.status] || row.status
              }}</template></el-table-column
            ><el-table-column
              prop="error"
              :label="t('common.details')"
              min-width="220"
            /><el-table-column :label="t('common.actions')" width="130"
              ><template #default="{ row }"
                ><el-button
                  v-if="['QUEUED', 'RUNNING'].includes(row.status)"
                  text
                  @click="act(() => api.delete('/storage/jobs/' + row.id))"
                  >{{ t("common.cancel") }}</el-button
                ><el-button
                  v-if="
                    ['FAILED', 'INTERRUPTED', 'CANCELLED'].includes(row.status)
                  "
                  text
                  @click="
                    act(() => api.post('/storage/jobs/' + row.id + '/retry'))
                  "
                  >{{ t("common.retry") }}</el-button
                ></template
              ></el-table-column
            ></el-table
          >
        </el-tab-pane>
        <el-tab-pane :label="t('nodes.replicas')" name="replicas"
          ><p>
            {{ t("nodes.replicaHelp") }}
          </p>
          <el-table :data="data.protection"
            ><el-table-column
              prop="id"
              :label="t('nodes.object')"
              min-width="230"
            /><el-table-column
              prop="replica_backend"
              :label="t('nodes.replicaNode')"
              min-width="150"
            /><el-table-column
              prop="failure_domain"
              :label="t('nodes.failureDomain')"
            /><el-table-column
              prop="state"
              :label="t('nodes.integrity')"
            /><el-table-column
              prop="health"
              :label="t('nodes.health')"
            /><el-table-column :label="t('nodes.verifiedAt')" min-width="170"
              ><template #default="{ row }">{{
                row.verified_at ? date(row.verified_at) : t("nodes.unverified")
              }}</template></el-table-column
            ></el-table
          ></el-tab-pane
        >
        <el-tab-pane :label="t('nodes.monitoring')" name="alerts"
          ><el-empty
            v-if="!data.alerts.length"
            :description="t('nodes.noAlerts')"
          /><el-alert
            v-for="n in data.alerts"
            :key="n.backend + n.state"
            type="warning"
            :closable="false"
            :title="
              n.state === 'CAPACITY_WARNING'
                ? n.name + t('nodes.capacityWarning')
                : n.name + '：' + n.state + t('nodes.connectionWarning')
            "
          />
          <h2>{{ t("nodes.capacityReservations") }}</h2>
          <el-table :data="nodes"
            ><el-table-column
              prop="name"
              :label="t('nodes.node')"
            /><el-table-column :label="t('nodes.physicalUsage')"
              ><template #default="{ row }">{{
                bytes(row.usedBytes)
              }}</template></el-table-column
            ><el-table-column :label="t('nodes.reserved')"
              ><template #default="{ row }">{{
                bytes(row.reservedBytes)
              }}</template></el-table-column
            ><el-table-column :label="t('nodes.available')"
              ><template #default="{ row }">{{
                row.budget_bytes
                  ? bytes(
                      Math.max(
                        0,
                        row.budget_bytes - row.usedBytes - row.reservedBytes,
                      ),
                    )
                  : t("nodes.unlimited")
              }}</template></el-table-column
            ></el-table
          >
          <h2>{{ t("nodes.events") }}</h2>
          <el-table :data="data.events"
            ><el-table-column
              prop="backend"
              :label="t('nodes.node')"
            /><el-table-column
              prop="state"
              :label="t('common.status')"
            /><el-table-column :label="t('common.time')"
              ><template #default="{ row }">{{
                date(row.created_at)
              }}</template></el-table-column
            ></el-table
          >
        </el-tab-pane>
      </el-tabs>
    </template>
  </main>
</template>
<style scoped>
.node-center {
  min-height: 100dvh;
  max-width: 1440px;
  margin: auto;
  padding: 32px;
}
.node-center :deep(.el-tabs__content) {
  background: var(--cn-canvas);
  border: 1px solid var(--cn-border);
  border-radius: 10px;
  padding: 24px;
  box-shadow: var(--cn-panel-shadow);
}
.node-center :deep(.el-table) {
  border: 1px solid var(--cn-border);
  border-radius: 8px;
  overflow: hidden;
  margin-top: 12px;
}
.node-center :deep(.el-tab-pane > p) {
  margin-bottom: 12px;
}
header {
  display: flex;
  justify-content: space-between;
  gap: 20px;
  align-items: center;
  margin-bottom: 28px;
  padding-bottom: 20px;
  border-bottom: 1px solid var(--cn-border);
}
.node-header-actions {
  display: flex;
  align-items: center;
  gap: 8px;
  flex-wrap: wrap;
}
.node-back-link {
  display: inline-flex;
  align-items: center;
  min-height: 32px;
  padding: 4px 12px;
  border-radius: var(--cn-radius-control);
  background: var(--cn-surface);
  border: 1px solid var(--cn-border-strong);
  box-shadow: var(--cn-btn-secondary-shadow);
  color: var(--cn-heading);
  font-size: 13px;
  font-weight: 500;
  transition: background var(--cn-duration) var(--cn-ease);
}
.node-back-link:hover {
  background: var(--cn-hover);
}
h1 {
  margin: 6px 0;
  font-size: 22px;
  font-weight: 600;
}
p {
  color: var(--cn-muted);
  line-height: 1.65;
}
.eyebrow {
  font-size: 11px;
  letter-spacing: 0.1em;
}
.metrics {
  display: grid;
  grid-template-columns: repeat(auto-fit, minmax(150px, 1fr));
  gap: 14px;
  margin-bottom: 28px;
}
.metrics article,
.attributes article {
  background: var(--cn-surface);
  border: 1px solid var(--cn-border);
  box-shadow: var(--cn-specular);
  border-radius: 10px;
  padding: 18px 20px;
}
.metrics strong {
  display: block;
  font-size: 24px;
  font-weight: 600;
  font-variant-numeric: tabular-nums;
  color: var(--cn-heading);
  margin-top: 8px;
}
.metrics span {
  font-size: 12.5px;
  color: var(--cn-muted);
}
.attributes {
  display: grid;
  grid-template-columns: repeat(auto-fit, minmax(270px, 1fr));
  gap: 16px;
  margin: 24px 0;
}
.policy-form {
  max-width: 480px;
}
h2 {
  font-size: 17px;
  font-weight: 600;
  margin-top: 28px;
  margin-bottom: 12px;
}
@media (max-width: 640px) {
  .node-center {
    padding: 16px;
  }
  .node-center :deep(.el-tabs__content) {
    padding: 16px;
  }
  header {
    align-items: flex-start;
    flex-direction: column;
  }
}
</style>
