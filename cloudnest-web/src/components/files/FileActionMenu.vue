<script setup lang="ts">
import { t } from "../../i18n";
import { MoreFilled } from "@element-plus/icons-vue";
import type { Entry } from "../../api";
import type { FileAction } from "./actions";
defineProps<{ file: Entry }>();
const emit = defineEmits<{ action: [action: FileAction] }>();
</script>
<template>
  <el-dropdown trigger="click" @command="emit('action', $event)"
    ><el-button
      text
      :icon="MoreFilled"
      :aria-label="file.name + t('files.actionSuffix')"
    /><template #dropdown
      ><el-dropdown-menu>
        <el-dropdown-item v-if="file.kind === 'FILE'" command="download">{{
          t("files.download")
        }}</el-dropdown-item>
        <el-dropdown-item command="share">{{
          t("files.share")
        }}</el-dropdown-item>
        <el-dropdown-item command="favorite">{{
          file.favorite ? t("files.unfavorite") : t("files.favorite")
        }}</el-dropdown-item>
        <el-dropdown-item command="rename">{{
          t("files.rename")
        }}</el-dropdown-item>
        <el-dropdown-item command="move">{{
          t("files.move")
        }}</el-dropdown-item>
        <el-dropdown-item command="tags">{{
          t("files.tags")
        }}</el-dropdown-item>
        <el-dropdown-item v-if="file.kind === 'FILE'" command="versions">{{
          t("files.versions")
        }}</el-dropdown-item>
        <el-dropdown-item v-if="file.kind === 'FOLDER'" command="collect">{{
          t("files.collect")
        }}</el-dropdown-item>
        <el-dropdown-item command="storage">{{
          t("files.storage")
        }}</el-dropdown-item>
        <el-dropdown-item command="zip">{{ t("files.zip") }}</el-dropdown-item>
        <el-dropdown-item command="delete" divided class="cn-danger-action">{{
          t("files.trash")
        }}</el-dropdown-item>
      </el-dropdown-menu></template
    ></el-dropdown
  >
</template>
