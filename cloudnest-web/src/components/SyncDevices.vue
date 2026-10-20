<script setup lang="ts">
import { ref, watch, onBeforeUnmount } from "vue";
import { api, date, errorText } from "../api";
import { t } from "../i18n";
const props = defineProps<{ active: boolean }>();
interface Device {
  id: string;
  name: string;
  created_at: number;
  expires_at: number;
  last_seen_at: number;
  revoked: boolean;
}
const enabled = ref(false),
  loaded = ref(false),
  busy = ref(false);
const name = ref(""),
  failure = ref(""),
  secret = ref("");
const devices = ref<Device[]>([]);
let disposed = false;
let generation = 0;
async function load() {
  busy.value = true;
  failure.value = "";
  try {
    const caps = (await api.get<{ enabled: boolean }>("/sync/available")).data;
    if (disposed) return;
    enabled.value = caps.enabled;
    if (caps.enabled) {
      const result = (await api.get<Device[]>("/sync/devices")).data;
      if (disposed) return;
      devices.value = result;
    }
    loaded.value = true;
  } catch (e) {
    if (!disposed) failure.value = errorText(e);
  } finally {
    if (!disposed) busy.value = false;
  }
}
async function create() {
  if (busy.value || !name.value.trim()) return;
  busy.value = true;
  secret.value = "";
  failure.value = "";
  const ticket = ++generation;
  try {
    const result = (
      await api.post<{ token: string }>("/sync/devices", {
        name: name.value.trim(),
      })
    ).data;
    if (disposed || !props.active || ticket !== generation) return;
    secret.value = result.token;
    name.value = "";
    await load();
  } catch (e) {
    if (!disposed) failure.value = errorText(e);
  } finally {
    if (!disposed) busy.value = false;
  }
}
async function revoke(id: string) {
  busy.value = true;
  failure.value = "";
  try {
    await api.delete(`/sync/devices/${id}`);
    secret.value = "";
    await load();
  } catch (e) {
    if (!disposed) failure.value = errorText(e);
  } finally {
    if (!disposed) busy.value = false;
  }
}
async function copy() {
  try {
    await navigator.clipboard.writeText(secret.value);
  } catch {
    failure.value = t("syncDevices.copyFailed");
  }
}
watch(
  () => props.active,
  (active) => {
    if (active) void load();
    else {
      generation++;
      secret.value = "";
    }
  },
  { immediate: true },
);
onBeforeUnmount(() => {
  disposed = true;
  secret.value = "";
});
</script>
<template>
  <section class="sync-devices">
    <h3>{{ t("syncDevices.title") }}</h3>
    <el-alert
      :title="t('syncDevices.notice')"
      type="warning"
      :closable="false"
    />
    <p v-if="failure" role="alert">{{ failure }}</p>
    <el-button :loading="busy" @click="load">{{
      t("agentRuntime.refresh")
    }}</el-button>
    <p v-if="loaded && !enabled">{{ t("syncDevices.disabled") }}</p>
    <template v-if="enabled">
      <el-form label-position="top" @submit.prevent="create">
        <el-form-item :label="t('syncDevices.name')"
          ><el-input v-model="name" :maxlength="80"
        /></el-form-item>
        <el-button :disabled="!name.trim()" :loading="busy" @click="create">{{
          t("syncDevices.create")
        }}</el-button>
      </el-form>
      <div v-if="secret">
        <p>{{ t("syncDevices.once") }}</p>
        <el-input
          :model-value="secret"
          readonly
          type="password"
          show-password
        /><el-button @click="copy">{{ t("syncDevices.copy") }}</el-button>
      </div>
      <article v-for="device in devices" :key="device.id">
        <strong>{{ device.name }}</strong
        ><span>{{
          device.revoked ? t("syncDevices.revoked") : t("syncDevices.active")
        }}</span>
        <p>
          {{ t("syncDevices.expires") }} {{ date(device.expires_at) }} ·
          {{ t("syncDevices.seen") }}
          {{
            device.last_seen_at
              ? date(device.last_seen_at)
              : t("syncDevices.never")
          }}
        </p>
        <el-button
          v-if="!device.revoked"
          :disabled="busy"
          @click="revoke(device.id)"
          >{{ t("syncDevices.revoke") }}</el-button
        >
      </article>
    </template>
  </section>
</template>
<style scoped>
.sync-devices {
  display: grid;
  gap: 16px;
  min-width: 0;
}
article {
  border: 1px solid var(--el-border-color);
  border-radius: 8px;
  padding: 14px;
  overflow-wrap: anywhere;
}
article span {
  margin-left: 12px;
}
</style>
