<script setup lang="ts">
import { ref, watch, onBeforeUnmount } from "vue";
import { api, type Entry } from "../api";
import FileMark from "./FileMark.vue";
const props = defineProps<{ file: Entry }>(),
  url = ref("");
let generation = 0;
function clear() {
  if (url.value) URL.revokeObjectURL(url.value);
  url.value = "";
}
watch(
  () => [props.file.id, props.file.updatedAt],
  async () => {
    const mine = ++generation;
    clear();
    if (!props.file.mime?.startsWith("image/")) return;
    for (let i = 0; i < 6 && mine === generation; i++) {
      try {
        const r = await api.get(`/files/${props.file.id}/thumbnail`, {
          responseType: "blob",
        });
        if (mine !== generation) return;
        if (r.status === 200) {
          url.value = URL.createObjectURL(r.data);
          return;
        }
        await new Promise((r) => setTimeout(r, 1000));
      } catch {
        return;
      }
    }
  },
  { immediate: true },
);
onBeforeUnmount(() => {
  generation++;
  clear();
});
</script>
<template>
  <img
    v-if="url"
    :src="url"
    :alt="file.name"
    class="cn-thumbnail"
  /><FileMark v-else :kind="file.kind" :mime="file.mime" />
</template>
