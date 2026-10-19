import { test, expect } from "@playwright/test";
import { createHash } from "node:crypto";
import { writeFileSync } from "node:fs";
import { resolve, sep } from "node:path";

test("HORIZON01 节点中心、文件副本、校验故障切换与移动端", async ({ page }) => {
  test.skip(
    !process.env.CN_HORIZON_FIXTURE || !process.env.CN_HORIZON_DATA,
    "Requires an isolated rc server, horizonadmin fixture account and loopback S3 fixture on 18096",
  );
  test.setTimeout(120000);
  await page.goto("/login");
  await page.getByLabel("账号", { exact: true }).fill("horizonadmin");
  await page
    .getByLabel("密码", { exact: true })
    .fill("Horizon-Browser-Fixture-123");
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
  const name = "Horizon B " + Date.now();
  const policy = await call("POST", "/admin/storage/policies", {
    name,
    endpoint: "http://127.0.0.1:18096",
    bucket: "cloudnest",
    region: "garage",
    accessKey: "fixture-access",
    secretKey: "fixture-secret-key-for-browser-tests",
  });
  const backend = "s3:" + policy.id;
  expect((await call("POST", `/admin/nodes/${backend}/test`)).ok).toBeTruthy();
  await call("PUT", `/admin/horizon/nodes/${backend}`, {
    failureDomain: "browser-b",
    reliability: 70,
    priority: 50,
    cost: 50,
  });
  await call("PUT", `/admin/nodes/${backend}`, {
    name,
    enabled: true,
    management: "ACTIVE",
    allowUpload: true,
    allowMigration: true,
    budgetBytes: 1024 ** 3,
    publicEndpoint: "",
    directDownload: false,
  });
  await page.getByRole("link", { name: "节点中心", exact: true }).click();
  await expect(
    page.getByRole("heading", { name: "节点中心", exact: true }),
  ).toBeVisible();
  await page.getByRole("tab", { name: "调度策略", exact: true }).click();
  await page.locator(".policy-form .el-select").click();
  await page.getByRole("option", { name: "稳定优先", exact: true }).click();
  await page.getByRole("button", { name: "保存调度策略", exact: true }).click();
  await expect
    .poll(async () => (await call("GET", "/admin/horizon")).placement.mode)
    .toBe("STABILITY");
  await page.getByRole("link", { name: "返回我的云盘" }).click();
  const filename = "Horizon protected " + Date.now() + ".txt";
  const bytes = Buffer.from("Horizon checksum and fallback " + Date.now());
  const upload = await call("POST", "/uploads/init", {
    folderId: me.rootId,
    name: filename,
    size: bytes.length,
    sha256: createHash("sha256").update(bytes).digest("hex"),
  });
  const csrf = (await (await page.request.get("/api/auth/csrf")).json()).token;
  const part = await page.request.put(`/api/uploads/${upload.id}/parts/0`, {
    data: bytes,
    headers: {
      "X-XSRF-TOKEN": csrf,
      "Content-Type": "application/octet-stream",
    },
  });
  expect(part.ok()).toBeTruthy();
  const file = (await call("POST", `/uploads/${upload.id}/complete`)).fileId;
  await page.reload();
  await page
    .getByRole("button", { name: filename + "的操作", exact: true })
    .click();
  await page.getByRole("menuitem", { name: "存储与副本", exact: true }).click();
  const dialog = page.getByRole("dialog", { name: "存储与副本", exact: true });
  await expect(dialog).toBeVisible();
  await dialog.locator(".el-select").click();
  await page
    .getByRole("option", { name: name + " · browser-b", exact: true })
    .click();
  await dialog
    .getByRole("button", { name: "提交后台任务", exact: true })
    .click();
  await expect(
    dialog.getByText("已有两个不同故障域的在线已验证副本", { exact: true }),
  ).toBeVisible({ timeout: 30000 });
  const state = await call("GET", `/storage/files/${file}/replicas`);
  expect(state.protected).toBeTruthy();
  const local = state.replicas.find((r: any) => r.backend === "local");
  expect(local.object_key).toMatch(/^objects\/[a-f0-9-]{36}$/);
  const dataRoot = resolve(process.env.CN_HORIZON_DATA!);
  const objectPath = resolve(dataRoot, "storage", local.object_key);
  expect(objectPath.startsWith(dataRoot + sep)).toBeTruthy();
  try {
    writeFileSync(objectPath, Buffer.alloc(bytes.length, 120));
    const download = await page.request.get(`/api/files/${file}/download`);
    expect(download.status()).toBe(200);
    expect(await download.body()).toEqual(bytes);
    const ranged = await page.request.get(`/api/files/${file}/download`, {
      headers: { Range: "bytes=3-9" },
    });
    expect(ranged.status()).toBe(206);
    expect(await ranged.body()).toEqual(bytes.subarray(3, 10));
    const after = await call("GET", `/storage/files/${file}/replicas`);
    expect(after.replicas.find((r: any) => r.backend === "local").state).toBe(
      "CORRUPT",
    );
  } finally {
    writeFileSync(objectPath, bytes);
  }
  await call("POST", "/storage/jobs", {
    kind: "REPLICATE",
    target: "local",
    fileIds: [file],
  });
  await expect
    .poll(
      async () =>
        (await call("GET", `/storage/files/${file}/replicas`)).protected,
      { timeout: 30000 },
    )
    .toBeTruthy();
  await page.goto("/admin/nodes");
  for (const width of [1440, 390]) {
    await page.setViewportSize({ width, height: 900 });
    await page
      .getByRole("tab", { name: "文件迁移与任务", exact: true })
      .click();
    await expect(
      page.getByRole("cell", { name: filename, exact: true }).first(),
    ).toBeVisible();
    expect(
      await page.evaluate(
        () => document.documentElement.scrollWidth <= window.innerWidth + 1,
      ),
    ).toBeTruthy();
  }
  await page.screenshot({
    path: test.info().outputPath("node-center-mobile.png"),
    fullPage: true,
  });
  await page.setViewportSize({ width: 1440, height: 1000 });
  await page.goto("/");
  await page
    .getByRole("button", { name: filename + "的操作", exact: true })
    .click();
  await page.getByRole("menuitem", { name: "存储与副本", exact: true }).click();
  await page
    .getByRole("button", {
      name: "停止自动副本保护（保留已有数据）",
      exact: true,
    })
    .click();
  await expect
    .poll(
      async () =>
        (await call("GET", `/storage/files/${file}/replicas`)).desiredReplicas,
    )
    .toBe(1);
  await call("PUT", "/admin/horizon/placement", {
    mode: "MANUAL",
    minReliability: 0,
  });
});
