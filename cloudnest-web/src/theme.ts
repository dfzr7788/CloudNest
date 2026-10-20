import { ref, watch } from "vue";

export type ThemeMode = "dark" | "light";

const storageKey = "cloudnest-theme";

function initialTheme(): ThemeMode {
  try {
    const saved = localStorage.getItem(storageKey);
    if (saved === "light" || saved === "dark") return saved;
  } catch {
    /* Storage can be unavailable in restricted contexts. */
  }
  return "dark";
}

export const theme = ref<ThemeMode>(initialTheme());

export function applyTheme(mode: ThemeMode) {
  const root = document.documentElement;
  root.classList.toggle("dark", mode === "dark");
  root.classList.toggle("light", mode === "light");
  root.dataset.theme = mode;
  root.style.colorScheme = mode;
}

watch(
  theme,
  (mode) => {
    applyTheme(mode);
    try {
      localStorage.setItem(storageKey, mode);
    } catch {
      /* In-memory theme state still works. */
    }
  },
  { immediate: true },
);

export function toggleTheme() {
  theme.value = theme.value === "dark" ? "light" : "dark";
}

export function setTheme(mode: ThemeMode) {
  theme.value = mode;
}
