import { test, expect } from "@playwright/test";
const username = "ui_" + Date.now(),
  password = "Browser-test-123";
test("UI01 完整浏览器流程：注册、目录、上传预览、分享、恢复、网格与退出", async ({
  page,
  context,
}) => {
  const errors: string[] = [];
  page.on("pageerror", (e) => {
    errors.push(e.message);
    console.log("PAGE ERROR", e.message);
  });
  page.on("response", async (r) => {
    if (r.status() >= 400 && r.url().includes("/api/"))
      console.log("API failure", r.status(), r.url(), await r.text());
  });
  await page.goto("/login");
  await expect(page.getByRole("heading", { name: "欢迎回来" })).toBeVisible();
  await page.screenshot({
    path: "../docs/evidence/login-desktop.png",
    fullPage: true,
  });
  await page.getByRole("button", { name: "创建账号", exact: true }).click();
  await page.getByLabel("账号", { exact: true }).fill(username);
  await page.getByLabel("密码", { exact: true }).fill(password);
  await page.getByRole("button", { name: "注册并进入云盘" }).click();
  await expect(page.getByRole("heading", { name: "我的文件." })).toBeVisible();
  await expect(page.getByText("无法连接服务器")).toHaveCount(0);
  await page.getByRole("button", { name: "新建文件夹", exact: true }).click();
  await page.locator(".el-message-box input").fill("浏览器验收");
  await page.getByRole("button", { name: "创建", exact: true }).click();
  await expect(
    page.getByRole("button", { name: "浏览器验收", exact: true }),
  ).toBeVisible();
  await page.getByRole("button", { name: "浏览器验收", exact: true }).click();
  await expect(
    page.locator(".crumb-button").filter({ hasText: "浏览器验收" }),
  ).toBeVisible();
  await page
    .locator("input[type=file][multiple]:not([webkitdirectory])")
    .setInputFiles([
      {
        name: "验收笔记.txt",
        mimeType: "text/plain",
        buffer: Buffer.from(
          "CloudNest 浏览器端验收\n<script>这是文本，不执行</script>",
        ),
      },
    ]);
  await expect(page.getByText("已完成", { exact: true })).toBeVisible({
    timeout: 30000,
  });
  await expect(
    page.getByRole("button", { name: "验收笔记.txt", exact: true }),
  ).toBeVisible();
  await page.getByRole("button", { name: "验收笔记.txt", exact: true }).click();
  await expect(page.locator(".preview-body pre")).toContainText(
    "<script>这是文本，不执行</script>",
  );
  await page
    .getByRole("dialog")
    .getByRole("button", { name: "关闭", exact: true })
    .last()
    .click();
  await page
    .getByRole("button", { name: "分享 验收笔记.txt", exact: true })
    .click();
  await page.getByRole("button", { name: "创建分享", exact: true }).click();
  const link = await page.getByLabel("分享链接").inputValue();
  expect(link).toContain("/s/");
  const visitor = await context.browser()!.newContext();
  const sharePage = await visitor.newPage();
  await sharePage.goto(link);
  await expect(
    sharePage.getByRole("heading", { name: "验收笔记.txt" }),
  ).toBeVisible();
  await sharePage.getByRole("button", { name: "在线预览" }).click();
  await expect(sharePage.locator(".preview-body pre")).toContainText(
    "浏览器端验收",
  );
  await visitor.close();
  await page
    .getByRole("dialog")
    .getByRole("button", { name: "关闭", exact: true })
    .last()
    .click();
  await page
    .getByRole("button", { name: "验收笔记.txt的操作", exact: true })
    .click();
  await page.getByRole("menuitem", { name: "移入回收站" }).click();
  await page.getByRole("button", { name: "移入回收站", exact: true }).click();
  await expect(
    page.getByRole("button", { name: "验收笔记.txt", exact: true }),
  ).toHaveCount(0);
  await page.getByRole("button", { name: "回收站", exact: true }).click();
  await page.getByRole("button", { name: "恢复", exact: true }).click();
  await expect(page.getByText("已恢复", { exact: true })).toBeVisible();
  await page.getByRole("button", { name: "我的文件", exact: true }).click();
  await expect(
    page.getByRole("button", { name: "验收笔记.txt", exact: true }),
  ).toBeVisible();
  await page.getByLabel("搜索文件名称").fill("验收笔记");
  await expect(page.getByText("搜索结果", { exact: true })).toBeVisible();
  await page.getByRole("button", { name: "网格视图" }).click();
  await expect(page.locator(".file-tile")).toHaveCount(1);
  await page.getByLabel("搜索文件名称").clear();
  await page.getByRole("button", { name: "我的云盘", exact: true }).click();
  await expect(
    page.getByRole("button", { name: "浏览器验收 文件夹" }),
  ).toBeVisible();
  await page.screenshot({
    path: "../docs/evidence/drive-desktop.png",
    fullPage: true,
  });
  await page.setViewportSize({ width: 390, height: 844 });
  await page.screenshot({
    path: "../docs/evidence/drive-mobile.png",
    fullPage: true,
  });
  expect(
    await page.evaluate(
      () => document.documentElement.scrollWidth <= innerWidth,
    ),
  ).toBeTruthy();
  await page.getByRole("button", { name: "退出登录" }).click();
  await expect(page.getByRole("heading", { name: "欢迎回来" })).toBeVisible();
  expect(errors).toEqual([]);
});

test("UI02 错误登录显示明确反馈", async ({ page }) => {
  await page.goto("/login");
  await page.getByLabel("账号", { exact: true }).fill("bad");
  await page.getByLabel("密码", { exact: true }).fill("wrong-password");
  await page.getByRole("button", { name: "登录云盘", exact: true }).click();
  await expect(page.getByRole("alert")).toContainText("账号或密码错误");
});

test("UI03 上传暂停、刷新重选原文件续传与秒传", async ({ page }) => {
  await page.goto("/login");
  await page.getByRole("button", { name: "创建账号", exact: true }).click();
  await page.getByLabel("账号", { exact: true }).fill("resume_" + Date.now());
  await page.getByLabel("密码", { exact: true }).fill(password);
  await page.getByRole("button", { name: "注册并进入云盘" }).click();
  await expect(page.locator(".crumb-button")).toBeVisible();
  await page.route("**/api/uploads/*/parts/*", async (route) => {
    await new Promise((r) => setTimeout(r, 1000));
    await route.continue().catch(() => {});
  });
  const file = {
    name: "断点测试.bin",
    mimeType: "application/octet-stream",
    buffer: Buffer.alloc(20 * 1024 * 1024, 77),
  };
  await page
    .locator("input[type=file][multiple]:not([webkitdirectory])")
    .setInputFiles([file]);
  await expect(page.getByText("上传中", { exact: true })).toBeVisible();
  await page.getByRole("button", { name: "暂停", exact: true }).click();
  await expect(page.getByText("已暂停", { exact: true })).toBeVisible();
  await page.reload();
  await expect(page.getByText("等待选择原文件", { exact: true })).toBeVisible();
  const chooserEvent = page.waitForEvent("filechooser");
  await page.getByRole("button", { name: "选择原文件", exact: true }).click();
  await (await chooserEvent).setFiles(file);
  await expect(page.getByText("已完成", { exact: true })).toBeVisible({
    timeout: 30000,
  });
  await expect(
    page.getByRole("button", { name: "断点测试.bin", exact: true }),
  ).toBeVisible();
  await page
    .locator("input[type=file][multiple]:not([webkitdirectory])")
    .setInputFiles([{ ...file, name: "秒传副本.bin" }]);
  await expect(page.getByText("秒传完成", { exact: true })).toBeVisible({
    timeout: 30000,
  });
  await expect(
    page.getByRole("button", { name: "秒传副本.bin", exact: true }),
  ).toBeVisible();
});

test("UI04 图片与 PDF 双页实际渲染", async ({ page }) => {
  await page.goto("/login");
  await page.getByRole("button", { name: "创建账号", exact: true }).click();
  await page.getByLabel("账号", { exact: true }).fill("preview_" + Date.now());
  await page.getByLabel("密码", { exact: true }).fill(password);
  await page.getByRole("button", { name: "注册并进入云盘" }).click();
  await expect(page.locator(".crumb-button")).toBeVisible();
  const stream = (text: string) => `BT /F1 20 Tf 45 180 Td (${text}) Tj ET`;
  const first = stream("CloudNest PDF preview / page one"),
    second = stream("Page two renders correctly");
  const objects = [
    "<< /Type /Catalog /Pages 2 0 R >>",
    "<< /Type /Pages /Kids [3 0 R 6 0 R] /Count 2 >>",
    "<< /Type /Page /Parent 2 0 R /MediaBox [0 0 500 260] /Resources << /Font << /F1 4 0 R >> >> /Contents 5 0 R >>",
    "<< /Type /Font /Subtype /Type1 /BaseFont /Helvetica >>",
    `<< /Length ${first.length} >>\nstream\n${first}\nendstream`,
    "<< /Type /Page /Parent 2 0 R /MediaBox [0 0 500 260] /Resources << /Font << /F1 4 0 R >> >> /Contents 7 0 R >>",
    `<< /Length ${second.length} >>\nstream\n${second}\nendstream`,
  ];
  let pdf = "%PDF-1.4\n";
  const offsets = [0];
  for (const [i, object] of objects.entries()) {
    offsets.push(Buffer.byteLength(pdf));
    pdf += `${i + 1} 0 obj\n${object}\nendobj\n`;
  }
  const xref = Buffer.byteLength(pdf);
  pdf += `xref\n0 8\n0000000000 65535 f \n${offsets
    .slice(1)
    .map((n) => n.toString().padStart(10, "0") + " 00000 n ")
    .join(
      "\n",
    )}\ntrailer\n<< /Size 8 /Root 1 0 R >>\nstartxref\n${xref}\n%%EOF`;
  const png = Buffer.from(
    "iVBORw0KGgoAAAANSUhEUgAAAAEAAAABCAQAAAC1HAwCAAAAC0lEQVR42mP8/x8AAusB9Wl6l1sAAAAASUVORK5CYII=",
    "base64",
  );
  await page
    .locator("input[type=file][multiple]:not([webkitdirectory])")
    .setInputFiles([
      {
        name: "两页文档.pdf",
        mimeType: "application/pdf",
        buffer: Buffer.from(pdf),
      },
      { name: "图片.png", mimeType: "image/png", buffer: png },
    ]);
  await expect(
    page.getByRole("button", { name: "图片.png", exact: true }),
  ).toBeVisible({ timeout: 30000 });
  await page.getByRole("button", { name: "两页文档.pdf", exact: true }).click();
  await expect(page.locator("canvas[data-rendered=true]")).toBeVisible({
    timeout: 30000,
  });
  await expect(page.getByText("第 1 / 2 页", { exact: true })).toBeVisible();
  await page.getByRole("button", { name: "下一页", exact: true }).click();
  await expect(page.getByText("第 2 / 2 页", { exact: true })).toBeVisible();
  await expect(page.locator("canvas[data-rendered=true]")).toBeVisible();
  await page.screenshot({
    path: "../docs/evidence/pdf-preview.png",
    fullPage: true,
  });
  await page
    .getByRole("dialog")
    .getByRole("button", { name: "关闭", exact: true })
    .last()
    .click();
  await page.getByRole("button", { name: "图片.png", exact: true }).click();
  const img = page.locator(".preview-body img");
  await expect(img).toBeVisible();
  expect(
    await img.evaluate((image: HTMLImageElement) => image.naturalWidth),
  ).toBe(1);
});
