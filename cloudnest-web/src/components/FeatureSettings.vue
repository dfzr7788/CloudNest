<script setup lang="ts">
import { ref, watch, onUnmounted } from "vue";
import { api, errorText } from "../api";
import { t } from "../i18n";
const props = defineProps<{ active: boolean }>();
const keys = ["sync", "agentTools", "agentRuntime", "agentApprovals"] as const;
const values = ref<Record<string, string>>({});
const busy = ref(false),
  loaded = ref(false),
  failure = ref("");
let generation = 0;
onUnmounted(() => generation++);
watch(
  () => props.active,
  async (active) => {
    const token = ++generation;
    loaded.value = false;
    if (!active) return;
    busy.value = true;
    try {
      const { data } = await api.get("/admin/config/features");
      if (token !== generation) return;
      values.value = data;
      loaded.value = true;
      failure.value = "";
    } catch (e) {
      if (token === generation) failure.value = errorText(e);
    } finally {
      if (token === generation) busy.value = false;
    }
  },
  { immediate: true },
);
async function change(key: string, value: boolean | string | number) {
  const token = generation;
  busy.value = true;
  try {
    await api.put("/admin/config/features", { [key]: String(value) });
    if (token !== generation) return;
    values.value[key] = String(value);
    failure.value = "";
  } catch (e) {
    if (token === generation) failure.value = errorText(e);
  } finally {
    if (token === generation) busy.value = false;
  }
}
</script>
<template>
  <section class="feature-settings" v-loading="busy">
    <h3>{{ t("features.title") }}</h3>
    <p class="muted">{{ t("features.help") }}</p>
    <el-alert v-if="failure" :title="failure" type="error" :closable="false" />
    <el-form label-position="top" class="settings-form">
      <el-form-item
        v-for="key in keys"
        :key="key"
        :label="t(`features.${key}`)"
      >
        <el-switch
          :model-value="values[key] === 'true'"
          :aria-label="t(`features.${key}`)"
          :disabled="busy || !loaded"
          @change="(value: boolean | string | number) => change(key, value)"
        />
        <p class="muted">{{ t(`features.${key}Help`) }}</p>
      </el-form-item>
    </el-form>
    <el-alert
      v-if="values.agentRuntime === 'true' && values.agentTools !== 'true'"
      :title="t('features.runtimeDependency')"
      type="warning"
      :closable="false"
    />
    <p class="muted">{{ t("features.other") }}</p>
  </section>
</template>
