import { ref, type Ref } from "vue";
import { ElMessage } from "element-plus";
import type { Entry } from "./api";

export function useSelection(
  items: Ref<Entry[]>,
  folderPath: () => string = () => "",
) {
  const selected = ref<Entry[]>([]);
  const isSelected = (entry: Entry) =>
    selected.value.some((e) => e.id === entry.id);
  const allSelected = () =>
    items.value.length > 0 && items.value.every(isSelected);
  function toggle(entry: Entry) {
    if (isSelected(entry))
      selected.value = selected.value.filter((e) => e.id !== entry.id);
    else if (selected.value.length < 100)
      selected.value.push({
        ...entry,
        path:
          entry.path || [folderPath(), entry.name].filter(Boolean).join("/"),
      });
    else ElMessage.warning("最多选择 100 项，请分批打包或操作");
  }
  function togglePage() {
    if (allSelected()) {
      const ids = new Set(items.value.map((e) => e.id));
      selected.value = selected.value.filter((e) => !ids.has(e.id));
    } else {
      const additions = items.value.filter((e) => !isSelected(e));
      if (selected.value.length + additions.length > 100) {
        ElMessage.warning("最多选择 100 项，请分批选择");
        return;
      }
      selected.value.push(
        ...additions.map((entry) => ({
          ...entry,
          path:
            entry.path || [folderPath(), entry.name].filter(Boolean).join("/"),
        })),
      );
    }
  }
  return { selected, isSelected, allSelected, toggle, togglePage };
}
