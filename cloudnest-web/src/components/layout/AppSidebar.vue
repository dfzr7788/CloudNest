<script setup lang="ts">
import {
  FolderOpened,
  Star,
  Clock,
  Upload,
  Share,
  Delete,
  MoreFilled,
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
const links = [
  { key: "all", label: "我的文件", icon: FolderOpened },
  { key: "favorites", label: "我的收藏", icon: Star },
  { key: "opened", label: "最近使用", icon: Clock },
  { key: "uploaded", label: "最近上传", icon: Upload },
];
</script>
<template>
  <aside class="sidebar">
    <router-link to="/" class="sidebar-brand" aria-label="CloudNest"
      ><CloudNestLogo
    /></router-link>
    <div class="workspace-caption">个人工作空间</div>
    <nav aria-label="工作空间导航">
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
      <el-tooltip content="我的分享" placement="right" :show-after="600"
        ><button
          aria-label="我的分享"
          :aria-current="section === 'shares' ? 'page' : undefined"
          :class="{ active: section === 'shares' }"
          @click="emit('section', 'shares')"
        >
          <el-icon><Share /></el-icon><span>我的分享</span>
        </button></el-tooltip
      >
      <el-tooltip content="回收站" placement="right" :show-after="600"
        ><button
          aria-label="回收站"
          :aria-current="section === 'trash' ? 'page' : undefined"
          :class="{ active: section === 'trash' }"
          @click="emit('section', 'trash')"
        >
          <el-icon><Delete /></el-icon><span>回收站</span>
        </button></el-tooltip
      >
    </nav>
    <StorageUsage :user="user" />
    <button
      class="sidebar-profile"
      aria-label="编辑个人资料"
      @click="emit('profile')"
    >
      <span class="avatar">{{ user?.nickname?.[0]?.toUpperCase() || "C" }}</span
      ><span class="profile-copy"
        ><strong>{{ user?.nickname }}</strong
        ><small>{{ user?.administrator ? "管理员" : "个人账户" }}</small></span
      ><el-icon><MoreFilled /></el-icon>
    </button>
  </aside>
</template>
