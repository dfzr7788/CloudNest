<script setup lang="ts">
import { t } from "../../i18n";
import { computed } from "vue";
import {
  FolderOpened,
  Star,
  Clock,
  Upload,
  Share,
  Delete,
  MoreFilled,
  Connection,
} from "@element-plus/icons-vue";
import type { User } from "../../api";
import CloudNestLogo from "../brand/CloudNestLogo.vue";
import StorageUsage from "./StorageUsage.vue";
defineProps<{ section: string; collection: string; user: User | null }>();
const emit = defineEmits<{
  collection: [value: string];
  section: [value: string];
  profile: [];
}>();
const links = computed(() => [
  { key: "all", label: t("nav.files"), icon: FolderOpened },
  { key: "favorites", label: t("nav.favorites"), icon: Star },
  { key: "opened", label: t("nav.recent"), icon: Clock },
  { key: "uploaded", label: t("nav.uploaded"), icon: Upload },
]);
</script>
<template>
  <aside class="sidebar">
    <router-link to="/" class="sidebar-brand" aria-label="CloudNest"
      ><CloudNestLogo
    /></router-link>
    <div class="workspace-caption">{{ t("nav.workspace") }}</div>
    <nav :aria-label="t('nav.navigation')">
      <el-tooltip
        v-for="link in links"
        :key="link.key"
        :content="link.label"
        placement="right"
        :show-after="600"
        ><button
          :aria-label="link.label"
          :aria-current="
            section === 'drive' && collection === link.key ? 'page' : undefined
          "
          :class="{ active: section === 'drive' && collection === link.key }"
          @click="emit('collection', link.key)"
        >
          <el-icon><component :is="link.icon" /></el-icon
          ><span>{{ link.label }}</span>
        </button></el-tooltip
      >
      <div class="nav-divider" />
      <el-tooltip :content="t('nav.shares')" placement="right" :show-after="600"
        ><button
          :aria-label="t('nav.shares')"
          :aria-current="section === 'shares' ? 'page' : undefined"
          :class="{ active: section === 'shares' }"
          @click="emit('section', 'shares')"
        >
          <el-icon><Share /></el-icon><span>{{ t("nav.shares") }}</span>
        </button></el-tooltip
      >
      <el-tooltip :content="t('nav.trash')" placement="right" :show-after="600"
        ><button
          :aria-label="t('nav.trash')"
          :aria-current="section === 'trash' ? 'page' : undefined"
          :class="{ active: section === 'trash' }"
          @click="emit('section', 'trash')"
        >
          <el-icon><Delete /></el-icon><span>{{ t("nav.trash") }}</span>
        </button></el-tooltip
      >
      <router-link
        v-if="user?.administrator"
        to="/admin/nodes"
        class="node-center-link"
        :aria-label="t('nav.nodes')"
        ><el-icon><Connection /></el-icon
        ><span>{{ t("nav.nodes") }}</span></router-link
      >
    </nav>
    <StorageUsage :user="user" />
    <button
      class="sidebar-profile"
      :aria-label="t('nav.profile')"
      @click="emit('profile')"
    >
      <span class="avatar">{{ user?.nickname?.[0]?.toUpperCase() || "C" }}</span
      ><span class="profile-copy"
        ><strong>{{ user?.nickname }}</strong
        ><small>{{
          user?.administrator
            ? t("nav.administrator")
            : t("nav.personalAccount")
        }}</small></span
      ><el-icon><MoreFilled /></el-icon>
    </button>
  </aside>
</template>
