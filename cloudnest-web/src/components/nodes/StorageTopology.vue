<script setup lang="ts">
import { ref, computed, watch, onBeforeUnmount } from "vue";
import { t } from "../../i18n";
import { api, bytes, date, errorText } from "../../api";
interface Node {
  backend: string;
  name: string;
  enabled: boolean;
  management: string;
  state: string;
  failureDomain: string;
  checkedAt: number;
  stale: boolean;
}
interface Capacity {
  backend: string;
  usedBytes: number;
  reservedBytes: number;
  budgetBytes: number;
  remainingBudgetBytes: number | null;
}
interface Topology {
  observedAt: number;
  nodes: { items: Node[]; hasMore: boolean };
  capacity: {
    logicalBytes: number;
    nodes: { items: Capacity[]; hasMore: boolean };
  };
  jobs: {
    items: {
      id: string;
      kind: string;
      status: string;
      targetBackend: string;
    }[];
    hasMore: boolean;
  };
  relations: {
    source: string;
    target: string;
    state: string;
    objects: number;
    bytes: number;
  }[];
  hasMoreRelations: boolean;
}
const props = defineProps<{ active: boolean }>();
const data = ref<Topology | null>(null),
  failure = ref("");
const loading = ref(false),
  selected = ref("");
const events = ref<{ state: string; createdAt: number }[]>([]);
let disposed = false,
  generation = 0,
  timer: ReturnType<typeof setTimeout> | undefined;
const domains = computed(() =>
  [
    ...new Set(data.value?.nodes.items.map((n) => n.failureDomain) || []),
  ].sort(),
);
const positions = computed(() => {
  const result = new Map<string, { x: number; y: number; node: Node }>();
  for (const [domainIndex, domain] of domains.value.entries()) {
    const nodes = data.value!.nodes.items.filter(
      (n) => n.failureDomain === domain,
    );
    nodes.forEach((node, index) =>
      result.set(node.backend, {
        x: 40 + domainIndex * 260,
        y: 80 + index * 85,
        node,
      }),
    );
  }
  return result;
});
const lines = computed(() =>
  (data.value?.relations || []).flatMap((edge) => {
    const a = positions.value.get(edge.source),
      b = positions.value.get(edge.target);
    if (!a || !b) return [];
    if (a.x !== b.x)
      return [
        {
          x1: a.x + (b.x > a.x ? 200 : 0),
          y1: a.y + 29,
          x2: b.x + (b.x > a.x ? -3 : 203),
          y2: b.y + 29,
        },
      ];
    return [
      {
        x1: a.x + 100,
        y1: a.y + (b.y > a.y ? 58 : 0),
        x2: b.x + 100,
        y2: b.y + (b.y > a.y ? -3 : 61),
      },
    ];
  }),
);
const width = computed(() => Math.max(600, domains.value.length * 260 + 20));
const height = computed(() =>
  Math.max(240, ...[...positions.value.values()].map((p) => p.y + 100)),
);
const total = computed(() =>
  (data.value?.capacity.nodes.items || []).reduce(
    (sum, n) => ({
      used: sum.used + n.usedBytes,
      reserved: sum.reserved + n.reservedBytes,
    }),
    { used: 0, reserved: 0 },
  ),
);
function schedule() {
  clearTimeout(timer);
  if (!disposed && props.active) timer = setTimeout(() => void load(), 60000);
}
async function load() {
  if (loading.value) return;
  loading.value = true;
  failure.value = "";
  try {
    const value = (await api.get<Topology>("/admin/topology")).data;
    if (!disposed) data.value = value;
  } catch (e) {
    if (!disposed) {
      failure.value = errorText(e);
      data.value = null;
      events.value = [];
      selected.value = "";
      generation++;
    }
  } finally {
    if (!disposed) {
      loading.value = false;
      schedule();
    }
  }
}
async function inspect(backend: string) {
  selected.value = backend;
  events.value = [];
  failure.value = "";
  const ticket = ++generation;
  try {
    const result = (
      await api.get<{ items: { state: string; createdAt: number }[] }>(
        "/admin/topology/events",
        { params: { backend } },
      )
    ).data;
    if (!disposed && ticket === generation) events.value = result.items;
  } catch (e) {
    if (!disposed && ticket === generation) failure.value = errorText(e);
  }
}
watch(
  () => props.active,
  (active) => {
    if (active) void load();
    else clearTimeout(timer);
  },
  { immediate: true },
);
onBeforeUnmount(() => {
  disposed = true;
  generation++;
  clearTimeout(timer);
});
</script>
<template>
  <section class="storage-topology">
    <el-alert :title="t('topology.notice')" type="info" :closable="false" />
    <el-button :loading="loading" @click="load">{{
      t("topology.refresh")
    }}</el-button>
    <p v-if="failure" role="alert">{{ failure }}</p>
    <template v-if="data">
      <p>
        {{ date(data.observedAt) }} · {{ t("topology.logical") }}
        {{ bytes(data.capacity.logicalBytes) }} · {{ t("topology.physical") }}
        {{ bytes(total.used) }} · {{ t("topology.reserved") }}
        {{ bytes(total.reserved) }}
      </p>
      <p
        v-if="
          data.nodes.hasMore ||
          data.capacity.nodes.hasMore ||
          data.hasMoreRelations
        "
      >
        {{ t("topology.partial") }}
      </p>
      <div class="graph">
        <svg
          :width="width"
          :height="height"
          :viewBox="`0 0 ${width} ${height}`"
          role="img"
          :aria-label="t('topology.graph')"
        >
          <defs>
            <marker
              id="replica-arrow"
              markerWidth="8"
              markerHeight="8"
              refX="7"
              refY="4"
              orient="auto"
            >
              <path d="M0,0 L8,4 L0,8" fill="currentColor" />
            </marker>
          </defs>
          <g v-for="(domain, index) in domains" :key="domain">
            <rect
              :x="20 + index * 260"
              y="10"
              width="240"
              :height="height - 20"
              rx="12"
              class="domain"
            />
            <text :x="35 + index * 260" y="40">{{ domain }}</text>
          </g>
          <template v-for="(edge, index) in lines" :key="index">
            <line
              :x1="edge.x1"
              :y1="edge.y1"
              :x2="edge.x2"
              :y2="edge.y2"
              marker-end="url(#replica-arrow)"
              class="relation"
            />
          </template>
          <g v-for="[backend, p] in positions" :key="backend">
            <title>{{ p.node.name }}</title>
            <rect
              :x="p.x"
              :y="p.y"
              width="200"
              height="58"
              rx="8"
              class="node"
            />
            <text :x="p.x + 8" :y="p.y + 23">
              {{ Array.from(p.node.name).slice(0, 14).join("")
              }}{{ p.node.name.length > 14 ? "…" : "" }}
            </text>
            <text :x="p.x + 8" :y="p.y + 44">
              {{ p.node.state }} {{ p.node.stale ? t("topology.stale") : "" }}
            </text>
          </g>
        </svg>
      </div>
      <div class="node-cards">
        <article v-for="node in data.nodes.items" :key="node.backend">
          <el-button @click="inspect(node.backend)"
            >{{ node.name }} · {{ node.state }}</el-button
          >
          <p>
            {{ node.backend }} · {{ node.failureDomain }} ·
            {{ node.management }} {{ node.stale ? t("topology.stale") : "" }}
          </p>
          <p>
            {{ t("topology.budget") }}
            {{
              bytes(
                data.capacity.nodes.items.find(
                  (c) => c.backend === node.backend,
                )?.budgetBytes || 0,
              )
            }}
          </p>
        </article>
      </div>
      <h3>{{ t("topology.relations") }}</h3>
      <el-table :data="data.relations"
        ><el-table-column
          prop="source"
          :label="t('topology.source')" /><el-table-column
          prop="target"
          :label="t('topology.target')" /><el-table-column
          prop="state"
          :label="t('common.status')" /><el-table-column
          prop="objects"
          :label="t('topology.objects')"
      /></el-table>
      <template v-if="selected"
        ><h3>{{ selected }} · {{ t("topology.events") }}</h3>
        <p v-for="event in events" :key="`${event.createdAt}-${event.state}`">
          {{ date(event.createdAt) }} · {{ event.state }}
        </p>
        <p v-if="!events.length">{{ t("topology.noEvents") }}</p></template
      >
      <h3>{{ t("topology.jobs") }}</h3>
      <p v-for="job in data.jobs.items" :key="job.id">
        {{ job.id }} · {{ job.kind }} · {{ job.status }} ·
        {{ job.targetBackend }}
      </p>
    </template>
  </section>
</template>
<style scoped>
.storage-topology {
  display: grid;
  gap: 16px;
  min-width: 0;
}
.graph {
  overflow: auto;
  border: 1px solid var(--el-border-color);
  border-radius: 12px;
}
svg {
  color: var(--el-color-primary);
}
text {
  fill: var(--el-text-color-primary);
  font-size: 12px;
}
.domain {
  fill: var(--el-fill-color-light);
  stroke: var(--el-border-color);
}
.node {
  fill: var(--el-bg-color);
  stroke: var(--el-color-primary);
}
.relation {
  stroke: currentColor;
  stroke-width: 2;
  opacity: 0.5;
}
.node-cards {
  display: flex;
  flex-wrap: wrap;
  gap: 12px;
}
article {
  max-width: 100%;
  min-width: 0;
  box-sizing: border-box;
  padding: 12px;
  border: 1px solid var(--el-border-color);
  border-radius: 8px;
  overflow-wrap: anywhere;
}
.node-cards :deep(.el-button) {
  max-width: 100%;
  height: auto;
  white-space: normal;
}
.node-cards :deep(.el-button span) {
  white-space: normal;
  overflow-wrap: anywhere;
}
</style>
