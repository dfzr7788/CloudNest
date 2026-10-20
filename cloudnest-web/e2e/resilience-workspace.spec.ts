import { test, expect } from "@playwright/test";
import { createHash } from "node:crypto";

test("NEXT01 real folder snapshot, team invitation, editor upload and access revocation", async ({
  page,
  browser,
}) => {
  test.skip(
    !process.env.CN_NEXT_FIXTURE,
    "Requires an isolated Horizon Next server",
  );
  test.setTimeout(120000);
  page.setDefaultTimeout(15000);
  await page.addInitScript(() =>
    localStorage.setItem("cloudnest-locale", "zh-CN"),
  );
  await page.goto("/login");
  await page.getByLabel("账号", { exact: true }).fill("horizonnext");
  await page
    .getByLabel("密码", { exact: true })
    .fill("Next-Isolated-Fixture-123");
  await page.getByRole("button", { name: "登录云盘", exact: true }).click();
  await expect(
    page.getByRole("heading", { name: "我的云盘", exact: true }),
  ).toBeVisible();
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
  const folder = await call("POST", "/folders", {
    parentId: me.rootId,
    name: "Snapshot source " + Date.now(),
  });
  const body = Buffer.from("historical browser content");
  const upload = await call("POST", "/uploads/init", {
    folderId: folder.id,
    name: "history.txt",
    size: body.length,
    sha256: createHash("sha256").update(body).digest("hex"),
  });
  const csrf = (await (await page.request.get("/api/auth/csrf")).json()).token;
  if (upload.status !== "COMPLETE") {
    const part = await page.request.put(`/api/uploads/${upload.id}/parts/0`, {
      data: body,
      headers: {
        "X-XSRF-TOKEN": csrf,
        "Content-Type": "application/octet-stream",
      },
    });
    expect(part.ok(), await part.text()).toBeTruthy();
    await call("POST", `/uploads/${upload.id}/complete`);
  }
  await page.goto("/resilience?folder=" + folder.id);
  await expect(
    page.getByRole("heading", { name: "数据保护", exact: true }),
  ).toBeVisible();
  await page.getByRole("button", { name: "创建快照", exact: true }).click();
  await expect(
    page.getByRole("row").filter({ hasText: folder.name }),
  ).toBeVisible();
  await call("DELETE", `/folders/${folder.id}`);
  await page.goto("/resilience");
  await page.getByText("显示所有历史目录", { exact: true }).click();
  const row = page.getByRole("row").filter({ hasText: folder.name });
  await row.getByRole("button", { name: "浏览与恢复" }).click();
  const dialog = page.getByRole("dialog");
  await expect(
    dialog.getByText("history.txt", { exact: false }).first(),
  ).toBeVisible();
  await dialog.getByRole("button", { name: "生成恢复预览" }).click();
  await expect(dialog.getByText(/将创建 2 项/)).toBeVisible();
  await dialog.getByRole("button", { name: "确认创建恢复副本" }).click();
  await expect(
    page
      .locator(".next-panel")
      .first()
      .getByText("完成", { exact: true })
      .first(),
  ).toBeVisible({ timeout: 30000 });
  await page.goto("/teams");
  const teamName = "Browser team " + Date.now();
  await page.getByPlaceholder("团队名称", { exact: true }).fill(teamName);
  await page
    .locator(".next-panel")
    .first()
    .locator(".el-input-number input")
    .fill("0.01");
  await page.getByRole("button", { name: "创建团队", exact: true }).click();
  await page.getByRole("button", { name: new RegExp(teamName) }).click();
  await page.getByRole("button", { name: "新建文件夹", exact: true }).click();
  await page.getByRole("dialog").getByRole("textbox").fill("Team docs");
  await page
    .getByRole("dialog")
    .getByRole("button", { name: /确定|Confirm/, exact: true })
    .click();
  await expect(
    page.getByRole("button", { name: "📁 Team docs", exact: true }),
  ).toBeVisible();
  const team = (await call("GET", "/teams")).find(
    (w: any) => w.name === teamName,
  );
  const invite = await call("POST", `/teams/${team.id}/invitations`, {
    role: "EDITOR",
    contributor: false,
    hours: 1,
  });
  const second = await browser.newContext({
    baseURL: process.env.CN_E2E_BASE_URL || "http://127.0.0.1:5173",
  });
  try {
    const p = await second.newPage();
    await p.addInitScript(() =>
      localStorage.setItem("cloudnest-locale", "zh-CN"),
    );
    await p.goto("/login");
    const registerCsrf = (await (await p.request.get("/api/auth/csrf")).json())
      .token;
    const username = "next" + Date.now();
    const registered = await p.request.post("/api/auth/register", {
      data: { username, password: "Next-Member-Fixture-123" },
      headers: { "X-XSRF-TOKEN": registerCsrf },
    });
    expect(registered.ok(), await registered.text()).toBeTruthy();
    const member = await registered.json();
    await p.goto("/teams");
    await p.getByPlaceholder("输入邀请令牌").fill(invite.token);
    await p.getByRole("button", { name: "加入团队", exact: true }).click();
    await p.getByRole("button", { name: new RegExp(teamName) }).click();
    await p
      .locator('input[type="file"]')
      .setInputFiles({
        name: "member.txt",
        mimeType: "text/plain",
        buffer: Buffer.from("team member bytes"),
      });
    await expect(
      p.getByRole("cell", { name: "member.txt", exact: true }),
    ).toBeVisible({ timeout: 30000 });
    await call("PUT", `/teams/${team.id}/members/${member.id}`, {
      role: "VIEWER",
      contributor: true,
    });
    await p.reload();
    await p.getByRole("button", { name: new RegExp(teamName) }).click();
    await expect(p.locator('input[type="file"]')).toHaveCount(0);
    await expect(
      p.getByRole("button", { name: "下载", exact: true }).first(),
    ).toBeVisible();
    await p.getByRole("tab", { name: "存储贡献", exact: true }).click();
    await expect(
      p.getByRole("button", { name: "登记贡献节点", exact: true }),
    ).toBeVisible();
    await p.setViewportSize({ width: 390, height: 844 });
    expect(
      await p.evaluate(
        () => document.documentElement.scrollWidth <= innerWidth,
      ),
    ).toBeTruthy();
  } finally {
    await second.close();
  }
  await page.screenshot({
    path: "../.tools/next-browser-desktop.png",
    fullPage: true,
  });
});
