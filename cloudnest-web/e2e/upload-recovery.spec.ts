import { test, expect } from "@playwright/test";
import { mkdirSync, writeFileSync } from "node:fs";
import { join } from "node:path";

for (const interruptedAt of ["directory", "parts", "paused", "quota"]) {
  test(`folder queue recovery: ${interruptedAt}`, async ({ page }, info) => {
    const sessions = new Map<string, any>();
    const received = new Map<string, Buffer>();
    let interrupted = false;
    let released = false;
    let unblock!: () => void;
    const blocked = new Promise<void>((resolve) => {
      unblock = resolve;
    });
    const partRequests: string[] = [];
    await page.addInitScript(() =>
      localStorage.setItem("cloudnest-locale", "zh-CN"),
    );
    if (interruptedAt === "quota") {
      await page.addInitScript(() => {
        IDBObjectStore.prototype.put = function () {
          throw new DOMException("storage full", "QuotaExceededError");
        };
      });
    }
    await page.route(
      (url) => url.pathname.startsWith("/api/"),
      async (route) => {
        const request = route.request();
        const path = new URL(request.url()).pathname.replace(/^\/api/, "");
        const json = (body: unknown) => route.fulfill({ json: body });
        if (path === "/user/me")
          return json({
            id: "owner",
            username: "test",
            nickname: "test",
            rootId: "root",
            quota: 1e9,
            used: 0,
            reserved: 0,
          });
        if (path === "/auth/csrf") return json({ token: "test" });
        if (path === "/entries")
          return json({ items: [], total: 0, parentId: "root" });
        if (path.endsWith("/breadcrumbs"))
          return json([{ id: "root", name: "全部文件" }]);
        if (path === "/stats")
          return json({ files: 0, folders: 0, images: 0, documents: 0 });
        if (path === "/downloads") return json([]);
        if (path === "/uploads")
          return json(
            [...sessions.values()].filter((s) => s.status === "UPLOADING"),
          );
        if (path === "/folders/resolve-path") {
          if (["directory", "paused"].includes(interruptedAt) && !interrupted) {
            interrupted = true;
            await blocked;
            return route.abort();
          }
          return json({ id: request.postDataJSON().segments.join("/") });
        }
        if (path === "/entries/upload-name")
          return json({ name: request.postDataJSON().name });
        if (path === "/uploads/init") {
          const input = request.postDataJSON();
          const session = {
            ...input,
            id: input.name,
            status: "UPLOADING",
            partSize: 4,
            partCount: Math.max(1, Math.ceil(input.size / 4)),
            uploadedParts: [],
          };
          sessions.set(session.id, session);
          return json(session);
        }
        const match = path.match(/^\/uploads\/([^/]+)(.*)$/);
        if (match) {
          const session = sessions.get(match[1]);
          const action = match[2];
          if (!action) return json(session);
          if (action === "/plan") return json({ mode: "chunked" });
          if (action.startsWith("/parts/")) {
            const index = Number(action.split("/").at(-1));
            if (interruptedAt === "parts" && index === 1 && !released) {
              interrupted = true;
              await blocked;
              return route.abort();
            }
            partRequests.push(path);
            received.set(path, request.postDataBuffer() || Buffer.alloc(0));
            session.uploadedParts.push(index);
            return json({});
          }
          if (action === "/complete") {
            expect(new Set(session.uploadedParts).size).toBe(session.partCount);
            session.status = "COMPLETE";
            return json(session);
          }
        }
        throw new Error(`Unexpected API: ${request.method()} ${path}`);
      },
    );
    const directory = info.outputPath("folder");
    mkdirSync(join(directory, "nested"), { recursive: true });
    const sources = { "a.txt": "abcdefgh", "b.txt": "ijklmn", "empty.txt": "" };
    for (const [name, body] of Object.entries(sources))
      writeFileSync(join(directory, "nested", name), body);
    await page.goto("/");
    await expect(
      page.getByRole("heading", { name: "我的云盘", exact: true }),
    ).toBeVisible();
    await page.locator("input[webkitdirectory]").setInputFiles(directory);
    if (interruptedAt === "quota") {
      await expect(page.getByText(/无法保存上传队列/)).toBeVisible();
      await expect(page.locator(".upload-task")).toHaveCount(0);
      expect(interrupted).toBe(false);
      expect(sessions.size).toBe(0);
      return;
    }
    await expect.poll(() => interrupted).toBe(true);
    await expect(page.locator(".upload-task")).toHaveCount(3);
    if (interruptedAt === "parts") {
      await expect
        .poll(() => partRequests.filter((p) => p.endsWith("/parts/0")).length)
        .toBe(1);
    }
    if (interruptedAt === "paused") {
      await page.getByRole("button", { name: "全部暂停", exact: true }).click();
      await expect(page.locator(".upload-task [role=status]")).toHaveText([
        "已暂停",
        "已暂停",
        "已暂停",
      ]);
      await expect
        .poll(() =>
          page.evaluate(
            () =>
              new Promise<number>((resolve) => {
                const open = indexedDB.open("cloudnest-upload-queue", 1);
                open.onsuccess = () => {
                  const db = open.result;
                  const all = db
                    .transaction("tasks")
                    .objectStore("tasks")
                    .getAll();
                  all.onsuccess = () => {
                    resolve(
                      all.result.filter((t) => t.state === "已暂停").length,
                    );
                    db.close();
                  };
                };
              }),
          ),
        )
        .toBe(3);
    }
    await page.reload();
    released = true;
    unblock();
    await expect(page.locator(".upload-task")).toHaveCount(3);
    if (interruptedAt === "paused") {
      await expect(page.locator(".upload-task [role=status]")).toHaveText([
        "已暂停",
        "已暂停",
        "已暂停",
      ]);
      expect(sessions.size).toBe(0);
      await page
        .getByRole("button", { name: "继续 / 重试全部", exact: true })
        .click();
    }
    await expect
      .poll(
        () =>
          [...sessions.values()].filter((s) => s.status === "COMPLETE").length,
      )
      .toBe(3);
    for (const [name, body] of Object.entries(sources)) {
      const session = sessions.get(name);
      expect(session.folderId).toBe("folder/nested");
      const chunks = Array.from(
        { length: session.partCount },
        (_, index) => received.get(`/uploads/${name}/parts/${index}`)!,
      );
      expect(Buffer.concat(chunks).toString()).toBe(body);
      expect(
        partRequests.filter((p) => p === `/uploads/${name}/parts/0`),
      ).toHaveLength(1);
    }
    await expect
      .poll(() =>
        page.evaluate(async () => {
          return new Promise<number>((resolve) => {
            const open = indexedDB.open("cloudnest-upload-queue", 1);
            open.onsuccess = () => {
              const db = open.result;
              const count = db
                .transaction("tasks")
                .objectStore("tasks")
                .count();
              count.onsuccess = () => {
                resolve(count.result);
                db.close();
              };
            };
          });
        }),
      )
      .toBe(0);
  });
}
