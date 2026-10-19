<script setup lang="ts">
import { useDownload } from "../api/download";
const { downloading, download } = useDownload();
import { computed, onMounted, onBeforeUnmount, ref, watch } from "vue";
import PageHeading from "../components/common/PageHeading.vue";
import DriveFilesSection from "../components/drive/DriveFilesSection.vue";
import SharesSection from "../components/drive/SharesSection.vue";
import FileCollectionTabs from "../components/files/FileCollectionTabs.vue";
import FileFilters from "../components/files/FileFilters.vue";
import BatchActionBar from "../components/files/BatchActionBar.vue";
import type { FileAction } from "../components/files/actions";
import AppSidebar from "../components/layout/AppSidebar.vue";
import AppTopbar from "../components/layout/AppTopbar.vue";
import IconButton from "../components/common/IconButton.vue";
import { useRouter } from "vue-router";
import { ElMessage, ElMessageBox } from "element-plus";
import {
  FolderOpened,
  Delete,
  Search,
  Upload,
  Plus,
  Grid,
  List,
  Refresh,
  Download,
  MoreFilled,
  Rank,
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
import ShareSettings from "../components/ShareSettings.vue";
import DownloadCenter from "../components/DownloadCenter.vue";
import SelectionBasket from "../components/SelectionBasket.vue";
import AdvancedSearch from "../components/AdvancedSearch.vue";
import CollectionShare from "../components/CollectionShare.vue";
const navOpen = ref(false),
  isMobile = ref(window.innerWidth < 768);
function resizeLayout() {
  isMobile.value = window.innerWidth < 768;
  if (!isMobile.value) navOpen.value = false;
}
onMounted(() => window.addEventListener("resize", resizeLayout));
onBeforeUnmount(() => window.removeEventListener("resize", resizeLayout));
function showProfile() {
  nickname.value = store.user?.nickname || "";
  profile.value = true;
}
function chooseCollection(value: string) {
  if (section.value !== "drive") selected.value = [];
  section.value = "drive";
  collection.value = value;
  query.value = "";
  page.value = 1;
  sort.value =
    value === "opened"
      ? "lastOpened"
      : value === "uploaded"
        ? "createdAt"
        : "name";
  direction.value = value === "all" || value === "favorites" ? "asc" : "desc";
  navOpen.value = false;
  void load();
}
function searchDrive() {
  if (section.value !== "drive") {
    selected.value = [];
    section.value = "drive";
    items.value = [];
  }
}
function sortBy(value: string) {
  if (sort.value === value) {
    direction.value = direction.value === "asc" ? "desc" : "asc";
  } else {
    sort.value = value;
    direction.value = "asc";
  }
}
const collectionFiles = ref<Entry[] | null>(null),
  collecting = ref(false);
import { useSelection } from "../selection";
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
  copyMode = ref(false),
  clipboard = ref<{ ids: string[]; cut: boolean } | null>(null),
  batchBusy = ref(false);
const fileTypeLabel = computed(
  () =>
    (
      ({ image: "图片", document: "文档", folder: "文件夹" }) as Record<
        string,
        string
      >
    )[fileType.value],
);
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
  preview = ref<Entry | null>(null),
  moving = ref<Entry | null>(null),
  fileInput = ref<HTMLInputElement>(),
  drop = ref(false);
const {
  selected,
  allSelected,
  toggle: selectCard,
  togglePage,
} = useSelection(items, () => crumbs.value.map((c) => c.name).join("/"));
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
let requestId = 0,
  searchTimer: ReturnType<typeof setTimeout> | undefined;
async function load() {
  clearTimeout(searchTimer);
  if (!current.value && store.user) current.value = store.user.rootId;
  const thisRequest = ++requestId;
  busy.value = true;
  failure.value = "";
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
  if (section.value !== value) selected.value = [];
  section.value = value;
  navOpen.value = false;
  drop.value = false;
  dragDepth = 0;
  items.value = [];
  shares.value = [];
  query.value = "";
  page.value = 1;
  load();
}
watch(
  [query, scope, collection, fileType, minMiB, rangeDays, sort, direction],
  () => {
    clearTimeout(searchTimer);
    searchTimer = setTimeout(() => {
      page.value = 1;
      load();
    }, 300);
  },
  { flush: "sync" },
);
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
  if (copyMode.value) {
    await copyTo(id);
    copyMode.value = false;
    return;
  }
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
async function copyTo(parentId: string) {
  try {
    batchBusy.value = true;
    const ids = clipboard.value?.ids || selected.value.map((e) => e.id);
    if (!ids.length) return;
    if (clipboard.value?.cut) {
      const { data } = await api.post("/entries/batch", {
        ids,
        parentId,
        action: "move",
      });
      if (data.failed)
        throw new Error(`${data.failed} 项移动失败，请检查目标目录`);
    } else await api.post("/entries/copy", { ids, parentId });
    clipboard.value = null;
    await load();
    ElMessage.success("文件操作已完成");
  } catch (e) {
    ElMessage.error(errorText(e));
  } finally {
    batchBusy.value = false;
  }
}
function putClipboard(cut: boolean) {
  clipboard.value = { ids: selected.value.map((e) => e.id), cut };
  ElMessage.info(cut ? "已剪切，进入目标目录粘贴" : "已复制，进入目标目录粘贴");
}
function shortcut(event: KeyboardEvent) {
  const node = event.target as HTMLElement;
  if (
    node.closest(
      "input, textarea, [contenteditable=true], [role=dialog], [role=menu], [role=listbox], .el-popper, .upload-panel",
    ) ||
    section.value !== "drive"
  )
    return;
  if (!(event.ctrlKey || event.metaKey)) return;
  const key = event.key.toLowerCase();
  if (["c", "x"].includes(key) && selected.value.length) {
    event.preventDefault();
    putClipboard(key === "x");
  }
  if (key === "v" && clipboard.value) {
    event.preventDefault();
    void copyTo(current.value);
  }
  if (key === "a") {
    event.preventDefault();
    if (!allSelected()) togglePage();
  }
}
onMounted(() => window.addEventListener("keydown", shortcut));
onBeforeUnmount(() => window.removeEventListener("keydown", shortcut));
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
    const done = new Set(
      data.results
        .filter((r: { status: string }) => r.status === "success")
        .map((r: { id: string }) => r.id),
    );
    selected.value = selected.value.filter((e) => !done.has(e.id));
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
let dragDepth = 0;
function dragEnter(e: DragEvent) {
  if (section.value !== "drive" || !e.dataTransfer?.types.includes("Files"))
    return;
  dragDepth++;
  drop.value = true;
}
function dragLeave() {
  dragDepth = Math.max(0, dragDepth - 1);
  if (!dragDepth) drop.value = false;
}
async function dropped(e: DragEvent) {
  dragDepth = 0;
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
    const { data: estimate } = await api.post("/downloads/estimate", {
      fileIds: ids,
    });
    if (!estimate.canPack)
      throw new Error("服务器磁盘不足，请先清理或缩小打包范围");
    ElMessage.info(
      `预计打包 ${estimate.files} 个文件，共 ${bytes(estimate.bytes)}`,
    );
    await api.post("/downloads", { fileIds: ids });
    downloadRevision.value++;
    ElMessage.success("打包任务已创建，请在下载任务中查看进度");
    document.getElementById("download-tasks")?.scrollIntoView({
      behavior: window.matchMedia("(prefers-reduced-motion: reduce)").matches
        ? "instant"
        : "smooth",
      block: "start",
    });
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
async function editTags(e: Entry) {
  try {
    const { data } = await api.get(`/entries/${e.id}/tags`);
    const { value } = await ElMessageBox.prompt(
      "用逗号分隔标签，每项最多 40 字符，最多 20 项",
      "编辑标签",
      { inputValue: data.join(", ") },
    );
    await api.put(`/entries/${e.id}/tags`, {
      tags: value
        .split(/[,，]/)
        .map((s: string) => s.trim())
        .filter(Boolean),
    });
    ElMessage.success("标签已保存");
  } catch (error) {
    if (error !== "cancel" && error !== "close")
      ElMessage.error(errorText(error));
  }
}
function command(c: FileAction, e: Entry) {
  if (c === "download" && e.kind === "FILE")
    void download(`/api/files/${e.id}/download`);
  if (c === "restore") void restore(e);
  if (c === "purge") void purge(e);
  if (c === "locate" && e.parentId) navigate(e.parentId);
  if (c === "tags") void editTags(e);
  if (c === "collect") {
    collecting.value = true;
    collectionFiles.value = [e];
  }
  if (c === "zip") zip([e]);
  if (c === "versions") historyFile.value = e;
  if (c === "favorite") favorite(e);
  if (c === "rename") rename(e);
  if (c === "move") moving.value = e;
  if (c === "delete") remove(e);
  if (c === "share") share(e);
}
</script>
<template>
  <div class="app-shell">
    <el-drawer
      v-if="isMobile"
      v-model="navOpen"
      class="cn-drawer cn-nav-drawer"
      title="工作空间导航"
      direction="ltr"
      size="280px"
      :with-header="false"
    >
      <AppSidebar
        :section="section"
        :collection="collection"
        :user="store.user"
        @collection="chooseCollection"
        @section="tab"
        @profile="
          showProfile();
          navOpen = false;
        "
      />
    </el-drawer>
    <AppSidebar
      v-else
      :section="section"
      :collection="collection"
      :user="store.user"
      @collection="chooseCollection"
      @section="tab"
      @profile="showProfile"
    />
    <div class="main-shell">
      <AppTopbar :title="titles[section]" @menu="navOpen = true">
        <template #search
          ><div class="file-search">
            <el-input
              v-model="query"
              :prefix-icon="Search"
              placeholder="搜索文件名称"
              clearable
              aria-label="搜索文件名称"
              @input="searchDrive"
            /><el-select v-model="scope" aria-label="搜索范围"
              ><el-option label="全部目录" value="all" /><el-option
                label="当前目录"
                value="current"
            /></el-select></div
        ></template>
        <AccountSecurity />
        <IconButton :icon="Refresh" label="刷新" @click="load" />
        <IconButton :icon="SwitchButton" label="退出登录" @click="logout" />
      </AppTopbar>
      <main
        class="drive-main"
        :class="{ dragging: drop }"
        @dragenter.prevent="dragEnter"
        @dragover.prevent
        @dragleave.prevent="dragLeave"
        @drop.prevent="dropped"
      >
        <div v-if="drop && section === 'drive'" class="drop-zone">
          <el-icon><Upload /></el-icon>
          <h2>松开以上传文件</h2>
          <p>文件将保存到当前目录，上传进度可随时查看。</p>
        </div>
        <PageHeading
          :title="section === 'drive' ? '我的云盘' : titles[section]"
          :description="
            section === 'drive'
              ? '你的文件与灵感，都有自己的空间。'
              : section === 'trash'
                ? '恢复误删内容，或永久释放空间。'
                : '管理链接、访问权限与有效期。'
          "
        >
          <template v-if="section === 'drive'">
            <el-button :icon="Plus" @click="createFolder">新建文件夹</el-button>
            <el-button type="primary" :icon="Upload" @click="fileInput?.click()"
              >上传文件</el-button
            >
            <el-popover
              placement="bottom-end"
              trigger="click"
              :width="290"
              popper-class="cn-upload-popover"
              ><template #reference
                ><el-button aria-label="更多上传选项" :icon="MoreFilled"
              /></template>
              <div class="filter-fields">
                <el-button @click="folderInput?.click()">上传文件夹</el-button
                ><label
                  >遇到同名文件<el-select
                    :teleported="false"
                    v-model="conflictPolicy"
                    aria-label="上传同名处理"
                    ><el-option
                      label="覆盖并保留历史"
                      value="replace" /><el-option
                      label="保留两份"
                      value="keep" /><el-option
                      label="跳过"
                      value="skip" /></el-select
                ></label>
                <p class="muted">
                  目录结构会完整保留，上传任务支持暂停与恢复。
                </p>
              </div></el-popover
            >
          </template>
        </PageHeading>
        <template v-if="section === 'drive'">
          <div class="stat-grid" aria-label="文件概况">
            <div class="stat-card">
              <span class="stat-icon all"
                ><el-icon><Files /></el-icon
              ></span>
              <div>
                <span>文件</span><strong>{{ stats.files }}</strong>
              </div>
            </div>
            <div class="stat-card">
              <span class="stat-icon image"
                ><el-icon><Picture /></el-icon
              ></span>
              <div>
                <span>图片</span><strong>{{ stats.images }}</strong>
              </div>
            </div>
            <div class="stat-card">
              <span class="stat-icon doc"
                ><el-icon><Document /></el-icon
              ></span>
              <div>
                <span>文档</span><strong>{{ stats.documents }}</strong>
              </div>
            </div>
            <div class="stat-card">
              <span class="stat-icon folders"
                ><el-icon><FolderOpened /></el-icon
              ></span>
              <div>
                <span>文件夹</span><strong>{{ stats.folders }}</strong>
              </div>
            </div>
          </div>
        </template>
        <section class="workspace-surface" :aria-label="titles[section]">
          <template v-if="section === 'drive'">
            <div class="files-toolbar">
              <el-breadcrumb separator="/" aria-label="目录路径"
                ><el-breadcrumb-item v-for="c in crumbs" :key="c.id"
                  ><button
                    class="crumb-button"
                    :title="c.name"
                    @click="navigate(c.id)"
                  >
                    {{ c.name }}
                  </button></el-breadcrumb-item
                ></el-breadcrumb
              ><AdvancedSearch
                @choose="preview = $event"
                @folder="navigate($event)"
              />
            </div>
            <div class="list-toolbar">
              <div>
                <FileCollectionTabs
                  v-if="!query"
                  :model-value="collection"
                  :nested="crumbs.length > 1"
                  @update:model-value="chooseCollection"
                />
                <strong v-else>搜索结果</strong
                ><span class="sr-only">文件集合</span
                ><span class="muted">{{ total }} 项</span>
              </div>
              <div class="list-controls">
                <FileFilters
                  v-model:type="fileType"
                  v-model:size="minMiB"
                  v-model:days="rangeDays"
                /><el-select
                  v-model="sort"
                  aria-label="排序方式"
                  class="sort-select"
                  ><el-option label="按名称" value="name" /><el-option
                    label="按修改时间"
                    value="updatedAt" /><el-option
                    label="按大小"
                    value="size" /><el-option
                    v-if="collection === 'opened'"
                    label="按最近打开"
                    value="lastOpened" /><el-option
                    v-if="collection === 'uploaded'"
                    label="按最近上传"
                    value="createdAt" /></el-select
                ><el-button
                  :icon="Rank"
                  class="sort-direction"
                  aria-label="切换排序方向"
                  @click="direction = direction === 'asc' ? 'desc' : 'asc'"
                  ><span class="sort-direction-label">{{
                    direction === "asc" ? "升序" : "降序"
                  }}</span></el-button
                >
                <div class="view-toggle" role="group" aria-label="文件视图">
                  <button
                    :class="{ active: !grid }"
                    :aria-pressed="!grid"
                    aria-label="列表视图"
                    @click="grid = false"
                  >
                    <el-icon><List /></el-icon></button
                  ><button
                    :class="{ active: grid }"
                    :aria-pressed="grid"
                    aria-label="网格视图"
                    @click="grid = true"
                  >
                    <el-icon><Grid /></el-icon>
                  </button>
                </div>
              </div>
            </div>
            <div
              v-if="query || fileType !== 'all' || minMiB || rangeDays"
              class="filter-chips"
              aria-label="已生效条件"
            >
              <el-tag v-if="query" closable @close="query = ''"
                >{{ scope === "all" ? "全部目录" : "当前目录" }}：{{
                  query
                }}</el-tag
              ><el-tag
                v-if="fileType !== 'all'"
                closable
                @close="fileType = 'all'"
                >类型：{{ fileTypeLabel }}</el-tag
              ><el-tag v-if="minMiB" closable @close="minMiB = 0"
                >至少 {{ minMiB }} MiB</el-tag
              ><el-tag v-if="rangeDays" closable @close="rangeDays = 0"
                >近 {{ rangeDays }} 天</el-tag
              ><el-button
                link
                @click="
                  query = '';
                  fileType = 'all';
                  minMiB = 0;
                  rangeDays = 0;
                "
                >重置条件</el-button
              >
            </div>
          </template>
          <div v-else-if="section === 'trash'" class="trash-note">
            <el-icon><Delete /></el-icon
            >回收站中的文件继续占用配额。彻底删除后无法恢复。
          </div>
          <div v-if="section !== 'shares'" class="selection-row">
            <el-checkbox :model-value="allSelected()" @change="togglePage"
              >全选当前页</el-checkbox
            ><span v-if="selected.length"
              >已选 {{ selected.length }} 项（跨目录保留）</span
            ><el-button
              v-if="section === 'drive' && clipboard"
              :disabled="batchBusy"
              @click="copyTo(current)"
              >粘贴到当前目录</el-button
            >
          </div>
          <BatchActionBar :count="selected.length"
            ><strong>已选 {{ selected.length }} 项</strong
            ><SelectionBasket v-model="selected" /><template
              v-if="section === 'drive'"
              ><el-button :icon="Download" :loading="packaging" @click="zip()"
                >打包下载 ({{ selected.length }})</el-button
              ><el-button
                @click="
                  collecting = false;
                  collectionFiles = [...selected];
                "
                >分享选中项目</el-button
              ><el-button @click="putClipboard(false)">复制</el-button
              ><el-button @click="putClipboard(true)">剪切</el-button
              ><el-button :disabled="batchBusy" @click="batchMoving = true"
                >批量移动</el-button
              ><el-button :disabled="batchBusy" @click="batch('trash')"
                >批量删除</el-button
              ></template
            ><template v-else
              ><el-button
                type="primary"
                :disabled="batchBusy"
                @click="batch('restore')"
                >批量恢复</el-button
              ><el-button
                type="danger"
                :disabled="batchBusy"
                @click="batch('purge')"
                >批量彻底删除</el-button
              ></template
            ><el-button @click="selected = []"
              >取消选择</el-button
            ></BatchActionBar
          >
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
          <SharesSection
            v-if="section === 'shares'"
            v-model:filter="shareFilter"
            :shares="visibleShares"
            :busy="busy"
            @edit="editShare"
            @revoke="revoke"
          />
          <DriveFilesSection
            v-else
            :items="items"
            :selected="selected"
            :grid="grid"
            :busy="busy"
            :failure="failure"
            :trash="section === 'trash'"
            :sort="sort"
            :direction="direction"
            :empty-variant="
              section === 'trash'
                ? 'trash'
                : query || fileType !== 'all' || minMiB || rangeDays
                  ? 'search'
                  : collection === 'favorites'
                    ? 'favorite'
                    : collection === 'opened' || collection === 'uploaded'
                      ? 'recent'
                      : 'folder'
            "
            :empty-title="
              section === 'trash'
                ? '回收站是空的'
                : query || fileType !== 'all' || minMiB || rangeDays
                  ? '没有找到匹配的文件'
                  : collection === 'favorites'
                    ? '还没有收藏'
                    : collection === 'opened'
                      ? '还没有打开记录'
                      : collection === 'uploaded'
                        ? '还没有上传记录'
                        : '这个目录还没有文件'
            "
            :empty-description="
              section === 'trash'
                ? '删除的文件会在这里保留，直到你恢复或彻底删除。'
                : query || fileType !== 'all' || minMiB || rangeDays
                  ? '试试其他关键词，或重置筛选条件。'
                  : collection === 'favorites'
                    ? '在文件菜单中收藏，让常用内容触手可及。'
                    : '上传文件或创建文件夹，开始整理。'
            "
            @toggle="selectCard"
            @open="open"
            @action="command"
            @retry="load"
            @sort="sortBy"
            ><template #empty-action
              ><el-button
                v-if="
                  section === 'drive' &&
                  collection === 'all' &&
                  !query &&
                  fileType === 'all' &&
                  !minMiB &&
                  !rangeDays
                "
                type="primary"
                @click="fileInput?.click()"
                >上传第一个文件</el-button
              ></template
            ></DriveFilesSection
          >
          <div v-if="section === 'drive'" class="list-footer">
            <span>每页 30 项 · 选择可跨目录保留</span
            ><el-pagination
              v-if="total > 30"
              v-model:current-page="page"
              :total="total"
              :page-size="30"
              layout="prev, pager, next"
              @current-change="load"
            /><span v-else>{{ total }} 项内容</span>
          </div>
        </section>
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
    /><CollectionShare
      :files="collectionFiles"
      :collect="collecting"
      @close="collectionFiles = null"
      @created="load"
    /><FolderPicker
      :open="!!moving || batchMoving"
      :root-id="store.user?.rootId || ''"
      @close="
        moving = null;
        batchMoving = false;
      "
      @choose="moveTo"
    /><el-dialog
      class="cn-dialog"
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
    ><el-dialog
      class="cn-dialog"
      v-model="profile"
      title="个人资料"
      width="420px"
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
