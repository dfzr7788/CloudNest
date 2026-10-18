<script setup lang="ts">
import { ref, watch } from "vue";
import { api, errorText, type Entry } from "../api";
import { ElMessage } from "element-plus";
import { Folder, ArrowRight } from "@element-plus/icons-vue";
const props = defineProps<{ open: boolean; rootId: string }>(),
  emit = defineEmits<{ close: []; choose: [string] }>();
const current = ref(""),
  folders = ref<Entry[]>([]),
  crumbs = ref<{ id: string; name: string }[]>([]),
  busy = ref(false);
async function load(id: string) {
  busy.value = true;
  try {
    current.value = id;
    const [list, path] = await Promise.all([
      api.get<Entry[]>("/folders", { params: { parentId: id } }),
      api.get(`/folders/${id}/breadcrumbs`),
    ]);
    folders.value = list.data;
    crumbs.value = path.data;
  } catch (e) {
    ElMessage.error(errorText(e));
  } finally {
    busy.value = false;
  }
}
watch(
  () => props.open,
  (v) => {
    if (v) load(props.rootId);
  },
);
</script>
<template>
  <el-dialog class="cn-dialog"
    :model-value="open"
    title="移动到文件夹"
    width="500px"
    @close="emit('close')"
    ><el-breadcrumb separator="/"
      ><el-breadcrumb-item v-for="c in crumbs" :key="c.id"
        ><button class="crumb-button" @click="load(c.id)">
          {{ c.name }}
        </button></el-breadcrumb-item
      ></el-breadcrumb
    >
    <div class="folder-picker" v-loading="busy">
      <button v-for="f in folders" :key="f.id" @click="load(f.id)">
        <el-icon><Folder /></el-icon><span>{{ f.name }}</span
        ><el-icon><ArrowRight /></el-icon>
      </button>
      <p v-if="!folders.length" class="muted">此目录下没有子文件夹</p>
    </div>
    <p class="muted">选择上方目录作为目标。</p>
    <template #footer
      ><el-button @click="emit('close')">取消</el-button
      ><el-button
        type="primary"
        :disabled="busy || !current"
        @click="emit('choose', current)"
        >移动到这里</el-button
      ></template
    ></el-dialog
  >
</template>
