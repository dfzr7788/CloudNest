<script setup lang="ts">
import { ref, watch } from "vue";
import { useRouter } from "vue-router";
import { ElMessage, ElMessageBox } from "element-plus";
import { api, date, errorText } from "../api";
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
      { inputValue: "5", confirmButtonText: "生成", cancelButtonText: "取消" },
    );
    const quota = Number(value) * 1024 ** 3;
    if (!Number.isSafeInteger(quota) || quota < 1048576)
      throw new Error("请输入至少 1 MiB 的有效容量");
    const { data } = await api.post("/admin/invitations", { quota });
    inviteLink.value = location.origin + "/login?invitation=" + data.token;
  } catch (e) {
    if (e !== "cancel" && e !== "close") ElMessage.error(errorText(e));
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
const activityLabels: Record<string, string> = {
  FOLDER_CREATED: "创建文件夹",
  ENTRY_UPDATED: "修改文件或目录",
  TRASH: "移入回收站",
  RESTORE: "恢复文件或目录",
  PURGE: "彻底删除",
  UPLOAD_COMPLETED: "上传完成",
  SHARE_CREATED: "创建分享",
  SHARE_REVOKED: "撤销分享",
  SHARE_UPDATED: "修改分享设置",
  ACCESS_DENIED: "访问被拒或资源不可见",
  VERSION_APPENDED: "保存新版本",
  VERSION_RESTORED: "恢复历史版本",
  VERSION_DELETED: "删除历史版本",
  PASSWORD_CHANGED: "修改密码",
  SESSION_REVOKED: "退出指定会话",
  OTHER_SESSIONS_REVOKED: "退出其他会话",
  TRASH_AUTO_PURGED: "自动清理回收站",
  USER_UPDATED: "修改用户状态或配额",
  CREATE_ADMIN: "创建管理员",
  CREATE_USER: "创建用户",
  RESET_PASSWORD: "维护人员重置密码",
};
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
      { confirmButtonText: "保存", cancelButtonText: "取消" },
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
        confirmButtonText: "保存",
        cancelButtonText: "取消",
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
      { confirmButtonText: "确认", cancelButtonText: "取消" },
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
      "修改密码",
      { confirmButtonText: "确认修改", cancelButtonText: "取消" },
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
    style="width: auto; padding: 0 12px; border-radius: 9px"
    @click="show"
    >账号与安全</el-button
  >
  <el-dialog
    append-to-body
    v-model="visible"
    title="账号与安全"
    width="min(640px, 94vw)"
  >
    <el-tabs>
      <el-tab-pane label="回收站设置"
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
      <el-tab-pane label="活动记录"
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
          ><el-table-column label="操作"
            ><template #default="{ row }">{{
              activityLabels[row.action] || "其他操作"
            }}</template></el-table-column
          ><el-table-column label="时间"
            ><template #default="{ row }">{{
              date(row.created_at)
            }}</template></el-table-column
          ></el-table
        ></el-tab-pane
      >
      <el-tab-pane v-if="account.user?.administrator" label="系统管理"
        ><el-button @click="invite">邀请新用户</el-button
        ><el-input
          v-if="inviteLink"
          :model-value="inviteLink"
          readonly
          aria-label="邀请注册链接"
        />
        <p v-if="inviteLink">请复制并妥善发送；链接仅在本次创建时显示。</p>
        <p>
          服务器剩余磁盘：{{ bytes(adminStatus.freeBytes || 0) }} · 待处理上传
          {{ adminStatus.pendingUploads || 0 }} · 待清理对象
          {{ adminStatus.pendingDeletes || 0 }}
        </p>
        <div>
          <el-button :disabled="adminPage <= 1" @click="adminPage--"
            >上一页用户</el-button
          ><span>第 {{ adminPage }} 页</span
          ><el-button :disabled="adminUsers.length < 50" @click="adminPage++"
            >下一页用户</el-button
          >
        </div>
        <el-table :data="adminUsers"
          ><el-table-column prop="username" label="账号" /><el-table-column
            label="配额"
            ><template #default="{ row }">{{
              bytes(row.storage_quota)
            }}</template></el-table-column
          ><el-table-column label="管理"
            ><template #default="{ row }"
              ><el-button text @click="editUser(row)">调整配额</el-button
              ><el-button text @click="toggleUser(row)">{{
                row.enabled ? "禁用" : "启用"
              }}</el-button></template
            ></el-table-column
          ></el-table
        >
        <p>
          账号创建、密码重置与初始管理员设置通过本机维护命令执行，详见部署手册。
        </p></el-tab-pane
      >
      <el-tab-pane label="修改密码">
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
          <el-form-item label="再次输入新密码"
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
      <el-tab-pane label="登录会话">
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
          ><el-table-column label="操作" width="80"
            ><template #default="{ row }"
              ><el-button v-if="!row.current" text @click="revoke(row.id)"
                >退出</el-button
              ></template
            ></el-table-column
          ></el-table
        >
      </el-tab-pane>
      <el-tab-pane label="空间明细"
        ><template v-if="space"
          ><p>有效文件：{{ bytes(space.live) }}</p>
          <p>回收站当前内容：{{ bytes(space.trash) }}</p>
          <p>全部历史版本：{{ bytes(space.versions) }}</p>
          <p>上传预留：{{ bytes(space.reserved) }}</p>
          <p>回收站继续占用配额；同内容副本按各自大小计费。</p></template
        ></el-tab-pane
      >
    </el-tabs>
  </el-dialog>
</template>
