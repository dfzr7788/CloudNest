<script setup lang="ts">
import { onUnmounted, ref, watch, computed } from "vue";
import { ElMessageBox } from "element-plus";
import { api, date, errorText } from "../api";
import { t } from "../i18n";

const props = defineProps<{ teamId: string; owner: boolean }>();
const emit = defineEmits<{ changed: [] }>();
const rows = ref<any[]>([]),
  busy = ref(false),
  failure = ref("");
const role = ref("VIEWER"),
  contributor = ref(false),
  hours = ref(24);
const issued = ref<{ id: string; token: string; expiresAt: number } | null>(
  null,
);
const roles = computed(() =>
  props.owner
    ? ["ADMIN", "EDITOR", "VIEWER", "NONE"]
    : ["EDITOR", "VIEWER", "NONE"],
);
let generation = 0;
onUnmounted(() => generation++);
function status(row: any) {
  if (row.revoked) return "revoked";
  if (row.consumed_by) return "used";
  return row.expires_at <= Date.now() ? "expired" : "active";
}
async function load() {
  const token = ++generation;
  const team = props.teamId;
  busy.value = true;
  try {
    const { data } = await api.get(`/teams/${team}/invitations`);
    if (token !== generation) return;
    rows.value = data;
    failure.value = "";
  } catch (error) {
    if (token === generation) failure.value = errorText(error);
  } finally {
    if (token === generation) busy.value = false;
  }
}
watch(
  () => [props.teamId, props.owner],
  () => {
    rows.value = [];
    issued.value = null;
    role.value = "VIEWER";
    contributor.value = false;
    hours.value = 24;
    void load();
  },
  { immediate: true },
);
async function create() {
  if (busy.value) return;
  const team = props.teamId,
    token = generation;
  busy.value = true;
  try {
    const { data } = await api.post(`/teams/${team}/invitations`, {
      role: role.value,
      contributor: contributor.value,
      hours: hours.value,
    });
    if (token !== generation) return;
    issued.value = data;
    emit("changed");
    await load();
  } catch (error) {
    if (token === generation) failure.value = errorText(error);
  } finally {
    if (token === generation) busy.value = false;
  }
}
async function revoke(row: any) {
  const team = props.teamId,
    token = generation;
  try {
    await ElMessageBox.confirm(
      t("teamManagement.revokeConfirm"),
      t("teamManagement.revoke"),
    );
  } catch {
    return;
  }
  if (token !== generation || busy.value) return;
  busy.value = true;
  try {
    await api.delete(`/teams/${team}/invitations/${row.id}`);
    if (token !== generation) return;
    if (issued.value?.id === row.id) issued.value = null;
    emit("changed");
    await load();
  } catch (error) {
    if (token === generation) failure.value = errorText(error);
  } finally {
    if (token === generation) busy.value = false;
  }
}
</script>
<template>
  <section class="team-invitations" v-loading="busy">
    <div class="next-toolbar">
      <h2>{{ t("teamManagement.invitations") }}</h2>
      <el-button :disabled="busy" @click="load">{{
        t("next.refresh")
      }}</el-button>
    </div>
    <el-alert v-if="failure" type="error" :title="failure" :closable="false" />
    <el-form label-position="top" class="next-form" @submit.prevent="create">
      <el-form-item :label="t('next.role')">
        <el-select v-model="role"
          ><el-option
            v-for="r in roles"
            :key="r"
            :value="r"
            :label="t(`next.${r}`)"
        /></el-select>
      </el-form-item>
      <el-form-item :label="t('teamManagement.validHours')">
        <el-input-number v-model="hours" :min="1" :max="168" :precision="0" />
      </el-form-item>
      <el-form-item
        ><el-checkbox v-model="contributor">{{
          t("next.contributor")
        }}</el-checkbox></el-form-item
      >
      <el-form-item
        ><el-button :disabled="busy || !hours" native-type="submit">{{
          t("next.createInvitation")
        }}</el-button></el-form-item
      >
    </el-form>
    <el-alert
      v-if="issued"
      :title="issued.token"
      :description="t('next.invitationNotice')"
      :closable="false"
      type="success"
    />
    <p class="muted">{{ t("teamManagement.invitationHistoryNotice") }}</p>
    <el-table :data="rows" :empty-text="t('teamManagement.noInvitations')">
      <el-table-column :label="t('next.role')" min-width="140"
        ><template #default="{ row }">{{
          t(`next.${row.file_role}`)
        }}</template></el-table-column
      >
      <el-table-column :label="t('next.contributor')" min-width="150"
        ><template #default="{ row }"
          ><el-checkbox :model-value="row.contributor" disabled /></template
      ></el-table-column>
      <el-table-column :label="t('teamManagement.expires')" min-width="180"
        ><template #default="{ row }">{{
          date(row.expires_at)
        }}</template></el-table-column
      >
      <el-table-column :label="t('next.status')" min-width="120"
        ><template #default="{ row }">{{
          t(`teamManagement.${status(row)}`)
        }}</template></el-table-column
      >
      <el-table-column :label="t('next.actions')" min-width="130"
        ><template #default="{ row }"
          ><el-button
            v-if="status(row) === 'active'"
            link
            type="danger"
            :disabled="busy"
            @click="revoke(row)"
            >{{ t("teamManagement.revoke") }}</el-button
          ></template
        ></el-table-column
      >
    </el-table>
  </section>
</template>
