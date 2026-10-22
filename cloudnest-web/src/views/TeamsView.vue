<script setup lang="ts">
import { onMounted, onUnmounted, computed, ref, watch } from "vue";
import { ElMessage, ElMessageBox } from "element-plus";
import { sha256 } from "@noble/hashes/sha2.js";
import { api, bytes, date, errorText } from "../api";
import { t } from "../i18n";
import LanguageSwitcher from "../components/common/LanguageSwitcher.vue";
import ThemeSwitcher from "../components/common/ThemeSwitcher.vue";
import TeamInvitations from "../components/TeamInvitations.vue";
import TimeMachine from "../components/TimeMachine.vue";
import FolderPicker from "../components/FolderPicker.vue";
import { useUser } from "../stores/user";
const user = useUser();
const workspaces = ref<any[]>([]),
  current = ref<any>(null),
  files = ref<any[]>([]),
  members = ref<any[]>([]),
  contributions = ref<any[]>([]),
  audits = ref<any[]>([]),
  trash = ref<any[]>([]);
const failure = ref(""),
  busy = ref(false),
  tab = ref("files"),
  parent = ref(""),
  crumbs = ref<any[]>([]),
  page = ref(1),
  total = ref(0);
const newTeam = ref({ name: "", quotaGiB: 1 }),
  invitation = ref("");
const acl = ref({ entry: "", user: "", permission: "READ" });
const moving = ref<any>(null);
const selectedFiles = ref<any[]>([]),
  storageTargets = ref<any[]>([]),
  storageJobs = ref<any[]>([]),
  storageTarget = ref("");
const node = ref({ name: "", endpoint: "", nodeToken: "", capacityGiB: 1 }),
  heartbeat = ref("");
const pending = ref<{ id: string; file: File; team: string } | null>(null),
  progress = ref(0),
  uploadBusy = ref(false),
  pause = ref(false);
const admin = computed(() =>
  ["OWNER", "ADMIN"].includes(current.value?.file_role),
);
const editor = computed(() =>
  ["OWNER", "ADMIN", "EDITOR"].includes(current.value?.file_role),
);
const readable = computed(
  () => current.value && current.value.file_role !== "NONE",
);
const base = () => `/teams/${current.value.id}`;
const assignableRoles = computed(() =>
  current.value?.file_role === "OWNER"
    ? ["ADMIN", "EDITOR", "VIEWER", "NONE"]
    : ["EDITOR", "VIEWER", "NONE"],
);
function canManageMember(member: any) {
  return (
    admin.value &&
    (current.value?.file_role === "OWNER" ||
      !["OWNER", "ADMIN"].includes(member.file_role))
  );
}
async function updateMember(
  member: any,
  change: { role?: string; contributor?: boolean },
) {
  await act(() =>
    api.put(`${base()}/members/${member.user_id}`, {
      role: change.role ?? member.file_role,
      contributor: change.contributor ?? member.contributor,
    }),
  );
}
async function removeMember(member: any) {
  const path = `${base()}/members/${member.user_id}`;
  try {
    await ElMessageBox.confirm(
      t("teamManagement.removeConfirm", { name: member.nickname }),
      t("next.removeMember"),
    );
  } catch {
    return;
  }
  await act(() => api.delete(path));
}
const aclRows = ref<any[]>([]),
  loadedAcl = ref(""),
  aclBusy = ref(false),
  aclFailure = ref("");
let aclGeneration = 0;
watch(
  [() => current.value?.id, () => acl.value.entry],
  () => {
    aclGeneration++;
    aclRows.value = [];
    loadedAcl.value = "";
    aclFailure.value = "";
    aclBusy.value = false;
    acl.value.user = "";
    acl.value.permission = "INHERIT";
  },
  { flush: "sync" },
);
onUnmounted(() => aclGeneration++);
async function loadAcl() {
  const token = ++aclGeneration,
    entry = acl.value.entry;
  loadedAcl.value = "";
  if (!entry || !admin.value) return;
  aclBusy.value = true;
  try {
    const { data } = await api.get(
      `${base()}/entries/${encodeURIComponent(entry)}/acl`,
    );
    if (token !== aclGeneration) return;
    aclRows.value = data;
    loadedAcl.value = entry;
    aclFailure.value = "";
    selectAclMember();
  } catch (error) {
    if (token === aclGeneration) aclFailure.value = errorText(error);
  } finally {
    if (token === aclGeneration) aclBusy.value = false;
  }
}
function selectAcl(entry: string) {
  acl.value.entry = entry;
  tab.value = "members";
  void loadAcl();
}
function selectAclMember() {
  acl.value.permission =
    aclRows.value.find((row) => row.user_id === acl.value.user)?.permission ||
    "INHERIT";
}
async function saveAcl() {
  await act(async () => {
    await api.put(
      `${base()}/entries/${encodeURIComponent(acl.value.entry)}/acl`,
      {
        userId: acl.value.user,
        permission: acl.value.permission,
      },
    );
    await loadAcl();
  });
}
let loadGeneration = 0;
onUnmounted(() => loadGeneration++);
async function refreshTeams() {
  workspaces.value = (await api.get("/teams")).data;
  if (current.value)
    current.value =
      workspaces.value.find((w) => w.id === current.value.id) || null;
}
async function load() {
  const generation = ++loadGeneration;
  if (!current.value) return;
  const team = current.value.id;
  const path = `/teams/${team}`;
  const parentId = parent.value;
  const requestedPage = page.value;
  const isCurrent = () =>
    generation === loadGeneration && current.value?.id === team;
  const [m, c] = await Promise.all([
    api.get(`${path}/members`),
    api.get(`${path}/contributions`),
  ]);
  if (!isCurrent()) return;
  members.value = m.data;
  contributions.value = c.data;
  if (readable.value) {
    const result = (
      await api.get(`${path}/files`, {
        params: { parentId, page: requestedPage },
      })
    ).data;
    if (!isCurrent()) return;
    files.value = result.items;
    parent.value = result.parentId;
    crumbs.value = result.breadcrumbs;
    total.value = result.total;
  } else {
    files.value = [];
    crumbs.value = [];
  }
  if (admin.value) {
    const [a, r, targets, jobs] = await Promise.all([
      api.get(`${path}/audit`),
      api.get(`${path}/trash`),
      api.get(`${path}/storage/targets`),
      api.get(`${path}/storage/jobs`),
    ]);
    if (!isCurrent()) return;
    audits.value = a.data;
    trash.value = r.data;
    storageTargets.value = targets.data;
    storageJobs.value = jobs.data;
  }
}
async function act(fn: () => Promise<unknown> = async () => {}) {
  busy.value = true;
  try {
    await fn();
    await refreshTeams();
    await load();
    failure.value = "";
  } catch (e) {
    failure.value = errorText(e);
  } finally {
    busy.value = false;
  }
}
onMounted(() =>
  act(async () => {
    await user.load();
    await refreshTeams();
  }),
);
async function choose(team: any) {
  if (uploadBusy.value) return;
  current.value = team;
  files.value = [];
  members.value = [];
  contributions.value = [];
  audits.value = [];
  trash.value = [];
  storageTargets.value = [];
  storageJobs.value = [];
  selectedFiles.value = [];
  storageTarget.value = "";
  moving.value = null;
  acl.value = { entry: "", user: "", permission: "READ" };
  parent.value = team.root_id;
  page.value = 1;
  tab.value = "files";
  heartbeat.value = "";
  await act(load);
}
async function navigate(id: string) {
  parent.value = id;
  page.value = 1;
  await act(load);
}
async function createFolder() {
  let name: string;
  try {
    name = (
      await ElMessageBox.prompt(t("next.folderName"), t("next.newFolder"))
    ).value;
  } catch {
    return;
  }
  await act(() =>
    api.post(`${base()}/folders`, { parentId: parent.value, name }),
  );
}
async function edit(entry: any) {
  let name: string;
  try {
    name = (
      await ElMessageBox.prompt(t("next.name"), t("next.rename"), {
        inputValue: entry.name,
      })
    ).value;
  } catch {
    return;
  }
  await act(() => api.patch(`${base()}/entries/${entry.id}`, { name }));
}
async function moveTo(target: string) {
  const entry = moving.value;
  moving.value = null;
  await act(() =>
    api.patch(`${base()}/entries/${entry.id}`, { parentId: target }),
  );
}
async function storageTask(kind: string) {
  const ids = selectedFiles.value
    .filter((f) => f.kind === "FILE")
    .map((f) => f.id);
  if (!ids.length || !storageTarget.value) return;
  try {
    await ElMessageBox.confirm(
      t("next.storageConfirm"),
      t("next.storageTasks"),
    );
  } catch {
    return;
  }
  await act(() =>
    api.post(`${base()}/storage/jobs`, {
      target: storageTarget.value,
      kind,
      fileIds: ids,
    }),
  );
}
async function quota() {
  let value: string;
  try {
    value = (
      await ElMessageBox.prompt(t("next.budgetGiB"), t("next.teamQuota"), {
        inputValue: String(current.value.storage_quota / 1024 ** 3),
      })
    ).value;
  } catch {
    return;
  }
  const bytes = Math.floor(Number(value) * 1024 ** 3);
  if (!Number.isSafeInteger(bytes) || bytes < 1024) {
    ElMessage.error(t("next.invalidQuota"));
    return;
  }
  await act(() => api.put(`${base()}/quota`, { bytes }));
}
async function remove(entry: any) {
  try {
    await ElMessageBox.confirm(t("next.trashConfirm"), t("next.delete"));
  } catch {
    return;
  }
  await act(() => api.delete(`${base()}/entries/${entry.id}`));
}
function download(entry: any) {
  const a = document.createElement("a");
  a.href = `/api${base()}/files/${entry.id}/download`;
  a.referrerPolicy = "no-referrer";
  a.click();
}
async function picked(event: Event) {
  const input = event.target as HTMLInputElement,
    file = input.files?.[0];
  input.value = "";
  if (!file || uploadBusy.value) return;
  uploadBusy.value = true;
  pause.value = false;
  try {
    const digest = sha256.create();
    for (let offset = 0; offset < file.size; offset += 4 * 1024 * 1024)
      digest.update(
        new Uint8Array(
          await file.slice(offset, offset + 4 * 1024 * 1024).arrayBuffer(),
        ),
      );
    const hash = Array.from(digest.digest())
      .map((b) => b.toString(16).padStart(2, "0"))
      .join("");
    const existing = files.value.find(
      (e) => e.kind === "FILE" && e.name === file.name,
    );
    if (existing)
      await ElMessageBox.confirm(t("next.overwriteConfirm"), t("next.upload"));
    const session = (
      await api.post(`${base()}/uploads/init`, {
        folderId: parent.value,
        name: file.name,
        size: file.size,
        sha256: hash,
        targetId: existing?.id || null,
        expectedRevision: existing?.revision || null,
      })
    ).data;
    if (session.status === "COMPLETE") {
      await load();
      return;
    }
    pending.value = { id: session.id, file, team: current.value.id };
  } catch (e) {
    if (e !== "cancel" && e !== "close") ElMessage.error(errorText(e));
  } finally {
    uploadBusy.value = false;
  }
  if (pending.value) await resumeUpload();
}
async function resumeUpload() {
  if (!pending.value || uploadBusy.value) return;
  const p = pending.value;
  uploadBusy.value = true;
  pause.value = false;
  try {
    const path = `/teams/${p.team}/uploads/${p.id}`;
    const session = (await api.get(path)).data;
    const done = new Set<number>(session.uploadedParts);
    for (let part = 0; part < session.partCount; part++) {
      if (pause.value) break;
      if (!done.has(part))
        await api.put(
          `${path}/parts/${part}`,
          p.file.slice(
            part * session.partSize,
            Math.min(p.file.size, (part + 1) * session.partSize),
          ),
          {
            timeout: 180000,
            headers: { "Content-Type": "application/octet-stream" },
          },
        );
      done.add(part);
      progress.value = Math.round((done.size / session.partCount) * 100);
    }
    if (!pause.value) {
      await api.post(`${path}/complete`, undefined, { timeout: 180000 });
      pending.value = null;
      progress.value = 0;
      await refreshTeams();
      await load();
    }
  } catch (e) {
    ElMessage.error(errorText(e));
  } finally {
    uploadBusy.value = false;
  }
}
async function register() {
  await act(async () => {
    const r = (
      await api.post(`${base()}/contributions`, {
        name: node.value.name,
        endpoint: node.value.endpoint,
        nodeToken: node.value.nodeToken,
        capacityBytes: Math.floor(node.value.capacityGiB * 1024 ** 3),
      })
    ).data;
    heartbeat.value = `${r.id}\n${r.heartbeatToken}`;
    node.value.nodeToken = "";
  });
}
</script>
<template>
  <main class="next-page" v-loading="busy">
    <header class="next-toolbar">
      <router-link to="/">← {{ t("next.back") }}</router-link
      ><router-link to="/resilience">{{ t("next.resilience") }}</router-link
      ><LanguageSwitcher /><ThemeSwitcher />
    </header>
    <h1>{{ t("next.teams") }}</h1>
    <p class="muted">{{ t("next.teamIntro") }}</p>
    <el-alert v-if="failure" :title="failure" type="error" :closable="false" />
    <section class="next-panel">
      <div class="next-toolbar">
        <el-input
          v-model="newTeam.name"
          :placeholder="t('next.teamName')"
        /><el-input-number
          v-model="newTeam.quotaGiB"
          :min="0.001"
          :max="100000"
          :precision="3"
        /><span>GiB</span
        ><el-button
          :disabled="!newTeam.name"
          type="primary"
          @click="
            act(() =>
              api.post('/teams', {
                name: newTeam.name,
                quota: Math.floor(newTeam.quotaGiB * 1024 ** 3),
              }),
            )
          "
          >{{ t("next.createTeam") }}</el-button
        >
      </div>
      <p class="muted">{{ t("next.teamQuotaNotice") }}</p>
      <div class="next-toolbar">
        <el-input
          v-model="invitation"
          :placeholder="t('next.invitationToken')"
        /><el-button
          :disabled="!invitation"
          @click="
            act(async () => {
              await api.post('/teams/accept', { token: invitation });
              invitation = '';
            })
          "
          >{{ t("next.joinTeam") }}</el-button
        >
      </div>
      <div class="next-toolbar">
        <el-button
          v-for="team in workspaces"
          :key="team.id"
          :type="current?.id === team.id ? 'primary' : 'default'"
          :disabled="uploadBusy"
          @click="choose(team)"
          >{{ team.name }} · {{ t(`next.${team.file_role}`) }}</el-button
        >
      </div>
    </section>
    <section v-if="current" class="next-panel">
      <div class="next-toolbar">
        <h2>{{ current.name }}</h2>
        <span
          >{{ bytes(current.storage_used) }} /
          {{ bytes(current.storage_quota) }}</span
        ><el-button v-if="current.file_role === 'OWNER'" @click="quota">{{
          t("next.teamQuota")
        }}</el-button
        ><el-button @click="act(load)">{{ t("next.refresh") }}</el-button>
      </div>
      <el-tabs v-model="tab">
        <el-tab-pane v-if="readable" :label="t('next.files')" name="files">
          <el-breadcrumb separator="/"
            ><el-breadcrumb-item v-for="c in crumbs" :key="c.id"
              ><button class="crumb-button" @click="navigate(c.id)">
                {{ c.name }}
              </button></el-breadcrumb-item
            ></el-breadcrumb
          >
          <div class="next-toolbar" v-if="editor">
            <el-button @click="createFolder">{{
              t("next.newFolder")
            }}</el-button
            ><label class="el-button"
              ><span>{{ t("next.upload") }}</span
              ><input
                type="file"
                :disabled="uploadBusy || !!pending"
                style="display: none"
                @change="picked"
            /></label>
          </div>
          <div v-if="pending" class="next-toolbar">
            <span>{{ pending.file.name }} · {{ progress }}%</span
            ><el-button v-if="uploadBusy" @click="pause = true">{{
              t("next.pause")
            }}</el-button
            ><el-button v-else @click="resumeUpload">{{
              t("next.resume")
            }}</el-button
            ><el-button
              v-if="!uploadBusy"
              type="danger"
              @click="
                act(async () => {
                  await api.delete(
                    `/teams/${pending!.team}/uploads/${pending!.id}`,
                  );
                  pending = null;
                })
              "
              >{{ t("next.cancel") }}</el-button
            >
          </div>
          <div v-if="admin" class="next-toolbar">
            <el-select
              v-model="storageTarget"
              :placeholder="t('next.candidates')"
              style="width: 240px"
              ><el-option
                v-for="n in storageTargets"
                :key="n.backend"
                :value="n.backend"
                :label="n.name + ' · ' + n.state" /></el-select
            ><el-button
              :disabled="!storageTarget || !selectedFiles.length"
              @click="storageTask('MIGRATE')"
              >{{ t("next.migrate") }}</el-button
            ><el-button
              :disabled="!storageTarget || !selectedFiles.length"
              @click="storageTask('REPLICATE')"
              >{{ t("next.replicate") }}</el-button
            >
          </div>
          <el-table
            :data="files"
            @selection-change="(rows: any[]) => (selectedFiles = rows)"
            ><el-table-column
              v-if="admin"
              type="selection"
              width="44"
            /><el-table-column :label="t('next.name')" min-width="200"
              ><template #default="{ row }"
                ><el-button
                  v-if="row.kind === 'FOLDER'"
                  link
                  @click="navigate(row.id)"
                  >📁 {{ row.name }}</el-button
                ><span v-else>{{ row.name }}</span></template
              ></el-table-column
            ><el-table-column :label="t('next.size')" width="120"
              ><template #default="{ row }">{{
                bytes(row.size)
              }}</template></el-table-column
            ><el-table-column :label="t('next.actions')" min-width="220"
              ><template #default="{ row }"
                ><el-button
                  v-if="row.kind === 'FILE'"
                  link
                  @click="download(row)"
                  >{{ t("next.download") }}</el-button
                ><el-button v-if="editor" link @click="moving = row">{{
                  t("next.move")
                }}</el-button
                ><el-button v-if="editor" link @click="edit(row)">{{
                  t("next.rename")
                }}</el-button
                ><el-button
                  v-if="editor"
                  link
                  type="danger"
                  @click="remove(row)"
                  >{{ t("next.delete") }}</el-button
                ><el-button v-if="admin" link @click="selectAcl(row.id)">{{
                  t("next.permissions")
                }}</el-button></template
              ></el-table-column
            ></el-table
          >
          <el-pagination
            v-if="total > 100"
            v-model:current-page="page"
            :page-size="100"
            :total="total"
            layout="prev,pager,next"
            @current-change="act(load)"
          />
        </el-tab-pane>
        <el-tab-pane :label="t('next.members')" name="members">
          <TeamInvitations
            v-if="admin"
            :team-id="current.id"
            :owner="current.file_role === 'OWNER'"
            @changed="act()"
          />
          <el-table :data="members" class="team-members"
            ><el-table-column
              prop="nickname"
              :label="t('next.name')"
            /><el-table-column :label="t('next.role')"
              ><template #default="{ row }"
                ><el-select
                  v-if="canManageMember(row) && row.file_role !== 'OWNER'"
                  :model-value="row.file_role"
                  @change="
                    (value: string) => updateMember(row, { role: value })
                  "
                  ><el-option
                    v-for="r in assignableRoles"
                    :key="r"
                    :value="r"
                    :label="t(`next.${r}`)" /></el-select
                ><span v-else>{{ t(`next.${row.file_role}`) }}</span></template
              ></el-table-column
            ><el-table-column :label="t('next.contributor')"
              ><template #default="{ row }"
                ><el-switch
                  :model-value="row.contributor"
                  :disabled="busy || !canManageMember(row)"
                  @change="
                    (value: boolean | string | number) =>
                      updateMember(row, { contributor: Boolean(value) })
                  " /></template></el-table-column
            ><el-table-column v-if="admin" :label="t('next.actions')"
              ><template #default="{ row }"
                ><el-button
                  v-if="canManageMember(row) && row.file_role !== 'OWNER'"
                  link
                  type="danger"
                  @click="removeMember(row)"
                  >{{ t("next.removeMember") }}</el-button
                ></template
              ></el-table-column
            ></el-table
          >
          <el-form
            v-if="admin"
            label-position="top"
            class="next-form team-acl-form"
            ><el-form-item :label="t('next.entryId')"
              ><el-input v-model="acl.entry" @change="loadAcl" /></el-form-item
            ><el-form-item :label="t('next.member')"
              ><el-select v-model="acl.user" @change="selectAclMember"
                ><el-option
                  v-for="m in members"
                  :key="m.user_id"
                  :value="m.user_id"
                  :label="m.nickname" /></el-select></el-form-item
            ><el-form-item :label="t('next.permissions')"
              ><el-select v-model="acl.permission"
                ><el-option
                  v-for="r in ['READ', 'WRITE', 'DENY', 'INHERIT']"
                  :key="r"
                  :value="r"
                  :label="t(`next.${r}`)" /></el-select></el-form-item
            ><el-form-item
              ><el-button
                :disabled="
                  !acl.entry || !acl.user || aclBusy || loadedAcl !== acl.entry
                "
                @click="saveAcl"
                >{{ t("next.save") }}</el-button
              ></el-form-item
            ></el-form
          >
          <template v-if="admin">
            <p class="muted">{{ t("teamManagement.aclNotice") }}</p>
            <el-button :disabled="!acl.entry || aclBusy" @click="loadAcl">{{
              t("teamManagement.loadAcl")
            }}</el-button>
            <el-alert
              v-if="aclFailure"
              :title="aclFailure"
              type="error"
              :closable="false"
            />
            <el-table
              v-if="loadedAcl === acl.entry && loadedAcl"
              :data="aclRows"
              :empty-text="t('teamManagement.noAcl')"
              v-loading="aclBusy"
              @row-click="
                (row: any) => {
                  acl.user = row.user_id;
                  selectAclMember();
                }
              "
            >
              <el-table-column :label="t('next.member')" min-width="180"
                ><template #default="{ row }">{{
                  members.find((m) => m.user_id === row.user_id)?.nickname ||
                  row.user_id
                }}</template></el-table-column
              >
              <el-table-column :label="t('next.permissions')" min-width="160"
                ><template #default="{ row }">{{
                  t(`next.${row.permission}`)
                }}</template></el-table-column
              >
            </el-table>
          </template>
        </el-tab-pane>
        <el-tab-pane :label="t('next.contributions')" name="nodes"
          ><p>{{ t("next.contributionNotice") }}</p>
          <el-form
            v-if="current.contributor"
            class="next-form"
            label-position="top"
            ><el-form-item :label="t('next.name')"
              ><el-input v-model="node.name" /></el-form-item
            ><el-form-item :label="t('next.endpoint')"
              ><el-input v-model="node.endpoint" /></el-form-item
            ><el-form-item :label="t('next.nodeToken')"
              ><el-input
                v-model="node.nodeToken"
                type="password"
                autocomplete="off" /></el-form-item
            ><el-form-item :label="t('next.budgetGiB')"
              ><el-input-number
                v-model="node.capacityGiB"
                :min="0.001"
                :precision="3" /></el-form-item
            ><el-form-item
              ><el-button @click="register">{{
                t("next.registerNode")
              }}</el-button></el-form-item
            ></el-form
          >
          <el-alert
            v-if="heartbeat"
            :title="t('next.heartbeatSecret')"
            :description="heartbeat"
            type="success"
            :closable="false"
          />
          <el-table :data="contributions"
            ><el-table-column
              prop="name"
              :label="t('next.name')"
            /><el-table-column :label="t('next.size')"
              ><template #default="{ row }">{{
                bytes(row.capacity_bytes)
              }}</template></el-table-column
            ><el-table-column :label="t('next.status')"
              ><template #default="{ row }">{{
                t(`next.${row.status}`)
              }}</template></el-table-column
            ><el-table-column
              prop="failure_domain"
              :label="t('next.failureDomain')"
            /><el-table-column :label="t('next.actions')"
              ><template #default="{ row }"
                ><el-button
                  v-if="admin && row.status === 'PENDING'"
                  link
                  @click="
                    act(() =>
                      api.post(`${base()}/contributions/${row.id}/review`),
                    )
                  "
                  >{{ t("next.review") }}</el-button
                ><el-button
                  v-if="admin || row.contributor_id === user.user?.id"
                  link
                  type="danger"
                  @click="
                    act(() =>
                      api.post(`${base()}/contributions/${row.id}/stop`),
                    )
                  "
                  >{{ t("next.stopContribution") }}</el-button
                ></template
              ></el-table-column
            ></el-table
          >
        </el-tab-pane>
        <el-tab-pane
          v-if="admin"
          :label="t('next.timeMachine')"
          name="snapshots"
          ><TimeMachine
            v-if="tab === 'snapshots'"
            :key="current.id + parent"
            :folder-id="parent"
            :base="`${base()}/resilience`"
        /></el-tab-pane>
        <el-tab-pane v-if="admin" :label="t('next.trash')" name="trash"
          ><el-table :data="trash"
            ><el-table-column
              prop="name"
              :label="t('next.name')"
            /><el-table-column :label="t('next.actions')"
              ><template #default="{ row }"
                ><el-button
                  link
                  @click="
                    act(() => api.post(`${base()}/trash/${row.id}/restore`))
                  "
                  >{{ t("next.restore") }}</el-button
                ><el-button
                  link
                  type="danger"
                  @click="
                    act(async () => {
                      await ElMessageBox.confirm(
                        t('next.purgeConfirm'),
                        t('next.delete'),
                      );
                      await api.delete(`${base()}/trash/${row.id}`);
                    })
                  "
                  >{{ t("next.purge") }}</el-button
                ></template
              ></el-table-column
            ></el-table
          ></el-tab-pane
        >
        <el-tab-pane v-if="admin" :label="t('next.storageTasks')" name="storage"
          ><el-table :data="storageJobs"
            ><el-table-column
              prop="id"
              :label="t('next.taskId')"
              min-width="240"
            /><el-table-column
              prop="kind"
              :label="t('next.type')"
            /><el-table-column
              prop="status"
              :label="t('next.status')"
            /><el-table-column
              prop="error"
              :label="t('next.result')"
            /><el-table-column :label="t('next.actions')"
              ><template #default="{ row }"
                ><el-button
                  v-if="
                    ['FAILED', 'INTERRUPTED', 'CANCELLED'].includes(row.status)
                  "
                  link
                  @click="
                    act(() =>
                      api.post(`${base()}/storage/jobs/${row.id}/retry`),
                    )
                  "
                  >{{ t("next.retry") }}</el-button
                ><el-button
                  v-if="['QUEUED', 'RUNNING'].includes(row.status)"
                  link
                  @click="
                    act(() => api.delete(`${base()}/storage/jobs/${row.id}`))
                  "
                  >{{ t("next.cancel") }}</el-button
                ></template
              ></el-table-column
            ></el-table
          ></el-tab-pane
        ><el-tab-pane v-if="admin" :label="t('next.audit')" name="audit"
          ><el-table :data="audits"
            ><el-table-column :label="t('next.created')"
              ><template #default="{ row }">{{
                date(row.created_at)
              }}</template></el-table-column
            ><el-table-column
              prop="action"
              :label="t('next.actions')" /><el-table-column
              prop="actor_id"
              :label="t('next.member')" /><el-table-column
              prop="target_id"
              :label="t('next.object')" /></el-table
        ></el-tab-pane>
      </el-tabs>
    </section>
    <FolderPicker
      v-if="current"
      :open="!!moving"
      :team-id="current.id"
      :root-id="current.root_id"
      :title="t('next.move')"
      :choose-label="t('next.choose')"
      @close="moving = null"
      @choose="moveTo"
    />
  </main>
</template>
