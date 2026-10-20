<script setup lang="ts">
import { t } from "../../i18n";
const type = defineModel<string>("type", { required: true });
const size = defineModel<number>("size", { required: true });
const days = defineModel<number>("days", { required: true });
function reset() {
  type.value = "all";
  size.value = 0;
  days.value = 0;
}
</script>
<template>
  <el-popover
    placement="bottom-end"
    trigger="click"
    :width="280"
    popper-class="cn-filter-popover"
    ><template #reference
      ><el-button
        :type="type !== 'all' || size || days ? 'primary' : undefined"
        :plain="type !== 'all' || !!size || !!days"
        >{{ t("files.filter")
        }}{{
          type !== "all" || size || days ? t("files.filterApplied") : ""
        }}</el-button
      ></template
    >
    <div class="filter-fields">
      <label
        >{{ t("files.type")
        }}<el-select
          :teleported="false"
          v-model="type"
          :aria-label="t('files.type')"
          ><el-option :label="t('files.allTypes')" value="all" /><el-option
            :label="t('files.images')"
            value="image" /><el-option
            :label="t('files.documents')"
            value="document" /><el-option
            :label="t('files.folder')"
            value="folder" /></el-select></label
      ><label
        >{{ t("files.fileSize")
        }}<el-select
          :teleported="false"
          v-model="size"
          :aria-label="t('files.fileSize')"
          ><el-option :label="t('files.allSizes')" :value="0" /><el-option
            :label="t('files.atLeastTen')"
            :value="10" /><el-option
            :label="t('files.atLeastHundred')"
            :value="100" /></el-select></label
      ><label
        >{{ t("files.modified")
        }}<el-select
          :teleported="false"
          v-model="days"
          :aria-label="t('files.modifiedFilter')"
          ><el-option :label="t('files.allTimes')" :value="0" /><el-option
            :label="t('files.lastWeek')"
            :value="7" /><el-option
            :label="t('files.lastMonth')"
            :value="30" /></el-select></label
      ><el-button @click="reset">{{ t("files.resetFilters") }}</el-button>
    </div></el-popover
  >
</template>
