import { test, expect, type Page } from "@playwright/test";
import { mkdirSync, readFileSync, writeFileSync } from "node:fs";
import { resolve } from "node:path";
import { inflateRawSync } from "node:zlib";

// Read central directory sizes, including Java ZIP data-descriptor entries.
function contents(buffer: Buffer) {
  const end = buffer.lastIndexOf(Buffer.from([0x50, 0x4b, 0x05, 0x06]));
  expect(end).toBeGreaterThanOrEqual(0);
  let position = buffer.readUInt32LE(end + 16);
  const result = new Map<string, string>();
  for (let i = 0; i < buffer.readUInt16LE(end + 10); i++) {
    expect(buffer.readUInt32LE(position)).toBe(0x02014b50);
    const nameLength = buffer.readUInt16LE(position + 28);
    const name = buffer
      .subarray(position + 46, position + 46 + nameLength)
      .toString("utf8");
    const local = buffer.readUInt32LE(position + 42);
    const start =
      local +
      30 +
      buffer.readUInt16LE(local + 26) +
      buffer.readUInt16LE(local + 28);
    const bytes = buffer.subarray(
      start,
      start + buffer.readUInt32LE(position + 20),
    );
    const body =
      buffer.readUInt16LE(position + 10) === 8 ? inflateRawSync(bytes) : bytes;
    expect(result.has(name)).toBeFalsy();
    result.set(name, body.toString("utf8"));
    position +=
      46 +
      nameLength +
      buffer.readUInt16LE(position + 30) +
      buffer.readUInt16LE(position + 32);
  }
  return result;
}

async function folder(page: Page, name: string) {
  await page.getByRole("button", { name: "新建文件夹", exact: true }).click();
  await page.locator(".el-message-box input").fill(name);
  await page.getByRole("button", { name: "创建", exact: true }).click();
  await expect(page.getByRole("button", { name, exact: true })).toBeVisible();
}

test("ZIPUI01 勾选多个文件夹打包并校验 ZIP 目录和文件内容", async ({
  page,
}) => {
  await page.goto("/login");
  await page.getByRole("button", { name: "创建账号", exact: true }).click();
  await page.getByLabel("账号", { exact: true }).fill("zipui_" + Date.now());
  await page.getByLabel("密码", { exact: true }).fill("Folder-Zip-123");
  await page.getByRole("button", { name: "注册并进入云盘" }).click();
  await expect(page.getByRole("button", { name: /打包下载/ })).toHaveCount(0);
  const directory = resolve("../.tools/zipui-" + Date.now());
  const name = directory.split(/[\\/]/).at(-1)!;
  mkdirSync(resolve(directory, "算法"), { recursive: true });
  writeFileSync(resolve(directory, "算法/笔记.txt"), "中文内容");
  const chooser = page.waitForEvent("filechooser");
  await page.getByRole("button", { name: "更多上传选项", exact: true }).click();
  await page.getByRole("button", { name: "上传文件夹", exact: true }).click();
  await (await chooser).setFiles(directory);
  await page.getByRole("button", { name, exact: true }).click();
  await page.getByRole("button", { name: "算法", exact: true }).click();
  await expect(
    page.getByRole("button", { name: "笔记.txt", exact: true }),
  ).toBeVisible();
  await folder(page, "空目录");
  await page.locator(".crumb-button").first().click();
  await folder(page, "en");
  await page.getByRole("button", { name: "en", exact: true }).click();
  await page
    .locator("input[type=file][multiple]:not([webkitdirectory])")
    .setInputFiles({
      name: "words.txt",
      mimeType: "text/plain",
      buffer: Buffer.from("vocabulary"),
    });
  await expect(
    page.getByRole("button", { name: "words.txt", exact: true }),
  ).toBeVisible();
  await page.locator(".crumb-button").first().click();
  await page.getByLabel("选择 " + name, { exact: true }).check();
  await page.getByLabel("选择 en", { exact: true }).check();
  await page.getByRole("button", { name: "打包下载 (2)", exact: true }).click();
  await expect(page.getByText("可下载", { exact: true })).toBeVisible({
    timeout: 30000,
  });
  await expect(
    page.getByText("2 个文件 · 4 个目录", { exact: true }),
  ).toBeVisible();
  const downloaded = page.waitForEvent("download");
  await page.getByRole("link", { name: "下载 ZIP", exact: true }).click();
  const file = await downloaded;
  expect(await file.failure()).toBeNull();
  const entries = contents(readFileSync((await file.path())!));
  expect(entries.get(`${name}/算法/笔记.txt`)).toBe("中文内容");
  expect(entries.get(`${name}/算法/空目录/`)).toBe("");
  expect(entries.get("en/words.txt")).toBe("vocabulary");
  expect(entries.size).toBe(6);
  await page.getByRole("button", { name: "网格视图", exact: true }).click();
  await page.getByRole("button", { name: "en的操作", exact: true }).click();
  await page.getByRole("menuitem", { name: "压缩下载", exact: true }).click();
  await expect(
    page.locator("#download-tasks .el-table__body tbody tr"),
  ).toHaveCount(2);
  await page.screenshot({
    path: "../.tools/ui-ux-refactor/screenshots/e2e/folder-zip-download.png",
    fullPage: true,
  });
});
