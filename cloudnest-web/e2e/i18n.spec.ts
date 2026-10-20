import { test, expect } from "@playwright/test";

// These tests exercise locale state and layout against deterministic HTTP fixtures.
// The real upload/storage end-to-end suite remains a separate release gate.
for (const width of [390, 1440]) {
  test(`locale switching preserves login and drive state at ${width}px`, async ({
    page,
  }, testInfo) => {
    await page.setViewportSize({ width, height: 900 });
    await page.addInitScript(() => {
      if (!localStorage.getItem("cloudnest-locale"))
        localStorage.setItem("cloudnest-locale", "zh-CN");
    });
    const user = {
      id: "fixture",
      username: "localeuser",
      nickname: "Locale",
      rootId: "root",
      quota: 1024 ** 3,
      used: 0,
      reserved: 0,
      administrator: true,
    };
    const file = {
      id: "example",
      parentId: "root",
      name: "中文 file.txt",
      kind: "FILE",
      size: 1234,
      createdAt: 1700000000000,
      updatedAt: 1700000000000,
      deletedAt: null,
      mime: "text/plain",
    };
    await page.route((url) => url.pathname.startsWith("/api/"), async (route) => {
      const path = new URL(route.request().url()).pathname;
      const body =
        path === "/api/auth/csrf"
          ? { token: "fixture-csrf" }
          : ["/api/auth/login", "/api/user/me"].includes(path)
            ? user
            : path === "/api/entries"
              ? {
                  items: [file],
                  total: 1,
                  page: 1,
                  pageSize: 30,
                  parentId: "root",
                }
              : path.endsWith("/breadcrumbs")
                ? [{ id: "root", name: "Root" }]
                : path === "/api/stats"
                  ? { files: 1, folders: 0, images: 0, documents: 1 }
                  : path === "/api/admin/horizon"
                    ? {
                        placement: { mode: "MANUAL", min_reliability: 0 },
                        decisions: [],
                        jobs: [],
                        alerts: [],
                        protection: [],
                        events: [],
                      }
                    : path === "/api/admin/nodes"
                      ? { nodes: [], unknownBackends: [] }
                      : path === "/api/admin/storage"
                        ? { policies: [], defaultBackend: "local" }
                        : [];
      await route.fulfill({ json: body });
    });
    const runtimeErrors: string[] = [];
    page.on("pageerror", (error) => runtimeErrors.push(error.message));
    await page.goto("/login");
    await page.getByLabel("账号", { exact: true }).fill("localeuser");
    await page.getByLabel("密码", { exact: true }).fill("Fixture-Password-123");
    await page
      .getByRole("combobox", { name: "Language / 语言" })
      .selectOption("en-US");
    await expect(page.getByLabel("Account", { exact: true })).toHaveValue(
      "localeuser",
    );
    await expect(page.getByLabel("Password", { exact: true })).toHaveValue(
      "Fixture-Password-123",
    );
    await expect(page.locator("html")).toHaveAttribute("lang", "en-US");
    await page.getByRole("button", { name: "Sign in", exact: true }).click();
    await expect(
      page.getByRole("heading", { name: "My drive", exact: true }),
    ).toBeVisible();
    await expect(
      page.getByRole("button", { name: "中文 file.txt", exact: true }),
    ).toBeVisible();
    await page
      .getByRole("row")
      .filter({
        has: page.getByRole("checkbox", {
          name: "Select 中文 file.txt",
          exact: true,
        }),
      })
      .locator(".el-checkbox__inner")
      .click();
    await expect(
      page.getByRole("checkbox", { name: "Select 中文 file.txt", exact: true }),
    ).toBeChecked();
    const language = page.getByRole("combobox", { name: "Language / 语言" });
    await language.selectOption("zh-CN");
    await expect(
      page.getByRole("heading", { name: "我的云盘", exact: true }),
    ).toBeVisible();
    await expect(
      page.getByRole("checkbox", { name: "选择 中文 file.txt", exact: true }),
    ).toBeChecked();
    await language.selectOption("en-US");
    await page.reload();
    await expect(
      page.getByRole("heading", { name: "My drive", exact: true }),
    ).toBeVisible();
    await expect(page.locator("html")).toHaveAttribute("lang", "en-US");
    await page.screenshot({
      path: testInfo.outputPath(`drive-en-${width}.png`),
      fullPage: true,
    });
    await expect
      .poll(() =>
        page.evaluate(
          () => document.documentElement.scrollWidth <= window.innerWidth,
        ),
      )
      .toBeTruthy();
    await page.goto("/admin/nodes");
    await expect(
      page.getByRole("heading", { name: "Node center", exact: true }),
    ).toBeVisible();
    await page
      .getByRole("tab", { name: "Placement policy", exact: true })
      .click();
    await expect(
      page.getByRole("tabpanel", { name: "Placement policy", exact: true }).getByText("Manual (global default node)", { exact: true }),
    ).toBeVisible();
    await page
      .getByRole("combobox", { name: "Language / 语言" })
      .selectOption("zh-CN");
    await expect(
      page.getByRole("tab", { name: "调度策略", exact: true }),
    ).toHaveAttribute("aria-selected", "true");
    await expect(
      page.getByRole("tabpanel", { name: "调度策略", exact: true }).getByText("手动选择（全局默认节点）", { exact: true }),
    ).toBeVisible();
    expect(runtimeErrors).toEqual([]);
  });
}
