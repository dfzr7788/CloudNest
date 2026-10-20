<script setup lang="ts">
import { t } from "../i18n";
import LanguageSwitcher from "../components/common/LanguageSwitcher.vue";
import ThemeSwitcher from "../components/common/ThemeSwitcher.vue";
import { useDownload } from "../api/download";
const { downloading, download } = useDownload();
import { onMounted, ref } from "vue";
import CloudNestLogo from "../components/brand/CloudNestLogo.vue";
import { api, errorText, bytes, date, type Entry } from "../api";
import { Lock, Download, View } from "@element-plus/icons-vue";
import FileMark from "../components/FileMark.vue";
import PreviewDialog from "../components/PreviewDialog.vue";
import SelectionBasket from "../components/SelectionBasket.vue";
import { useSelection } from "../selection";
const props = defineProps<{ token: string }>(),
  info = ref<{
    locked: boolean;
    mode?: string;
    fileIds?: string[];
    expiresAt: number;
    file?: Entry;
    allowDownload?: boolean;
  }>(),
  code = ref(""),
  error = ref(""),
  busy = ref(false),
  preview = ref<Entry | null>(null);
const collectBusy = ref(false),
  collected = ref(0),
  zipId = ref(""),
  zipProgress = ref(0);
async function cancelZip() {
  if (zipId.value)
    try {
      await api.delete(
        `/public/shares/${props.token}/downloads/${zipId.value}`,
      );
    } catch (e) {
      error.value = errorText(e);
    }
}
async function collectFiles(event: Event) {
  const input = event.target as HTMLInputElement;
  const files = Array.from(input.files || []);
  collectBusy.value = true;
  try {
    for (const file of files) {
      const form = new FormData();
      form.append("file", file);
      await api.post(`/public/shares/${props.token}/collect`, form, {
        timeout: 600000,
      });
      collected.value++;
    }
  } catch (e) {
    error.value = errorText(e);
  } finally {
    collectBusy.value = false;
    input.value = "";
  }
}
const children = ref<Entry[]>([]),
  stack = ref<string[]>([]),
  stackNames = ref<string[]>([]),
  page = ref(1),
  total = ref(0),
  zipBusy = ref(false);
const { selected, isSelected, allSelected, toggle, togglePage } = useSelection(
  children,
  () =>
    [info.value?.file?.name || t("share.root"), ...stackNames.value].join("/"),
);
async function folder(id?: string) {
  try {
    const { data } = await api.get(`/public/shares/${props.token}/entries`, {
      params: { parentId: id, page: page.value },
    });
    children.value = data.items;
    total.value = data.total;
  } catch (e) {
    error.value = errorText(e);
  }
}
function enter(e: Entry) {
  if (e.kind === "FOLDER") {
    stack.value.push(e.id);
    stackNames.value.push(e.name);
    page.value = 1;
    folder(e.id);
  } else preview.value = e;
}
function back() {
  stack.value.pop();
  stackNames.value.pop();
  page.value = 1;
  folder(stack.value.at(-1));
}
async function zip(entries = selected.value) {
  if (!entries.length || zipBusy.value) return;
  error.value = "";
  zipBusy.value = true;
  try {
    const { data } = await api.post(`/public/shares/${props.token}/downloads`, {
      fileIds: entries.map((e) => e.id),
    });
    zipId.value = data.id;
    zipProgress.value = 0;
    for (let n = 0; n < 600; n++) {
      const status = (
        await api.get(`/public/shares/${props.token}/downloads/${data.id}`)
      ).data;
      zipProgress.value = status.totalBytes
        ? Math.min(
            100,
            Math.round((status.completedBytes / status.totalBytes) * 100),
          )
        : 0;
      if (status.status === "CANCELLED") return;
      if (status.status === "READY") {
        location.assign(
          `/api/public/shares/${props.token}/downloads/${data.id}/file`,
        );
        return;
      }
      if (status.status === "FAILED") throw new Error(status.error);
      await new Promise((r) => setTimeout(r, 1000));
    }
    throw new Error(t("share.timeout"));
  } catch (e) {
    error.value = errorText(e);
  } finally {
    zipBusy.value = false;
    zipId.value = "";
  }
}
async function load() {
  busy.value = true;
  error.value = "";
  try {
    info.value = (await api.get(`/public/shares/${props.token}`)).data;
    if (info.value?.file?.kind === "FOLDER" && info.value.mode !== "COLLECT")
      await folder();
  } catch (e) {
    error.value = errorText(e);
  } finally {
    busy.value = false;
  }
}
async function verify() {
  busy.value = true;
  error.value = "";
  try {
    await api.post(`/public/shares/${props.token}/verify`, {
      code: code.value,
    });
    await load();
  } catch (e) {
    error.value = errorText(e);
  } finally {
    busy.value = false;
  }
}
onMounted(load);
</script>
<template>
  <main class="share-page">
    <header>
      <router-link to="/" class="brand"><CloudNestLogo /></router-link
      ><span>{{ t("share.title") }}</span
      ><div class="share-header-actions">
        <ThemeSwitcher /><LanguageSwitcher />
      </div>
    </header>
    <section class="share-card" v-loading="busy">
      <div v-if="zipBusy" role="status">
        {{ t("downloads.packing") }} {{ zipProgress }}%
        <el-button size="small" @click="cancelZip">{{
          t("downloads.cancel")
        }}</el-button>
      </div>
      <span class="eyebrow">SHARED WITH YOU</span
      ><template v-if="error && !info"
        ><h1>{{ t("share.unavailable") }}</h1>
        <el-alert :title="error" type="error" :closable="false" /><el-button
          @click="load"
          >{{ t("common.reload") }}</el-button
        ></template
      ><template v-else-if="info?.locked"
        ><div class="share-lock">
          <el-icon><Lock /></el-icon>
        </div>
        <h1>{{ t("share.welcome") }}</h1>
        <p class="muted">{{ t("share.codeHelp") }}</p>
        <form @submit.prevent="verify">
          <el-input
            v-model="code"
            :placeholder="t('share.enterCode')"
            size="large"
            maxlength="32"
            :aria-label="t('share.code')"
          /><el-alert
            v-if="error"
            :title="error"
            type="error"
            :closable="false"
          /><el-button
            type="primary"
            native-type="submit"
            :loading="busy"
            size="large"
            >{{ t("share.open") }}</el-button
          >
        </form></template
      ><template v-else-if="info?.file"
        ><FileMark :kind="info.file.kind" :mime="info.file.mime" />
        <h1>{{ info.file.name }}</h1>
        <p class="muted">
          {{ bytes(info.file.size) }} {{ t("share.expires") }}
          {{ date(info.expiresAt) }}
        </p>
        <p
          v-if="stackNames.length"
          class="public-breadcrumb"
          :title="stackNames.join(' / ')"
        >
          {{ info.file.name }} / {{ stackNames.join(" / ") }}
        </p>
        <div v-if="info.mode !== 'COLLECT'" class="share-actions">
          <template v-if="info.file.kind === 'FOLDER'"
            ><el-button v-if="stack.length" @click="back">{{
              t("share.parent")
            }}</el-button
            ><el-button
              v-if="info.allowDownload && selected.length > 0"
              :loading="zipBusy"
              @click="zip()"
              >{{ t("share.zipSelection") }}{{ selected.length }})</el-button
            ><el-button
              v-if="info.allowDownload"
              :loading="zipBusy"
              @click="
                zip(
                  (info.fileIds || [info.file!.id]).map((id) => ({
                    ...info!.file!,
                    id,
                  })),
                )
              "
              >{{ t("share.downloadFolder") }}</el-button
            ></template
          >
          <template v-else>
            <el-button
              :icon="View"
              size="large"
              @click="preview = info!.file!"
              >{{ t("share.preview") }}</el-button
            ><a
              v-if="info.allowDownload"
              :href="`/api/public/shares/${token}/download`"
              :aria-busy="downloading"
              @click.prevent="download(`/api/public/shares/${token}/download`)"
              class="el-button el-button--primary el-button--large"
              ><el-icon><Download /></el-icon>{{ t("files.download") }}</a
            >
          </template>
        </div>
        <section
          v-if="info.mode === 'COLLECT'"
          class="collect-panel"
          aria-live="polite"
        >
          <p>{{ t("share.collectHelp") }}</p>
          <input
            type="file"
            multiple
            :disabled="collectBusy"
            :aria-label="t('share.submitFiles')"
            @change="collectFiles"
          />
          <p>
            {{
              collectBusy
                ? t("share.uploading")
                : t("share.receivedPrefix") + collected + t("share.filesSuffix")
            }}
          </p>
          <el-alert
            v-if="error"
            :title="error"
            type="error"
            :closable="false"
          />
        </section>
        <template v-else-if="info.file.kind === 'FOLDER'"
          ><p>{{ t("share.dynamic") }}</p>
          <div v-if="info.allowDownload" class="share-selection">
            <el-checkbox :model-value="allSelected()" @change="togglePage">{{
              t("drive.selectPage")
            }}</el-checkbox
            ><span
              >{{ t("drive.selected") }} {{ selected.length }}
              {{ t("drive.crossFolder") }}</span
            ><SelectionBasket v-model="selected" /><el-button
              v-if="selected.length"
              size="small"
              @click="selected = []"
              >{{ t("drive.deselect") }}</el-button
            >
          </div>
          <el-table :data="children"
            ><el-table-column v-if="info.allowDownload" width="45"
              ><template #default="{ row }"
                ><el-checkbox
                  :model-value="isSelected(row)"
                  :aria-label="t('files.selectPrefix') + row.name"
                  @change="toggle(row)" /></template></el-table-column
            ><el-table-column :label="t('files.name')"
              ><template #default="{ row }"
                ><el-button text @click="enter(row)"
                  >{{ row.kind === "FOLDER" ? "📁 " : ""
                  }}{{ row.name }}</el-button
                ></template
              ></el-table-column
            ><el-table-column :label="t('common.download')" width="90"
              ><template #default="{ row }"
                ><a
                  v-if="info.allowDownload && row.kind === 'FILE'"
                  :href="`/api/public/shares/${token}/entries/${row.id}/download`"
                  :aria-busy="downloading"
                  @click.prevent="
                    download(
                      `/api/public/shares/${token}/entries/${row.id}/download`,
                    )
                  "
                  >{{ t("common.download") }}</a
                ><el-button
                  v-else-if="info.allowDownload"
                  text
                  :disabled="zipBusy"
                  @click="zip([row])"
                  >{{ t("share.archive") }}</el-button
                ></template
              ></el-table-column
            ></el-table
          ><el-pagination
            v-if="total > 50"
            v-model:current-page="page"
            :total="total"
            :page-size="50"
            layout="prev,pager,next"
            @current-change="folder(stack.at(-1))" /><el-alert
            v-if="error"
            :title="error"
            type="error"
            :closable="false"
        /></template>
        <p v-if="!info.allowDownload && info.mode !== 'COLLECT'" class="muted">
          {{ t("share.previewOnly") }}
        </p></template
      >
    </section>
    <footer>{{ t("share.footer") }}</footer>
    <PreviewDialog
      :file="preview"
      :base="
        `/api/public/shares/${token}` +
        (info?.file?.kind === 'FOLDER' && preview
          ? `/entries/${preview.id}`
          : '')
      "
      :allow-download="info?.allowDownload"
      @close="preview = null"
    />
  </main>
</template>

<style scoped>
.share-selection {
  display: flex;
  gap: 12px;
  align-items: center;
  flex-wrap: wrap;
  margin: 16px 0;
  text-align: left;
}
</style>
