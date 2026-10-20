import { ref } from "vue";
import { ElMessage } from "element-plus";
import { api, errorText } from "./index";

// All ordinary download buttons request fresh authorization on every click.
// Preview, archives and historical versions retain their existing transports.
export function useDownload() {
  const downloading = ref(false);
  async function download(proxy: string) {
    if (downloading.value) return;
    downloading.value = true;
    let notice: ReturnType<typeof ElMessage> | undefined;
    const timer = setTimeout(() => {
      notice = ElMessage({ message: "正在获取下载授权…", duration: 0 });
    }, 400);
    try {
      const endpoint = proxy.replace(/^\/api/, "") + "-authorization";
      let result = (await api.get(endpoint)).data;
      if (result.expiresAt <= Date.now() + 1000)
        result = (await api.get(endpoint)).data;
      if (result.mode === "direct") {
        const controller = new AbortController();
        const timeout = setTimeout(() => controller.abort(), 5000);
        try {
          const probe = await fetch(result.url, {
            headers: { Range: "bytes=0-0" },
            signal: controller.signal,
            credentials: "omit",
            referrerPolicy: "no-referrer",
          });
          await probe.body?.cancel();
          if (!probe.ok || (result.supportsRange && probe.status !== 206))
            result.url = result.fallbackUrl || proxy;
        } catch {
          result.url = result.fallbackUrl || proxy;
        } finally {
          clearTimeout(timeout);
        }
      }
      const url = new URL(result.url, location.origin);
      if (!["https:", "http:"].includes(url.protocol))
        throw new Error("下载地址无效");
      const link = document.createElement("a");
      link.href = url.href;
      link.referrerPolicy = "no-referrer";
      document.body.appendChild(link);
      link.click();
      link.remove();
    } catch (error) {
      ElMessage.error(errorText(error));
    } finally {
      clearTimeout(timer);
      notice?.close();
      downloading.value = false;
    }
  }
  return { downloading, download };
}
