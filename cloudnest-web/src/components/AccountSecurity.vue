<script setup lang="ts">
import { t } from "../i18n";
import { computed, ref, watch } from "vue";
import { useRouter } from "vue-router";
import { ElMessage, ElMessageBox } from "element-plus";
import { api, date, errorText } from "../api";
import { Lock } from "@element-plus/icons-vue";
import SystemUpdate from "./SystemUpdate.vue";
import AccessSettings from "./AccessSettings.vue";
import ServerSettings from "./ServerSettings.vue";
import AiSettings from "./AiSettings.vue";
import AiKnowledge from "./AiKnowledge.vue";
import AiOrganize from "./AiOrganize.vue";
const activeTab = ref("trash"),
  inviteBusy = ref(false);
async function copyInvite() {
  try {
    if (navigator.clipboard?.writeText)
      await navigator.clipboard.writeText(inviteLink.value);
    else {
      const input = document.createElement("textarea");
      input.value = inviteLink.value;
      input.style.position = "fixed";
      input.style.opacity = "0";
      document.body.appendChild(input);
      input.select();
      const copied = document.execCommand("copy");
      input.remove();
      if (!copied) throw new Error("请选中链接后手动复制");
    }
    ElMessage.success("邀请链接已复制");
  } catch {
    ElMessage.info("请选中下方链接，按 Ctrl+C 复制");
  }
}
const router = useRouter(),
  visible = ref(false),
  oldPassword = ref(""),
  newPassword = ref(""),
  confirmation = ref(""),
  busy = ref(false);
const inviteLink = ref("");
async function invite() {
  try {
    const { value } = await ElMessageBox.prompt(
      "新账号配额（GiB，邀请码一次有效，48 小时过期）",
      "邀请新用户",
      {
        inputValue: "5",
        confirmButtonText: "生成",
        cancelButtonText: t("common.cancel"),
      },
    );
    const quota = Number(value) * 1024 ** 3;
    if (!Number.isSafeInteger(quota) || quota < 1048576)
      throw new Error("请输入至少 1 MiB 的有效容量");
    inviteBusy.value = true;
    const { data } = await api.post("/admin/invitations", { quota });
    inviteLink.value = location.origin + "/login?invitation=" + data.token;
  } catch (e) {
    if (e !== "cancel" && e !== "close") ElMessage.error(errorText(e));
  } finally {
    inviteBusy.value = false;
  }
}
const sessions = ref<
  {
    id: string;
    client_label: string;
    last_seen: number;
    expires_at: number;
    current: boolean;
  }[]
>([]);
const space = ref<{
  live: number;
  trash: number;
  reserved: number;
  versions: number;
}>();
import { bytes } from "../api";
import { useUser } from "../stores/user";
const settingsGroups = computed(() => [
  {
    label: t("settings.account"),
    items: [
      { value: "password", label: t("settings.password") },
      { value: "access", label: t("settings.clients") },
      { value: "sessions", label: t("settings.sessions") },
    ],
  },
  {
    label: t("settings.files"),
    items: [
      { value: "space", label: t("settings.space") },
      { value: "trash", label: t("settings.trash") },
      { value: "activity", label: t("settings.activity") },
    ],
  },
  {
    label: t("settings.aiWorkspace"),
    items: [
      { value: "ai", label: t("settings.ai") },
      { value: "knowledge", label: t("settings.knowledge") },
      { value: "organize", label: t("settings.organize") },
    ],
  },
  ...(account.user?.administrator
    ? [
        {
          label: t("settings.admin"),
          items: [
            { value: "admin", label: t("settings.admin") },
            { value: "server", label: t("settings.server") },
            { value: "updates", label: t("settings.updates") },
          ],
        },
      ]
    : []),
]);
const activityLabels = computed<Record<string, string>>(() => ({
  FOLDER_CREATED: "创建文件夹",
  ENTRY_UPDATED: "修改文件或目录",
  TRASH: t("files.trash"),
  RESTORE: "恢复文件或目录",
  PURGE: t("common.purge"),
  UPLOAD_COMPLETED: "上传完成",
  SHARE_CREATED: t("drive.createShare"),
  SHARE_REVOKED: t("drive.revokeShare"),
  SHARE_UPDATED: "修改分享设置",
  ACCESS_DENIED: "访问被拒或资源不可见",
  VERSION_APPENDED: "保存新版本",
  VERSION_RESTORED: "恢复历史版本",
  VERSION_DELETED: "删除历史版本",
  PASSWORD_CHANGED: t("settings.password"),
  SESSION_REVOKED: "退出指定会话",
  OTHER_SESSIONS_REVOKED: "退出其他会话",
  TRASH_AUTO_PURGED: "自动清理回收站",
  USER_UPDATED: "修改用户状态或配额",
  CREATE_ADMIN: "创建管理员",
  CREATE_USER: "创建用户",
  RESET_PASSWORD: "维护人员重置密码",
  SYSTEM_UPDATE_REQUESTED: "请求更新系统",
  SYSTEM_UPDATE_CHECKED: "检查系统版本",
}));
const account = useUser(),
  activityPage = ref(1),
  adminPage = ref(1),
  trashDays = ref(0),
  activity = ref<{ action: string; target_id: string; created_at: number }[]>(
    [],
  ),
  adminUsers = ref<
    {
      id: string;
      username: string;
      storage_quota: number;
      storage_used: number;
      storage_reserved: number;
      enabled: boolean;
    }[]
  >([]),
  adminStatus = ref<Record<string, number>>({});
async function extra() {
  try {
    trashDays.value = (await api.get("/user/trash-policy")).data.days;
    activity.value = (
      await api.get("/user/activity", { params: { page: activityPage.value } })
    ).data;
    if (account.user?.administrator) {
      adminUsers.value = (
        await api.get("/admin/users", { params: { page: adminPage.value } })
      ).data;
      adminStatus.value = (await api.get("/admin/status")).data;
    }
  } catch (e) {
    ElMessage.error(errorText(e));
  }
}
async function policy() {
  try {
    await ElMessageBox.confirm(
      trashDays.value
        ? `启用后，删除超过 ${trashDays.value} 天的内容会永久清理，无法恢复。`
        : "关闭回收站自动清理。",
      "回收站保留策略",
      {
        confirmButtonText: t("common.save"),
        cancelButtonText: t("common.cancel"),
      },
    );
    await api.put("/user/trash-policy", { days: trashDays.value });
    ElMessage.success("保留策略已保存");
  } catch (e) {
    if (e !== "cancel" && e !== "close") ElMessage.error(errorText(e));
  }
}
watch([activityPage, adminPage], extra);
async function editUser(u: (typeof adminUsers.value)[number]) {
  try {
    const { value } = await ElMessageBox.prompt(
      "新的容量配额（GiB）",
      "调整用户配额",
      {
        inputValue: String(u.storage_quota / 1024 ** 3),
        confirmButtonText: t("common.save"),
        cancelButtonText: t("common.cancel"),
      },
    );
    const quota = Number(value) * 1024 ** 3;
    if (!Number.isSafeInteger(quota) || quota < 0)
      throw new Error("请输入有效容量");
    await api.patch(`/admin/users/${u.id}`, { quota, enabled: u.enabled });
    await extra();
  } catch (e) {
    if (e !== "cancel" && e !== "close") ElMessage.error(errorText(e));
  }
}
async function toggleUser(u: (typeof adminUsers.value)[number]) {
  try {
    await ElMessageBox.confirm(
      `${u.enabled ? "禁用会退出该用户全部会话" : "将启用该用户"}。`,
      "修改用户状态",
      {
        confirmButtonText: t("common.confirm"),
        cancelButtonText: t("common.cancel"),
      },
    );
    await api.patch(`/admin/users/${u.id}`, {
      quota: u.storage_quota,
      enabled: !u.enabled,
    });
    await extra();
  } catch (e) {
    if (e !== "cancel" && e !== "close") ElMessage.error(errorText(e));
  }
}
async function load() {
  try {
    sessions.value = (await api.get("/user/sessions")).data;
    space.value = (await api.get("/user/storage-breakdown")).data;
  } catch (e) {
    ElMessage.error(errorText(e));
  }
}
async function show() {
  visible.value = true;
  oldPassword.value = "";
  newPassword.value = "";
  confirmation.value = "";
  await load();
  await extra();
}
async function revoke(id?: string) {
  try {
    await api.delete("/user/sessions" + (id ? "/" + id : ""));
    ElMessage.success("已退出所选会话");
    await load();
  } catch (e) {
    ElMessage.error(errorText(e));
  }
}
async function change() {
  if (newPassword.value !== confirmation.value) {
    ElMessage.error("两次新密码不一致");
    return;
  }
  if (newPassword.value.length < 8) {
    ElMessage.error("新密码至少 8 个字符");
    return;
  }
  try {
    await ElMessageBox.confirm(
      "修改密码将退出所有设备，请重新登录。",
      t("settings.password"),
      { confirmButtonText: "确认修改", cancelButtonText: t("common.cancel") },
    );
    busy.value = true;
    await api.post("/user/password", {
      oldPassword: oldPassword.value,
      newPassword: newPassword.value,
    });
    visible.value = false;
    ElMessage.success("密码已修改，请重新登录");
    await router.push("/login");
    location.reload();
  } catch (e) {
    if (e !== "cancel" && e !== "close") ElMessage.error(errorText(e));
  } finally {
    busy.value = false;
  }
}
</script>
<template>
  <el-button
    class="account-trigger"
    :aria-label="t('settings.account')"
    @click="show"
    ><el-icon><Lock /></el-icon
    ><span class="account-trigger-label">{{
      t("settings.account")
    }}</span></el-button
  >
  <el-dialog
    append-to-body
    v-model="visible"
    :title="t('settings.account')"
    width="min(1000px, 94vw)"
    class="cn-dialog account-dialog"
    top="6vh"
  >
    <p class="account-subtitle">{{ t("settings.intro") }}</p>
    <el-select
      v-model="activeTab"
      class="settings-mobile-nav"
      :aria-label="t('settings.groups')"
      ><el-option-group
        v-for="group in settingsGroups"
        :key="group.label"
        :label="group.label"
        ><el-option
          v-for="item in group.items"
          :key="item.value"
          :label="item.label"
          :value="item.value" /></el-option-group
    ></el-select>
    <el-tabs v-model="activeTab" class="account-tabs" tab-position="left">
      <el-tab-pane name="password" :label="t('settings.password')">
        <el-form label-position="top" @submit.prevent="change">
          <el-form-item label="原密码"
            ><el-input
              v-model="oldPassword"
              type="password"
              show-password
              autocomplete="current-password"
          /></el-form-item>
          <el-form-item label="新密码（8～64 个字符）"
            ><el-input
              v-model="newPassword"
              type="password"
              show-password
              autocomplete="new-password"
              maxlength="64"
          /></el-form-item>
          <el-form-item :label="t('login.confirmPasswordHint')"
            ><el-input
              v-model="confirmation"
              type="password"
              show-password
              autocomplete="new-password"
          /></el-form-item>
          <el-button type="primary" :loading="busy" native-type="submit"
            >修改密码并退出所有设备</el-button
          >
        </el-form>
      </el-tab-pane>
      <el-tab-pane name="access" :label="t('settings.clients')"
        ><AccessSettings :active="visible && activeTab === 'access'"
      /></el-tab-pane>
      <el-tab-pane name="sessions" :label="t('settings.sessions')">
        <p>客户端信息用于辅助识别。最近活动时间最多延迟一分钟。</p>
        <el-button @click="revoke()">退出其他会话</el-button>
        <el-table :data="sessions"
          ><el-table-column label="客户端" min-width="180"
            ><template #default="{ row }"
              >{{ row.current ? "当前会话 · " : ""
              }}{{ row.client_label }}</template
            ></el-table-column
          ><el-table-column label="最近活动" min-width="150"
            ><template #default="{ row }">{{
              date(row.last_seen)
            }}</template></el-table-column
          ><el-table-column :label="t('common.actions')" width="80"
            ><template #default="{ row }"
              ><el-button v-if="!row.current" text @click="revoke(row.id)"
                >退出</el-button
              ></template
            ></el-table-column
          ></el-table
        >
      </el-tab-pane>
      <el-tab-pane name="space" :label="t('settings.space')"
        ><template v-if="space"
          ><p>有效文件：{{ bytes(space.live) }}</p>
          <p>回收站当前内容：{{ bytes(space.trash) }}</p>
          <p>全部历史版本：{{ bytes(space.versions) }}</p>
          <p>上传预留：{{ bytes(space.reserved) }}</p>
          <p>回收站继续占用配额；同内容副本按各自大小计费。</p></template
        ></el-tab-pane
      >
      <el-tab-pane name="trash" :label="t('settings.trash')"
        ><p>
          默认关闭自动清理。启用后按服务器时间每小时检查，永久清理不会进入另一个回收站。
        </p>
        <div
          style="
            display: flex;
            gap: 12px;
            align-items: center;
            flex-wrap: wrap;
            margin-top: 12px;
          "
        >
          <el-input-number
            v-model="trashDays"
            :min="0"
            :max="365"
            aria-label="回收站保留天数"
          /><span>天（0 表示关闭）</span
          ><el-button @click="policy">保存保留策略</el-button>
        </div></el-tab-pane
      >
      <el-tab-pane name="activity" :label="t('settings.activity')"
        ><p>每页 50 条安全与整理、版本和自动清理操作，保留 90 天。</p>
        <div>
          <el-button :disabled="activityPage <= 1" @click="activityPage--"
            >上一页活动</el-button
          ><span>第 {{ activityPage }} 页</span
          ><el-button :disabled="activity.length < 50" @click="activityPage++"
            >下一页活动</el-button
          >
        </div>
        <el-table :data="activity"
          ><el-table-column :label="t('common.actions')"
            ><template #default="{ row }">{{
              activityLabels[row.action] || "其他操作"
            }}</template></el-table-column
          ><el-table-column :label="t('common.time')"
            ><template #default="{ row }">{{
              date(row.created_at)
            }}</template></el-table-column
          ></el-table
        ></el-tab-pane
      >
      <el-tab-pane name="ai" :label="t('settings.ai')">
        <AiSettings :active="visible && activeTab === 'ai'" />
      </el-tab-pane>
      <el-tab-pane name="knowledge" :label="t('settings.knowledge')"
        ><AiKnowledge :active="visible && activeTab === 'knowledge'"
      /></el-tab-pane>
      <el-tab-pane name="organize" :label="t('settings.organize')"
        ><AiOrganize :active="visible && activeTab === 'organize'"
      /></el-tab-pane>
      <el-tab-pane
        v-if="account.user?.administrator"
        name="admin"
        :label="t('settings.admin')"
      >
        <div class="admin-overview">
          <div class="admin-stat">
            <span>剩余磁盘</span
            ><strong>{{ bytes(adminStatus.freeBytes || 0) }}</strong>
          </div>
          <div class="admin-stat">
            <span>待处理上传</span
            ><strong
              >{{ adminStatus.pendingUploads || 0 }}
              <small>{{ t("drive.items") }}</small></strong
            >
          </div>
          <div class="admin-stat">
            <span>待清理对象</span
            ><strong
              >{{ adminStatus.pendingDeletes || 0 }}
              <small>{{ t("drive.items") }}</small></strong
            >
          </div>
        </div>
        <section class="admin-card invitation-card">
          <div class="admin-section-heading">
            <div>
              <h3>邀请新成员</h3>
              <p>每个链接限用一次，48 小时后失效。</p>
            </div>
            <el-button type="primary" :loading="inviteBusy" @click="invite"
              >邀请新用户</el-button
            >
          </div>
          <div v-if="inviteLink" class="invitation-result">
            <label>邀请注册链接</label>
            <div class="invitation-link">
              <el-input
                v-if="inviteLink"
                :model-value="inviteLink"
                readonly
                aria-label="邀请注册链接"
              /><el-button @click="copyInvite">{{
                t("drive.copyLink")
              }}</el-button>
            </div>
            <p>链接仅在本次创建时显示，请复制后发送给受邀人。</p>
          </div>
        </section>
        <section class="admin-card">
          <div class="admin-section-heading">
            <div>
              <h3>用户管理</h3>
              <p>管理账号的存储配额和访问状态。</p>
            </div>
            <span class="page-label">第 {{ adminPage }} 页</span>
          </div>
          <el-table
            :data="adminUsers"
            class="admin-user-table"
            empty-text="暂无用户"
          >
            <el-table-column
              prop="username"
              :label="t('common.account')"
              min-width="150"
            />
            <el-table-column label="存储使用" min-width="180"
              ><template #default="{ row }"
                ><strong>{{ bytes(row.storage_used) }}</strong
                ><span class="quota-detail">
                  / {{ bytes(row.storage_quota) }}</span
                ></template
              ></el-table-column
            >
            <el-table-column :label="t('common.status')" width="100"
              ><template #default="{ row }"
                ><el-tag
                  :type="row.enabled ? 'success' : 'info'"
                  size="small"
                  >{{
                    row.enabled ? t("nodeSettings.active") : "已禁用"
                  }}</el-tag
                ></template
              ></el-table-column
            >
            <el-table-column label="管理" width="180"
              ><template #default="{ row }"
                ><el-button link type="primary" @click="editUser(row)"
                  >调整配额</el-button
                ><el-button
                  link
                  :disabled="row.id === account.user?.id"
                  :type="row.enabled ? 'danger' : 'primary'"
                  @click="toggleUser(row)"
                  >{{ row.enabled ? "禁用" : t("common.enable") }}</el-button
                ></template
              ></el-table-column
            >
          </el-table>
          <div class="account-pagination">
            <el-button :disabled="adminPage <= 1" @click="adminPage--"
              >上一页用户</el-button
            ><el-button :disabled="adminUsers.length < 50" @click="adminPage++"
              >下一页用户</el-button
            >
          </div>
        </section>
        <p class="account-hint">初始管理员与账号恢复由服务器维护人员处理。</p>
      </el-tab-pane>
      <el-tab-pane
        v-if="account.user?.administrator"
        name="server"
        :label="t('settings.server')"
        ><ServerSettings :active="visible && activeTab === 'server'"
      /></el-tab-pane>
      <el-tab-pane
        v-if="account.user?.administrator"
        name="updates"
        :label="t('settings.updates')"
        ><SystemUpdate :active="visible && activeTab === 'updates'"
      /></el-tab-pane>
    </el-tabs>
  </el-dialog>
</template>
<style scoped>
.account-subtitle {
  margin: -6px 0 22px;
  color: var(--cn-muted);
  font-size: 13px;
}
.account-tabs :deep(.el-tabs__header) {
  margin-bottom: 24px;
}
.account-tabs :deep(.el-tabs__item) {
  height: 48px;
  font-size: 14px;
}
.account-tabs :deep(.el-tab-pane) {
  min-width: 0;
  padding: 2px 2px 12px;
}
.admin-overview {
  display: grid;
  grid-template-columns: repeat(3, 1fr);
  gap: 14px;
  margin-bottom: 20px;
}
.admin-stat {
  background: var(--cn-subtle);
  border: 1px solid var(--cn-border);
  border-radius: 14px;
  padding: 20px;
}
.admin-stat span {
  display: block;
  color: var(--cn-muted);
  font-size: 12px;
  margin-bottom: 12px;
}
.admin-stat strong {
  color: var(--cn-heading);
  font-size: 24px;
  font-weight: 600;
}
.admin-stat small {
  font-size: 12px;
  color: var(--cn-muted);
  font-weight: 400;
}
.admin-card {
  border: 1px solid var(--cn-border);
  background: var(--cn-surface);
  border-radius: 16px;
  padding: 22px;
  margin-bottom: 18px;
}
.admin-section-heading {
  display: flex;
  align-items: center;
  justify-content: space-between;
  gap: 16px;
  margin-bottom: 18px;
}
.admin-section-heading h3 {
  color: var(--cn-heading);
  margin: 0 0 7px;
  font-size: 17px;
}
.admin-section-heading p,
.invitation-result p,
.account-hint {
  color: var(--cn-muted);
  margin: 0;
  font-size: 12px;
  line-height: 1.7;
}
.invitation-card .admin-section-heading {
  margin-bottom: 0;
}
.invitation-result {
  margin-top: 20px;
  padding: 16px;
  border-radius: 6px;
  background: var(--cn-subtle);
}
.invitation-result label {
  display: block;
  font-size: 12px;
  margin-bottom: 8px;
  color: var(--cn-muted);
}
.invitation-link {
  display: flex;
  gap: 10px;
  margin-bottom: 10px;
}
.invitation-link :deep(.el-input__inner) {
  font-size: 12px;
}
.quota-detail,
.page-label {
  color: var(--cn-muted);
  font-size: 12px;
}
.account-pagination {
  display: flex;
  justify-content: flex-end;
  gap: 10px;
  margin-top: 18px;
}
.account-pagination .el-button + .el-button {
  margin-left: 0;
}
.admin-user-table :deep(.el-table__cell) {
  padding: 16px 0;
}
.account-hint {
  padding: 0 4px;
}
@media (max-width: 600px) {
  .admin-overview {
    grid-template-columns: 1fr;
    gap: 8px;
  }
  .admin-stat {
    display: flex;
    align-items: center;
    justify-content: space-between;
    padding: 12px 16px;
  }
  .admin-stat span {
    margin: 0;
  }
  .admin-stat strong {
    font-size: 20px;
  }
  .admin-card {
    padding: 16px;
  }
  .admin-section-heading {
    flex-wrap: wrap;
  }
  .invitation-link {
    flex-direction: column;
  }
}
</style>
