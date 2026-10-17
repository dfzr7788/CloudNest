import { test, expect, type Page } from "@playwright/test";
import { readFileSync } from "node:fs";
import { docx, pdfFixture } from "./document-fixtures";

async function account(page: Page) {
  await page.goto("/login");
  await page.getByRole("button", { name: "创建账号", exact: true }).click();
  await page.getByLabel("账号", { exact: true }).fill("preview_" + Date.now());
  await page.getByLabel("密码", { exact: true }).fill("Preview-Test-123");
  // Full-suite runs share a loopback IP; respect the production registration limit.
  for (let attempt = 0; attempt < 3; attempt++) {
    const response = page.waitForResponse(
      (r) =>
        r.url().endsWith("/api/auth/register") &&
        r.request().method() === "POST",
    );
    await page.getByRole("button", { name: "注册并进入云盘" }).click();
    if ((await response).status() !== 429) break;
    await page.waitForTimeout(Math.min(30000, 60050 - (Date.now() % 60000)));
  }
  await expect(page.getByRole("heading", { name: "我的文件." })).toBeVisible();
  return (await (await page.request.get("/api/user/me")).json())
    .rootId as string;
}
async function post(page: Page, path: string, data: unknown) {
  const csrf = await (await page.request.get("/api/auth/csrf")).json();
  const result = await page.request.post("/api" + path, {
    data,
    headers: { "X-XSRF-TOKEN": csrf.token },
  });
  expect(result.ok(), await result.text()).toBeTruthy();
  return result.json();
}
async function upload(
  page: Page,
  folderId: string,
  name: string,
  buffer: Buffer,
) {
  const csrf = await (await page.request.get("/api/auth/csrf")).json();
  const result = await page.request.post("/api/files/upload", {
    multipart: {
      folderId,
      file: { name, mimeType: "application/octet-stream", buffer },
    },
    headers: { "X-XSRF-TOKEN": csrf.token },
  });
  expect(result.ok(), await result.text()).toBeTruthy();
  return result.json();
}
async function close(page: Page) {
  await page
    .getByRole("dialog")
    .getByRole("button", { name: "关闭", exact: true })
    .click();
}
async function seedFolders(page: Page, root: string) {
  const parent = await post(page, "/folders", {
    parentId: root,
    name: "Shared root",
  });
  const a = await post(page, "/folders", {
    parentId: parent.id,
    name: "Folder A",
  });
  const b = await post(page, "/folders", {
    parentId: parent.id,
    name: "Folder B",
  });
  await upload(page, a.id, "keep-a.txt", Buffer.from("selected A"));
  await upload(page, a.id, "omit-a.txt", Buffer.from("not selected"));
  await upload(page, b.id, "keep-b.txt", Buffer.from("selected B"));
  await upload(page, b.id, "omit-b.txt", Buffer.from("not selected"));
  return parent;
}
function zipNames(bytes: Buffer) {
  const end = bytes.lastIndexOf(Buffer.from([0x50, 0x4b, 0x05, 0x06]));
  expect(end).toBeGreaterThan(0);
  let offset = bytes.readUInt32LE(end + 16);
  const names: string[] = [];
  while (bytes.readUInt32LE(offset) === 0x02014b50) {
    const length = bytes.readUInt16LE(offset + 28);
    names.push(bytes.subarray(offset + 46, offset + 46 + length).toString());
    offset +=
      46 +
      length +
      bytes.readUInt16LE(offset + 30) +
      bytes.readUInt16LE(offset + 32);
  }
  return names;
}

test("COREUI Markdown 数学代码与相对图片、复制粘贴和搜索入口", async ({
  page,
}) => {
  const root = await account(page);
  const folder = await post(page, "/folders", {
    parentId: root,
    name: "Destination",
  });
  await upload(
    page,
    root,
    "formula.md",
    Buffer.from(
      "# Formula\n\n$x^2 + y^2$\n\n```python\nprint(42)\n```\n\n![local](image.png)",
    ),
  );
  await upload(
    page,
    root,
    "image.png",
    Buffer.from(
      "iVBORw0KGgoAAAANSUhEUgAAAAEAAAABCAQAAAC1HAwCAAAAC0lEQVR42mP8/x8AAwMCAO+jRZkAAAAASUVORK5CYII=",
      "base64",
    ),
  );
  await page.reload();
  await page.getByRole("button", { name: "formula.md", exact: true }).click();
  await expect(page.locator(".rendered-document .katex")).toBeVisible();
  await expect(page.locator(".rendered-document .hljs-number")).toHaveText(
    "42",
  );
  await expect(page.locator(".rendered-document img")).toBeVisible();
  expect(
    await page
      .locator(".rendered-document img")
      .evaluate((image: any) => image.naturalWidth),
  ).toBe(1);
  await close(page);
  await page.getByLabel("选择 formula.md", { exact: true }).check();
  await page.getByRole("button", { name: "复制", exact: true }).click();
  await page.getByRole("button", { name: "Destination", exact: true }).click();
  await page
    .getByRole("button", { name: "粘贴到当前目录", exact: true })
    .click();
  await expect(
    page.getByRole("button", { name: "formula.md", exact: true }),
  ).toBeVisible();
  await page
    .getByRole("button", { name: "标签与正文搜索", exact: true })
    .click();
  await page.getByLabel("高级搜索关键词").fill("formula");
  await page
    .getByRole("dialog")
    .getByRole("button", { name: "搜索", exact: true })
    .click();
  await expect(
    page
      .getByRole("dialog")
      .getByRole("button", { name: "formula.md", exact: true }),
  ).toHaveCount(2);
});

test("PREVIEW01 生产构建 PDF、Markdown 安全渲染及 DOCX 阅读预览", async ({
  page,
}) => {
  const root = await account(page);
  await upload(
    page,
    root,
    "notes.md",
    Buffer.from(
      '# Rendered heading\n\n**Bold text**\n\n| A | B |\n|---|---|\n|1|2|\n\n<script>window.previewAttack=1</script><img src=x onerror="window.previewAttack=2">\n\n[bad](javascript:alert(1))',
    ),
  );
  await upload(page, root, "document.docx", docx);
  await upload(page, root, "pages.pdf", pdfFixture());
  if (process.env.CN_PREVIEW_DOC)
    await upload(
      page,
      root,
      "legacy.doc",
      readFileSync(process.env.CN_PREVIEW_DOC),
    );
  await page.reload();
  await page.getByRole("button", { name: "notes.md", exact: true }).click();
  await expect(page.locator(".rendered-document h1")).toHaveText(
    "Rendered heading",
  );
  await expect(page.locator(".rendered-document strong")).toHaveText(
    "Bold text",
  );
  await expect(page.locator(".rendered-document table")).toBeVisible();
  expect(
    await page.evaluate(() => (window as any).previewAttack),
  ).toBeUndefined();
  await expect(
    page.locator(
      ".rendered-document script, .rendered-document img, .rendered-document a[href^='javascript:']",
    ),
  ).toHaveCount(0);
  await page.locator(".document-toolbar .el-switch").click();
  await expect(page.locator(".document-preview pre")).toContainText(
    "# Rendered heading",
  );
  await close(page);
  await page
    .getByRole("button", { name: "document.docx", exact: true })
    .click();
  await expect(page.locator(".rendered-document h1")).toHaveText(
    "CloudNest Word heading",
  );
  await expect(page.locator(".rendered-document strong")).toHaveText(
    "Formatted paragraph",
  );
  await expect(page.locator(".rendered-document td")).toContainText(
    "Table cell",
  );
  await close(page);
  if (process.env.CN_PREVIEW_DOC) {
    await page.getByRole("button", { name: "legacy.doc", exact: true }).click();
    await expect(page.locator(".rendered-document")).not.toHaveText("");
    await expect(
      page.getByText("文档损坏、已加密或格式不受支持", { exact: false }),
    ).toHaveCount(0);
    await close(page);
  }
  const worker = page.waitForResponse(
    (r) => r.url().includes("pdf.worker") && r.status() === 200,
  );
  await page.getByRole("button", { name: "pages.pdf", exact: true }).click();
  expect((await worker).headers()["content-type"]).toMatch(/javascript/);
  await expect(page.locator('canvas[data-rendered="true"]')).toBeVisible();
  await page.getByRole("button", { name: "下一页", exact: true }).click();
  await expect(page.getByText("第 2 / 2 页", { exact: true })).toBeVisible();
  await expect(page.locator('canvas[data-rendered="true"]')).toBeVisible();
});

test("ZIPSELECT01 私有云盘跨目录选择部分文件，当前页取消不影响其他目录", async ({
  page,
}) => {
  const root = await account(page);
  await seedFolders(page, root);
  await page.reload();
  await page.getByRole("button", { name: "Shared root", exact: true }).click();
  await page.getByRole("button", { name: "Folder A", exact: true }).click();
  await page.getByLabel("选择 keep-a.txt", { exact: true }).check();
  await page
    .locator(".crumb-button")
    .filter({ hasText: "Shared root" })
    .click();
  await page.getByRole("button", { name: "Folder B", exact: true }).click();
  await page
    .locator("label.el-checkbox")
    .filter({ hasText: "全选当前页" })
    .click();
  await page
    .locator("label.el-checkbox")
    .filter({ hasText: "全选当前页" })
    .click();
  await expect(
    page.getByRole("button", { name: "打包下载 (1)", exact: true }),
  ).toBeEnabled();
  await page.getByLabel("选择 keep-b.txt", { exact: true }).check();
  await page.getByRole("button", { name: "查看已选 (2)", exact: true }).click();
  await expect(page.locator(".selection-basket")).toContainText(
    "Folder A/keep-a.txt",
  );
  await expect(page.locator(".selection-basket")).toContainText(
    "Folder B/keep-b.txt",
  );
  await page.getByRole("button", { name: "完成", exact: true }).click();
  await page.getByRole("button", { name: "打包下载 (2)", exact: true }).click();
  await expect(
    page.getByRole("link", { name: "下载 ZIP", exact: true }),
  ).toBeVisible({ timeout: 30000 });
  const download = page.waitForEvent("download");
  await page.getByRole("link", { name: "下载 ZIP", exact: true }).click();
  const names = zipNames(readFileSync((await (await download).path())!));
  expect(names.filter((n) => !n.endsWith("/")).sort()).toEqual([
    "keep-a.txt",
    "keep-b.txt",
  ]);
});

test("SHAREZIP01 分享目录递归打包、跨目录部分选择与 PDF/Word/Markdown 预览", async ({
  page,
  browser,
}) => {
  const root = await account(page);
  const parent = await seedFolders(page, root);
  await upload(page, parent.id, "shared.md", Buffer.from("# Shared Markdown"));
  await upload(page, parent.id, "shared.docx", docx);
  await upload(page, parent.id, "shared.pdf", pdfFixture());
  const share = await post(page, "/shares", {
    fileId: parent.id,
    hours: 1,
    allowDownload: true,
  });
  const visitor = await browser.newContext();
  const shared = await visitor.newPage();
  await shared.goto(new URL("/s/" + share.token, page.url()).href);
  for (const name of ["shared.md", "shared.docx"]) {
    await shared.getByRole("button", { name, exact: true }).click();
    await expect(shared.locator(".rendered-document h1")).toContainText(
      name.endsWith("md") ? "Shared Markdown" : "CloudNest Word heading",
    );
    await close(shared);
  }
  await shared.getByRole("button", { name: "shared.pdf", exact: true }).click();
  await expect(shared.locator('canvas[data-rendered="true"]')).toBeVisible();
  await close(shared);
  await shared
    .getByRole("button", { name: "📁 Folder A", exact: true })
    .click();
  await shared.getByLabel("选择 keep-a.txt", { exact: true }).check();
  await shared.getByRole("button", { name: "返回上级", exact: true }).click();
  await shared
    .getByRole("button", { name: "📁 Folder B", exact: true })
    .click();
  await shared.getByLabel("选择 keep-b.txt", { exact: true }).check();
  const partial = shared.waitForEvent("download");
  await shared
    .getByRole("button", { name: "打包选中项目 (2)", exact: true })
    .click();
  expect(
    zipNames(readFileSync((await (await partial).path())!))
      .filter((n) => !n.endsWith("/"))
      .sort(),
  ).toEqual(["keep-a.txt", "keep-b.txt"]);
  await shared.getByRole("button", { name: "返回上级", exact: true }).click();
  const all = shared.waitForEvent("download");
  await shared
    .getByRole("button", { name: "下载整个文件夹", exact: true })
    .click();
  const names = zipNames(readFileSync((await (await all).path())!));
  expect(names).toContain("Shared root/Folder A/omit-a.txt");
  expect(names).toContain("Shared root/Folder B/omit-b.txt");
  const previewOnly = await post(page, "/shares", {
    fileId: parent.id,
    hours: 1,
    allowDownload: false,
  });
  await shared.goto(new URL("/s/" + previewOnly.token, page.url()).href);
  await expect(
    shared.getByText("分享者仅开放了预览权限", { exact: true }),
  ).toBeVisible();
  await expect(shared.getByRole("checkbox")).toHaveCount(0);
  await expect(
    shared.getByRole("button", { name: "下载整个文件夹", exact: true }),
  ).toHaveCount(0);
  await visitor.close();
});

test("PDFHTTP01 普通 HTTP 域名下 PDF worker 正常渲染", async ({
  page,
  browser,
}) => {
  test.skip(
    !process.env.CN_HTTP_PREVIEW_ORIGIN,
    "Requires a local HTTP hostname mapped to the production test server",
  );
  const root = await account(page);
  const file = await upload(page, root, "http.pdf", pdfFixture());
  const share = await post(page, "/shares", {
    fileId: file.fileId,
    hours: 1,
    allowDownload: false,
  });
  const visitor = await browser.newContext();
  const shared = await visitor.newPage();
  await shared.goto(process.env.CN_HTTP_PREVIEW_ORIGIN + "/s/" + share.token);
  expect(await shared.evaluate(() => window.isSecureContext)).toBe(false);
  await shared.getByRole("button", { name: "在线预览", exact: true }).click();
  await expect(shared.locator('canvas[data-rendered="true"]')).toBeVisible({
    timeout: 15000,
  });
  await visitor.close();
});
