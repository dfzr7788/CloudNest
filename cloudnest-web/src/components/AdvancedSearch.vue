<script setup lang="ts">
import { ref, onUnmounted } from "vue";
import { api, errorText, type Entry } from "../api";
import { ElMessage } from "element-plus";
const emit = defineEmits<{ choose: [Entry]; folder: [string] }>();
const open = ref(false),
  q = ref(""),
  tag = ref(""),
  content = ref(true),
  page = ref(1),
  result = ref<any>({ items: [], total: 0, pending: 0 }),
  tags = ref<any[]>([]),
  busy = ref(false);
let timer: ReturnType<typeof setTimeout> | undefined;
async function search() {
  busy.value = true;
  try {
    result.value = (
      await api.get("/search", {
        params: {
          q: q.value,
          tag: tag.value,
          content: content.value,
          page: page.value,
        },
      })
    ).data;
  } catch (e) {
    ElMessage.error(errorText(e));
  } finally {
    busy.value = false;
    clearTimeout(timer);
    if (open.value && result.value.pending > 0)
      timer = setTimeout(search, 5000);
  }
}
async function show() {
  open.value = true;
  page.value = 1;
  tags.value = (await api.get("/tags")).data;
  await search();
}
function choose(entry: Entry) {
  if (entry.kind === "FOLDER") {
    emit("folder", entry.id);
    open.value = false;
  } else emit("choose", entry);
}
async function reindex() {
  try {
    await api.post("/search/reindex");
    await search();
    ElMessage.success("已加入后台索引队列");
  } catch (e) {
    ElMessage.error(errorText(e));
  }
}
function stopPolling() {
  clearTimeout(timer);
}
onUnmounted(stopPolling);
</script>
<template>
  <el-button size="small" @click="show">标签与正文搜索</el-button
  ><el-dialog class="cn-dialog"
    v-model="open"
    title="搜索全部目录"
    width="min(900px,94vw)"
    append-to-body
    @closed="stopPolling"
    ><div class="search-controls">
      <el-input
        v-model="q"
        placeholder="文件名或正文关键词"
        aria-label="高级搜索关键词"
        clearable
        @keyup.enter="
          page = 1;
          search();
        "
      /><el-select v-model="tag" placeholder="全部标签" clearable
        ><el-option
          v-for="t in tags"
          :key="t.tag"
          :label="t.tag + ' (' + t.count + ')'"
          :value="t.tag" /></el-select
      ><el-checkbox v-model="content">搜索文档正文</el-checkbox
      ><el-button
        type="primary"
        @click="
          page = 1;
          search();
        "
        >搜索</el-button
      >
    </div>
    <p>
      正文索引支持文本、Markdown、Word、Excel、PPT 和带文本层的 PDF；扫描件不做
      OCR。待处理 {{ result.pending }} 项。
    </p>
    <el-button size="small" @click="reindex">重建我的正文索引</el-button
    ><el-table :data="result.items" v-loading="busy"
      ><el-table-column label="名称"
        ><template #default="{ row }"
          ><el-button text @click="choose(row)">{{
            row.name
          }}</el-button></template
        ></el-table-column
      ><el-table-column prop="path" label="所在目录" /><el-table-column
        label="标签"
        ><template #default="{ row }">{{
          row.tags?.join("、")
        }}</template></el-table-column
      ><el-table-column width="100"
        ><template #default="{ row }"
          ><el-button
            text
            @click="
              emit('folder', row.parentId);
              open = false;
            "
            >打开位置</el-button
          ></template
        ></el-table-column
      ></el-table
    ><el-pagination
      v-model:current-page="page"
      :total="result.total"
      :page-size="50"
      layout="prev,pager,next"
      @current-change="search"
  /></el-dialog>
</template>
<style scoped>
.search-controls {
  display: flex;
  gap: 12px;
  flex-wrap: wrap;
}
.search-controls > .el-input {
  flex: 1;
  min-width: 220px;
}
.search-controls > .el-select {
  width: 180px;
}
p {
  font-size: 13px;
  color: var(--cn-muted);
  line-height: 1.8;
}
</style>
