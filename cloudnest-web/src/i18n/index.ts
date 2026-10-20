import { createI18n } from "vue-i18n";
import { watch } from "vue";
import zhCN from "./locales/zh-CN.json";
import enUS from "./locales/en-US.json";

export type Locale = "zh-CN" | "en-US";
const preferenceKey = "cloudnest-locale";
function preferredLocale(): Locale {
  try {
    const saved = localStorage.getItem(preferenceKey);
    if (saved === "zh-CN" || saved === "en-US") return saved;
  } catch {
    /* Storage can be disabled by the browser. */
  }
  return navigator.language.toLowerCase().startsWith("en") ? "en-US" : "zh-CN";
}

export const i18n = createI18n({
  legacy: false,
  locale: preferredLocale(),
  fallbackLocale: "zh-CN",
  flatJson: true,
  messages: { "zh-CN": zhCN, "en-US": enUS },
});
export const t = i18n.global.t;
export const locale = i18n.global.locale;

watch(
  locale,
  (value) => {
    document.documentElement.lang = value;
    try {
      localStorage.setItem(preferenceKey, value);
    } catch {
      /* In-memory preference still works. */
    }
  },
  { immediate: true },
);

export function setLocale(value: string) {
  if (value === "zh-CN" || value === "en-US") locale.value = value;
}
