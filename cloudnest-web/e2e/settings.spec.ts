import { test, expect } from "@playwright/test";

test("SETUI01 管理面板分区、邀请复制及一键更新任务", async ({ page }) => {
  let phase = "READY",
    available = true;
  const actions: string[] = [];
  await page.route("**/api/user/me", async (route) => {
    const response = await route.fetch();
    const data = await response.json();
    await route.fulfill({ response, json: { ...data, administrator: true } });
  });
  await page.route("**/api/admin/users?*", (route) =>
    route.fulfill({
      json: [
        {
          id: "fixture",
          username: "owner",
          storage_quota: 5368709120,
          storage_used: 1048576,
          enabled: true,
        },
      ],
    }),
  );
  await page.route("**/api/admin/status", (route) =>
    route.fulfill({
      json: { freeBytes: 9556302233, pendingUploads: 1, pendingDeletes: 0 },
    }),
  );
  await page.route("**/api/admin/invitations", (route) =>
    route.fulfill({
      json: { token: "test-invitation", expiresAt: Date.now() + 172800000 },
    }),
  );
  await page.route("**/api/admin/updates", async (route) => {
    if (route.request().method() === "POST") {
      const action = route.request().postDataJSON().action;
      actions.push(action);
      phase = action === "update" ? "BUILDING" : "READY";
      await route.fulfill({ json: { accepted: true } });
    } else
      await route.fulfill({
        json: {
          enabled: true,
          busy: phase === "BUILDING",
          phase,
          current: "aaaaaaaa",
          latest: "bbbbbbbb",
          available,
          message:
            phase === "COMPLETE"
              ? "更新完成，服务健康检查通过，请刷新页面"
              : "发现新版本，可以更新",
        },
      });
  });
  await page.goto("/login");
  await page.getByRole("button", { name: "创建账号", exact: true }).click();
  await page.getByLabel("账号", { exact: true }).fill("settings_" + Date.now());
  await page.getByLabel("密码", { exact: true }).fill("Settings-Test-123");
  await page.getByRole("button", { name: "注册并进入云盘" }).click();
  await page.getByRole("button", { name: "账号与安全", exact: true }).click();
  await page.getByRole("tab", { name: "系统管理", exact: true }).click();
  await expect(page.getByRole("heading", { name: "用户管理" })).toBeVisible();
  await page.getByRole("button", { name: "邀请新用户", exact: true }).click();
  await page.getByRole("button", { name: "生成", exact: true }).click();
  await expect(page.getByLabel("邀请注册链接")).toHaveValue(/test-invitation$/);
  await page.evaluate(() => {
    Object.defineProperty(navigator, "clipboard", {
      value: undefined,
      configurable: true,
    });
    document.execCommand = () => true;
  });
  await page.getByRole("button", { name: "复制链接", exact: true }).click();
  await expect(page.getByText("邀请链接已复制", { exact: true })).toBeVisible();
  await page.screenshot({
    path: "../docs/evidence/settings-admin-desktop.png",
    fullPage: true,
  });
  await page.getByRole("tab", { name: "系统更新", exact: true }).click();
  await expect(
    page.getByRole("button", { name: "一键更新", exact: true }),
  ).toBeEnabled();
  await page.getByRole("button", { name: "检查更新", exact: true }).click();
  await expect.poll(() => actions.includes("check")).toBeTruthy();
  await expect(
    page.getByRole("button", { name: "一键更新", exact: true }),
  ).toBeEnabled({ timeout: 10000 });
  await page.screenshot({
    path: "../docs/evidence/settings-update-desktop.png",
    fullPage: true,
  });
  await page.getByRole("button", { name: "一键更新", exact: true }).click();
  await page.getByRole("button", { name: "备份并更新", exact: true }).click();
  await expect.poll(() => actions.includes("update")).toBeTruthy();
  await expect(page.getByText("构建新版", { exact: true })).toBeVisible({
    timeout: 10000,
  });
  await expect(
    page.getByRole("button", { name: "一键更新", exact: true }),
  ).toBeDisabled();
  phase = "COMPLETE";
  available = false;
  await expect(page.getByText("更新完成", { exact: true })).toBeVisible({
    timeout: 10000,
  });
  await page.setViewportSize({ width: 390, height: 844 });
  await page.screenshot({
    path: "../docs/evidence/settings-update-mobile.png",
    fullPage: true,
  });
  expect(
    await page.evaluate(
      () => document.documentElement.scrollWidth <= innerWidth,
    ),
  ).toBeTruthy();
});
