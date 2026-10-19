import { test, expect } from "@playwright/test";
import { createHash } from "node:crypto";
import { readFileSync, writeFileSync } from "node:fs";

test("NODEUI01 节点管理与真实浏览器授权直连、代理、分享、Range", async ({
  page,
}) => {
  test.skip(
    !process.env.CN_V1_FIXTURE,
    "Requires scripts/s3-contract-fixture.py and an isolated v1admin account",
  );
  await page.goto("/login");
  await page.getByLabel("账号", { exact: true }).fill("v1admin");
  await page.getByLabel("密码", { exact: true }).fill("Browser-Fixture-123");
  await page.getByRole("button", { name: "登录云盘", exact: true }).click();
  await expect(page.getByRole("heading", { name: "我的云盘" })).toBeVisible();
  async function call(method: string, path: string, data?: unknown) {
    const csrf = (await (await page.request.get("/api/auth/csrf")).json())
      .token;
    const response = await page.request.fetch("/api" + path, {
      method,
      data,
      headers: { "X-XSRF-TOKEN": csrf },
    });
    expect(response.ok(), await response.text()).toBeTruthy();
    return (await response.text()) ? response.json() : null;
  }
  const me = await call("GET", "/user/me");
  const name = "浏览器 B " + Date.now();
  const policy = await call("POST", "/admin/storage/policies", {
    name,
    endpoint: "http://127.0.0.1:18092",
    bucket: "cloudnest",
    region: "garage",
    accessKey: "fixture-access",
    secretKey: "fixture-secret-key-for-browser-tests",
  });
  const backend = "s3:" + policy.id;
  expect((await call("POST", `/admin/nodes/${backend}/test`)).ok).toBeTruthy();
  async function configure(direct: boolean) {
    await call("PUT", `/admin/nodes/${backend}`, {
      name,
      enabled: true,
      management: "ACTIVE",
      allowUpload: true,
      allowMigration: true,
      budgetBytes: 1024 ** 3,
      publicEndpoint: "http://127.0.0.1:18093",
      directDownload: direct,
    });
  }
  await configure(true);
  await call("PUT", "/admin/config/storage", {
    defaultBackend: backend,
    warningPercent: "80",
  });
  const content = Buffer.from(
    "双节点浏览器校验\nRange and attachment # &\n" + Date.now(),
  );
  const filename = "中文 #& test " + Date.now() + ".txt";
  async function upload(parent: string, name: string, bytes: Buffer) {
    const job = await call("POST", "/uploads/init", {
      folderId: parent,
      name,
      size: bytes.length,
      sha256: createHash("sha256").update(bytes).digest("hex"),
    });
    if (job.status === "COMPLETE") return job.fileId;
    const csrf = (await (await page.request.get("/api/auth/csrf")).json())
      .token;
    const part = await page.request.put(`/api/uploads/${job.id}/parts/0`, {
      data: bytes,
      headers: {
        "X-XSRF-TOKEN": csrf,
        "Content-Type": "application/octet-stream",
      },
    });
    expect(part.ok(), await part.text()).toBeTruthy();
    return (await call("POST", `/uploads/${job.id}/complete`)).fileId;
  }
  const id = await upload(me.rootId, filename, content);
  await page.reload();
  const before = await (
    await page.request.get("http://127.0.0.1:18092/__stats")
  ).json();
  const transfer = page.waitForEvent("download");
  await page
    .getByRole("link", { name: "下载 " + filename, exact: true })
    .click();
  const file = await transfer;
  expect(file.url()).toContain(":18093/");
  expect(file.suggestedFilename()).toBe(filename);
  expect(readFileSync((await file.path())!)).toEqual(content);
  const after = await (
    await page.request.get("http://127.0.0.1:18092/__stats")
  ).json();
  expect(after.internalBytes).toBe(before.internalBytes);
  writeFileSync(
    "../.tools/v1-direct-proof.json",
    JSON.stringify({
      environment: "loopback-s3-fixture",
      before,
      after,
      filename,
      bodyVerified: true,
    }),
  );
  const authorization = await call(
    "GET",
    `/files/${id}/download-authorization`,
  );
  const range = await page.request.get(authorization.url, {
    headers: { Range: "bytes=3-7" },
  });
  expect(range.status()).toBe(206);
  expect(await range.body()).toEqual(content.subarray(3, 8));
  expect(
    (
      await page.request.get("http://127.0.0.1:18093/cloudnest/unsigned")
    ).status(),
  ).toBe(403);
  expect(
    (
      await page.request.put("http://127.0.0.1:18093/cloudnest/write", {
        data: "x",
      })
    ).status(),
  ).toBe(405);
  expect(
    (
      await page.request.get(
        authorization.url.replace(/objects\/[^?]+/, "objects/tampered"),
      )
    ).status(),
  ).toBe(403);
  await page.getByRole("button", { name: filename, exact: true }).click();
  const previewTransfer = page.waitForEvent("download");
  await page
    .getByRole("dialog")
    .getByRole("link", { name: "下载", exact: true })
    .click();
  expect((await previewTransfer).url()).toContain(":18093/");
  await page
    .getByRole("dialog")
    .getByRole("button", { name: "关闭", exact: true })
    .last()
    .click();
  const share = await call("POST", "/shares", {
    fileId: id,
    hours: 1,
    allowDownload: true,
  });
  await page.goto("/s/" + share.token);
  const shared = page.waitForEvent("download");
  await page.getByRole("link", { name: "下载文件", exact: true }).click();
  expect((await shared).url()).toContain(":18093/");
  const folder = await call("POST", "/folders", {
    parentId: me.rootId,
    name: "目录分享 " + Date.now(),
  });
  await upload(
    folder.id,
    "目录内.txt",
    Buffer.from("directory shared contents " + Date.now()),
  );
  const folderShare = await call("POST", "/shares", {
    fileId: folder.id,
    hours: 1,
    allowDownload: true,
  });
  await page.goto("/s/" + folderShare.token);
  const member = page.waitForEvent("download");
  await page.getByRole("link", { name: "下载", exact: true }).click();
  expect((await member).url()).toContain(":18093/");
  await configure(false);
  await page.goto("/");
  const proxy = page.waitForEvent("download");
  await page
    .getByRole("link", { name: "下载 " + filename, exact: true })
    .click();
  const proxied = await proxy;
  expect(proxied.url()).toContain(`/api/files/${id}/download`);
  expect(readFileSync((await proxied.path())!)).toEqual(content);
  await page.getByRole("button", { name: "账号与安全", exact: true }).click();
  await page.getByRole("tab", { name: "服务与存储", exact: true }).click();
  const row = page
    .locator(".node-settings .el-table__row")
    .filter({ hasText: name });
  await expect(row).toContainText("在线");
  await row.getByRole("button", { name: "详情与配置" }).click();
  await expect(
    page.getByRole("dialog", { name: "节点配置与健康记录" }),
  ).toBeVisible();
  await page.screenshot({
    path: "../.tools/v1-browser/screenshots/nodes-desktop.png",
    fullPage: true,
    animations: "disabled",
  });
  await page
    .getByRole("dialog", { name: "节点配置与健康记录" })
    .getByRole("button", { name: "取消", exact: true })
    .click();
  await expect(
    page.getByRole("dialog", { name: "节点配置与健康记录" }),
  ).not.toBeVisible();
  await page.setViewportSize({ width: 390, height: 844 });
  await page.screenshot({
    path: "../.tools/v1-browser/screenshots/nodes-mobile.png",
    fullPage: true,
    animations: "disabled",
  });
  await configure(true);
  const benchmarkBytes = Buffer.alloc(1024 * 1024, 65);
  benchmarkBytes.write(String(Date.now()));
  const benchmarkId = await upload(
    me.rootId,
    "benchmark " + Date.now() + ".bin",
    benchmarkBytes,
  );
  writeFileSync(
    "../.tools/v1-browser/dataset.json",
    JSON.stringify([
      {
        id: benchmarkId,
        size: benchmarkBytes.length,
        sha256: createHash("sha256").update(benchmarkBytes).digest("hex"),
      },
    ]),
  );
  writeFileSync(
    "../.tools/v1-browser/session.json",
    JSON.stringify(
      Object.fromEntries(
        (await page.context().cookies())
          .filter((c) => c.name === "CN_AUTH")
          .map((c) => [c.name, c.value]),
      ),
    ),
  );
});
