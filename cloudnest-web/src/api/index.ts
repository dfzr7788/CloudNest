import axios from "axios";
import { locale, t, i18n } from "../i18n";
export interface User {
  administrator?: boolean;
  id: string;
  username: string;
  nickname: string;
  rootId: string;
  quota: number;
  used: number;
  reserved: number;
}
export interface Entry {
  id: string;
  parentId: string | null;
  kind: "FILE" | "FOLDER";
  name: string;
  size: number;
  mime: string | null;
  createdAt: number;
  updatedAt: number;
  deletedAt: number | null;
  path?: string;
  favorite?: boolean;
}
export interface Page {
  items: Entry[];
  total: number;
  page: number;
  pageSize: number;
  parentId: string;
}
export interface UploadSession {
  id: string;
  name: string;
  size: number;
  sha256: string;
  status: string;
  folderId: string;
  partSize: number;
  partCount: number;
  uploadedParts: number[];
  fileId: string | null;
  expiresAt: number;
}
export const api = axios.create({
  baseURL: "/api",
  withCredentials: true,
  timeout: 30000,
});
let csrf = "";
let csrfRequest: Promise<void> | undefined;
export async function refreshCsrf() {
  csrfRequest ??= api
    .get("/auth/csrf")
    .then((r) => {
      csrf = r.data.token;
    })
    .finally(() => {
      csrfRequest = undefined;
    });
  await csrfRequest;
}
api.interceptors.request.use(async (config) => {
  config.headers["Accept-Language"] = locale.value;
  if (!["get", "head", "options"].includes(config.method || "get")) {
    await refreshCsrf();
    config.headers["X-XSRF-TOKEN"] = csrf;
  }
  return config;
});
export function errorText(e: unknown) {
  if (axios.isAxiosError(e)) {
    const code = e.response?.data?.code;
    if (typeof code === "string" && i18n.global.te(`errors.${code}`))
      return t(`errors.${code}`);
  }
  return axios.isAxiosError(e)
    ? e.response?.data?.message ||
        (e.code === "ERR_NETWORK" ? t("errors.network") : e.message)
    : e instanceof Error
      ? e.message
      : t("errors.failed");
}
export function bytes(n: number) {
  if (!Number.isFinite(n) || n <= 0) return "0 B";
  const i = Math.max(0, Math.min(4, Math.floor(Math.log(n) / Math.log(1024))));
  return `${new Intl.NumberFormat(locale.value, { minimumFractionDigits: i === 0 ? 0 : 1, maximumFractionDigits: i === 0 ? 0 : 1 }).format(n / 1024 ** i)} ${["B", "KiB", "MiB", "GiB", "TiB"][i]}`;
}
export function date(n: number | null) {
  return n ? new Date(n).toLocaleString(locale.value, { hour12: false }) : "—";
}
export const typePath = (e: Entry) =>
  e.kind === "FOLDER" ? "folders" : "files";
