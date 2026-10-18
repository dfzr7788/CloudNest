<script setup lang="ts">
import EmptyState from "../common/EmptyState.vue";
import { date } from "../../api";
export interface ShareItem {
  id: string;
  name: string;
  expiresAt: number;
  revoked: boolean;
  hasCode: boolean;
  allowDownload: boolean;
}
defineProps<{ shares: ShareItem[]; busy: boolean }>();
const filter = defineModel<string>("filter", { required: true });
const emit = defineEmits<{ edit: [id: string]; revoke: [id: string] }>();
</script>
<template>
  <section class="shares-section" v-loading="busy">
    <div class="collection-tabs" role="group" aria-label="分享状态筛选">
      <button
        v-for="item in [
          { key: 'all', label: '全部分享' },
          { key: 'active', label: '生效中' },
          { key: 'expired', label: '已过期' },
          { key: 'revoked', label: '已撤销' },
        ]"
        :key="item.key"
        :class="{ active: filter === item.key }"
        :aria-pressed="filter === item.key"
        @click="filter = item.key"
      >
        {{ item.label }}
      </button>
    </div>
    <div class="share-list">
      <article v-for="share in shares" :key="share.id" class="share-list-item">
        <div class="share-item-main">
          <h3 :title="share.name">{{ share.name }}</h3>
          <p>
            {{ share.allowDownload ? "预览和下载" : "仅预览" }} ·
            <span>{{ share.hasCode ? "有提取码" : "无需提取码" }}</span>
          </p>
        </div>
        <div class="share-item-meta">
          <span
            class="share-status"
            :class="{
              'is-active': !share.revoked && share.expiresAt > Date.now(),
            }"
            ><i aria-hidden="true" />{{
              share.revoked
                ? "已撤销"
                : share.expiresAt <= Date.now()
                  ? "已过期"
                  : "生效中"
            }}</span
          >
          <p>有效期至 {{ date(share.expiresAt) }}</p>
        </div>
        <div class="share-item-actions">
          <el-button
            v-if="!share.revoked && share.expiresAt > Date.now()"
            @click="emit('edit', share.id)"
            >修改设置</el-button
          ><el-button
            v-if="!share.revoked"
            text
            type="danger"
            @click="emit('revoke', share.id)"
            >撤销</el-button
          >
        </div>
      </article>
      <EmptyState
        v-if="!shares.length && !busy"
        :title="filter === 'all' ? '还没有分享' : '没有此状态的分享'"
        description="从文件的分享菜单创建链接，在这里管理权限与有效期。"
      />
    </div>
    <p class="muted share-help">
      分享链接仅在创建时展示，请及时复制保存。遗失后可撤销并重新创建。
    </p>
  </section>
</template>
