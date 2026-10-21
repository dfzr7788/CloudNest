<script setup lang="ts">
import { t } from "../i18n";
import { computed, ref, watch } from "vue";
import { useRouter } from "vue-router";
import { ElMessage, ElMessageBox } from "element-plus";
import { api, date, errorText } from "../api";
import { Lock } from "@element-plus/icons-vue";
import SystemUpdate from "./SystemUpdate.vue";
import AccessSettings from "./AccessSettings.vue";
import SyncDevices from "./SyncDevices.vue";
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
      if (!copied) throw new Error(t("account.selectTheLinkAndCopyIt"));
    }
    ElMessage.success(t("account.invitationLinkCopied"));
  } catch {
    ElMessage.info(t("account.selectTheLinkBelowAndPress"));
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
      t("account.newAccountQuotaInGibSingle"),
      t("account.inviteUser"),
      {
        inputValue: "5",
        confirmButtonText: t("account.generate"),
        cancelButtonText: t("common.cancel"),
      },
    );
    const quota = Number(value) * 1024 ** 3;
    if (!Number.isSafeInteger(quota) || quota < 1048576)
      throw new Error(t("account.enterAValidQuotaOfAt"));
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
  FOLDER_CREATED: t("account.createFolder"),
  ENTRY_UPDATED: t("account.updateFileOrFolder"),
  TRASH: t("files.trash"),
  RESTORE: t("account.restoreFileOrFolder"),
  PURGE: t("common.purge"),
  UPLOAD_COMPLETED: t("account.uploadCompleted"),
  SHARE_CREATED: t("drive.createShare"),
  SHARE_REVOKED: t("drive.revokeShare"),
  SHARE_UPDATED: t("account.updateShareSettings"),
  ACCESS_DENIED: t("account.accessDeniedOrResourceUnavailable"),
  VERSION_APPENDED: t("account.saveNewVersion"),
  VERSION_RESTORED: t("account.restoreVersion"),
  VERSION_DELETED: t("account.deleteVersion"),
  PASSWORD_CHANGED: t("settings.password"),
  SESSION_REVOKED: t("account.signOutSession"),
  OTHER_SESSIONS_REVOKED: t("account.signOutOtherSessions"),
  TRASH_AUTO_PURGED: t("account.automaticallyEmptyTrash"),
  USER_UPDATED: t("account.updateAccountStatusOrQuota"),
  CREATE_ADMIN: t("account.createAdministrator"),
  CREATE_USER: t("account.createUser"),
  RESET_PASSWORD: t("account.passwordResetByOperator"),
  SYSTEM_UPDATE_REQUESTED: t("account.requestSystemUpdate"),
  SYSTEM_UPDATE_CHECKED: t("account.checkSystemVersion"),
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
        ? t("account.retentionConfirm", { days: trashDays.value })
        : t("account.turnOffAutomaticTrashCleanup"),
      t("account.trashRetention"),
      {
        confirmButtonText: t("common.save"),
        cancelButtonText: t("common.cancel"),
      },
    );
    await api.put("/user/trash-policy", { days: trashDays.value });
    ElMessage.success(t("account.retentionPolicySaved"));
  } catch (e) {
    if (e !== "cancel" && e !== "close") ElMessage.error(errorText(e));
  }
}
watch([activityPage, adminPage], extra);
async function editUser(u: (typeof adminUsers.value)[number]) {
  try {
    const { value } = await ElMessageBox.prompt(
      t("account.newStorageQuotaGib"),
      t("account.changeUserQuota"),
      {
        inputValue: String(u.storage_quota / 1024 ** 3),
        confirmButtonText: t("common.save"),
        cancelButtonText: t("common.cancel"),
      },
    );
    const quota = Number(value) * 1024 ** 3;
    if (!Number.isSafeInteger(quota) || quota < 0)
      throw new Error(t("account.enterAValidQuota"));
    await api.patch(`/admin/users/${u.id}`, { quota, enabled: u.enabled });
    await extra();
  } catch (e) {
    if (e !== "cancel" && e !== "close") ElMessage.error(errorText(e));
  }
}
async function toggleUser(u: (typeof adminUsers.value)[number]) {
  try {
    await ElMessageBox.confirm(
      u.enabled
        ? t("account.disablingSignsThisUserOutOf")
        : t("account.thisUserWillBeEnabled"),
      t("account.changeAccountStatus"),
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
    ElMessage.success(t("account.selectedSessionsSignedOut"));
    await load();
  } catch (e) {
    ElMessage.error(errorText(e));
  }
}
async function change() {
  if (newPassword.value !== confirmation.value) {
    ElMessage.error(t("account.passwordsDoNotMatch"));
    return;
  }
  if (newPassword.value.length < 8) {
    ElMessage.error(t("account.useAPasswordOfAtLeast"));
    return;
  }
  try {
    await ElMessageBox.confirm(
      t("account.changingYourPasswordSignsOutAll"),
      t("settings.password"),
      {
        confirmButtonText: t("account.confirmChange"),
        cancelButtonText: t("common.cancel"),
      },
    );
    busy.value = true;
    await api.post("/user/password", {
      oldPassword: oldPassword.value,
      newPassword: newPassword.value,
    });
    visible.value = false;
    ElMessage.success(t("account.passwordChangedSignInAgain"));
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
          <el-form-item :label="t('account.currentPassword')"
            ><el-input
              v-model="oldPassword"
              type="password"
              show-password
              autocomplete="current-password"
          /></el-form-item>
          <el-form-item :label="t('account.newPassword864Characters')"
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
          <el-button type="primary" :loading="busy" native-type="submit">{{
            t("account.changePasswordAndSignOutAll")
          }}</el-button>
        </el-form>
      </el-tab-pane>
      <el-tab-pane name="access" :label="t('settings.clients')"
        ><AccessSettings :active="visible && activeTab === 'access'" />
        <h3>{{ t("syncDevices.title") }}</h3>
        <SyncDevices :active="visible && activeTab === 'access'" />
      </el-tab-pane>
      <el-tab-pane name="sessions" :label="t('settings.sessions')">
        <p>{{ t("account.clientDetailsHelpIdentifySessionsLast") }}</p>
        <el-button @click="revoke()">{{
          t("account.signOutOtherSessions")
        }}</el-button>
        <el-table :data="sessions"
          ><el-table-column :label="t('account.client')" min-width="180"
            ><template #default="{ row }"
              >{{ row.current ? t("account.currentSession") : ""
              }}{{ row.client_label }}</template
            ></el-table-column
          ><el-table-column :label="t('account.lastActive')" min-width="150"
            ><template #default="{ row }">{{
              date(row.last_seen)
            }}</template></el-table-column
          ><el-table-column :label="t('common.actions')" width="80"
            ><template #default="{ row }"
              ><el-button v-if="!row.current" text @click="revoke(row.id)">{{
                t("account.signOut")
              }}</el-button></template
            ></el-table-column
          ></el-table
        >
      </el-tab-pane>
      <el-tab-pane name="trash" :label="t('settings.trash')">
        <template v-if="space"
          ><p>{{ t("account.activeFiles") }} {{ bytes(space.live) }}</p>
          <p>{{ t("account.trash") }} {{ bytes(space.trash) }}</p>
          <p>{{ t("account.allFileVersions") }} {{ bytes(space.versions) }}</p>
          <p>
            {{ t("account.reservedForUploads") }} {{ bytes(space.reserved) }}
          </p>
          <p>{{ t("account.trashCountsTowardsYourQuotaEach") }}</p></template
        >

        <h3>{{ t("settings.retention") }}</h3>
        <p>
          {{ t("account.automaticCleanupIsOffByDefault") }}
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
            :aria-label="t('account.trashRetentionDays')"
          /><span>{{ t("account.days0DisablesCleanup") }}</span
          ><el-button @click="policy">{{
            t("account.saveRetentionPolicy")
          }}</el-button>
        </div></el-tab-pane
      >
      <el-tab-pane name="activity" :label="t('settings.activity')"
        ><p>{{ t("account.securityOrganizationVersionAndCleanupEvents") }}</p>
        <div>
          <el-button :disabled="activityPage <= 1" @click="activityPage--">{{
            t("account.previousEvents")
          }}</el-button
          ><span>{{ t("account.page", { page: activityPage }) }}</span
          ><el-button
            :disabled="activity.length < 50"
            @click="activityPage++"
            >{{ t("account.nextEvents") }}</el-button
          >
        </div>
        <el-table :data="activity"
          ><el-table-column :label="t('common.actions')"
            ><template #default="{ row }">{{
              activityLabels[row.action] || t("account.otherAction")
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
            <span>{{ t("account.freeDiskSpace") }}</span
            ><strong>{{ bytes(adminStatus.freeBytes || 0) }}</strong>
          </div>
          <div class="admin-stat">
            <span>{{ t("account.pendingUploads") }}</span
            ><strong
              >{{ adminStatus.pendingUploads || 0 }}
              <small>{{ t("drive.items") }}</small></strong
            >
          </div>
          <div class="admin-stat">
            <span>{{ t("account.objectsAwaitingCleanup") }}</span
            ><strong
              >{{ adminStatus.pendingDeletes || 0 }}
              <small>{{ t("drive.items") }}</small></strong
            >
          </div>
        </div>
        <section class="admin-card invitation-card">
          <div class="admin-section-heading">
            <div>
              <h3>{{ t("account.inviteAMember") }}</h3>
              <p>{{ t("account.eachLinkCanBeUsedOnce") }}</p>
            </div>
            <el-button type="primary" :loading="inviteBusy" @click="invite">{{
              t("account.inviteUser")
            }}</el-button>
          </div>
          <div v-if="inviteLink" class="invitation-result">
            <label>{{ t("account.registrationInvitationLink") }}</label>
            <div class="invitation-link">
              <el-input
                v-if="inviteLink"
                :model-value="inviteLink"
                readonly
                :aria-label="t('account.registrationInvitationLink')"
              /><el-button @click="copyInvite">{{
                t("drive.copyLink")
              }}</el-button>
            </div>
            <p>{{ t("account.thisLinkIsShownOnlyNow") }}</p>
          </div>
        </section>
        <section class="admin-card">
          <div class="admin-section-heading">
            <div>
              <h3>{{ t("account.userManagement") }}</h3>
              <p>{{ t("account.manageAccountQuotasAndAccess") }}</p>
            </div>
            <span class="page-label">{{
              t("account.page", { page: adminPage })
            }}</span>
          </div>
          <el-table
            :data="adminUsers"
            class="admin-user-table"
            :empty-text="t('account.noUsers')"
          >
            <el-table-column
              prop="username"
              :label="t('common.account')"
              min-width="150"
            />
            <el-table-column :label="t('account.storageUsage')" min-width="180"
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
                    row.enabled
                      ? t("nodeSettings.active")
                      : t("account.disabled")
                  }}</el-tag
                ></template
              ></el-table-column
            >
            <el-table-column :label="t('account.manage')" width="180"
              ><template #default="{ row }"
                ><el-button link type="primary" @click="editUser(row)">{{
                  t("account.changeQuota")
                }}</el-button
                ><el-button
                  link
                  :disabled="row.id === account.user?.id"
                  :type="row.enabled ? 'danger' : 'primary'"
                  @click="toggleUser(row)"
                  >{{
                    row.enabled ? t("account.disable") : t("common.enable")
                  }}</el-button
                ></template
              ></el-table-column
            >
          </el-table>
          <div class="account-pagination">
            <el-button :disabled="adminPage <= 1" @click="adminPage--">{{
              t("account.previousUsers")
            }}</el-button
            ><el-button
              :disabled="adminUsers.length < 50"
              @click="adminPage++"
              >{{ t("account.nextUsers") }}</el-button
            >
          </div>
        </section>
        <p class="account-hint">
          {{ t("account.initialAdministratorSetupAndAccountRecovery") }}
        </p>
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
  min-height: 48px;
  height: auto;
  white-space: normal;
  overflow-wrap: anywhere;
  line-height: 1.4;
  padding-block: 10px;
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
