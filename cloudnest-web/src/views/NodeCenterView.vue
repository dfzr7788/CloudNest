<script setup lang="ts">
import { ref, onMounted, onUnmounted } from "vue";
import { api, bytes, date, errorText } from "../api";
import { ElMessage } from "element-plus";
import NodePolicyForm from "../components/nodes/NodePolicyForm.vue";
import NodeSettings from "../components/nodes/NodeSettings.vue";
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
const modes: Record<string, string> = {
  MANUAL: "手动选择（全局默认节点）",
  CAPACITY: "容量优先",
  STABILITY: "稳定优先",
  HIGH_AVAILABILITY: "高可用（异域双副本）",
};
const statuses: Record<string, string> = {
  QUEUED: "排队中",
  RUNNING: "执行中",
  COMPLETE: "已校验完成",
  FAILED: "失败",
  INTERRUPTED: "已中断",
  CANCELLED: "已取消",
};
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
    ElMessage.success("操作已完成");
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
        <h1>节点中心</h1>
        <p>统一管理存储资源、文件放置与数据保护。</p>
      </div>
      <router-link to="/">返回我的云盘</router-link>
    </header>
    <el-alert v-if="failure" type="error" :title="failure" :closable="false" />
    <el-alert
      v-if="user.user && !user.user.administrator"
      type="error"
      title="此页面需要管理员权限"
      :closable="false"
    />
    <template v-if="user.user?.administrator">
      <section class="metrics" aria-label="存储概况">
        <article>
          <span>存储节点</span><strong>{{ nodes.length }}</strong>
        </article>
        <article>
          <span>健康节点</span
          ><strong>{{
            nodes.filter((n) => n.enabled && n.state === "ONLINE" && !n.stale)
              .length
          }}</strong>
        </article>
        <article>
          <span>节点告警</span><strong>{{ data.alerts.length }}</strong>
        </article>
        <article>
          <span>用户逻辑占用</span
          ><strong>{{ bytes(data.logicalBytes || 0) }}</strong>
        </article>
        <article>
          <span>节点物理占用</span
          ><strong>{{
            bytes(nodes.reduce((sum, n) => sum + n.usedBytes, 0))
          }}</strong>
        </article>
      </section>
      <el-tabs v-model="tab">
        <el-tab-pane label="节点总览" name="overview"
          ><NodeSettings :active="tab === 'overview'" /><NodePolicyForm
        /></el-tab-pane>
        <el-tab-pane label="调度策略" name="placement">
          <el-form label-position="top" class="policy-form">
            <el-form-item label="新上传的调度策略"
              ><el-select v-model="policy.mode"
                ><el-option
                  v-for="(label, key) in modes"
                  :key="key"
                  :label="label"
                  :value="key" /></el-select
            ></el-form-item>
            <el-form-item label="最低可靠性（管理员评级 0–100）"
              ><el-input-number
                v-model="policy.minReliability"
                :min="0"
                :max="100"
            /></el-form-item>
            <el-button
              type="primary"
              @click="act(() => api.put('/admin/horizon/placement', policy))"
              >保存调度策略</el-button
            >
          </el-form>
          <p>
            容量评分使用管理员预算余量；未设预算的远端容量按未知处理。高可用上传先预留两个故障域的容量，副本任务校验完成后才达到双副本保护。
          </p>
          <div class="attributes">
            <article v-for="n in nodes" :key="n.backend">
              <h3>{{ n.name }}</h3>
              <el-form label-position="top"
                ><el-form-item label="故障域（同一机器或磁盘风险用相同值）"
                  ><el-input v-model="n.failure_domain" maxlength="80"
                /></el-form-item>
                <el-form-item label="可靠性"
                  ><el-input-number v-model="n.reliability" :min="0" :max="100"
                /></el-form-item>
                <el-form-item label="优先级"
                  ><el-input-number v-model="n.priority" :min="0" :max="100"
                /></el-form-item>
                <el-form-item label="成本（越低越优先）"
                  ><el-input-number v-model="n.cost" :min="0" :max="100"
                /></el-form-item>
                <el-button @click="act(() => saveNode(n))"
                  >保存节点属性</el-button
                ></el-form
              >
            </article>
          </div>
          <h2>最近调度记录</h2>
          <el-table :data="data.decisions"
            ><el-table-column
              prop="reason"
              label="选择原因"
              min-width="350"
            /><el-table-column label="时间" width="180"
              ><template #default="{ row }">{{
                date(row.created_at)
              }}</template></el-table-column
            ></el-table
          >
        </el-tab-pane>
        <el-tab-pane label="文件迁移与任务" name="jobs"
          ><p>
            在文件操作菜单或批量选择栏打开「存储与副本」，可迁移文件、目录或所选文件。来源数据保留，失败后可重试。
          </p>
          <el-table :data="data.jobs"
            ><el-table-column
              prop="file_name"
              label="文件"
              min-width="220"
            /><el-table-column label="任务" width="100"
              ><template #default="{ row }">{{
                row.kind === "MIGRATE" ? "迁移" : "副本"
              }}</template></el-table-column
            ><el-table-column
              prop="target_backend"
              label="目标节点"
              min-width="160"
            /><el-table-column label="状态" width="130"
              ><template #default="{ row }">{{
                statuses[row.status] || row.status
              }}</template></el-table-column
            ><el-table-column
              prop="error"
              label="详情"
              min-width="220"
            /><el-table-column label="操作" width="130"
              ><template #default="{ row }"
                ><el-button
                  v-if="['QUEUED', 'RUNNING'].includes(row.status)"
                  text
                  @click="act(() => api.delete('/storage/jobs/' + row.id))"
                  >取消</el-button
                ><el-button
                  v-if="
                    ['FAILED', 'INTERRUPTED', 'CANCELLED'].includes(row.status)
                  "
                  text
                  @click="
                    act(() => api.post('/storage/jobs/' + row.id + '/retry'))
                  "
                  >重试</el-button
                ></template
              ></el-table-column
            ></el-table
          >
        </el-tab-pane>
        <el-tab-pane label="副本管理" name="replicas"
          ><p>
            此处列出请求双副本保护的对象。只有两个不同故障域、在线且已验证的副本才满足高可用要求。双副本不能替代独立备份。
          </p>
          <el-table :data="data.protection"
            ><el-table-column
              prop="id"
              label="对象"
              min-width="230"
            /><el-table-column
              prop="replica_backend"
              label="副本节点"
              min-width="150"
            /><el-table-column
              prop="failure_domain"
              label="故障域"
            /><el-table-column prop="state" label="完整性" /><el-table-column
              prop="health"
              label="节点健康"
            /><el-table-column label="校验时间" min-width="170"
              ><template #default="{ row }">{{
                row.verified_at ? date(row.verified_at) : "尚未校验"
              }}</template></el-table-column
            ></el-table
          ></el-tab-pane
        >
        <el-tab-pane label="监控与告警" name="alerts"
          ><el-empty
            v-if="!data.alerts.length"
            description="当前没有节点健康告警"
          /><el-alert
            v-for="n in data.alerts"
            :key="n.backend + n.state"
            type="warning"
            :closable="false"
            :title="
              n.state === 'CAPACITY_WARNING'
                ? n.name + '：容量预算接近耗尽，请扩容或调整分配'
                : n.name + '：' + n.state + '，请检查连接并执行完整检测'
            "
          />
          <h2>容量与预留</h2>
          <el-table :data="nodes"
            ><el-table-column prop="name" label="节点" /><el-table-column
              label="物理占用"
              ><template #default="{ row }">{{
                bytes(row.usedBytes)
              }}</template></el-table-column
            ><el-table-column label="任务预留"
              ><template #default="{ row }">{{
                bytes(row.reservedBytes)
              }}</template></el-table-column
            ><el-table-column label="可用预算"
              ><template #default="{ row }">{{
                row.budget_bytes
                  ? bytes(
                      Math.max(
                        0,
                        row.budget_bytes - row.usedBytes - row.reservedBytes,
                      ),
                    )
                  : "未设置上限"
              }}</template></el-table-column
            ></el-table
          >
          <h2>健康事件</h2>
          <el-table :data="data.events"
            ><el-table-column prop="backend" label="节点" /><el-table-column
              prop="state"
              label="状态"
            /><el-table-column label="时间"
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
  max-width: 1440px;
  margin: auto;
  padding: 32px;
}
header {
  display: flex;
  justify-content: space-between;
  gap: 20px;
  align-items: center;
  margin-bottom: 28px;
}
h1 {
  margin: 6px 0;
}
p {
  color: var(--cn-muted);
  line-height: 1.7;
}
.eyebrow {
  font-size: 12px;
  letter-spacing: 2px;
}
.metrics {
  display: grid;
  grid-template-columns: repeat(auto-fit, minmax(150px, 1fr));
  gap: 16px;
  margin-bottom: 28px;
}
.metrics article,
.attributes article {
  border: 1px solid var(--cn-border-strong);
  border-radius: 12px;
  padding: 20px;
}
.metrics strong {
  display: block;
  font-size: 28px;
  margin-top: 12px;
}
.metrics span {
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
  font-size: 19px;
  margin-top: 30px;
}
@media (max-width: 640px) {
  .node-center {
    padding: 16px;
  }
  header {
    align-items: flex-start;
  }
}
</style>
