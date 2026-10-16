<script setup lang="ts">
import { onMounted, ref } from "vue";
import { api, errorText, bytes, date, type Entry } from "../api";
import { Cloudy, Lock, Download, View } from "@element-plus/icons-vue";
import FileMark from "../components/FileMark.vue";
import PreviewDialog from "../components/PreviewDialog.vue";
const props = defineProps<{ token: string }>(),
  info = ref<{
    locked: boolean;
    expiresAt: number;
    file?: Entry;
    allowDownload?: boolean;
  }>(),
  code = ref(""),
  error = ref(""),
  busy = ref(false),
  preview = ref<Entry | null>(null);
const children = ref<Entry[]>([]),
  stack = ref<string[]>([]),
  selected = ref<Entry[]>([]),
  page = ref(1),
  total = ref(0),
  zipBusy = ref(false);
async function folder(id?: string) {
  try {
    const { data } = await api.get(`/public/shares/${props.token}/entries`, {
      params: { parentId: id, page: page.value },
    });
    children.value = data.items;
    total.value = data.total;
    selected.value = [];
  } catch (e) {
    error.value = errorText(e);
  }
}
function enter(e: Entry) {
  if (e.kind === "FOLDER") {
    stack.value.push(e.id);
    page.value = 1;
    folder(e.id);
  } else preview.value = e;
}
function back() {
  stack.value.pop();
  page.value = 1;
  folder(stack.value.at(-1));
}
async function zip() {
  zipBusy.value = true;
  try {
    const { data } = await api.post(`/public/shares/${props.token}/downloads`, {
      fileIds: selected.value.filter((e) => e.kind === "FILE").map((e) => e.id),
    });
    for (let n = 0; n < 600; n++) {
      const status = (
        await api.get(`/public/shares/${props.token}/downloads/${data.id}`)
      ).data;
      if (status.status === "READY") {
        location.assign(
          `/api/public/shares/${props.token}/downloads/${data.id}/file`,
        );
        return;
      }
      if (status.status === "FAILED") throw new Error(status.error);
      await new Promise((r) => setTimeout(r, 1000));
    }
    throw new Error("任务耗时较长，请稍后重试");
  } catch (e) {
    error.value = errorText(e);
  } finally {
    zipBusy.value = false;
  }
}
async function load() {
  busy.value = true;
  error.value = "";
  try {
    info.value = (await api.get(`/public/shares/${props.token}`)).data;
    if (info.value?.file?.kind === "FOLDER") await folder();
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
      <router-link to="/" class="brand"
        ><el-icon><Cloudy /></el-icon> CloudNest</router-link
      ><span>让文件自由抵达</span>
    </header>
    <section class="share-card" v-loading="busy">
      <span class="eyebrow">SHARED WITH YOU</span
      ><template v-if="error && !info"
        ><h1>分享暂时无法访问</h1>
        <el-alert :title="error" type="error" :closable="false" /><el-button
          @click="load"
          >重新加载</el-button
        ></template
      ><template v-else-if="info?.locked"
        ><div class="share-lock">
          <el-icon><Lock /></el-icon>
        </div>
        <h1>一份专属分享，等待开启</h1>
        <p class="muted">请输入分享者提供的提取码</p>
        <form @submit.prevent="verify">
          <el-input
            v-model="code"
            placeholder="请输入提取码"
            size="large"
            maxlength="32"
            aria-label="提取码"
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
            >查看文件</el-button
          >
        </form></template
      ><template v-else-if="info?.file"
        ><FileMark :kind="info.file.kind" :mime="info.file.mime" />
        <h1>{{ info.file.name }}</h1>
        <p class="muted">
          {{ bytes(info.file.size) }} · 有效期至 {{ date(info.expiresAt) }}
        </p>
        <div class="share-actions">
          <template v-if="info.file.kind === 'FOLDER'"
            ><el-button v-if="stack.length" @click="back">返回上级</el-button
            ><el-button
              v-if="
                info.allowDownload && selected.some((e) => e.kind === 'FILE')
              "
              :loading="zipBusy"
              @click="zip"
              >打包选中文件</el-button
            ></template
          >
          <template v-else>
            <el-button :icon="View" size="large" @click="preview = info!.file!"
              >在线预览</el-button
            ><a
              v-if="info.allowDownload"
              :href="`/api/public/shares/${token}/download`"
              class="el-button el-button--primary el-button--large"
              ><el-icon><Download /></el-icon>下载文件</a
            >
          </template>
        </div>
        <template v-if="info.file.kind === 'FOLDER'"
          ><p>动态分享：目录内容随分享者整理而变化。</p>
          <el-table :data="children" @selection-change="selected = $event"
            ><el-table-column
              v-if="info.allowDownload"
              type="selection"
              width="45"
              :selectable="(e: Entry) => e.kind === 'FILE'"
            /><el-table-column label="名称"
              ><template #default="{ row }"
                ><el-button text @click="enter(row)"
                  >{{ row.kind === "FOLDER" ? "📁 " : ""
                  }}{{ row.name }}</el-button
                ></template
              ></el-table-column
            ><el-table-column label="下载" width="90"
              ><template #default="{ row }"
                ><a
                  v-if="info.allowDownload && row.kind === 'FILE'"
                  :href="`/api/public/shares/${token}/entries/${row.id}/download`"
                  >下载</a
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
        <p v-if="!info.allowDownload" class="muted">
          分享者仅开放了预览权限
        </p></template
      >
    </section>
    <footer>由 CloudNest 安全提供文件分享</footer>
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
