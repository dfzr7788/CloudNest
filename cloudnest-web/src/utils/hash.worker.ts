import { sha256 } from "@noble/hashes/sha2.js";
self.onmessage = async (event: MessageEvent<File>) => {
  try {
    const file = event.data,
      hash = sha256.create(),
      chunk = 4 * 1024 * 1024;
    for (let offset = 0; offset < file.size; offset += chunk) {
      hash.update(
        new Uint8Array(await file.slice(offset, offset + chunk).arrayBuffer()),
      );
      self.postMessage({ progress: Math.min(1, (offset + chunk) / file.size) });
    }
    self.postMessage({
      hash: Array.from(hash.digest())
        .map((b) => b.toString(16).padStart(2, "0"))
        .join(""),
    });
  } catch (e) {
    self.postMessage({
      error: e instanceof Error ? e.message : "哈希计算失败",
    });
  }
};
