import fs from "node:fs";
import path from "node:path";
import { fileURLToPath } from "node:url";
import { createI18n } from "vue-i18n";

const root = fileURLToPath(new URL("../src/", import.meta.url));
const load = (locale) =>
  JSON.parse(
    fs.readFileSync(path.join(root, `i18n/locales/${locale}.json`), "utf8"),
  );
const zh = load("zh-CN"),
  en = load("en-US");
const errors = [];
const slots = (text) =>
  [...text.matchAll(/\{(\w+)\}/g)]
    .map((m) => m[1])
    .sort()
    .join(",");
for (const key of new Set([...Object.keys(zh), ...Object.keys(en)])) {
  if (!/^[a-z][a-zA-Z0-9]*(\.[a-zA-Z][a-zA-Z0-9_]*)+$/.test(key))
    errors.push(`Invalid message key: ${key}`);
  if (
    typeof zh[key] !== "string" ||
    !zh[key].trim() ||
    typeof en[key] !== "string" ||
    !en[key].trim()
  ) {
    errors.push(`Missing translation: ${key}`);
    continue;
  }
  if (slots(zh[key]) !== slots(en[key]))
    errors.push(`Interpolation mismatch: ${key}`);
}
function walk(dir) {
  for (const file of fs.readdirSync(dir, { withFileTypes: true })) {
    const full = path.join(dir, file.name);
    if (file.isDirectory()) walk(full);
    else if (/\.(vue|ts)$/.test(file.name)) {
      const source = fs.readFileSync(full, "utf8");
      for (const match of source.matchAll(
        /\b(?:t|tr)\(\s*["']([a-zA-Z][\w.]*)["']/g,
      )) {
        if (!(match[1] in zh) || !(match[1] in en))
          errors.push(`Unknown key ${match[1]} in ${full}`);
      }
    }
  }
}
walk(root);
// Exercise the same message compiler used by the application, including all placeholders.
for (const [locale, messages] of [
  ["zh-CN", zh],
  ["en-US", en],
]) {
  const instance = createI18n({
    legacy: false,
    locale,
    flatJson: true,
    messages: { [locale]: structuredClone(messages) },
  });
  const warn = console.warn,
    error = console.error;
  console.warn = console.error = (...args) =>
    errors.push(`${locale}: ${args.join(" ")}`);
  try {
    for (const [key, text] of Object.entries(messages)) {
      const params = Object.fromEntries(
        [...text.matchAll(/\{(\w+)\}/g)].map((m) => [m[1], "value"]),
      );
      instance.global.t(key, params);
    }
  } finally {
    console.warn = warn;
    console.error = error;
  }
}
if (errors.length) {
  console.error(errors.join("\n"));
  process.exit(1);
}
console.log(
  `i18n contract passed: ${Object.keys(zh).length} paired messages; source references and placeholders verified.`,
);
