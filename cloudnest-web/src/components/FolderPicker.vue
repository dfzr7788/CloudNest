<script setup lang="ts">
import { onUnmounted, ref, watch } from "vue";
import { api, errorText, type Entry } from "../api";
import { ElMessage } from "element-plus";
import { Folder, ArrowRight } from "@element-plus/icons-vue";
const props = defineProps<{
    open: boolean;
    rootId: string;
    title?: string;
    chooseLabel?: string;
    teamId?: string;
  }>(),
  emit = defineEmits<{ close: []; choose: [string] }>();
const current = ref(""),
  folders = ref<Entry[]>([]),
  crumbs = ref<{ id: string; name: string }[]>([]),
  busy = ref(false);
let generation = 0;
onUnmounted(() => generation++);
async function load(id: string) {
  const token = ++generation;
  busy.value = true;
  current.value = "";
  folders.value = [];
  crumbs.value = [];
  try {
    if (props.teamId) {
      const { data } = await api.get(`/teams/${props.teamId}/folders`, {
        params: { parentId: id },
      });
      if (token !== generation) return;
      folders.value = data.items;
      crumbs.value = data.breadcrumbs;
    } else {
      const [list, path] = await Promise.all([
        api.get<Entry[]>("/folders", { params: { parentId: id } }),
        api.get(`/folders/${id}/breadcrumbs`),
      ]);
      if (token !== generation) return;
      folders.value = list.data;
      crumbs.value = path.data;
    }
    current.value = id;
  } catch (e) {
    if (token === generation) ElMessage.error(errorText(e));
  } finally {
    if (token === generation) busy.value = false;
  }
}
watch(
  () => [props.open, props.rootId, props.teamId],
  () => {
    if (props.open) void load(props.rootId);
    else {
      generation++;
      current.value = "";
      busy.value = false;
    }
  },
  { immediate: true },
);
</script>
<template>
  <el-dialog
    class="cn-dialog"
    :model-value="open"
    :title="title || '移动到文件夹'"
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
        >{{ chooseLabel || "移动到这里" }}</el-button
      ></template
    ></el-dialog
  >
</template>
