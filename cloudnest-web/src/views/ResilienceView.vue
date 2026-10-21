<script setup lang="ts">
import { requestKey } from "../utils/requestKey";
import { onMounted, ref, watch } from "vue";
import { useRoute } from "vue-router";
import { ElMessage } from "element-plus";
import { api, errorText, bytes } from "../api";
import { t } from "../i18n";
import { useUser } from "../stores/user";
import TimeMachine from "../components/TimeMachine.vue";
import FolderPicker from "../components/FolderPicker.vue";
import LanguageSwitcher from "../components/common/LanguageSwitcher.vue";
import ThemeSwitcher from "../components/common/ThemeSwitcher.vue";
const user = useUser(),
  route = useRoute();
const folder = ref(""),
  picking = ref(false),
  failure = ref(""),
  busy = ref(false);
const folderLabel = ref("—");
watch(folder, async (id) => {
  if (!id) return;
  try {
    const { data } = await api.get(`/folders/${id}/breadcrumbs`);
    if (folder.value === id)
      folderLabel.value = data.map((entry: any) => entry.name).join(" / ");
  } catch {
    if (folder.value === id) folderLabel.value = t("next.chooseFolder");
  }
});
const scan = ref({
  enabled: false,
  intervalHours: 168,
  bytesPerSecond: 8388608,
});
const suggestions = ref<any[]>([]),
  contributions = ref<any[]>([]),
  scanResults = ref<any[]>([]),
  scanId = ref("");
const approval = ref({
  failureDomain: "",
  reliability: 50,
  performanceClass: "STANDARD",
});
async function loadAdmin() {
  if (!user.user?.administrator) return;
  const [p, tier, nodes] = await Promise.all([
    api.get("/resilience/integrity-policy"),
    api.get("/resilience/tiering"),
    api.get("/admin/contributions"),
  ]);
  scan.value = {
    enabled: p.data.enabled,
    intervalHours: p.data.interval_hours,
    bytesPerSecond: p.data.bytes_per_second,
  };
  suggestions.value = tier.data.suggestions;
  contributions.value = nodes.data;
}
onMounted(async () => {
  try {
    await user.load();
    folder.value = String(route.query.folder || user.user?.rootId || "");
    await loadAdmin();
  } catch (e) {
    failure.value = errorText(e);
  }
});
async function act(fn: () => Promise<unknown>) {
  busy.value = true;
  try {
    await fn();
    await loadAdmin();
    ElMessage.success(t("next.saved"));
  } catch (e) {
    ElMessage.error(errorText(e));
  } finally {
    busy.value = false;
  }
}
async function startScan() {
  await act(async () => {
    scanId.value = (
      await api.post("/resilience/integrity", {
        requestKey: requestKey(),
      })
    ).data.id;
  });
}
</script>
<template>
  <main class="next-page">
    <header class="next-toolbar">
      <router-link to="/">← {{ t("next.back") }}</router-link>
      <div class="next-toolbar"><LanguageSwitcher /><ThemeSwitcher /></div>
    </header>
    <h1>{{ t("next.resilience") }}</h1>
    <p class="muted">{{ t("next.resilienceIntro") }}</p>
    <el-alert v-if="failure" :title="failure" type="error" :closable="false" />
    <div class="next-toolbar">
      <span>{{ t("next.scope") }}</span
      ><span>{{ folderLabel }}</span
      ><el-button @click="picking = true">{{
        t("next.chooseFolder")
      }}</el-button>
    </div>
    <TimeMachine v-if="folder" :key="folder" :folder-id="folder" />
    <FolderPicker
      v-if="user.user"
      :open="picking"
      :root-id="user.user.rootId"
      :title="t('next.chooseFolder')"
      :choose-label="t('next.choose')"
      @close="picking = false"
      @choose="
        (id) => {
          folder = id;
          picking = false;
        }
      "
    />
    <template v-if="user.user?.administrator">
      <section class="next-panel" v-loading="busy">
        <h2>{{ t("next.integrity") }}</h2>
        <p>{{ t("next.integrityNotice") }}</p>
        <el-form class="next-form" label-position="top"
          ><el-form-item :label="t('next.scheduledScan')"
            ><el-switch v-model="scan.enabled" /></el-form-item
          ><el-form-item :label="t('next.intervalHours')"
            ><el-input-number
              v-model="scan.intervalHours"
              :min="1"
              :max="8760" /></el-form-item
          ><el-form-item :label="t('next.scanRate')"
            ><el-input-number
              v-model="scan.bytesPerSecond"
              :min="65536"
              :max="1073741824" /></el-form-item
          ><el-form-item
            ><el-button
              @click="act(() => api.put('/resilience/integrity-policy', scan))"
              >{{ t("next.save") }}</el-button
            ><el-button type="primary" @click="startScan">{{
              t("next.scanNow")
            }}</el-button></el-form-item
          ></el-form
        >
        <div class="next-toolbar">
          <el-input
            v-model="scanId"
            :placeholder="t('next.taskId')"
          /><el-button
            :disabled="!scanId"
            @click="
              act(async () => {
                scanResults = (await api.get(`/resilience/integrity/${scanId}`))
                  .data;
              })
            "
            >{{ t("next.scanResults") }}</el-button
          >
        </div>
        <el-table v-if="scanResults.length" :data="scanResults" max-height="300"
          ><el-table-column
            prop="blob_id"
            :label="t('next.object')"
            min-width="220" /><el-table-column
            prop="backend"
            :label="t('next.node')" /><el-table-column
            prop="status"
            :label="t('next.status')" /><el-table-column
            prop="error_code"
            :label="t('next.result')"
        /></el-table>
      </section>
      <section class="next-panel">
        <h2>{{ t("next.tiering") }}</h2>
        <p>{{ t("next.tieringNotice") }}</p>
        <el-table :data="suggestions"
          ><el-table-column :label="t('next.name')"
            ><template #default="{ row }">{{
              row.file.name
            }}</template></el-table-column
          ><el-table-column :label="t('next.size')"
            ><template #default="{ row }">{{
              bytes(row.file.size)
            }}</template></el-table-column
          ><el-table-column :label="t('next.candidates')"
            ><template #default="{ row }">{{
              row.candidates.map((n: any) => n.name).join(" · ")
            }}</template></el-table-column
          ></el-table
        >
      </section>
      <section class="next-panel" v-loading="busy">
        <h2>{{ t("next.nodeApproval") }}</h2>
        <p>{{ t("next.nodeApprovalNotice") }}</p>
        <el-form class="next-form" label-position="top"
          ><el-form-item :label="t('next.failureDomain')"
            ><el-input v-model="approval.failureDomain" /></el-form-item
          ><el-form-item :label="t('next.reliability')"
            ><el-input-number
              v-model="approval.reliability"
              :min="0"
              :max="100" /></el-form-item
          ><el-form-item :label="t('next.performance')"
            ><el-select v-model="approval.performanceClass"
              ><el-option
                v-for="v in ['FAST', 'STANDARD', 'CAPACITY']"
                :key="v"
                :value="v"
                :label="t(`next.${v}`)" /></el-select></el-form-item
        ></el-form>
        <el-table :data="contributions"
          ><el-table-column
            prop="name"
            :label="t('next.name')"
          /><el-table-column
            prop="endpoint"
            :label="t('next.endpoint')"
            min-width="220"
          /><el-table-column :label="t('next.status')"
            ><template #default="{ row }">{{
              t(`next.${row.status}`)
            }}</template></el-table-column
          ><el-table-column :label="t('next.actions')"
            ><template #default="{ row }"
              ><el-button
                v-if="row.status === 'REVIEWED'"
                :disabled="!approval.failureDomain"
                @click="
                  act(() =>
                    api.post(
                      `/admin/contributions/${row.id}/approve`,
                      approval,
                    ),
                  )
                "
                >{{ t("next.approve") }}</el-button
              ></template
            ></el-table-column
          ></el-table
        >
      </section>
    </template>
  </main>
</template>
