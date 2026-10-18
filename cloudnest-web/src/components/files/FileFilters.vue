<script setup lang="ts">
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
        >筛选{{ type !== "all" || size || days ? " · 已生效" : "" }}</el-button
      ></template
    >
    <div class="filter-fields">
      <label
        >文件类型<el-select
          :teleported="false"
          v-model="type"
          aria-label="文件类型"
          ><el-option label="全部类型" value="all" /><el-option
            label="图片"
            value="image" /><el-option
            label="文档"
            value="document" /><el-option
            label="文件夹"
            value="folder" /></el-select></label
      ><label
        >文件大小<el-select
          :teleported="false"
          v-model="size"
          aria-label="文件大小"
          ><el-option label="全部大小" :value="0" /><el-option
            label="至少 10 MiB"
            :value="10" /><el-option
            label="至少 100 MiB"
            :value="100" /></el-select></label
      ><label
        >修改时间<el-select
          :teleported="false"
          v-model="days"
          aria-label="修改时间筛选"
          ><el-option label="全部时间" :value="0" /><el-option
            label="近 7 天修改"
            :value="7" /><el-option
            label="近 30 天修改"
            :value="30" /></el-select></label
      ><el-button @click="reset">重置全部筛选</el-button>
    </div></el-popover
  >
</template>
