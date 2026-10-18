import { test, expect, type Page } from "@playwright/test";

async function account(page: Page) {
  await page.goto("/login");
  await page.getByRole("button", { name: "创建账号", exact: true }).click();
  await page.getByLabel("账号", { exact: true }).fill("layout_" + Date.now());
  await page.getByLabel("密码", { exact: true }).fill("Layout-Test-123");
  // Full-suite runs share a loopback IP. Observe the real registration limit.
  for (let attempt = 0; attempt < 3; attempt++) {
    const response = page.waitForResponse(
      (r) =>
        r.url().endsWith("/api/auth/register") &&
        r.request().method() === "POST",
    );
    await page.getByRole("button", { name: "注册并进入云盘" }).click();
    if ((await response).status() !== 429) break;
    const wait = Math.min(60000, 60050 - (Date.now() % 60000));
    test.setTimeout(test.info().timeout + wait + 1000);
    await page.waitForTimeout(wait);
  }
  await expect(page.locator(".crumb-button").first()).toBeVisible();
  return (await (await page.request.get("/api/user/me")).json())
    .rootId as string;
}
async function post(page: Page, path: string, data: unknown) {
  const csrf = await (await page.request.get("/api/auth/csrf")).json();
  const r = await page.request.post("/api" + path, {
    data,
    headers: { "X-XSRF-TOKEN": csrf.token },
  });
  expect(r.ok(), await r.text()).toBeTruthy();
  return r.json();
}
async function screenshot(page: Page, name: string) {
  await page.screenshot({
    path: `../.tools/ui-ux-refactor/screenshots/acceptance/${name}.png`,
    animations: "disabled",
  });
}
async function noOverflow(page: Page) {
  expect(
    await page.evaluate(
      () => document.documentElement.scrollWidth <= innerWidth,
    ),
  ).toBeTruthy();
  expect(
    await page
      .locator(".main-shell")
      .evaluate((e) => e.scrollWidth <= e.clientWidth + 1),
  ).toBeTruthy();
}
async function nav(page: Page, name: string) {
  if (
    await page
      .getByRole("button", { name: "打开导航", exact: true })
      .isVisible()
  )
    await page.getByRole("button", { name: "打开导航", exact: true }).click();
  await page
    .getByRole("navigation", { name: "工作空间导航" })
    .getByRole("button", { name, exact: true })
    .click();
}
test("LAYOUT01 六视口真实分页、网格、菜单、导航同步、筛选与搜索范围", async ({
  page,
}) => {
  test.setTimeout(90000);
  const errors: string[] = [];
  page.on("pageerror", (e) => errors.push(e.message));
  const root = await account(page);
  for (let n = 0; n < 30; n++)
    await post(page, "/folders", {
      parentId: root,
      name:
        n === 0
          ? "很长的中文项目名称与 English document collection 2026 需求资料归档"
          : `项目 ${String(n).padStart(2, "0")}`,
    });
  await page.reload();
  await expect(page.locator(".cn-file-list tbody tr")).toHaveCount(30);
  for (const [width, height] of [
    [1440, 900],
    [1920, 1080],
    [1024, 768],
    [768, 1024],
    [390, 844],
    [360, 740],
  ]) {
    await page.setViewportSize({ width, height });
    await noOverflow(page);
    await screenshot(page, `list-${width}`);
    await page.getByRole("button", { name: "网格视图", exact: true }).click();
    await expect(page.locator(".file-tile")).toHaveCount(30);
    await noOverflow(page);
    await screenshot(page, `grid-${width}`);
    await page.locator(".tile-more button").first().focus();
    await page.keyboard.press("Enter");
    await expect(
      page.getByRole("menuitem", { name: "重命名", exact: true }),
    ).toBeVisible();
    await page.keyboard.press("Escape");
    await page.getByRole("button", { name: "列表视图", exact: true }).click();
  }
  await nav(page, "我的收藏");
  await expect(
    page
      .getByRole("group", { name: "文件集合" })
      .getByRole("button", { name: "我的收藏" }),
  ).toHaveAttribute("aria-pressed", "true");
  await screenshot(page, "favorites-empty-mobile");
  await nav(page, "我的文件");
  await page.getByRole("button", { name: "筛选", exact: true }).click();
  await page
    .getByRole("combobox", { name: "文件类型", exact: true })
    .press("Enter");
  await page.getByRole("option", { name: "图片", exact: true }).click();
  await screenshot(page, "filters-mobile");
  await page.keyboard.press("Escape");
  await expect(page.getByLabel("已生效条件")).toContainText("图片");
  await page.getByRole("button", { name: "重置条件" }).click();
  await expect(page.locator(".cn-file-list tbody tr")).toHaveCount(30);
  await nav(page, "我的分享");
  await page.getByRole("textbox", { name: "搜索文件名称" }).fill("项目 01");
  await expect(page.locator(".cn-file-list tbody tr")).toHaveCount(1);
  await expect(page.getByLabel("已生效条件")).toContainText("全部目录");
  await page.getByLabel("搜索文件名称").clear();
  await expect(page.locator(".cn-file-list tbody tr")).toHaveCount(30);
  await page.getByLabel("选择 项目 01", { exact: true }).check();
  await screenshot(page, "selection-mobile");
  await page.getByRole("button", { name: "复制", exact: true }).click();
  await page.getByRole("button", { name: "取消选择", exact: true }).click();
  await expect(
    page.getByRole("button", { name: "粘贴到当前目录", exact: true }),
  ).toBeVisible();
  await page.emulateMedia({ reducedMotion: "reduce" });
  await page.setViewportSize({ width: 720, height: 450 });
  await noOverflow(page);
  await screenshot(page, "reduced-motion-200-percent-equivalent");
  expect(errors).toEqual([]);
});

test("LAYOUT02 加载、失败重试、空状态、深路径和焦点", async ({ page }) => {
  const root = await account(page);
  await screenshot(page, "empty-desktop");
  let parent = root;
  for (const name of ["项目", "资料", "2026年秋季 设计规范和产品需求长目录"])
    parent = (await post(page, "/folders", { parentId: parent, name })).id;
  await page.reload();
  for (const name of ["项目", "资料", "2026年秋季 设计规范和产品需求长目录"])
    await page.getByRole("button", { name, exact: true }).click();
  await page.setViewportSize({ width: 360, height: 740 });
  await screenshot(page, "deep-breadcrumb-mobile");
  await noOverflow(page);
  let release!: () => void;
  const hold = new Promise<void>((r) => (release = r));
  await page.route("**/api/entries?*", async (route) => {
    await hold;
    await route.continue();
  });
  await page.getByRole("button", { name: "刷新", exact: true }).click();
  await expect(
    page.getByRole("status", { name: "正在加载文件" }),
  ).toBeVisible();
  await screenshot(page, "loading-mobile");
  release();
  await expect(page.getByRole("status", { name: "正在加载文件" })).toHaveCount(
    0,
  );
  await page.unroute("**/api/entries?*");
  await page.route("**/api/entries?*", (route) =>
    route.fulfill({
      status: 503,
      json: { message: "测试：文件服务暂时不可用" },
    }),
  );
  await page.getByRole("button", { name: "刷新", exact: true }).click();
  await expect(
    page.getByRole("heading", { name: "文件暂时无法加载" }),
  ).toBeVisible();
  await screenshot(page, "error-mobile");
  await page.unroute("**/api/entries?*");
  await page.getByRole("button", { name: "重新加载", exact: true }).click();
  await expect(
    page.getByRole("heading", { name: "这个目录还没有文件" }),
  ).toBeVisible();
  await page.getByRole("button", { name: "新建文件夹", exact: true }).focus();
  await page.keyboard.press("Enter");
  await expect(page.getByRole("dialog", { name: "新建文件夹" })).toBeVisible();
  await page.keyboard.press("Escape");
  await expect(
    page.getByRole("button", { name: "新建文件夹", exact: true }),
  ).toBeFocused();
});

test("LAYOUT03 普通用户与管理员所有设置面板、小屏切换不产生写入", async ({
  page,
}) => {
  test.setTimeout(90000);
  await account(page);
  await page.getByRole("button", { name: "账号与安全", exact: true }).click();
  await expect(
    page.getByRole("tab", { name: "系统管理", exact: true }),
  ).toHaveCount(0);
  await screenshot(page, "settings-user-desktop");
  await page
    .getByRole("dialog", { name: "账号与安全", exact: true })
    .getByRole("button", { name: "关闭此对话框" })
    .click();
  await page.route("**/api/user/me", async (route) => {
    const r = await route.fetch();
    await route.fulfill({
      response: r,
      json: { ...(await r.json()), administrator: true },
    });
  });
  await page.route("**/api/admin/**", (route) => {
    const url = route.request().url();
    return route.fulfill({
      json: url.includes("/users")
        ? []
        : url.endsWith("/storage")
          ? { policies: [], jobs: [], usage: [], defaultBackend: "local" }
          : {},
    });
  });
  await page.reload();
  const writes: string[] = [];
  page.on("request", (r) => {
    if (r.url().includes("/api/") && !["GET", "HEAD"].includes(r.method()))
      writes.push(r.method() + " " + new URL(r.url()).pathname);
  });
  await page.getByRole("button", { name: "账号与安全", exact: true }).click();
  const labels = [
    "修改密码",
    "验证与客户端",
    "登录会话",
    "空间明细",
    "回收站设置",
    "活动记录",
    "AI 设置",
    "AI 知识库",
    "智能整理",
    "系统管理",
    "服务与存储",
    "系统更新",
  ];
  for (const name of labels) {
    await page.getByRole("tab", { name, exact: true }).click();
    await screenshot(page, `settings-desktop-${name}`);
  }
  await page.setViewportSize({ width: 360, height: 740 });
  for (const name of labels) {
    await page
      .getByRole("combobox", { name: "设置分组", exact: true })
      .press("Enter");
    await page.getByRole("option", { name, exact: true }).click();
    await screenshot(page, `settings-mobile-${name}`);
    expect(
      await page.evaluate(
        () => document.documentElement.scrollWidth <= innerWidth,
      ),
    ).toBeTruthy();
    const body = page.locator(".account-dialog .el-dialog__body");
    expect(
      await body.evaluate((e) => e.scrollWidth <= e.clientWidth + 1),
    ).toBeTruthy();
  }
  expect(writes).toEqual([]);
});

test("LAYOUT04 公开提取码、仅预览、收集与撤销、手机文件操作", async ({
  page,
  browser,
}) => {
  const root = await account(page);
  const headers = {
    "X-XSRF-TOKEN": (await (await page.request.get("/api/auth/csrf")).json())
      .token,
  };
  const upload = await page.request.post("/api/files/upload", {
    headers,
    multipart: {
      folderId: root,
      file: {
        name: "共享说明.txt",
        mimeType: "text/plain",
        buffer: Buffer.from("分享说明内容"),
      },
    },
  });
  const file = (await upload.json()).fileId;
  const share = await post(page, "/shares", {
    fileId: file,
    hours: 1,
    code: "2026",
    allowDownload: false,
  });
  const guest = await browser.newPage({
    viewport: { width: 390, height: 844 },
  });
  await guest.goto(new URL(share.path, page.url()).href);
  await screenshot(guest, "public-code-mobile");
  await guest.getByLabel("提取码").fill("2026");
  await guest.getByRole("button", { name: "查看文件" }).click();
  await guest.getByRole("button", { name: "在线预览" }).click();
  await expect(guest.locator(".preview-body pre")).toHaveText("分享说明内容");
  await expect(
    guest.getByRole("link", { name: "下载文件", exact: true }),
  ).toHaveCount(0);
  await expect(guest.getByRole("button", { name: "AI 阅读助手" })).toHaveCount(
    0,
  );
  await screenshot(guest, "public-preview-only-mobile");
  await guest.getByRole("button", { name: "关闭", exact: true }).click();
  const collection = await post(page, "/shares/collection", {
    fileIds: [root],
    title: "收集资料",
    hours: 1,
    collect: true,
    allowDownload: false,
  });
  await guest.goto(new URL(collection.path, page.url()).href);
  await guest.getByLabel("提交收集文件").setInputFiles({
    name: "访客资料.txt",
    mimeType: "text/plain",
    buffer: Buffer.from("访客提交"),
  });
  await expect(
    guest.getByText("已接收 1 个文件", { exact: true }),
  ).toBeVisible();
  await screenshot(guest, "public-collect-mobile");
  await page.request.delete("/api/shares/" + share.id, { headers });
  await guest.goto(new URL(share.path, page.url()).href);
  await expect(
    guest.getByRole("heading", { name: "分享暂时无法访问" }),
  ).toBeVisible();
  await screenshot(guest, "public-revoked-mobile");
  await guest.close();
  await page.reload();
  await page.setViewportSize({ width: 390, height: 844 });
  await page.getByRole("button", { name: "共享说明.txt", exact: true }).click();
  await expect(page.locator(".preview-body pre")).toHaveText("分享说明内容");
  await page.getByRole("button", { name: "关闭", exact: true }).click();
  await page
    .getByRole("button", { name: "共享说明.txt的操作", exact: true })
    .click();
  await page.getByRole("menuitem", { name: "分享", exact: true }).click();
  await screenshot(page, "share-create-mobile");
  await page.getByRole("button", { name: "关闭", exact: true }).click();
  await nav(page, "回收站");
  await screenshot(page, "trash-mobile");
  await nav(page, "我的文件");
  await page.getByRole("button", { name: "更多上传选项" }).click();
  await expect(
    page.getByRole("button", { name: "上传文件夹", exact: true }),
  ).toBeVisible();
  await screenshot(page, "upload-menu-mobile");
});

test("LAYOUT05 登录、邀请、OTP、重置与邮箱反馈", async ({ page }) => {
  await page.setViewportSize({ width: 360, height: 740 });
  await page.goto("/login?invitation=ui-fixture");
  await expect(page.getByLabel("邀请码（开放注册时可留空）")).toHaveValue(
    "ui-fixture",
  );
  await screenshot(page, "invitation-mobile");
  await page.goto("/login");
  await expect(page.getByLabel("登录双重验证代码")).toBeVisible();
  await screenshot(page, "otp-mobile");
  await page.getByRole("button", { name: "忘记密码" }).click();
  await expect(page.getByLabel("找回账号")).toBeVisible();
  await screenshot(page, "forgot-mobile");
  await page.goto("/login?reset=ui-fixture");
  await expect(page.getByLabel("重置新密码")).toBeVisible();
  await screenshot(page, "reset-mobile");
  await page.getByLabel("重置新密码").fill("New-Test-123");
  await page.getByLabel("确认重置密码").fill("Different-Test-123");
  await page.getByRole("button", { name: "保存新密码" }).click();
  await expect(page.getByRole("status")).toContainText("两次密码不同");
  await page.route("**/api/public/auth/verify-email", (route) =>
    route.fulfill({ status: 400, json: { message: "测试：验证链接已过期" } }),
  );
  await page.goto("/login?verifyEmail=ui-fixture");
  await expect(page.getByRole("alert")).toContainText("验证链接已过期");
  await screenshot(page, "verify-email-error-mobile");
});

test("LAYOUT06 上传失败、刷新重选、重试、折叠和同名三策略", async ({
  page,
}) => {
  test.setTimeout(90000);
  const root = await account(page);
  await page.setViewportSize({ width: 390, height: 844 });
  const original = {
    name: "恢复上传.txt",
    mimeType: "text/plain",
    buffer: Buffer.alloc(512 * 1024, 65),
  };
  let fail = true;
  await page.route("**/api/uploads/*/parts/*", (route) =>
    fail
      ? route.fulfill({ status: 400, json: { message: "测试：上传连接中断" } })
      : route.continue(),
  );
  await page
    .locator("input[type=file][multiple]:not([webkitdirectory])")
    .setInputFiles(original);
  await expect(page.locator(".task-error")).toContainText("上传连接中断");
  await screenshot(page, "upload-failed-mobile");
  await page.getByRole("button", { name: /传输列表/ }).click();
  await expect(page.locator(".upload-list")).toHaveCount(0);
  await page.reload();
  await expect(
    page.getByRole("button", { name: "选择原文件", exact: true }),
  ).toBeVisible();
  await screenshot(page, "upload-recover-mobile");
  fail = false;
  const chooser = page.waitForEvent("filechooser");
  await page.getByRole("button", { name: "选择原文件", exact: true }).click();
  await (await chooser).setFiles(original);
  await expect(page.getByText("已完成", { exact: true })).toBeVisible();
  await expect(
    page.getByRole("button", { name: original.name, exact: true }),
  ).toBeVisible();
  await page.getByRole("button", { name: "清除已结束" }).click();
  await expect(page.locator(".upload-panel")).toHaveCount(0);
  async function upload(text: string) {
    await page
      .locator("input[type=file][multiple]:not([webkitdirectory])")
      .setInputFiles({
        name: "同名策略.txt",
        mimeType: "text/plain",
        buffer: Buffer.from(text),
      });
    await expect(
      page.locator(".upload-task").last().locator(".task-bottom > span"),
    ).toHaveText(/已完成|秒传完成|已跳过/);
  }
  async function policy(label: string) {
    await page.getByRole("button", { name: "更多上传选项" }).click();
    await expect(page.locator(".cn-upload-popover")).toBeVisible();
    await page.locator(".cn-upload-popover .el-select__wrapper").click();
    await page.getByRole("option", { name: label, exact: true }).click();
    await page.getByRole("button", { name: "更多上传选项" }).click();
    await expect(page.locator(".cn-upload-popover")).not.toBeVisible();
  }
  await upload("first");
  await policy("覆盖并保留历史");
  await upload("second");
  let items = (
    await (
      await page.request.get("/api/entries", {
        params: { parentId: root, q: "同名策略" },
      })
    ).json()
  ).items;
  expect(items).toHaveLength(1);
  expect(
    (
      await (
        await page.request.get(`/api/files/${items[0].id}/versions`)
      ).json()
    ).items,
  ).toHaveLength(1);
  await policy("跳过");
  await upload("skipped");
  await expect(page.getByText("已跳过", { exact: true })).toBeVisible();
  await policy("保留两份");
  await upload("kept");
  items = (
    await (
      await page.request.get("/api/entries", {
        params: { parentId: root, q: "同名策略" },
      })
    ).json()
  ).items;
  expect(items).toHaveLength(2);
  await screenshot(page, "upload-policies-mobile");
});

test("LAYOUT07 图片变换、音视频实际解码和 AI 关闭/失败门控", async ({
  page,
}) => {
  test.setTimeout(60000);
  const root = await account(page);
  const csrf = (await (await page.request.get("/api/auth/csrf")).json()).token;
  const png = await page.evaluate(() => {
    const c = document.createElement("canvas");
    c.width = 1400;
    c.height = 900;
    const ctx = c.getContext("2d")!;
    ctx.fillStyle = "#dce5fa";
    ctx.fillRect(0, 0, 1400, 900);
    ctx.fillStyle = "#5266f1";
    ctx.fillRect(200, 200, 1000, 500);
    return c.toDataURL().split(",")[1]!;
  });
  const video = Buffer.from(
    "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",
    "base64",
  );
  // Short silence generated locally; no external media or runtime codec dependency.
  const audio = Buffer.from(
    "SUQzBAAAAAAAI1RTU0UAAAAPAAADTGF2ZjYyLjEyLjEwMgAAAAAAAAAAAAAA//tAwAAAAAAAAAAAAAAAAAAAAAAASW5mbwAAAA8AAAAFAAAEygBRUVFRUVFRUVFRUVFRUVFRUVFRfX19fX19fX19fX19fX19fX19fX2oqKioqKioqKioqKioqKioqKioqNTU1NTU1NTU1NTU1NTU1NTU1NTU//////////////////////////8AAAAATGF2ZgAAAAAAAAAAAAAAAAAAAAAAJAJAAAAAAAAABMqrzfccAAAAAAD/+1DEAAPAAAGkAAAAIAAANIAAAARMQU1FMy4xMDBVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVTEFNRTMuMTAwVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVV//tSxF2DwAABpAAAACAAADSAAAAEVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVMQU1FMy4xMDBVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVX/+1LEoYPAAAGkAAAAIAAANIAAAARVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVUxBTUUzLjEwMFVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVf/7UsShg8AAAaQAAAAgAAA0gAAABFVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVV//tSxKGDwAABpAAAACAAADSAAAAEVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVU=",
    "base64",
  );
  for (const [name, mimeType, buffer] of [
    ["图片 A.png", "image/png", Buffer.from(png, "base64")],
    ["图片 B.png", "image/png", Buffer.from(png, "base64")],
    ["音频.mp3", "audio/mpeg", audio],
    ["视频.mp4", "video/mp4", video],
    ["门控.txt", "text/plain", Buffer.from("不会自动调用模型")],
  ] as const) {
    const r = await page.request.post("/api/files/upload", {
      headers: { "X-XSRF-TOKEN": csrf },
      multipart: { folderId: root, file: { name, mimeType, buffer } },
    });
    expect(r.ok(), await r.text()).toBeTruthy();
  }
  await page.reload();
  await page.getByRole("button", { name: "图片 A.png", exact: true }).click();
  await expect(
    page.getByRole("link", { name: "下载文件", exact: true }),
  ).toBeVisible();
  await expect(page.locator(".preview-body img")).toBeVisible();
  await page.getByRole("button", { name: "放大", exact: true }).click();
  await page.getByRole("button", { name: "旋转", exact: true }).click();
  await expect(page.locator(".preview-body img")).toHaveAttribute(
    "style",
    /rotate\(90deg\)/,
  );
  await page.getByRole("button", { name: "下一张", exact: true }).click();
  await expect(page.getByRole("dialog")).toHaveAccessibleName("图片 B.png");
  await screenshot(page, "image-desktop");
  await page.getByRole("button", { name: "关闭", exact: true }).click();
  for (const [name, tag] of [
    ["音频.mp3", "audio"],
    ["视频.mp4", "video"],
  ]) {
    await page.getByRole("button", { name, exact: true }).click();
    await page
      .locator(`.preview-body ${tag}`)
      .evaluate((element: HTMLMediaElement) => element.play());
    await expect
      .poll(() =>
        page.locator(`.preview-body ${tag}`).evaluate((e: any) => e.readyState),
      )
      .toBeGreaterThanOrEqual(2);
    await screenshot(page, `preview-${tag}`);
    await page.getByRole("button", { name: "关闭", exact: true }).click();
  }
  await page.route("**/api/ai/capabilities", (route) =>
    route.fulfill({ json: { enabled: false } }),
  );
  await page.getByRole("button", { name: "门控.txt", exact: true }).click();
  await expect(page.locator(".preview-body pre")).toHaveText(
    "不会自动调用模型",
  );
  await expect(
    page.getByRole("button", { name: "AI 阅读助手", exact: true }),
  ).toHaveCount(0);
  await page.getByRole("button", { name: "关闭", exact: true }).click();
  await page.unroute("**/api/ai/capabilities");
  await page.route("**/api/ai/capabilities", (route) =>
    route.fulfill({ status: 503, json: { message: "测试：能力读取失败" } }),
  );
  await page.getByRole("button", { name: "门控.txt", exact: true }).click();
  await expect(
    page.getByText("AI 状态读取失败", { exact: false }),
  ).toBeVisible();
  await expect(
    page.getByRole("button", { name: "AI 阅读助手", exact: true }),
  ).toHaveCount(0);
  await page.unroute("**/api/ai/capabilities");
  await page.getByRole("button", { name: "重试", exact: true }).click();
  await page.getByRole("button", { name: "AI 阅读助手", exact: true }).click();
  await expect(
    page.getByRole("button", { name: "配置自己的 API", exact: true }),
  ).toBeVisible();
  await screenshot(page, "ai-unconfigured-desktop");
  await page.setViewportSize({ width: 390, height: 844 });
  await screenshot(page, "ai-unconfigured-mobile");
  await page.getByRole("button", { name: "关闭阅读助手", exact: true }).click();
  await expect(
    page.getByRole("button", { name: "AI 阅读助手", exact: true }),
  ).toBeFocused();
});
