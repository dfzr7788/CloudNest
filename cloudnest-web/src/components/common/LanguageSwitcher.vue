<script setup lang="ts">
import { computed } from "vue";
import { ArrowDown, Check } from "@element-plus/icons-vue";
import { locale, setLocale } from "../../i18n";

const options = [
  { value: "zh-CN", label: "简体中文" },
  { value: "en-US", label: "English" },
] as const;

const currentLabel = computed(
  () => options.find((o) => o.value === locale.value)?.label || "简体中文",
);
</script>
<template>
  <div class="language-switcher">
    <select
      class="language-native-proxy"
      aria-label="Language / 语言"
      tabindex="-1"
      :value="locale"
      @change="setLocale(($event.target as HTMLSelectElement).value)"
    >
      <option v-for="opt in options" :key="opt.value" :value="opt.value">
        {{ opt.label }}
      </option>
    </select>
    <el-dropdown
      trigger="click"
      placement="bottom-end"
      popper-class="cn-lang-dropdown"
      @command="setLocale"
    >
      <button
        type="button"
        class="language-trigger"
        aria-label="切换语言 / Switch language"
      >
        <svg
          class="language-globe"
          viewBox="0 0 16 16"
          fill="none"
          aria-hidden="true"
        >
          <circle
            cx="8"
            cy="8"
            r="6.25"
            stroke="currentColor"
            stroke-width="1.3"
          />
          <ellipse
            cx="8"
            cy="8"
            rx="2.7"
            ry="6.25"
            stroke="currentColor"
            stroke-width="1.3"
          />
          <path
            d="M2.1 6h11.8M2.1 10h11.8"
            stroke="currentColor"
            stroke-width="1.2"
          />
        </svg>
        <span class="language-label">{{ currentLabel }}</span>
        <el-icon class="language-caret"><ArrowDown /></el-icon>
      </button>
      <template #dropdown>
        <el-dropdown-menu>
          <el-dropdown-item
            v-for="opt in options"
            :key="opt.value"
            :command="opt.value"
            :class="{ 'is-active-lang': locale === opt.value }"
          >
            <span>{{ opt.label }}</span>
            <el-icon v-if="locale === opt.value" class="lang-check">
              <Check />
            </el-icon>
          </el-dropdown-item>
        </el-dropdown-menu>
      </template>
    </el-dropdown>
  </div>
</template>
<style scoped>
.language-switcher {
  position: relative;
  display: inline-flex;
  align-items: center;
  flex-shrink: 0;
}
.language-native-proxy {
  position: absolute;
  inset: 0;
  width: 100%;
  height: 100%;
  opacity: 0;
  pointer-events: none;
}
.language-trigger {
  display: inline-flex;
  align-items: center;
  gap: 6px;
  min-height: 32px;
  padding: 4px 10px;
  border-radius: var(--cn-radius-control);
  background: var(--cn-surface);
  border: 1px solid var(--cn-border-strong);
  box-shadow: var(--cn-btn-secondary-shadow);
  color: var(--cn-text);
  font-size: 12.5px;
  font-weight: 500;
  line-height: 1;
  white-space: nowrap;
  transition:
    background-color var(--cn-duration) var(--cn-ease),
    border-color var(--cn-duration) var(--cn-ease),
    color var(--cn-duration) var(--cn-ease),
    box-shadow var(--cn-duration) var(--cn-ease);
}
.language-trigger:hover {
  background: var(--cn-hover);
  color: var(--cn-heading);
}
.language-globe {
  width: 14px;
  height: 14px;
  color: var(--cn-muted);
  flex-shrink: 0;
}
.language-caret {
  font-size: 11px;
  color: var(--cn-muted);
  margin-left: 1px;
}
@media (max-width: 767px) {
  .language-trigger {
    min-height: 36px;
    padding: 4px 8px;
  }
}
</style>
