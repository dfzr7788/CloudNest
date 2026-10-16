import { defineStore } from "pinia";
import axios from "axios";
import { reactive, ref } from "vue";
import { api, errorText, type UploadSession } from "../api";
export interface UploadTask {
  key: string;
  name: string;
  size: number;
  progress: number;
  state: string;
  error: string;
  folderId: string;
  sessionId?: string;
  file?: File;
  controller?: AbortController;
  worker?: Worker;
  active?: boolean;
  policy?: string;
  sent?: number;
  speed?: number;
  remaining?: number;
}
export const useUploads = defineStore("uploads", () => {
  const tasks = reactive<UploadTask[]>([]),
    revision = ref(0);
  let pumping = false;
  async function restore() {
    const { data } = await api.get<UploadSession[]>("/uploads");
    for (const s of data)
      if (!tasks.some((t) => t.sessionId === s.id))
        tasks.push({
          key: s.id,
          name: s.name,
          size: s.size,
          folderId: s.folderId,
          sessionId: s.id,
          progress: (s.uploadedParts.length / s.partCount) * 100,
          state: "等待选择原文件",
          error: "",
        });
  }
  function digest(task: UploadTask, file: File) {
    return new Promise<string>((resolve, reject) => {
      const worker = new Worker(
        new URL("../utils/hash.worker.ts", import.meta.url),
        { type: "module" },
      );
      task.worker = worker;
      worker.onmessage = (e) => {
        if (e.data.hash) {
          worker.terminate();
          task.worker = undefined;
          resolve(e.data.hash);
        } else if (e.data.error) {
          worker.terminate();
          task.worker = undefined;
          reject(new Error(e.data.error));
        } else task.progress = e.data.progress * 100;
      };
      worker.onerror = () => {
        worker.terminate();
        task.worker = undefined;
        reject(new Error("文件校验失败"));
      };
      worker.postMessage(file);
      task.controller?.signal.addEventListener(
        "abort",
        () => {
          worker.terminate();
          task.worker = undefined;
          reject(new DOMException("paused", "AbortError"));
        },
        { once: true },
      );
    });
  }
  async function run(task: UploadTask) {
    if (task.active || !task.file) return;
    task.active = true;
    task.controller = new AbortController();
    const signal = task.controller.signal;
    try {
      task.state = "校验文件";
      task.error = "";
      const hash = await digest(task, task.file);
      if (signal.aborted) return;
      let s: UploadSession;
      if (task.sessionId) {
        s = (await api.get(`/uploads/${task.sessionId}`, { signal })).data;
        if (s.sha256 !== hash || s.size !== task.file.size)
          throw new Error("所选文件与原上传内容不一致");
      } else {
        const chosen = (
          await api.post(
            "/entries/upload-name",
            {
              parentId: task.folderId,
              name: task.name,
              policy: task.policy || "keep",
            },
            { signal },
          )
        ).data;
        if (chosen.skipped) {
          task.state = "已跳过";
          return;
        }
        task.name = chosen.name;
        s = (
          await api.post(
            "/uploads/init",
            {
              folderId: task.folderId,
              name: task.name,
              size: task.size,
              sha256: hash,
            },
            { signal },
          )
        ).data;
        task.sessionId = s.id;
      }
      if (s.status === "COMPLETE") {
        task.state = "秒传完成";
        task.progress = 100;
        revision.value++;
        return;
      }
      if (s.status !== "UPLOADING")
        throw new Error("上传会话已取消或失效，请创建新的上传任务");
      task.state = "上传中";
      const started = performance.now();
      let transmitted = 0;
      const done = new Set(s.uploadedParts),
        pending = Array.from({ length: s.partCount }, (_, i) => i).filter(
          (i) => !done.has(i),
        );
      task.progress = (done.size / s.partCount) * 100;
      async function worker() {
        while (pending.length && !signal.aborted) {
          const index = pending.shift()!;
          let attempt = 0;
          for (;;) {
            try {
              await api.put(
                `/uploads/${s.id}/parts/${index}`,
                task.file!.slice(
                  index * s.partSize,
                  Math.min(task.size, (index + 1) * s.partSize),
                ),
                {
                  signal,
                  timeout: 180000,
                  headers: { "Content-Type": "application/octet-stream" },
                },
              );
              break;
            } catch (e) {
              if (
                signal.aborted ||
                (axios.isAxiosError(e) &&
                  e.response &&
                  e.response.status < 500) ||
                ++attempt >= 3
              )
                throw e;
              await new Promise((r) => setTimeout(r, attempt * 1000));
            }
          }
          done.add(index);
          const chunk = Math.min(s.partSize, task.size - index * s.partSize);
          transmitted += chunk;
          task.sent = Math.min(
            task.size,
            Array.from(done).reduce(
              (sum, i) =>
                sum + Math.min(s.partSize, task.size - i * s.partSize),
              0,
            ),
          );
          task.speed =
            transmitted / Math.max(0.001, (performance.now() - started) / 1000);
          task.remaining =
            task.speed > 0 ? (task.size - task.sent) / task.speed : undefined;
          task.progress = (done.size / s.partCount) * 100;
        }
      }
      const outcomes = await Promise.allSettled(
        Array.from({ length: Math.min(3, pending.length) }, () => worker()),
      );
      const failure = outcomes.find((o) => o.status === "rejected");
      if (failure?.status === "rejected") throw failure.reason;
      if (signal.aborted) return;
      task.state = "合并校验中";
      await api.post(
        `/uploads/${s.id}/complete`,
        {},
        { signal, timeout: 600000 },
      );
      task.state = "已完成";
      task.progress = 100;
      revision.value++;
    } catch (e) {
      if (task.state === "已取消") return;
      if (signal.aborted) {
        task.state = "已暂停";
      } else {
        task.state = "上传失败";
        task.error = errorText(e);
      }
    } finally {
      task.active = false;
      task.worker?.terminate();
    }
  }
  function enqueue(files: File[], folderId: string, policy = "keep") {
    // A file queue limits simultaneous hash/merge memory while each file has three part workers.
    for (const file of files) {
      const task: UploadTask = reactive({
        key: crypto.randomUUID(),
        name: file.name,
        size: file.size,
        progress: 0,
        state: "排队中",
        error: "",
        folderId,
        file,
        policy,
      });
      tasks.push(task);
    }
  }
  async function add(files: File[], folderId: string, policy = "keep") {
    enqueue(files, folderId, policy);
    await pump();
  }
  async function pump() {
    if (pumping) return;
    pumping = true;
    try {
      for (const task of tasks) if (task.state === "排队中") await run(task);
    } finally {
      pumping = false;
    }
  }
  function pause(task: UploadTask) {
    task.controller?.abort();
    task.state = "已暂停";
  }
  async function cancel(task: UploadTask) {
    task.state = "已暂停";
    task.controller?.abort();
    if (task.sessionId) await api.delete(`/uploads/${task.sessionId}`);
    task.state = "已取消";
    task.file = undefined;
    revision.value++;
  }
  async function choose(task: UploadTask, file: File) {
    if (task.active) return;
    if (file.size !== task.size)
      throw new Error("请选择原文件（大小及内容摘要需要一致）");
    task.file = file;
    await run(task);
  }
  function clear() {
    if (tasks.some((t) => t.active)) throw new Error("请先暂停上传");
    tasks.splice(0, tasks.length);
  }
  function pauseAll() {
    for (const task of tasks)
      if (task.active || task.state === "排队中") pause(task);
  }
  async function resumeAll() {
    for (const task of tasks)
      if (
        !task.active &&
        task.file &&
        ["已暂停", "上传失败"].includes(task.state)
      )
        task.state = "排队中";
    await pump();
  }
  function clearCompleted() {
    for (let i = tasks.length - 1; i >= 0; i--)
      if (
        !tasks[i]!.active &&
        ["已完成", "秒传完成", "已取消", "已跳过"].includes(tasks[i]!.state)
      )
        tasks.splice(i, 1);
  }
  return {
    tasks,
    revision,
    restore,
    add,
    enqueue,
    pump,
    run,
    pause,
    pauseAll,
    resumeAll,
    clearCompleted,
    cancel,
    choose,
    clear,
  };
});
