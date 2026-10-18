import { test, expect, type Page } from "@playwright/test";
import { mkdirSync, writeFileSync, readFileSync } from "node:fs";
import { resolve } from "node:path";
test("IMPUI04 并行读取不清除安全令牌且无令牌写入仍被拒绝", async ({
  page,
  context,
}) => {
  await register(page);
  const token = (await (await context.request.get("/api/auth/csrf")).json())
    .token;
  for (let i = 0; i < 3; i++) {
    await Promise.all([
      context.request.get("/api/user/me"),
      context.request.get("/api/stats"),
    ]);
    const cookies = await context.cookies();
    expect(cookies.find((c) => c.name === "XSRF-TOKEN")?.value).toBe(token);
    const result = await context.request.post("/api/folders", {
      data: { name: "parallel-" + i },
      headers: { "X-XSRF-TOKEN": token },
    });
    expect(result.status()).toBe(200);
  }
  const denied = await context.request.post("/api/folders", {
    data: { name: "missing-csrf" },
  });
  expect(denied.status()).toBe(403);
});
async function register(page: Page) {
  await page.goto("/login");
  await page.getByRole("button", { name: "创建账号", exact: true }).click();
  await page.getByLabel("账号", { exact: true }).fill("imp_" + Date.now());
  await page.getByLabel("密码", { exact: true }).fill("Improvement-123");
  await page.getByRole("button", { name: "注册并进入云盘" }).click();
  await expect(page.getByRole("heading", { name: "我的文件." })).toBeVisible();
  await expect(page.locator(".crumb-button").first()).toBeVisible();
}
async function upload(page: Page, name: string, text: string) {
  await page
    .locator("input[type=file][multiple]:not([webkitdirectory])")
    .setInputFiles({ name, mimeType: "text/plain", buffer: Buffer.from(text) });
  await expect(page.getByRole("button", { name, exact: true })).toBeVisible();
}
test("IMPUI01 收藏、批量删除撤销与账号会话设置", async ({ page }) => {
  await register(page);
  await upload(page, "daily.txt", "daily content");
  await page
    .getByRole("button", { name: "daily.txt的操作", exact: true })
    .click();
  await page.getByRole("menuitem", { name: "收藏", exact: true }).click();
  await page.getByRole("group", { name: "文件集合", exact: true }).getByRole("button", { name: "我的收藏", exact: true }).click();
  await expect(
    page.getByRole("button", { name: "★ daily.txt", exact: true }),
  ).toBeVisible();
  await page.getByLabel("选择 daily.txt", { exact: true }).check();
  await page.getByRole("button", { name: "批量删除", exact: true }).click();
  await page.getByRole("button", { name: "确认", exact: true }).click();
  await page.getByRole("button", { name: "撤销刚才的删除（10 秒）" }).click();
  await expect(
    page.getByRole("button", { name: "★ daily.txt", exact: true }),
  ).toBeVisible();
  await page.getByRole("button", { name: "账号与安全", exact: true }).click();
  await page.getByRole("tab", { name: "登录会话" }).click();
  await expect(page.getByText(/当前会话 ·/)).toBeVisible();
  await page.getByRole("tab", { name: "空间明细" }).click();
  await expect(page.getByText("有效文件：13 B")).toBeVisible();
  await page.getByRole("tab", { name: "回收站设置" }).click();
  await expect(page.getByLabel("回收站保留天数")).toHaveValue("0");
  await page.screenshot({
    path: "../.tools/ui-ux-refactor/screenshots/e2e/improvement-account.png",
    animations: "disabled",
    fullPage: true,
  });
});
test("IMPUI02 文件夹导入保留三层结构", async ({ page }) => {
  // HTTP public-IP origins lack randomUUID; uploads must not require it.
  await page.addInitScript(() => {
    Object.defineProperty(crypto, "randomUUID", {
      value: undefined,
      configurable: true,
    });
  });
  const directory = resolve("../.tools/e2e-import-" + Date.now());
  mkdirSync(resolve(directory, "章节/示例"), { recursive: true });
  writeFileSync(resolve(directory, "章节/示例/笔记.txt"), "folder import");
  await register(page);
  expect(await page.evaluate(() => typeof crypto.randomUUID)).toBe("undefined");
  await upload(page, "standalone.txt", "ordinary upload without randomUUID");
  const chooser = page.waitForEvent("filechooser");
  await page.getByRole("button", { name: "更多上传选项", exact: true }).click();
  await page.getByRole("button", { name: "上传文件夹", exact: true }).click();
  await (await chooser).setFiles(directory);
  const rootName = directory.split(/[\\/]/).at(-1)!;
  await expect(
    page.getByRole("button", { name: rootName, exact: true }),
  ).toBeVisible({ timeout: 30000 });
  await page.getByRole("button", { name: rootName, exact: true }).click();
  await page.getByRole("button", { name: "章节", exact: true }).click();
  await page.getByRole("button", { name: "示例", exact: true }).click();
  await expect(
    page.getByRole("button", { name: "笔记.txt", exact: true }),
  ).toBeVisible();
  await page.getByRole("button", { name: "笔记.txt", exact: true }).click();
  await expect(page.locator(".preview-body pre")).toHaveText("folder import");
});
test("IMPUI03 版本上传恢复和文件夹匿名分享", async ({ page, browser }) => {
  await register(page);
  await upload(page, "version.txt", "version-one");
  await page
    .getByRole("button", { name: "version.txt的操作", exact: true })
    .click();
  await page.getByText("版本历史", { exact: true }).click();
  const dialog = page.getByRole("dialog", { name: "文件版本历史" });
  await dialog.locator("input[type=file]").setInputFiles({
    name: "replacement.txt",
    mimeType: "text/plain",
    buffer: Buffer.from("version-two"),
  });
  await expect(dialog.getByText("11 B", { exact: true })).toBeVisible({
    timeout: 30000,
  });
  await dialog.getByRole("button", { name: "恢复", exact: true }).click();
  await expect(
    page.getByText("已切换当前版本，原当前内容保留在历史中"),
  ).toBeVisible();
  await dialog.getByRole("button", { name: "关闭此对话框" }).click();
  await page.getByRole("button", { name: "version.txt", exact: true }).click();
  await expect(page.locator(".preview-body pre")).toHaveText("version-one");
  await page
    .getByRole("dialog")
    .getByRole("button", { name: "关闭", exact: true })
    .click();
  await page.getByRole("button", { name: "新建文件夹", exact: true }).click();
  await page.locator(".el-message-box input").fill("共享资料");
  await page.getByRole("button", { name: "创建", exact: true }).click();
  await page.getByRole("button", { name: "共享资料", exact: true }).click();
  await upload(page, "guest.txt", "guest data");
  await page.locator(".crumb-button").first().click();
  await page
    .getByRole("button", { name: "分享 共享资料", exact: true })
    .click();
  await page.getByRole("button", { name: "创建分享", exact: true }).click();
  const url = await page.getByLabel("分享链接").inputValue();
  const visitor = await browser.newContext();
  const guest = await visitor.newPage();
  await guest.goto(url);
  await expect(
    guest.getByRole("button", { name: "guest.txt", exact: true }),
  ).toBeVisible();
  await guest.getByRole("button", { name: "guest.txt", exact: true }).click();
  await expect(guest.locator(".preview-body pre")).toHaveText("guest data");
  await guest.screenshot({
    path: "../.tools/ui-ux-refactor/screenshots/e2e/improvement-folder-share.png",
    animations: "disabled",
    fullPage: true,
  });
  await visitor.close();
});

test("IMPUI05 下载任务刷新保留、真实 ZIP 下载与分享设置修改", async ({
  page,
}) => {
  await register(page);
  await upload(page, "package.txt", "zip contents");
  await page.getByLabel("选择 package.txt", { exact: true }).check();
  await page.getByRole("button", { name: /打包下载/ }).click();
  await expect(page.getByText("可下载", { exact: true })).toBeVisible({
    timeout: 30000,
  });
  await page.reload();
  await expect(page.getByText("可下载", { exact: true })).toBeVisible();
  const downloaded = page.waitForEvent("download");
  await page.getByRole("link", { name: "下载 ZIP", exact: true }).click();
  const file = await downloaded;
  expect(await file.failure()).toBeNull();
  expect(
    readFileSync((await file.path())!)
      .subarray(0, 2)
      .toString(),
  ).toBe("PK");
  await page
    .getByRole("button", { name: "分享 package.txt", exact: true })
    .click();
  await page.getByRole("button", { name: "创建分享", exact: true }).click();
  await page
    .getByRole("dialog", { name: "分享文件" })
    .getByRole("button", { name: "关闭", exact: true })
    .click();
  await page.getByRole("button", { name: "我的分享", exact: true }).click();
  await page.getByRole("button", { name: "修改设置", exact: true }).click();
  const dialog = page.getByRole("dialog", { name: "修改分享设置" });
  await dialog.getByText("修改或移除提取码", { exact: true }).click();
  await dialog.getByRole("textbox").fill("updated-code");
  await dialog
    .getByRole("button", { name: "保存分享设置", exact: true })
    .click();
  await expect(page.getByText("有提取码", { exact: true })).toBeVisible();
});
