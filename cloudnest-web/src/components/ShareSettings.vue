<script setup lang="ts">
import { ref, watch } from "vue";
import { api, errorText } from "../api";
import { ElMessage } from "element-plus";
const props = defineProps<{
    share: { id: string; expiresAt: number; allowDownload: boolean } | null;
  }>(),
  emit = defineEmits<{ close: []; changed: [] }>();
const statistics = ref<any>(null);
watch(
  () => props.share,
  async (value) => {
    statistics.value = null;
    if (value)
      try {
        statistics.value = (await api.get(`/shares/${value.id}/stats`)).data;
      } catch {}
  },
);
const hours = ref(168),
  allow = ref(true),
  replaceCode = ref(false),
  code = ref(""),
  busy = ref(false);
watch(
  () => props.share,
  (s) => {
    if (s) {
      hours.value = Math.max(
        1,
        Math.min(720, Math.ceil((s.expiresAt - Date.now()) / 3600000)),
      );
      allow.value = s.allowDownload;
      replaceCode.value = false;
      code.value = "";
    }
  },
);
async function save() {
  if (!props.share) return;
  busy.value = true;
  try {
    await api.patch(`/shares/${props.share.id}`, {
      hours: hours.value,
      allowDownload: allow.value,
      ...(replaceCode.value ? { code: code.value } : {}),
    });
    ElMessage.success("分享设置已更新，已有提取码授权已撤销");
    emit("changed");
    emit("close");
  } catch (e) {
    ElMessage.error(errorText(e));
  } finally {
    busy.value = false;
  }
}
</script>
<template>
  <el-dialog
    :model-value="!!share"
    title="修改分享设置"
    width="min(480px,94vw)"
    @close="emit('close')"
    ><p v-if="statistics">
      访问 {{ statistics.totals.views }} 次 · 下载请求
      {{ statistics.totals.downloads }} 次 · 收集文件
      {{ statistics.totals.collected }} 个
    </p>
    <el-form label-position="top"
      ><el-form-item label="从现在起有效小时数"
        ><el-input-number v-model="hours" :min="1" :max="720" /></el-form-item
      ><el-form-item label="下载权限"
        ><el-switch
          v-model="allow"
          active-text="允许下载"
          inactive-text="仅预览" /></el-form-item
      ><el-checkbox v-model="replaceCode">修改或移除提取码</el-checkbox
      ><el-form-item v-if="replaceCode" label="新提取码（4～32 字符，留空移除）"
        ><el-input v-model="code" maxlength="32" /></el-form-item
    ></el-form>
    <p>保存后，访客需重新验证提取码。分享链接保持不变。</p>
    <template #footer
      ><el-button @click="emit('close')">取消</el-button
      ><el-button type="primary" :loading="busy" @click="save"
        >保存分享设置</el-button
      ></template
    ></el-dialog
  >
</template>
