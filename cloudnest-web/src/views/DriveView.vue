<script setup lang="ts">
import { computed, onMounted, onBeforeUnmount, ref, watch } from "vue";
import { useRouter } from "vue-router";
import { ElMessage, ElMessageBox } from "element-plus";
import {
  Cloudy,
  FolderOpened,
  Delete,
  Share,
  Search,
  Upload,
  Plus,
  Grid,
  List,
  Refresh,
  ArrowRight,
  Download,
  View,
  Edit,
  MoreFilled,
  Rank,
  User,
  SwitchButton,
  Document,
  Picture,
  Files,
  Link,
} from "@element-plus/icons-vue";
import {
  api,
  bytes,
  date,
  errorText,
  type Entry,
  type Page,
  typePath,
} from "../api";
import { useUser } from "../stores/user";
import { useUploads } from "../stores/uploads";
import FileMark from "../components/FileMark.vue";
import PreviewDialog from "../components/PreviewDialog.vue";
import UploadPanel from "../components/UploadPanel.vue";
import FolderPicker from "../components/FolderPicker.vue";
import AccountSecurity from "../components/AccountSecurity.vue";
import VersionHistory from "../components/VersionHistory.vue";
import Thumbnail from "../components/Thumbnail.vue";
import ShareSettings from "../components/ShareSettings.vue";
import DownloadCenter from "../components/DownloadCenter.vue";
const editingShare = ref<{
    id: string;
    expiresAt: number;
    allowDownload: boolean;
  } | null>(null),
  downloadRevision = ref(0);
const historyFile = ref<Entry | null>(null),
  shareFilter = ref("all");
const visibleShares = computed(() =>
  shares.value.filter(
    (s) =>
      shareFilter.value === "all" ||
      (shareFilter.value === "active"
        ? !s.revoked && s.expiresAt > Date.now()
        : shareFilter.value === "revoked"
          ? s.revoked
          : !s.revoked && s.expiresAt <= Date.now()),
  ),
);
function editShare(id: string) {
  editingShare.value = shares.value.find((s) => s.id === id) || null;
}
const collection = ref("all"),
  fileType = ref("all"),
  minMiB = ref(0),
  rangeDays = ref(0),
  folderInput = ref<HTMLInputElement>(),
  conflictPolicy = ref("keep"),
  batchMoving = ref(false),
  batchBusy = ref(false);
const router = useRouter(),
  store = useUser(),
  uploads = useUploads();
const section = ref("drive"),
  current = ref(""),
  items = ref<Entry[]>([]),
  crumbs = ref<{ id: string; name: string }[]>([]),
  query = ref(""),
  scope = ref("all"),
  sort = ref("name"),
  direction = ref("asc"),
  page = ref(1),
  total = ref(0),
  grid = ref(localStorage.getItem("cloudnest-grid") === "true"),
  busy = ref(false),
  failure = ref(""),
  selected = ref<Entry[]>([]),
  preview = ref<Entry | null>(null),
  moving = ref<Entry | null>(null),
  fileInput = ref<HTMLInputElement>(),
  drop = ref(false);
const undoIds = ref<string[]>([]);
let undoTimer: ReturnType<typeof setTimeout> | undefined;
function rememberDelete(ids: string[]) {
  undoIds.value = ids;
  clearTimeout(undoTimer);
  undoTimer = setTimeout(() => (undoIds.value = []), 10000);
}
async function undoDelete() {
  try {
    const { data } = await api.post("/entries/batch", {
      ids: undoIds.value,
      action: "restore",
    });
    undoIds.value = [];
    await load();
    ElMessage.info(`已恢复 ${data.succeeded} 项，失败 ${data.failed} 项`);
  } catch (e) {
    ElMessage.error(errorText(e));
  }
}
const shares = ref<
  {
    id: string;
    name: string;
    expiresAt: number;
    revoked: boolean;
    hasCode: boolean;
    allowDownload: boolean;
  }[]
>([]);
const stats = ref({ files: 0, folders: 0, images: 0, documents: 0 }),
  profile = ref(false),
  nickname = ref(""),
  profileBusy = ref(false),
  packaging = ref(false);
const sharing = ref<Entry | null>(null),
  shareHours = ref(168),
  shareCode = ref(""),
  shareDownload = ref(true),
  shareBusy = ref(false),
  shareUrl = ref("");
const titles: Record<string, string> = {
  drive: "我的文件",
  trash: "回收站",
  shares: "我的分享",
};
const storagePercent = computed(() =>
  store.user
    ? Math.min(
        100,
        Math.round(
          ((store.user.used + store.user.reserved) / store.user.quota) * 100,
        ),
      )
    : 0,
);
let requestId = 0,
  searchTimer: ReturnType<typeof setTimeout> | undefined;
async function load() {
  if (!current.value && store.user) current.value = store.user.rootId;
  const thisRequest = ++requestId;
  busy.value = true;
  failure.value = "";
  selected.value = [];
  try {
    if (section.value === "drive") {
      const [list, path] = await Promise.all([
        api.get<Page>("/entries", {
          params: {
            parentId: current.value,
            q: query.value,
            scope: scope.value,
            sort: sort.value,
            direction: direction.value,
            page: page.value,
            pageSize: 30,
            collection: collection.value,
            type: fileType.value,
            minSize: minMiB.value * 1024 * 1024,
            after: rangeDays.value
              ? Date.now() - rangeDays.value * 86400000
              : 0,
          },
        }),
        api.get(`/folders/${current.value}/breadcrumbs`),
      ]);
      if (thisRequest !== requestId) return;
      items.value = list.data.items;
      total.value = list.data.total;
      crumbs.value = path.data;
    } else if (section.value === "trash") {
      const r = await api.get<Entry[]>("/trash");
      if (thisRequest !== requestId) return;
      items.value = r.data;
      total.value = r.data.length;
    } else {
      const r = await api.get("/shares");
      if (thisRequest !== requestId) return;
      shares.value = r.data;
    }
    await Promise.all([
      store.load(),
      api.get("/stats").then((r) => (stats.value = r.data)),
    ]);
  } catch (e) {
    if (thisRequest === requestId) failure.value = errorText(e);
  } finally {
    if (thisRequest === requestId) busy.value = false;
  }
}
function navigate(id: string) {
  collection.value = "all";
  current.value = id;
  query.value = "";
  page.value = 1;
  section.value = "drive";
  load();
}
function tab(value: string) {
  section.value = value;
  query.value = "";
  page.value = 1;
  load();
}
watch([query, scope, collection, fileType, minMiB, rangeDays], () => {
  clearTimeout(searchTimer);
  searchTimer = setTimeout(() => {
    page.value = 1;
    load();
  }, 300);
});
watch([sort, direction], () => {
  page.value = 1;
  load();
});
watch(grid, (v) => localStorage.setItem("cloudnest-grid", String(v)));
watch(
  () => uploads.revision,
  () => {
    if (store.user) load();
  },
);
onBeforeUnmount(() => {
  clearTimeout(searchTimer);
  clearTimeout(undoTimer);
});
onMounted(async () => {
  try {
    if (!store.user) await store.load();
    current.value = store.user!.rootId;
    await load();
    await uploads.restore();
  } catch (e) {
    failure.value = errorText(e);
  }
});
async function createFolder() {
  try {
    const { value } = await ElMessageBox.prompt(
      "在当前目录创建一个新文件夹",
      "新建文件夹",
      {
        inputPlaceholder: "文件夹名称",
        confirmButtonText: "创建",
        cancelButtonText: "取消",
        inputValidator: (v) => !!v?.trim() || "请输入名称",
      },
    );
    await api.post("/folders", { parentId: current.value, name: value });
    ElMessage.success("文件夹已创建");
    await load();
  } catch (e) {
    if (e !== "cancel" && e !== "close") ElMessage.error(errorText(e));
  }
}
async function rename(e: Entry) {
  try {
    const { value } = await ElMessageBox.prompt("输入新的名称", "重命名", {
      inputValue: e.name,
      confirmButtonText: "保存",
      cancelButtonText: "取消",
    });
    await api.patch(`/${typePath(e)}/${e.id}`, { name: value });
    ElMessage.success("名称已更新");
    await load();
  } catch (e) {
    if (e !== "cancel" && e !== "close") ElMessage.error(errorText(e));
  }
}
async function moveTo(id: string) {
  if (batchMoving.value) {
    await batch("move", id);
    batchMoving.value = false;
    return;
  }
  if (!moving.value) return;
  try {
    await api.patch(`/${typePath(moving.value)}/${moving.value.id}`, {
      parentId: id,
    });
    moving.value = null;
    ElMessage.success("已移动");
    await load();
  } catch (e) {
    ElMessage.error(errorText(e));
  }
}
async function remove(e: Entry) {
  try {
    await ElMessageBox.confirm(
      `“${e.name}”${e.kind === "FOLDER" ? "及其中的内容" : ""}将移入回收站，可以恢复。`,
      "移入回收站",
      {
        type: "warning",
        confirmButtonText: "移入回收站",
        cancelButtonText: "取消",
      },
    );
    await api.delete(`/${typePath(e)}/${e.id}`);
    rememberDelete([e.id]);
    await load();
    ElMessage.success("已移入回收站");
  } catch (e) {
    if (e !== "cancel" && e !== "close") ElMessage.error(errorText(e));
  }
}
async function restore(e: Entry) {
  try {
    const r = await api.post(`/trash/${typePath(e)}/${e.id}/restore`);
    ElMessage.success(
      r.data.restoredToRoot
        ? "原目录不可用，已恢复到根目录"
        : "已恢复" + (r.data.name !== e.name ? "，重名已自动处理" : ""),
    );
    await load();
  } catch (e) {
    ElMessage.error(errorText(e));
  }
}
async function purge(e: Entry) {
  try {
    await ElMessageBox.confirm(
      `将永久删除“${e.name}”${e.kind === "FOLDER" ? "及其所有子项（包括此前单独删除的内容）" : ""}。此操作无法恢复。`,
      "彻底删除",
      {
        type: "warning",
        confirmButtonText: "彻底删除",
        cancelButtonText: "取消",
      },
    );
    await api.delete(`/trash/${typePath(e)}/${e.id}`);
    await load();
    ElMessage.success("已彻底删除，物理文件将在后台清理");
  } catch (e) {
    if (e !== "cancel" && e !== "close") ElMessage.error(errorText(e));
  }
}
function open(e: Entry) {
  if (e.kind === "FOLDER") navigate(e.id);
  else {
    preview.value = e;
  }
}
async function addFiles(files: File[]) {
  if (!files.length) return;
  await uploads.add(files, current.value, conflictPolicy.value);
}
async function foldersSelected(e: Event) {
  const input = e.target as HTMLInputElement,
    files = Array.from(input.files || []);
  input.value = "";
  await importFolderFiles(files);
}
async function importFolderFiles(files: File[]) {
  if (files.length > 2000) {
    ElMessage.error("一次最多导入 2000 个文件，请分批选择");
    return;
  }
  const base = current.value,
    parents = new Map<string, string>();
  for (const file of files) {
    const path = file.webkitRelativePath.split("/");
    path.pop();
    const key = path.join("/");
    try {
      let parent = parents.get(key);
      if (!parent) {
        parent = (
          await api.post("/folders/resolve-path", {
            parentId: base,
            segments: path,
          })
        ).data.id as string;
        parents.set(key, parent);
      }
      uploads.enqueue([file], parent, conflictPolicy.value);
    } catch (err) {
      ElMessage.error(`${file.webkitRelativePath}: ${errorText(err)}`);
    }
  }
  await uploads.pump();
  await load();
}
async function favorite(e: Entry) {
  try {
    await api.patch(`/entries/${e.id}/favorite`, { favorite: !e.favorite });
    await load();
  } catch (err) {
    ElMessage.error(errorText(err));
  }
}
async function batch(action: string, parentId?: string) {
  const snapshot = [...selected.value];
  if (!snapshot.length) return;
  try {
    if (action === "trash" || action === "purge")
      await ElMessageBox.confirm(
        `将${action === "purge" ? "永久删除（无法恢复）" : "移入回收站"}所选 ${snapshot.length} 项及其子项。`,
        "批量操作",
        {
          confirmButtonText: "确认",
          cancelButtonText: "取消",
          type: "warning",
        },
      );
    batchBusy.value = true;
    const { data } = await api.post("/entries/batch", {
      ids: snapshot.map((e) => e.id),
      action,
      parentId,
    });
    await load();
    if (action === "trash")
      rememberDelete(
        data.results
          .filter((r: { status: string }) => r.status === "success")
          .map((r: { id: string }) => r.id),
      );
    const failures = data.results.filter(
      (r: { status: string }) => r.status === "failed",
    );
    if (failures.length)
      await ElMessageBox.alert(
        failures
          .map(
            (r: { id: string; message: string }) =>
              `${snapshot.find((e) => e.id === r.id)?.name || r.id}：${r.message}`,
          )
          .join("\n"),
        `${data.succeeded} 成功 / ${data.failed} 失败`,
      );
    else
      ElMessage.success(
        `已处理 ${data.succeeded} 项（包括由父目录覆盖的子项及已完成项）`,
      );
  } catch (err) {
    if (err !== "cancel" && err !== "close") ElMessage.error(errorText(err));
  } finally {
    batchBusy.value = false;
  }
}
function filesSelected(e: Event) {
  const input = e.target as HTMLInputElement;
  addFiles(Array.from(input.files || []));
  input.value = "";
}
async function dropped(e: DragEvent) {
  drop.value = false;
  if (section.value !== "drive") return;
  const entries = Array.from(e.dataTransfer?.items || [])
    .map((item) => item.webkitGetAsEntry?.())
    .filter((item): item is FileSystemEntry => !!item);
  if (!entries.some((entry) => entry.isDirectory)) {
    await addFiles(Array.from(e.dataTransfer?.files || []));
    return;
  }
  const files: File[] = [];
  async function walk(
    entry: FileSystemEntry,
    path: string,
    depth: number,
  ): Promise<void> {
    if (depth > 32 || files.length >= 2000)
      throw new Error("一次最多导入 2000 个文件，目录最多 32 层");
    const name = path + entry.name;
    if (entry.isFile) {
      const file = await new Promise<File>((resolve, reject) =>
        (entry as FileSystemFileEntry).file(resolve, reject),
      );
      Object.defineProperty(file, "webkitRelativePath", { value: name });
      files.push(file);
    } else {
      const reader = (entry as FileSystemDirectoryEntry).createReader();
      for (;;) {
        const children = await new Promise<FileSystemEntry[]>(
          (resolve, reject) => reader.readEntries(resolve, reject),
        );
        if (!children.length) break;
        for (const child of children) await walk(child, name + "/", depth + 1);
      }
    }
  }
  try {
    for (const entry of entries) await walk(entry, "", 0);
    await importFolderFiles(files);
  } catch (err) {
    ElMessage.error(errorText(err));
  }
}
function share(e: Entry) {
  sharing.value = e;
  shareUrl.value = "";
  shareCode.value = "";
  shareHours.value = 168;
  shareDownload.value = true;
}
async function createShare() {
  if (!sharing.value) return;
  shareBusy.value = true;
  try {
    const r = await api.post("/shares", {
      fileId: sharing.value.id,
      hours: shareHours.value,
      code: shareCode.value,
      allowDownload: shareDownload.value,
    });
    shareUrl.value = location.origin + r.data.path;
    ElMessage.success("分享已创建");
  } catch (e) {
    ElMessage.error(errorText(e));
  } finally {
    shareBusy.value = false;
  }
}
async function copyShare() {
  try {
    await navigator.clipboard.writeText(shareUrl.value);
    ElMessage.success("链接已复制");
  } catch {
    ElMessage.info("请手动选择并复制链接");
  }
}
async function revoke(id: string) {
  try {
    await ElMessageBox.confirm("撤销后，此链接将无法再访问。", "撤销分享", {
      confirmButtonText: "撤销",
      cancelButtonText: "取消",
    });
    await api.delete(`/shares/${id}`);
    await load();
  } catch (e) {
    if (e !== "cancel" && e !== "close") ElMessage.error(errorText(e));
  }
}
async function zip(entries = selected.value) {
  const ids = entries.map((e) => e.id);
  if (!ids.length) {
    ElMessage.info("请选择要下载的文件或文件夹");
    return;
  }
  packaging.value = true;
  try {
    await api.post("/downloads", { fileIds: ids });
    downloadRevision.value++;
    ElMessage.success("打包任务已创建，请在下载任务中查看进度");
    document
      .getElementById("download-tasks")
      ?.scrollIntoView({ behavior: "smooth", block: "start" });
  } catch (e) {
    ElMessage.error(errorText(e));
  } finally {
    packaging.value = false;
  }
}
async function editProfile() {
  profileBusy.value = true;
  try {
    await api.patch("/user/me", { nickname: nickname.value });
    await store.load();
    profile.value = false;
    ElMessage.success("资料已保存");
  } catch (e) {
    ElMessage.error(errorText(e));
  } finally {
    profileBusy.value = false;
  }
}
async function logout() {
  if (uploads.tasks.some((t) => t.active)) {
    ElMessage.info("请先暂停正在进行的上传");
    return;
  }
  try {
    await store.logout();
    uploads.clear();
    await router.push("/login");
  } catch (e) {
    ElMessage.error(errorText(e));
  }
}
function command(c: string, e: Entry) {
  if (c === "zip") zip([e]);
  if (c === "versions") historyFile.value = e;
  if (c === "favorite") favorite(e);
  if (c === "rename") rename(e);
  if (c === "move") moving.value = e;
  if (c === "delete") remove(e);
  if (c === "share") share(e);
}
function selectCard(e: Entry) {
  selected.value = selected.value.some((v) => v.id === e.id)
    ? selected.value.filter((v) => v.id !== e.id)
    : [...selected.value, e];
}
</script>
<template>
  <div class="app-shell">
    <aside class="sidebar">
      <router-link to="/" class="brand"
        ><span class="brand-symbol"
          ><el-icon><Cloudy /></el-icon></span
        >CloudNest</router-link
      >
      <div class="workspace-caption">个人工作空间</div>
      <nav>
        <button :class="{ active: section === 'drive' }" @click="tab('drive')">
          <el-icon><FolderOpened /></el-icon>我的文件<el-icon class="nav-arrow"
            ><ArrowRight
          /></el-icon></button
        ><button
          :class="{ active: section === 'shares' }"
          @click="tab('shares')"
        >
          <el-icon><Share /></el-icon>我的分享</button
        ><button :class="{ active: section === 'trash' }" @click="tab('trash')">
          <el-icon><Delete /></el-icon>回收站
        </button>
      </nav>
      <div class="sidebar-tip">
        <span class="tip-graphic"
          ><el-icon><Cloudy /></el-icon
        ></span>
        <h3>为你的数字生活<br />留一片自由空间</h3>
        <p>随时整理，安心收藏。</p>
      </div>
      <div class="storage-card">
        <div>
          <span>存储空间</span><strong>{{ storagePercent }}%</strong>
        </div>
        <el-progress
          :percentage="storagePercent"
          :show-text="false"
          :stroke-width="6"
          :color="storagePercent > 90 ? '#e99c42' : '#6679ef'"
        />
        <p>
          {{ bytes(store.user?.used || 0) }} /
          {{ bytes(store.user?.quota || 0) }}
        </p>
        <small v-if="store.user?.reserved"
          >上传预留 {{ bytes(store.user.reserved) }}</small
        ><small v-else>回收站文件仍占用存储空间</small>
      </div>
      <button
        class="sidebar-profile"
        @click="
          nickname = store.user?.nickname || '';
          profile = true;
        "
      >
        <span class="avatar">{{
          store.user?.nickname?.[0]?.toUpperCase() || "C"
        }}</span
        ><span
          ><strong>{{ store.user?.nickname }}</strong
          ><small>个人账户</small></span
        ><el-icon><MoreFilled /></el-icon>
      </button>
    </aside>
    <div class="main-shell">
      <header class="topbar">
        <div class="topbar-context">
          <span>工作空间</span><el-icon><ArrowRight /></el-icon
          ><strong>{{ titles[section] }}</strong>
        </div>
        <div class="topbar-right">
          <AccountSecurity />
          <span class="private-badge"><span />私人空间</span
          ><el-tooltip content="刷新"
            ><el-button
              circle
              :icon="Refresh"
              aria-label="刷新"
              @click="load" /></el-tooltip
          ><el-tooltip content="退出登录"
            ><el-button
              circle
              :icon="SwitchButton"
              aria-label="退出登录"
              @click="logout"
          /></el-tooltip>
        </div>
      </header>
      <main
        class="drive-main"
        :class="{ dragging: drop }"
        @dragover.prevent="drop = true"
        @dragleave.self="drop = false"
        @drop.prevent="dropped"
      >
        <div v-if="drop && section === 'drive'" class="drop-zone">
          <el-icon><Upload /></el-icon>
          <h2>松开以上传文件</h2>
        </div>
        <div class="page-heading">
          <div>
            <span class="eyebrow">{{
              section === "drive"
                ? "YOUR DIGITAL HOME"
                : section === "trash"
                  ? "A SECOND CHANCE"
                  : "SHARING MADE SIMPLE"
            }}</span>
            <h1>{{ titles[section] }}<span class="heading-dot">.</span></h1>
            <p>
              {{
                section === "drive"
                  ? "把重要的事物，放在触手可及的地方。"
                  : section === "trash"
                    ? "找回误删的文件，或清理空间，重新出发。"
                    : "每一次分享，都由你掌握。"
              }}
            </p>
          </div>
          <div v-if="section === 'drive'" class="heading-actions">
            <el-button @click="folderInput?.click()">上传文件夹</el-button>
            <el-button :icon="Plus" @click="createFolder">新建文件夹</el-button
            ><el-button
              type="primary"
              :icon="Upload"
              @click="fileInput?.click()"
              >上传文件</el-button
            >
          </div>
        </div>
        <template v-if="section === 'drive'"
          ><div class="stat-grid">
            <div class="stat-card">
              <span class="stat-icon all"
                ><el-icon><Files /></el-icon
              ></span>
              <div>
                <span>全部文件</span
                ><strong>{{ stats.files }}<small> 个文件</small></strong>
              </div>
              <span class="stat-spark">✧</span>
            </div>
            <div class="stat-card">
              <span class="stat-icon image"
                ><el-icon><Picture /></el-icon
              ></span>
              <div>
                <span>图片收藏</span
                ><strong>{{ stats.images }}<small> 个文件</small></strong>
              </div>
            </div>
            <div class="stat-card">
              <span class="stat-icon doc"
                ><el-icon><Document /></el-icon
              ></span>
              <div>
                <span>文档资料</span
                ><strong>{{ stats.documents }}<small> 个文件</small></strong>
              </div>
            </div>
            <div class="stat-card">
              <span class="stat-icon folders"
                ><el-icon><FolderOpened /></el-icon
              ></span>
              <div>
                <span>文件夹</span
                ><strong>{{ stats.folders }}<small> 个目录</small></strong>
              </div>
            </div>
          </div>
          <div style="display: flex; gap: 8px; flex-wrap: wrap; margin: 12px 0">
            <el-select
              v-model="collection"
              aria-label="文件集合"
              style="width: 140px"
              @change="
                sort =
                  collection === 'opened'
                    ? 'lastOpened'
                    : collection === 'uploaded'
                      ? 'createdAt'
                      : 'name';
                direction =
                  collection === 'all' || collection === 'favorites'
                    ? 'asc'
                    : 'desc';
              "
              ><el-option label="当前目录" value="all" /><el-option
                label="我的收藏"
                value="favorites" /><el-option
                label="最近上传"
                value="uploaded" /><el-option label="最近打开" value="opened"
            /></el-select>
            <el-select
              v-model="fileType"
              aria-label="文件类型"
              style="width: 120px"
              ><el-option label="全部类型" value="all" /><el-option
                label="图片"
                value="image" /><el-option
                label="文档"
                value="document" /><el-option label="文件夹" value="folder"
            /></el-select>
            <el-select
              v-model="minMiB"
              aria-label="文件大小"
              style="width: 140px"
              ><el-option label="全部大小" :value="0" /><el-option
                label="至少 10 MiB"
                :value="10" /><el-option label="至少 100 MiB" :value="100"
            /></el-select>
            <el-select
              v-model="rangeDays"
              aria-label="修改时间筛选"
              style="width: 140px"
              ><el-option label="全部时间" :value="0" /><el-option
                label="近 7 天修改"
                :value="7" /><el-option label="近 30 天修改" :value="30"
            /></el-select>
            <el-select
              v-model="conflictPolicy"
              aria-label="上传同名处理"
              style="width: 160px"
              ><el-option label="同名：保留两份" value="keep" /><el-option
                label="同名：跳过"
                value="skip"
            /></el-select>
          </div>
          <div class="files-toolbar">
            <el-breadcrumb separator="/"
              ><el-breadcrumb-item v-for="c in crumbs" :key="c.id"
                ><button class="crumb-button" @click="navigate(c.id)">
                  {{ c.name }}
                </button></el-breadcrumb-item
              ></el-breadcrumb
            >
            <div class="file-search">
              <el-input
                v-model="query"
                :prefix-icon="Search"
                placeholder="搜索文件名称"
                clearable
                aria-label="搜索文件名称"
              /><el-select
                v-model="scope"
                aria-label="搜索范围"
                style="width: 112px"
                ><el-option label="全部目录" value="all" /><el-option
                  label="当前目录"
                  value="current"
              /></el-select>
            </div>
          </div>
          <div class="list-toolbar">
            <div>
              <strong>{{ query ? "搜索结果" : "全部内容" }}</strong
              ><span class="muted">{{ total }} 项</span
              ><el-button
                v-if="section === 'drive'"
                :disabled="!selected.length"
                :icon="Download"
                size="small"
                :loading="packaging"
                @click="zip()"
                >打包下载 ({{ selected.length }})</el-button
              >
            </div>
            <div class="list-controls">
              <el-select
                v-model="sort"
                aria-label="排序方式"
                style="width: 118px"
                ><el-option label="按名称" value="name" /><el-option
                  label="按修改时间"
                  value="updatedAt" /><el-option
                  label="按大小"
                  value="size" /></el-select
              ><el-button
                :icon="Rank"
                aria-label="切换排序方向"
                @click="direction = direction === 'asc' ? 'desc' : 'asc'"
                >{{ direction === "asc" ? "升序" : "降序" }}</el-button
              >
              <div class="view-toggle">
                <button
                  :class="{ active: !grid }"
                  aria-label="列表视图"
                  @click="grid = false"
                >
                  <el-icon><List /></el-icon></button
                ><button
                  :class="{ active: grid }"
                  aria-label="网格视图"
                  @click="grid = true"
                >
                  <el-icon><Grid /></el-icon>
                </button>
              </div>
            </div></div
        ></template>
        <div v-else-if="section === 'trash'" class="trash-note">
          <el-icon><Delete /></el-icon
          >回收站中的文件继续占用配额。彻底删除后无法恢复。
        </div>
        <div
          v-if="section !== 'shares'"
          style="display: flex; gap: 8px; flex-wrap: wrap; margin: 12px 0"
        >
          <el-checkbox
            :model-value="items.length > 0 && selected.length === items.length"
            @change="
              selected = selected.length === items.length ? [] : [...items]
            "
            >全选当前页</el-checkbox
          >
          <span>已选 {{ selected.length }} 项</span
          ><el-button size="small" @click="selected = []">取消选择</el-button>
          <template v-if="selected.length"
            ><template v-if="section === 'drive'"
              ><el-button
                size="small"
                :disabled="batchBusy"
                @click="batchMoving = true"
                >批量移动</el-button
              ><el-button
                size="small"
                :disabled="batchBusy"
                @click="batch('trash')"
                >批量删除</el-button
              ></template
            ><template v-else
              ><el-button
                size="small"
                :disabled="batchBusy"
                @click="batch('restore')"
                >批量恢复</el-button
              ><el-button
                size="small"
                type="danger"
                :disabled="batchBusy"
                @click="batch('purge')"
                >批量彻底删除</el-button
              ></template
            ></template
          >
        </div>
        <el-button
          v-if="undoIds.length"
          type="primary"
          plain
          @click="undoDelete"
          >撤销刚才的删除（10 秒）</el-button
        >
        <el-alert
          v-if="failure"
          :title="failure"
          type="error"
          show-icon
          :closable="false"
          class="load-error"
        />
        <div class="file-content" v-loading="busy">
          <template v-if="section === 'shares'"
            ><el-select
              v-model="shareFilter"
              style="width: 150px"
              aria-label="分享状态筛选"
              ><el-option label="全部分享" value="all" /><el-option
                label="生效中"
                value="active" /><el-option
                label="已过期"
                value="expired" /><el-option
                label="已撤销"
                value="revoked" /></el-select
            ><el-table
              :data="visibleShares"
              empty-text="还没有分享，从文件菜单创建一个吧"
              ><el-table-column
                prop="name"
                label="文件名称"
                min-width="200"
              /><el-table-column label="权限" width="160"
                ><template #default="{ row }"
                  ><el-tag size="small">{{
                    row.allowDownload ? "预览和下载" : "仅预览"
                  }}</el-tag
                  ><span class="small-note">{{
                    row.hasCode ? "有提取码" : ""
                  }}</span></template
                ></el-table-column
              ><el-table-column label="有效期至" min-width="170"
                ><template #default="{ row }">{{
                  date(row.expiresAt)
                }}</template></el-table-column
              ><el-table-column label="状态" width="110"
                ><template #default="{ row }"
                  ><el-tag
                    :type="
                      row.revoked || row.expiresAt < Date.now()
                        ? 'info'
                        : 'success'
                    "
                    >{{
                      row.revoked
                        ? "已撤销"
                        : row.expiresAt < Date.now()
                          ? "已过期"
                          : "生效中"
                    }}</el-tag
                  ></template
                ></el-table-column
              ><el-table-column label="操作" width="170"
                ><template #default="{ row }"
                  ><el-button
                    v-if="!row.revoked && row.expiresAt > Date.now()"
                    text
                    @click="editShare(row.id)"
                    >修改设置</el-button
                  ><el-button
                    v-if="!row.revoked"
                    text
                    type="danger"
                    @click="revoke(row.id)"
                    >撤销</el-button
                  ></template
                ></el-table-column
              ></el-table
            >
            <p class="muted share-help">
              分享链接仅在创建时展示，请及时复制保存。遗失后可撤销并重新创建。
            </p></template
          ><template v-else-if="grid && section === 'drive'"
            ><div v-if="items.length" class="file-grid">
              <article
                v-for="e in items"
                :key="e.id"
                class="file-tile"
                :class="{ selected: selected.some((v) => v.id === e.id) }"
              >
                <el-checkbox
                  :model-value="selected.some((v) => v.id === e.id)"
                  :aria-label="'选择 ' + e.name"
                  @change="selectCard(e)"
                /><el-dropdown class="tile-more" @command="command($event, e)"
                  ><el-button
                    text
                    :icon="MoreFilled"
                    :aria-label="e.name + '的操作'"
                  /><template #dropdown
                    ><el-dropdown-menu
                      ><el-dropdown-item command="zip"
                        >压缩下载</el-dropdown-item
                      ><el-dropdown-item command="favorite">{{
                        e.favorite ? "取消收藏" : "收藏"
                      }}</el-dropdown-item
                      ><el-dropdown-item command="rename"
                        >重命名</el-dropdown-item
                      ><el-dropdown-item command="move">移动</el-dropdown-item
                      ><el-dropdown-item command="share">分享</el-dropdown-item
                      ><el-dropdown-item command="delete" divided
                        >移入回收站</el-dropdown-item
                      ><el-dropdown-item
                        v-if="e.kind === 'FILE'"
                        command="versions"
                        >版本历史</el-dropdown-item
                      ></el-dropdown-menu
                    ></template
                  ></el-dropdown
                ><button class="tile-open" @click="open(e)">
                  <Thumbnail :file="e" /><strong :title="e.name">{{
                    e.name
                  }}</strong
                  ><small>{{
                    e.kind === "FOLDER" ? "文件夹" : bytes(e.size)
                  }}</small>
                </button>
              </article>
            </div>
            <div v-else class="empty-state">
              <el-icon><FolderOpened /></el-icon>
              <h3>
                {{ query ? "没有找到匹配的文件" : "给这个空间，放一点灵感" }}
              </h3>
              <p>
                {{
                  query
                    ? "试试其他关键词，或切换搜索范围。"
                    : "上传文件或创建文件夹，开始整理你的数字生活。"
                }}
              </p>
              <el-button
                v-if="!query"
                type="primary"
                :icon="Upload"
                @click="fileInput?.click()"
                >上传第一个文件</el-button
              >
            </div></template
          ><el-table
            v-else
            :data="items"
            @selection-change="selected = $event"
            :empty-text="
              section === 'trash'
                ? '回收站是空的'
                : '这里还没有文件，点击上传开始吧'
            "
            row-key="id"
            ><el-table-column v-if="section !== 'shares'" width="45"
              ><template #default="{ row }"
                ><el-checkbox
                  :model-value="selected.some((e) => e.id === row.id)"
                  :aria-label="'选择 ' + row.name"
                  @change="selectCard(row)" /></template></el-table-column
            ><el-table-column label="名称" min-width="280"
              ><template #default="{ row }"
                ><button
                  class="entry-name"
                  :aria-label="(row.favorite ? '★ ' : '') + row.name"
                  @click="section === 'drive' && open(row)"
                >
                  <FileMark :kind="row.kind" :mime="row.mime" /><span
                    ><strong
                      >{{ row.favorite ? "★ " : "" }}{{ row.name }}</strong
                    ><small v-if="row.path">{{ row.path }}</small></span
                  ></button
                ><el-button
                  v-if="row.path"
                  text
                  size="small"
                  @click="navigate(row.parentId)"
                  >打开所在目录</el-button
                ></template
              ></el-table-column
            ><el-table-column label="大小" width="120"
              ><template #default="{ row }"
                ><span class="cell-muted">{{
                  row.kind === "FOLDER" ? "—" : bytes(row.size)
                }}</span></template
              ></el-table-column
            ><el-table-column
              :label="section === 'trash' ? '删除时间' : '修改时间'"
              min-width="170"
              ><template #default="{ row }"
                ><span class="cell-muted">{{
                  date(section === "trash" ? row.deletedAt : row.updatedAt)
                }}</span></template
              ></el-table-column
            ><el-table-column label="操作" width="166" align="right"
              ><template #default="{ row }"
                ><template v-if="section === 'trash'"
                  ><el-button text type="primary" @click="restore(row)"
                    >恢复</el-button
                  ><el-button text type="danger" @click="purge(row)"
                    >彻底删除</el-button
                  ></template
                ><template v-else
                  ><el-tooltip v-if="row.kind === 'FILE'" content="下载"
                    ><a
                      :href="`/api/files/${row.id}/download`"
                      class="row-icon"
                      :aria-label="'下载 ' + row.name"
                      ><el-icon><Download /></el-icon></a></el-tooltip
                  ><el-button
                    text
                    :icon="Share"
                    :aria-label="'分享 ' + row.name"
                    @click="share(row)"
                  /><el-dropdown @command="command($event, row)"
                    ><el-button
                      text
                      :icon="MoreFilled"
                      :aria-label="row.name + '的操作'"
                    /><template #dropdown
                      ><el-dropdown-menu
                        ><el-dropdown-item command="zip" :icon="Download"
                          >压缩下载</el-dropdown-item
                        ><el-dropdown-item command="favorite">{{
                          row.favorite ? "取消收藏" : "收藏"
                        }}</el-dropdown-item
                        ><el-dropdown-item command="rename" :icon="Edit"
                          >重命名</el-dropdown-item
                        ><el-dropdown-item command="move" :icon="FolderOpened"
                          >移动</el-dropdown-item
                        ><el-dropdown-item
                          v-if="row.kind === 'FILE'"
                          command="versions"
                          >版本历史</el-dropdown-item
                        ><el-dropdown-item
                          command="delete"
                          :icon="Delete"
                          divided
                          >移入回收站</el-dropdown-item
                        ></el-dropdown-menu
                      ></template
                    ></el-dropdown
                  ></template
                ></template
              ></el-table-column
            ></el-table
          >
        </div>
        <div v-if="section === 'drive'" class="list-footer">
          <span>你的文件，由你掌握。</span
          ><el-pagination
            v-if="total > 30"
            v-model:current-page="page"
            :total="total"
            :page-size="30"
            layout="prev, pager, next"
            @current-change="load"
          /><span v-else>{{ total }} 项内容</span>
        </div>
        <UploadPanel /><DownloadCenter :revision="downloadRevision" />
      </main>
      <footer class="app-footer">
        CloudNest <span>私人空间 · 自由收藏</span>
      </footer>
    </div>
    <input
      ref="fileInput"
      hidden
      type="file"
      multiple
      @change="filesSelected"
    /><input
      ref="folderInput"
      type="file"
      hidden
      multiple
      webkitdirectory
      @change="foldersSelected"
    /><ShareSettings
      :share="editingShare"
      @close="editingShare = null"
      @changed="load"
    /><VersionHistory
      :file="historyFile"
      :folder-id="current"
      @close="historyFile = null"
      @changed="load"
    /><PreviewDialog
      :file="preview"
      :siblings="items"
      @select="preview = $event"
      @close="preview = null"
    /><FolderPicker
      :open="!!moving || batchMoving"
      :root-id="store.user?.rootId || ''"
      @close="
        moving = null;
        batchMoving = false;
      "
      @choose="moveTo"
    /><el-dialog
      :model-value="!!sharing"
      title="分享文件"
      width="480px"
      @close="sharing = null"
      ><div v-if="sharing" class="sharing-file">
        <FileMark :kind="sharing.kind" :mime="sharing.mime" />
        <div>
          <strong>{{ sharing.name }}</strong
          ><small>{{ bytes(sharing.size) }}</small>
        </div>
      </div>
      <template v-if="!shareUrl"
        ><el-form label-position="top"
          ><el-form-item label="有效期"
            ><el-select v-model="shareHours"
              ><el-option label="1 小时" :value="1" /><el-option
                label="1 天"
                :value="24" /><el-option label="7 天" :value="168" /><el-option
                label="30 天"
                :value="720" /></el-select></el-form-item
          ><el-form-item label="提取码（可选，4～32 个字符）"
            ><el-input
              v-model="shareCode"
              placeholder="留空则无需提取码"
              maxlength="32" /></el-form-item
          ><el-form-item label="访问权限"
            ><el-switch
              v-model="shareDownload"
              active-text="允许下载"
              inactive-text="仅预览" /></el-form-item
        ></el-form>
        <p class="muted">
          {{
            sharing?.kind === "FOLDER"
              ? "文件夹为动态分享，新增后代也会进入分享范围。"
              : "单文件分享固定到创建时的内容版本。"
          }}
          接收者仍可保存已预览的内容，请只分享给信任的人。
        </p></template
      >
      <div v-else class="share-result">
        <el-alert
          title="分享已创建，请保存链接及提取码"
          type="success"
          :closable="false"
        /><el-input :model-value="shareUrl" readonly aria-label="分享链接" />
        <p v-if="shareCode">
          提取码：<strong>{{ shareCode }}</strong>
        </p>
        <el-button :icon="Link" type="primary" @click="copyShare"
          >复制链接</el-button
        >
      </div>
      <template #footer
        ><el-button @click="sharing = null">关闭</el-button
        ><el-button
          v-if="!shareUrl"
          type="primary"
          :loading="shareBusy"
          @click="createShare"
          >创建分享</el-button
        ></template
      ></el-dialog
    ><el-dialog v-model="profile" title="个人资料" width="420px"
      ><el-form label-position="top" @submit.prevent="editProfile"
        ><el-form-item label="账号"
          ><el-input
            :model-value="store.user?.username"
            disabled /></el-form-item
        ><el-form-item label="显示名称"
          ><el-input
            v-model="nickname"
            maxlength="80" /></el-form-item></el-form
      ><template #footer
        ><el-button @click="profile = false">取消</el-button
        ><el-button type="primary" :loading="profileBusy" @click="editProfile"
          >保存</el-button
        ></template
      ></el-dialog
    >
  </div>
</template>
