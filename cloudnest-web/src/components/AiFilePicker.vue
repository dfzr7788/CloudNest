<script setup lang="ts">
import { ref, watch } from "vue";
import { api, errorText, type Entry, type Page } from "../api";
const props = defineProps<{
  modelValue: string[];
  foldersOnly?: boolean;
  filesOnly?: boolean;
  max?: number;
}>();
const emit = defineEmits<{ "update:modelValue": [string[]] }>();
const open = ref(false),
  entries = ref<Entry[]>([]),
  error = ref(""),
  busy = ref(false),
  page = ref(1),
  total = ref(0),
  current = ref<string>();
const path = ref<{ id: string | undefined; name: string }[]>([
  { id: undefined, name: "我的文件" },
]);
const names = ref<Record<string, string>>({});
async function load() {
  busy.value = true;
  error.value = "";
  try {
    const data = (
      await api.get<Page>("/entries", {
        params: { parentId: current.value, page: page.value, pageSize: 50 },
      })
    ).data;
    entries.value = data.items;
    total.value = data.total;
    for (const e of data.items) names.value[e.id] = e.name;
    if (!current.value) {
      current.value = data.parentId;
      path.value[0] = { id: data.parentId, name: "我的文件" };
    }
  } catch (e) {
    error.value = errorText(e);
  } finally {
    busy.value = false;
  }
}
function toggle(e: Entry) {
  const ids = props.modelValue.includes(e.id)
    ? props.modelValue.filter((id) => id !== e.id)
    : [...props.modelValue, e.id];
  if (ids.length > (props.max || 100)) {
    error.value = `最多选择 ${props.max || 100} 项`;
    return;
  }
  emit("update:modelValue", ids);
}
async function enter(e: Entry) {
  current.value = e.id;
  path.value.push({ id: e.id, name: e.name });
  page.value = 1;
  await load();
}
async function up() {
  if (path.value.length < 2) return;
  path.value.pop();
  current.value = path.value.at(-1)?.id;
  page.value = 1;
  await load();
}
watch(open, (value) => {
  if (value) void load();
});
</script>
<template>
  <div class="picker">
    <el-button @click="open = true"
      >选择{{
        foldersOnly ? "目标文件夹" : filesOnly ? "文件" : "文件或文件夹"
      }}</el-button
    >
    <span> 已选 {{ modelValue.length }} 项</span>
    <div class="chosen">
      <el-tag
        v-for="id in modelValue"
        :key="id"
        closable
        @close="
          emit(
            'update:modelValue',
            modelValue.filter((x) => x !== id),
          )
        "
        >{{ names[id] || "已选项目" }}</el-tag
      >
    </div>
    <el-dialog
      v-model="open"
      title="选择 AI 处理范围"
      width="650px"
      append-to-body
    >
      <p>{{ path.map((x) => x.name).join(" / ") }}</p>
      <el-button :disabled="path.length < 2" @click="up">返回上级</el-button>
      <el-button
        v-if="!filesOnly && current"
        @click="
          toggle({
            id: current,
            name: path.at(-1)?.name || '我的文件',
            kind: 'FOLDER',
          } as Entry)
        "
        >选择当前文件夹</el-button
      >
      <p v-if="error" role="alert">{{ error }}</p>
      <div v-loading="busy" class="entries">
        <div v-for="entry in entries" :key="entry.id" class="entry">
          <el-checkbox
            :model-value="modelValue.includes(entry.id)"
            :disabled="
              (foldersOnly && entry.kind !== 'FOLDER') ||
              (filesOnly && entry.kind !== 'FILE')
            "
            @change="toggle(entry)"
            >{{ entry.name }}</el-checkbox
          >
          <el-button v-if="entry.kind === 'FOLDER'" link @click="enter(entry)"
            >进入</el-button
          >
        </div>
        <el-empty v-if="!entries.length && !busy" description="此目录为空" />
      </div>
      <el-pagination
        v-model:current-page="page"
        :total="total"
        :page-size="50"
        layout="prev, pager, next"
        @current-change="load"
      />
      <template #footer
        ><el-button type="primary" @click="open = false"
          >完成选择</el-button
        ></template
      >
    </el-dialog>
  </div>
</template>
<style scoped>
.entry {
  display: flex;
  justify-content: space-between;
  gap: 12px;
  padding: 8px 0;
}
.entry :deep(.el-checkbox) {
  max-width: 85%;
  height: auto;
  white-space: normal;
}
.entry :deep(.el-checkbox__label) {
  white-space: normal;
  overflow-wrap: anywhere;
}
.entries {
  max-height: 360px;
  overflow: auto;
  margin: 12px 0;
}
.chosen {
  display: flex;
  gap: 6px;
  flex-wrap: wrap;
  margin: 8px 0;
}
</style>
