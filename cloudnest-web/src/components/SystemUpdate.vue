<script setup lang="ts">
import { onUnmounted, ref, watch } from "vue";
import { ElMessage, ElMessageBox } from "element-plus";
import { api, errorText, date } from "../api";
const props = defineProps<{ active: boolean }>();
const status = ref<{
  enabled?: boolean;
  backups?: {
    id: string;
    status: string;
    offsite?: string;
    verified?: boolean;
    createdAt?: number;
  }[];
  pending?: boolean;
  busy?: boolean;
  phase?: string;
  failedPhase?: string;
  message?: string;
  current?: string;
  latest?: string;
  available?: boolean;
  backup?: string;
  updatedAt?: number;
}>({});
const sending = ref(false),
  reconnecting = ref(false);
let timer: ReturnType<typeof setTimeout> | undefined;
const phases: Record<string, string> = {
  IDLE: "等待检查",
  CHECKING: "检查版本",
  SNAPSHOT: "保存回退镜像",
  READY: "检查完成",
  BUILDING: "构建新版",
  BACKUP: "备份数据",
  RESTARTING: "重启服务",
  COMPLETE: "更新完成",
  FAILED: "更新未完成",
};
async function load() {
  try {
    status.value = (await api.get("/admin/updates", { timeout: 8000 })).data;
    reconnecting.value = false;
  } catch {
    reconnecting.value = true;
  } finally {
    if (props.active) timer = setTimeout(load, 4000);
  }
}
watch(
  () => props.active,
  (active) => {
    clearTimeout(timer);
    if (active) void load();
  },
  { immediate: true },
);
onUnmounted(() => clearTimeout(timer));
async function request(action: "check" | "update" | "backup") {
  try {
    if (action === "update")
      await ElMessageBox.confirm(
        "将从 GitHub main 分支获取预构建镜像（未发布时使用源码构建），自动备份数据库与文件后重启服务。重启期间会短暂断开，请暂停上传。",
        "更新 CloudNest",
        { confirmButtonText: "备份并更新", cancelButtonText: "取消" },
      );
    if (action === "backup")
      await ElMessageBox.confirm(
        "备份期间将短暂停止文件服务，完成后自动恢复。",
        "创建备份",
        { confirmButtonText: "开始备份", cancelButtonText: "取消" },
      );
    sending.value = true;
    await api.post("/admin/updates", { action });
    status.value.pending = true;
    ElMessage.success(
      action === "check" ? "已提交版本检查" : "更新任务已提交，请在此查看进度",
    );
  } catch (e) {
    if (e !== "cancel" && e !== "close") ElMessage.error(errorText(e));
  } finally {
    sending.value = false;
  }
}
</script>
<template>
  <section class="update-card">
    <div class="update-heading">
      <div>
        <span class="eyebrow">CLOUDNEST UPDATE</span>
        <h3>系统更新</h3>
      </div>
      <el-tag
        :type="
          status.phase === 'FAILED'
            ? 'danger'
            : status.busy
              ? 'warning'
              : 'info'
        "
      >
        {{
          reconnecting
            ? "正在重新连接"
            : phases[status.phase || ""] || "更新服务未配置"
        }}
      </el-tag>
    </div>
    <p class="update-description">
      从项目 GitHub 获取新版，保留账号、文件和服务器配置。
    </p>
    <div class="version-grid">
      <div>
        <span>当前运行版本</span
        ><strong>{{ status.current?.slice(0, 8) || "等待检查" }}</strong>
      </div>
      <div>
        <span>GitHub 最新版本</span
        ><strong>{{ status.latest?.slice(0, 8) || "等待检查" }}</strong>
      </div>
    </div>
    <div v-if="!status.enabled && !reconnecting" class="setup-note">
      <strong>首次使用需要在服务器启用更新服务</strong>
      <p>在项目目录执行下方命令。完成后，此处即可检查并一键更新。</p>
      <code>bash scripts/install-updater.sh</code>
    </div>
    <div v-else class="update-message" role="status">
      {{
        reconnecting
          ? "服务暂时断开，正在等待恢复。更新任务会在服务器继续执行。"
          : status.pending
            ? "请求已提交，等待更新服务处理…"
            : status.message || "点击检查更新获取版本信息。"
      }}
      <small v-if="status.phase === 'FAILED' && status.failedPhase"
        >失败阶段：{{ phases[status.failedPhase] || status.failedPhase }}</small
      >
      <small v-if="status.updatedAt"
        >最近状态：{{ date(status.updatedAt) }}</small
      >
      <small v-if="status.backup">更新前备份：{{ status.backup }}</small>
    </div>
    <div class="update-actions">
      <el-button
        :disabled="
          !status.enabled || status.busy || status.pending || reconnecting
        "
        :loading="sending"
        @click="request('check')"
        >检查更新</el-button
      >
      <el-button
        type="primary"
        :disabled="
          !status.enabled ||
          !status.available ||
          status.busy ||
          status.pending ||
          reconnecting
        "
        :loading="sending"
        @click="request('update')"
        >一键更新</el-button
      >
    </div>
    <el-button
      :disabled="
        !status.enabled || status.busy || status.pending || reconnecting
      "
      @click="request('backup')"
      >立即备份</el-button
    >
    <el-table
      v-if="status.backups?.length"
      :data="status.backups"
      max-height="240"
      ><el-table-column prop="id" label="备份" /><el-table-column
        prop="status"
        label="状态" /><el-table-column prop="offsite" label="异地副本"
    /></el-table>
    <p class="update-footnote">
      恢复采用独立实例，操作见部署手册的备份恢复部分。
    </p>
    <p class="update-footnote">
      更新来源：duyan9030-dotcom/CloudNest · main<br />备份保留在服务器，不会自动清理。更新完成后刷新页面。
    </p>
  </section>
</template>
<style scoped>
.update-card {
  padding: 24px;
  border: 1px solid #e5e8f3;
  border-radius: 18px;
  background: white;
}
.update-heading {
  display: flex;
  align-items: center;
  justify-content: space-between;
  gap: 12px;
}
.eyebrow {
  color: var(--cn-muted);
  font-size: 12px;
  letter-spacing: 2px;
}
h3 {
  margin: 8px 0;
  font-size: 21px;
  color: #22314c;
}
.update-description,
.update-footnote {
  color: var(--cn-muted);
  line-height: 1.7;
  font-size: 13px;
}
.version-grid {
  display: grid;
  grid-template-columns: 1fr 1fr;
  gap: 12px;
  margin: 22px 0;
}
.version-grid div {
  background: #f6f7fc;
  border-radius: 12px;
  padding: 16px;
}
.version-grid span,
.version-grid strong {
  display: block;
}
.version-grid span {
  color: var(--cn-muted);
  font-size: 12px;
  margin-bottom: 10px;
}
.version-grid strong {
  font-size: 18px;
  color: #34446a;
  font-family: monospace;
}
.setup-note,
.update-message {
  padding: 16px;
  background: #f1f4ff;
  border-radius: 12px;
  line-height: 1.7;
  overflow-wrap: anywhere;
}
.setup-note p {
  margin: 6px 0 12px;
}
code {
  display: block;
  font-size: 13px;
  user-select: all;
}
.update-message small {
  display: block;
  color: var(--cn-muted);
  margin-top: 8px;
}
.update-actions {
  display: flex;
  gap: 10px;
  margin-top: 22px;
}
.update-actions .el-button + .el-button {
  margin-left: 0;
}
.update-footnote {
  margin: 18px 0 0;
  font-size: 12px;
}
@media (max-width: 600px) {
  .update-card {
    padding: 16px;
  }
  .version-grid {
    grid-template-columns: 1fr;
  }
}
</style>
