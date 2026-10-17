<script setup lang="ts">
import { ref, watch } from "vue";
import { api, errorText, type Entry } from "../api";
import { ElMessage } from "element-plus";
const props = defineProps<{ files: Entry[] | null; collect?: boolean }>();
const emit = defineEmits<{ close: []; created: [] }>();
const title = ref(""),
  hours = ref(168),
  code = ref(""),
  allow = ref(true),
  link = ref(""),
  busy = ref(false);
watch(
  () => props.files,
  (files) => {
    link.value = "";
    title.value = files?.length === 1 ? files[0]!.name : "我的文件合集";
    code.value = "";
  },
);
async function create() {
  busy.value = true;
  try {
    const { data } = await api.post("/shares/collection", {
      fileIds: props.files?.map((e) => e.id),
      title: title.value,
      hours: hours.value,
      code: code.value,
      allowDownload: allow.value,
      collect: props.collect || false,
    });
    link.value = location.origin + data.path;
    emit("created");
  } catch (e) {
    ElMessage.error(errorText(e));
  } finally {
    busy.value = false;
  }
}
</script>
<template>
  <el-dialog
    :model-value="!!files"
    :title="collect ? '创建文件收集入口' : '分享选中的文件'"
    width="min(560px,94vw)"
    append-to-body
    @close="emit('close')"
    ><p v-if="collect">
      访客只能上传，不能查看或下载已有文件。每个链接最多接收 100
      个文件，每文件最多 100 MiB，仍计入你的配额。
    </p>
    <p v-else>
      选中 {{ files?.length }} 项，组成一个分享链接；不会移动原文件。
    </p>
    <el-form label-position="top"
      ><el-form-item label="分享名称"
        ><el-input v-model="title" maxlength="180" /></el-form-item
      ><el-form-item label="有效时间（小时）"
        ><el-input-number v-model="hours" :min="1" :max="720" /></el-form-item
      ><el-form-item label="提取码（可选，至少4字符）"
        ><el-input v-model="code" maxlength="32" /></el-form-item
      ><el-checkbox v-if="!collect" v-model="allow"
        >允许下载</el-checkbox
      ></el-form
    ><el-input
      v-if="link"
      :model-value="link"
      readonly
      aria-label="合集分享链接"
    />
    <p v-if="link">请复制完整链接发送给对方，关闭后不再显示。</p>
    <template #footer
      ><el-button @click="emit('close')">关闭</el-button
      ><el-button v-if="!link" type="primary" :loading="busy" @click="create"
        >创建链接</el-button
      ></template
    ></el-dialog
  >
</template>
