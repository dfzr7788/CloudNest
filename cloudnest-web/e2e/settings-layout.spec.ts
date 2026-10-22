import { test, expect } from "@playwright/test";

for (const width of [390, 1024, 1440]) {
  test(`English settings remain readable and AI toggle works at ${width}px`, async ({
    page,
  }, info) => {
    await page.setViewportSize({ width, height: 900 });
    await page.addInitScript(() =>
      localStorage.setItem("cloudnest-locale", "en-US"),
    );
    let enabled = false;
    const features: Record<string, string> = {
      sync: "false",
      agentTools: "false",
      agentRuntime: "false",
      agentApprovals: "false",
    };
    let rejectFeature = false;
    let administrator = true;
    const mutations: boolean[] = [];
    const errors: string[] = [];
    page.on("pageerror", (error) => errors.push(error.message));
    await page.route(
      (url) => url.pathname.startsWith("/api/"),
      async (route) => {
        const path = new URL(route.request().url()).pathname;
        if (path === "/api/admin/config/features") {
          if (route.request().method() === "PUT") {
            if (rejectFeature) {
              await route.fulfill({
                status: 409,
                json: { message: "Feature update rejected" },
              });
              return;
            }
            Object.assign(features, route.request().postDataJSON());
          }
          await route.fulfill({ json: features });
          return;
        }
        if (path === "/api/ai/site-settings") {
          enabled = route.request().postDataJSON().enabled;
          mutations.push(enabled);
        }
        const body: unknown =
          path === "/api/auth/csrf"
            ? { token: "fixture" }
            : path === "/api/user/me"
              ? {
                  id: "fixture",
                  nickname: "Admin",
                  username: "admin",
                  rootId: "root",
                  quota: 1024 ** 3,
                  used: 0,
                  reserved: 0,
                  administrator,
                }
              : path === "/api/entries"
                ? {
                    items: [],
                    total: 0,
                    page: 1,
                    pageSize: 30,
                    parentId: "root",
                  }
                : path.endsWith("/breadcrumbs")
                  ? [{ id: "root", name: "Root" }]
                  : path === "/api/stats"
                    ? { files: 0, folders: 0, images: 0, documents: 0 }
                    : path === "/api/user/storage-breakdown"
                      ? { live: 0, trash: 0, versions: 0, reserved: 0 }
                      : path === "/api/user/trash-policy"
                        ? { days: 0 }
                        : path === "/api/admin/status"
                          ? { freeBytes: 1024 ** 3 }
                          : [
                                "/api/ai/capabilities",
                                "/api/ai/site-settings",
                              ].includes(path)
                            ? { enabled }
                            : path === "/api/ai/preferences"
                              ? { default_chat_model_id: null }
                              : path === "/api/ai/usage"
                                ? { items: [], total: 0 }
                                : path.startsWith("/api/admin/config/")
                                  ? {}
                                  : [];
        await route.fulfill({ json: body });
      },
    );
    await page.goto("/");
    await page
      .getByRole("button", { name: "Account and security", exact: true })
      .click();
    const dialog = page.getByRole("dialog", {
      name: "Account and security",
      exact: true,
    });
    async function select(name: string) {
      if (width < 768) {
        await dialog
          .locator(".settings-mobile-nav .el-select__wrapper")
          .click();
        await page.getByRole("option", { name, exact: true }).click();
      } else await dialog.getByRole("tab", { name, exact: true }).click();
    }
    await select("AI settings");
    await dialog
      .getByRole("button", { name: "Enable site AI", exact: true })
      .click();
    await expect(
      dialog.getByRole("button", { name: "Disable site AI", exact: true }),
    ).toBeEnabled();
    await page.reload();
    await page
      .getByRole("button", { name: "Account and security", exact: true })
      .click();
    await select("AI settings");
    await dialog
      .getByRole("button", { name: "Disable site AI", exact: true })
      .click();
    await expect(
      dialog.getByRole("button", { name: "Enable site AI", exact: true }),
    ).toBeEnabled();
    expect(mutations).toEqual([true, false]);
    await select("Integrations");
    const sync = dialog.getByRole("switch", {
      name: "Sync devices",
      exact: true,
    });
    await expect(sync).not.toBeChecked();
    await dialog.locator(".feature-settings .el-switch").first().click();
    await expect(sync).toBeChecked();
    await page.reload();
    await page
      .getByRole("button", { name: "Account and security", exact: true })
      .click();
    await select("Integrations");
    await expect(sync).toBeChecked();
    rejectFeature = true;
    await dialog.locator(".feature-settings .el-switch").first().click();
    await expect(
      dialog.getByText("Feature update rejected", { exact: true }),
    ).toBeVisible();
    await expect(sync).toBeChecked();
    rejectFeature = false;
    await dialog.locator(".feature-settings .el-switch").first().click();
    await expect(sync).not.toBeChecked();
    await expect(
      dialog.getByText("Email and account recovery", { exact: true }),
    ).toBeVisible();
    await expect(
      dialog.getByText("Storage policies", { exact: true }),
    ).toHaveCount(0);
    await expect(
      dialog.getByRole("tab", { name: "Services and storage", exact: true }),
    ).toHaveCount(0);
    await expect(
      dialog.getByRole("tab", { name: "Sync devices", exact: true }),
    ).toHaveCount(0);
    const clipped = await dialog
      .locator(
        ".el-tabs__item:visible, .el-form-item__label:visible, .el-button:visible",
      )
      .evaluateAll((nodes) =>
        nodes
          .filter(
            (el) =>
              el.scrollWidth > el.clientWidth + 2 ||
              el.scrollHeight > el.clientHeight + 2,
          )
          .map((el) => el.textContent),
      );
    expect(clipped).toEqual([]);
    expect(
      await dialog.evaluate((el) => el.scrollWidth <= el.clientWidth + 2),
    ).toBeTruthy();
    await page.screenshot({
      path: info.outputPath(`settings-en-${width}.png`),
    });
    administrator = false;
    await page.reload();
    await page
      .getByRole("button", { name: "Account and security", exact: true })
      .click();
    await select("AI settings");
    await expect(
      dialog.getByRole("button", { name: "Enable site AI", exact: true }),
    ).toHaveCount(0);
    expect(errors).toEqual([]);
  });
}
