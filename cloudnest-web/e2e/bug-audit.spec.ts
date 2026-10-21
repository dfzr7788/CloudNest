import { test, expect, type Page } from "@playwright/test";

const longName = "Project_".repeat(35);

test("switching teams clears the previous permission target", async ({
  page,
}) => {
  await fixture(page);
  await page.route("**/api/teams", (route) =>
    route.fulfill({
      json: ["Alpha", "Beta"].map((name) => ({
        id: name,
        name,
        root_id: "root",
        file_role: "OWNER",
        storage_used: 0,
        storage_quota: 1024,
      })),
    }),
  );
  await page.route("**/api/teams/*/files?*", (route) =>
    route.fulfill({
      json: {
        items: [],
        parentId: "root",
        breadcrumbs: [],
        total: 0,
      },
    }),
  );
  await page.goto("/teams");
  await page
    .getByRole("button", { name: "Alpha · Owner", exact: true })
    .click();
  await page.getByRole("tab", { name: "Members and access", exact: true }).click();
  const entry = page.locator(".next-form .el-input input").first();
  await entry.fill("alpha-file-id");
  await page.getByRole("button", { name: "Beta · Owner", exact: true }).click();
  await page.getByRole("tab", { name: "Members and access", exact: true }).click();
  await expect(entry).toHaveValue("");
});

async function fixture(page: Page) {
  await page.addInitScript(() => {
    localStorage.setItem("cloudnest-locale", "en-US");
    Object.defineProperty(crypto, "randomUUID", { value: undefined });
  });
  await page.route(
    (url) => url.pathname.startsWith("/api/"),
    async (route) => {
      const path = new URL(route.request().url()).pathname;
      const body =
        path === "/api/user/me"
          ? {
              id: "user",
              rootId: "root",
              nickname: "Admin",
              administrator: true,
            }
          : path === "/api/auth/csrf"
            ? { token: "fixture" }
            : path === "/api/teams"
              ? [
                  {
                    id: "team",
                    root_id: "root",
                    name: longName,
                    file_role: "OWNER",
                    storage_used: 0,
                    storage_quota: 1024,
                  },
                ]
              : path === "/api/teams/team/files"
                ? {
                    items: [],
                    parentId: "root",
                    breadcrumbs: [{ id: "root", name: longName }],
                    total: 0,
                  }
                : path.endsWith("/breadcrumbs")
                  ? [{ id: "root", name: longName }]
                  : path === "/api/folders"
                    ? [
                        {
                          id: "bad",
                          name: "Unavailable folder",
                          kind: "FOLDER",
                        },
                      ]
                    : path === "/api/resilience/tiering"
                      ? { suggestions: [] }
                      : path === "/api/resilience/integrity-policy"
                        ? {
                            enabled: false,
                            interval_hours: 24,
                            bytes_per_second: 8388608,
                          }
                        : path === "/api/resilience/integrity"
                          ? { id: "scan" }
                          : [];
      await route.fulfill({ json: body });
    },
  );
}

for (const width of [320, 390, 768, 1440]) {
  test(`workspace controls remain inside panels at ${width}px`, async ({
    page,
  }, info) => {
    await fixture(page);
    await page.setViewportSize({ width, height: 800 });
    for (const path of ["/teams", "/resilience"]) {
      await page.goto(path);
      if (path === "/teams")
        await page
          .getByRole("button", { name: new RegExp("Project_") })
          .click();
      await expect(page.locator(".next-panel").first()).toBeVisible();
      const overflow = await page.locator(".next-panel").evaluateAll((panels) =>
        panels.flatMap((panel) => {
          const bounds = panel.getBoundingClientRect();
          return Array.from(
            panel.querySelectorAll(
              ".next-toolbar > *, .next-form .el-form-item, .next-form .el-form-item__content > *",
            ),
          )
            .filter((el) => {
              const box = el.getBoundingClientRect();
              return (
                box.width > 0 &&
                (box.right > bounds.right + 1 || box.left < bounds.left - 1)
              );
            })
            .map((el) => el.className);
        }),
      );
      expect(overflow).toEqual([]);
      expect(
        await page.evaluate(
          () => document.documentElement.scrollWidth <= innerWidth,
        ),
      ).toBeTruthy();
      await page.screenshot({
        path: info.outputPath(`${path.slice(1)}-${width}.png`),
        fullPage: true,
      });
    }
  });
}

test("integrity scan works without secure-context randomUUID", async ({
  page,
}) => {
  await fixture(page);
  await page.goto("/resilience");
  const request = page.waitForRequest(
    (r) =>
      r.url().endsWith("/api/resilience/integrity") && r.method() === "POST",
  );
  await page
    .getByRole("button", { name: "Scan all objects", exact: true })
    .click();
  expect((await request).postDataJSON().requestKey).toMatch(
    /^[a-f0-9]{8}-[a-f0-9]{4}-4[a-f0-9]{3}-[89ab][a-f0-9]{3}-[a-f0-9]{12}$/,
  );
});

test("folder picker cannot choose a failed destination", async ({ page }) => {
  await fixture(page);
  await page.route("**/api/folders?parentId=bad", (route) =>
    route.fulfill({ status: 403, json: { message: "Denied" } }),
  );
  await page.goto("/resilience");
  await page
    .getByRole("button", { name: "Choose folder", exact: true })
    .click();
  const dialog = page.getByRole("dialog");
  const choose = dialog.getByRole("button", {
    name: "Use this folder",
    exact: true,
  });
  await expect(choose).toBeEnabled();
  await dialog.getByRole("button", { name: "Unavailable folder" }).click();
  await expect(page.getByText("Denied", { exact: true })).toBeVisible();
  await expect(choose).toBeDisabled();
});

test("reopening folder picker ignores an older failed request", async ({
  page,
}) => {
  await fixture(page);
  let release!: () => void;
  const held = new Promise<void>((resolve) => {
    release = resolve;
  });
  let started!: () => void;
  const requested = new Promise<void>((resolve) => {
    started = resolve;
  });
  await page.route("**/api/folders?parentId=bad", async (route) => {
    started();
    await held;
    await route.fulfill({ status: 403, json: { message: "Stale failure" } });
  });
  await page.goto("/resilience");
  const open = page.getByRole("button", { name: "Choose folder", exact: true });
  await open.click();
  const dialog = page.getByRole("dialog");
  await dialog.getByRole("button", { name: "Unavailable folder" }).click();
  await requested;
  await dialog.locator(".el-dialog__headerbtn").click();
  await open.click();
  await expect(
    dialog.getByRole("button", { name: "Use this folder", exact: true }),
  ).toBeEnabled();
  const response = page.waitForResponse((r) =>
    r.url().endsWith("/api/folders?parentId=bad"),
  );
  release();
  await response;
  await expect(
    dialog.getByRole("button", { name: "Unavailable folder" }),
  ).toBeVisible();
  await expect(page.getByText("Stale failure", { exact: true })).toHaveCount(0);
});
