<script setup lang="ts">
import { t } from "../i18n";
import { useDownload } from "../api/download";
const { downloading, download } = useDownload();
import { computed, onMounted, onBeforeUnmount, ref, watch } from "vue";
import PageHeading from "../components/common/PageHeading.vue";
import DriveFilesSection from "../components/drive/DriveFilesSection.vue";
import SharesSection from "../components/drive/SharesSection.vue";
import FileCollectionTabs from "../components/files/FileCollectionTabs.vue";
import FileFilters from "../components/files/FileFilters.vue";
import BatchActionBar from "../components/files/BatchActionBar.vue";
import FileStorageDialog from "../components/nodes/FileStorageDialog.vue";
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
const storageFiles = ref<Entry[]>([]);
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
      ({
        image: t("files.images"),
        document: t("files.documents"),
        folder: t("files.folder"),
      }) as Record<string, string>
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
    ElMessage.info(
      t("drive.restoreResult", {
        succeeded: data.succeeded,
        failed: data.failed,
      }),
    );
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
const titles = computed<Record<string, string>>(() => ({
  drive: t("nav.files"),
  trash: t("nav.trash"),
  shares: t("nav.shares"),
}));
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
      t("drive.newFolderHelp"),
      t("drive.newFolder"),
      {
        inputPlaceholder: t("drive.folderName"),
        confirmButtonText: t("common.create"),
        cancelButtonText: t("common.cancel"),
        inputValidator: (v) => !!v?.trim() || t("drive.enterName"),
      },
    );
    await api.post("/folders", { parentId: current.value, name: value });
    ElMessage.success(t("drive.folderCreated"));
    await load();
  } catch (e) {
    if (e !== "cancel" && e !== "close") ElMessage.error(errorText(e));
  }
}
async function rename(e: Entry) {
  try {
    const { value } = await ElMessageBox.prompt(
      t("drive.newName"),
      t("files.rename"),
      {
        inputValue: e.name,
        confirmButtonText: t("common.save"),
        cancelButtonText: t("common.cancel"),
      },
    );
    await api.patch(`/${typePath(e)}/${e.id}`, { name: value });
    ElMessage.success(t("drive.renamed"));
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
    ElMessage.success(t("drive.moved"));
    await load();
  } catch (e) {
    ElMessage.error(errorText(e));
  }
}
async function remove(e: Entry) {
  try {
    await ElMessageBox.confirm(
      t("drive.trashConfirm", {
        name: e.name,
        contents: e.kind === "FOLDER" ? t("drive.contentsSuffix") : "",
      }),
      t("files.trash"),
      {
        type: "warning",
        confirmButtonText: t("files.trash"),
        cancelButtonText: t("common.cancel"),
      },
    );
    await api.delete(`/${typePath(e)}/${e.id}`);
    rememberDelete([e.id]);
    await load();
    ElMessage.success(t("drive.trashed"));
  } catch (e) {
    if (e !== "cancel" && e !== "close") ElMessage.error(errorText(e));
  }
}
async function restore(e: Entry) {
  try {
    const r = await api.post(`/trash/${typePath(e)}/${e.id}/restore`);
    ElMessage.success(
      r.data.restoredToRoot
        ? t("drive.restoredRoot")
        : t("drive.restored") +
            (r.data.name !== e.name ? t("drive.resolvedName") : ""),
    );
    await load();
  } catch (e) {
    ElMessage.error(errorText(e));
  }
}
async function purge(e: Entry) {
  try {
    await ElMessageBox.confirm(
      t("drive.purgeConfirm", {
        name: e.name,
        contents: e.kind === "FOLDER" ? t("drive.allChildren") : "",
      }),
      t("common.purge"),
      {
        type: "warning",
        confirmButtonText: t("common.purge"),
        cancelButtonText: t("common.cancel"),
      },
    );
    await api.delete(`/trash/${typePath(e)}/${e.id}`);
    await load();
    ElMessage.success(t("drive.purged"));
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
    ElMessage.error(t("drive.importLimit"));
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
        throw new Error(t("drive.moveFailed", { failed: data.failed }));
    } else await api.post("/entries/copy", { ids, parentId });
    clipboard.value = null;
    await load();
    ElMessage.success(t("drive.fileActionComplete"));
  } catch (e) {
    ElMessage.error(errorText(e));
  } finally {
    batchBusy.value = false;
  }
}
function putClipboard(cut: boolean) {
  clipboard.value = { ids: selected.value.map((e) => e.id), cut };
  ElMessage.info(cut ? t("drive.cutReady") : t("drive.copyReady"));
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
        t("drive.batchConfirm", {
          action:
            action === "purge" ? t("drive.permanentDelete") : t("files.trash"),
          count: snapshot.length,
        }),
        t("files.batch"),
        {
          confirmButtonText: t("common.confirm"),
          cancelButtonText: t("common.cancel"),
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
        t("drive.batchResult", {
          succeeded: data.succeeded,
          failed: data.failed,
        }),
      );
    else
      ElMessage.success(
        t("drive.batchProcessed", { succeeded: data.succeeded }),
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
      throw new Error(t("drive.depthLimit"));
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
    ElMessage.success(t("drive.shareCreated"));
  } catch (e) {
    ElMessage.error(errorText(e));
  } finally {
    shareBusy.value = false;
  }
}
async function copyShare() {
  try {
    await navigator.clipboard.writeText(shareUrl.value);
    ElMessage.success(t("drive.linkCopied"));
  } catch {
    ElMessage.info(t("drive.copyManually"));
  }
}
async function revoke(id: string) {
  try {
    await ElMessageBox.confirm(t("drive.revokeHelp"), t("drive.revokeShare"), {
      confirmButtonText: t("common.revoke"),
      cancelButtonText: t("common.cancel"),
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
    ElMessage.info(t("drive.selectDownload"));
    return;
  }
  packaging.value = true;
  try {
    const { data: estimate } = await api.post("/downloads/estimate", {
      fileIds: ids,
    });
    if (!estimate.canPack) throw new Error(t("drive.diskFull"));
    ElMessage.info(
      t("drive.zipEstimate", {
        count: estimate.files,
        size: bytes(estimate.bytes),
      }),
    );
    await api.post("/downloads", { fileIds: ids });
    downloadRevision.value++;
    ElMessage.success(t("drive.zipQueued"));
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
    ElMessage.success(t("drive.profileSaved"));
  } catch (e) {
    ElMessage.error(errorText(e));
  } finally {
    profileBusy.value = false;
  }
}
async function logout() {
  if (uploads.tasks.some((t) => t.active)) {
    ElMessage.info(t("drive.pauseUploads"));
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
      t("drive.tagHelp"),
      t("files.tags"),
      { inputValue: data.join(", ") },
    );
    await api.put(`/entries/${e.id}/tags`, {
      tags: value
        .split(/[,，]/)
        .map((s: string) => s.trim())
        .filter(Boolean),
    });
    ElMessage.success(t("drive.tagsSaved"));
  } catch (error) {
    if (error !== "cancel" && error !== "close")
      ElMessage.error(errorText(error));
  }
}
function command(c: FileAction, e: Entry) {
  if (c === "storage") storageFiles.value = [e];
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
    <FileStorageDialog :files="storageFiles" @close="storageFiles = []" />
    <el-drawer
      v-if="isMobile"
      v-model="navOpen"
      class="cn-drawer cn-nav-drawer"
      :title="t('nav.navigation')"
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
              :placeholder="t('drive.searchName')"
              clearable
              :aria-label="t('drive.searchName')"
              @input="searchDrive"
            /><el-select v-model="scope" :aria-label="t('drive.searchScope')"
              ><el-option
                :label="t('drive.allFolders')"
                value="all" /><el-option
                :label="t('files.currentFolder')"
                value="current"
            /></el-select></div
        ></template>
        <AccountSecurity />
        <IconButton
          :icon="Refresh"
          :label="t('common.refresh')"
          @click="load"
        />
        <IconButton
          :icon="SwitchButton"
          :label="t('common.signOut')"
          @click="logout"
        />
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
          <h2>{{ t("drive.dropFiles") }}</h2>
          <p>{{ t("drive.dropHelp") }}</p>
        </div>
        <PageHeading
          :title="section === 'drive' ? t('drive.title') : titles[section]"
          :description="
            section === 'drive'
              ? t('drive.intro')
              : section === 'trash'
                ? t('drive.trashIntro')
                : t('drive.sharesIntro')
          "
        >
          <template v-if="section === 'drive'">
            <el-button :icon="Plus" @click="createFolder">{{
              t("drive.newFolder")
            }}</el-button>
            <el-button
              type="primary"
              :icon="Upload"
              @click="fileInput?.click()"
              >{{ t("drive.upload") }}</el-button
            >
            <el-popover
              placement="bottom-end"
              trigger="click"
              :width="290"
              popper-class="cn-upload-popover"
              ><template #reference
                ><el-button
                  :aria-label="t('drive.uploadOptions')"
                  :icon="MoreFilled"
              /></template>
              <div class="filter-fields">
                <el-button @click="folderInput?.click()">{{
                  t("drive.uploadFolder")
                }}</el-button
                ><label
                  >{{ t("drive.nameConflict")
                  }}<el-select
                    :teleported="false"
                    v-model="conflictPolicy"
                    :aria-label="t('drive.conflictHandling')"
                    ><el-option
                      :label="t('drive.replaceVersion')"
                      value="replace" /><el-option
                      :label="t('drive.keepBoth')"
                      value="keep" /><el-option
                      :label="t('drive.skip')"
                      value="skip" /></el-select
                ></label>
                <p class="muted">
                  {{ t("drive.uploadHelp") }}
                </p>
              </div></el-popover
            >
          </template>
        </PageHeading>
        <template v-if="section === 'drive'">
          <div class="stat-grid" :aria-label="t('drive.overview')">
            <div class="stat-card">
              <span class="stat-icon all"
                ><el-icon><Files /></el-icon
              ></span>
              <div>
                <span>{{ t("common.file") }}</span
                ><strong>{{ stats.files }}</strong>
              </div>
            </div>
            <div class="stat-card">
              <span class="stat-icon image"
                ><el-icon><Picture /></el-icon
              ></span>
              <div>
                <span>{{ t("files.images") }}</span
                ><strong>{{ stats.images }}</strong>
              </div>
            </div>
            <div class="stat-card">
              <span class="stat-icon doc"
                ><el-icon><Document /></el-icon
              ></span>
              <div>
                <span>{{ t("files.documents") }}</span
                ><strong>{{ stats.documents }}</strong>
              </div>
            </div>
            <div class="stat-card">
              <span class="stat-icon folders"
                ><el-icon><FolderOpened /></el-icon
              ></span>
              <div>
                <span>{{ t("files.folder") }}</span
                ><strong>{{ stats.folders }}</strong>
              </div>
            </div>
          </div>
        </template>
        <section class="workspace-surface" :aria-label="titles[section]">
          <template v-if="section === 'drive'">
            <div class="files-toolbar">
              <el-breadcrumb separator="/" :aria-label="t('drive.path')"
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
                <strong v-else>{{ t("drive.searchResults") }}</strong
                ><span class="sr-only">{{ t("files.collection") }}</span
                ><span class="muted">{{ total }} {{ t("drive.items") }}</span>
              </div>
              <div class="list-controls">
                <FileFilters
                  v-model:type="fileType"
                  v-model:size="minMiB"
                  v-model:days="rangeDays"
                /><el-select
                  v-model="sort"
                  :aria-label="t('drive.sort')"
                  class="sort-select"
                  ><el-option
                    :label="t('drive.sortName')"
                    value="name" /><el-option
                    :label="t('drive.sortModified')"
                    value="updatedAt" /><el-option
                    :label="t('drive.sortSize')"
                    value="size" /><el-option
                    v-if="collection === 'opened'"
                    :label="t('drive.sortOpened')"
                    value="lastOpened" /><el-option
                    v-if="collection === 'uploaded'"
                    :label="t('drive.sortUploaded')"
                    value="createdAt" /></el-select
                ><el-button
                  :icon="Rank"
                  class="sort-direction"
                  :aria-label="t('drive.reverseSort')"
                  @click="direction = direction === 'asc' ? 'desc' : 'asc'"
                  ><span class="sort-direction-label">{{
                    direction === "asc"
                      ? t("drive.ascending")
                      : t("drive.descending")
                  }}</span></el-button
                >
                <div
                  class="view-toggle"
                  role="group"
                  :aria-label="t('drive.view')"
                >
                  <button
                    :class="{ active: !grid }"
                    :aria-pressed="!grid"
                    :aria-label="t('drive.listView')"
                    @click="grid = false"
                  >
                    <el-icon><List /></el-icon></button
                  ><button
                    :class="{ active: grid }"
                    :aria-pressed="grid"
                    :aria-label="t('drive.gridView')"
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
              :aria-label="t('drive.activeFilters')"
            >
              <el-tag v-if="query" closable @close="query = ''"
                >{{
                  scope === "all"
                    ? t("drive.allFolders")
                    : t("files.currentFolder")
                }}：{{ query }}</el-tag
              ><el-tag
                v-if="fileType !== 'all'"
                closable
                @close="fileType = 'all'"
                >{{ t("drive.typePrefix") }}{{ fileTypeLabel }}</el-tag
              ><el-tag v-if="minMiB" closable @close="minMiB = 0"
                >{{ t("drive.atLeast") }} {{ minMiB }} MiB</el-tag
              ><el-tag v-if="rangeDays" closable @close="rangeDays = 0"
                >{{ t("drive.last") }} {{ rangeDays }}
                {{ t("drive.days") }}</el-tag
              ><el-button
                link
                @click="
                  query = '';
                  fileType = 'all';
                  minMiB = 0;
                  rangeDays = 0;
                "
                >{{ t("drive.reset") }}</el-button
              >
            </div>
          </template>
          <div v-else-if="section === 'trash'" class="trash-note">
            <el-icon><Delete /></el-icon>{{ t("drive.trashQuota") }}
          </div>
          <div v-if="section !== 'shares'" class="selection-row">
            <el-checkbox :model-value="allSelected()" @change="togglePage">{{
              t("drive.selectPage")
            }}</el-checkbox
            ><span v-if="selected.length"
              >{{ t("drive.selected") }} {{ selected.length }}
              {{ t("drive.crossFolder") }}</span
            ><el-button
              v-if="section === 'drive' && clipboard"
              :disabled="batchBusy"
              @click="copyTo(current)"
              >{{ t("drive.paste") }}</el-button
            >
          </div>
          <el-button
            v-if="selected.length && section !== 'trash'"
            @click="storageFiles = [...selected]"
            >{{ t("files.storage") }}</el-button
          >
          <BatchActionBar :count="selected.length"
            ><strong
              >{{ t("drive.selected") }} {{ selected.length }}
              {{ t("drive.items") }}</strong
            ><SelectionBasket v-model="selected" /><template
              v-if="section === 'drive'"
              ><el-button :icon="Download" :loading="packaging" @click="zip()"
                >{{ t("drive.zipCount") }}{{ selected.length }})</el-button
              ><el-button
                @click="
                  collecting = false;
                  collectionFiles = [...selected];
                "
                >{{ t("drive.shareSelection") }}</el-button
              ><el-button @click="putClipboard(false)">{{
                t("common.copy")
              }}</el-button
              ><el-button @click="putClipboard(true)">{{
                t("common.cut")
              }}</el-button
              ><el-button :disabled="batchBusy" @click="batchMoving = true">{{
                t("drive.batchMove")
              }}</el-button
              ><el-button :disabled="batchBusy" @click="batch('trash')">{{
                t("drive.batchDelete")
              }}</el-button></template
            ><template v-else
              ><el-button
                type="primary"
                :disabled="batchBusy"
                @click="batch('restore')"
                >{{ t("drive.batchRestore") }}</el-button
              ><el-button
                type="danger"
                :disabled="batchBusy"
                @click="batch('purge')"
                >{{ t("drive.batchPurge") }}</el-button
              ></template
            ><el-button @click="selected = []">{{
              t("drive.deselect")
            }}</el-button></BatchActionBar
          >
          <el-button
            v-if="undoIds.length"
            type="primary"
            plain
            @click="undoDelete"
            >{{ t("drive.undoDelete") }}</el-button
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
                ? t('drive.emptyTrash')
                : query || fileType !== 'all' || minMiB || rangeDays
                  ? t('drive.noResults')
                  : collection === 'favorites'
                    ? t('drive.noFavorites')
                    : collection === 'opened'
                      ? t('drive.noOpened')
                      : collection === 'uploaded'
                        ? t('drive.noUploaded')
                        : t('drive.emptyFolder')
            "
            :empty-description="
              section === 'trash'
                ? t('drive.emptyTrashHelp')
                : query || fileType !== 'all' || minMiB || rangeDays
                  ? t('drive.noResultsHelp')
                  : collection === 'favorites'
                    ? t('drive.noFavoritesHelp')
                    : t('drive.emptyHelp')
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
                >{{ t("drive.firstUpload") }}</el-button
              ></template
            ></DriveFilesSection
          >
          <div v-if="section === 'drive'" class="list-footer">
            <span>{{ t("drive.pageHelp") }}</span
            ><el-pagination
              v-if="total > 30"
              v-model:current-page="page"
              :total="total"
              :page-size="30"
              layout="prev, pager, next"
              @current-change="load"
            /><span v-else>{{ total }} {{ t("drive.itemContents") }}</span>
          </div>
        </section>
        <UploadPanel /><DownloadCenter :revision="downloadRevision" />
      </main>
      <footer class="app-footer">
        CloudNest <span>{{ t("drive.footer") }}</span>
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
      :title="t('drive.shareTitle')"
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
          ><el-form-item :label="t('drive.expiry')"
            ><el-select v-model="shareHours"
              ><el-option :label="t('drive.oneHour')" :value="1" /><el-option
                :label="t('drive.oneDay')"
                :value="24" /><el-option
                :label="t('drive.oneWeek')"
                :value="168" /><el-option
                :label="t('drive.oneMonth')"
                :value="720" /></el-select></el-form-item
          ><el-form-item :label="t('drive.shareCode')"
            ><el-input
              v-model="shareCode"
              :placeholder="t('drive.shareCodeHint')"
              maxlength="32" /></el-form-item
          ><el-form-item :label="t('drive.permissions')"
            ><el-switch
              v-model="shareDownload"
              :active-text="t('drive.allowDownload')"
              :inactive-text="t('drive.previewOnly')" /></el-form-item
        ></el-form>
        <p class="muted">
          {{
            sharing?.kind === "FOLDER"
              ? t("drive.dynamicShare")
              : t("drive.fixedShare")
          }}
          {{ t("drive.shareTrust") }}
        </p></template
      >
      <div v-else class="share-result">
        <el-alert
          :title="t('drive.saveShare')"
          type="success"
          :closable="false"
        /><el-input
          :model-value="shareUrl"
          readonly
          :aria-label="t('drive.shareLink')"
        />
        <p v-if="shareCode">
          {{ t("drive.codePrefix") }}<strong>{{ shareCode }}</strong>
        </p>
        <el-button :icon="Link" type="primary" @click="copyShare">{{
          t("drive.copyLink")
        }}</el-button>
      </div>
      <template #footer
        ><el-button @click="sharing = null">{{ t("common.close") }}</el-button
        ><el-button
          v-if="!shareUrl"
          type="primary"
          :loading="shareBusy"
          @click="createShare"
          >{{ t("drive.createShare") }}</el-button
        ></template
      ></el-dialog
    ><el-dialog
      class="cn-dialog"
      v-model="profile"
      :title="t('drive.profile')"
      width="420px"
      ><el-form label-position="top" @submit.prevent="editProfile"
        ><el-form-item :label="t('common.account')"
          ><el-input
            :model-value="store.user?.username"
            disabled /></el-form-item
        ><el-form-item :label="t('drive.displayName')"
          ><el-input
            v-model="nickname"
            maxlength="80" /></el-form-item></el-form
      ><template #footer
        ><el-button @click="profile = false">{{ t("common.cancel") }}</el-button
        ><el-button
          type="primary"
          :loading="profileBusy"
          @click="editProfile"
          >{{ t("common.save") }}</el-button
        ></template
      ></el-dialog
    >
  </div>
</template>
