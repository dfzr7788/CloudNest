import { test, expect, type Page } from "@playwright/test";

test("administrator gets creation requirements and newly created team opens", async ({
  page,
}) => {
  await setup(page);
  await page.route("**/api/user/me", (route) =>
    route.fulfill({
      json: {
        id: "owner",
        rootId: "personal",
        nickname: "Owner",
        administrator: true,
        quota: 1024 ** 3,
        used: 0,
        reserved: 0,
      },
    }),
  );
  const teams = [
    {
      id: "team",
      name: "Demo",
      root_id: "root",
      file_role: "OWNER",
      storage_used: 0,
      storage_quota: 1024,
    },
  ];
  let submitted: any;
  await page.route("**/api/teams", async (route) => {
    if (route.request().method() === "POST") {
      submitted = route.request().postDataJSON();
      teams.push({
        id: "new",
        name: submitted.name,
        root_id: "new-root",
        file_role: "OWNER",
        storage_used: 0,
        storage_quota: submitted.quota,
      });
      await route.fulfill({ json: { id: "new", rootId: "new-root" } });
    } else await route.fulfill({ json: teams });
  });
  await page.reload();
  const create = page.getByRole("button", { name: "Create team", exact: true });
  await expect(create).toBeDisabled();
  await expect(page.getByRole("status")).toContainText("Enter a team name");
  await page.getByPlaceholder("Team name", { exact: true }).fill("   ");
  await expect(create).toBeDisabled();
  await page
    .getByPlaceholder("Team name", { exact: true })
    .fill("  New team  ");
  const quota = page.locator(".next-panel").first().getByRole("spinbutton");
  await quota.fill("2");
  await expect(create).toBeDisabled();
  await expect(page.getByRole("status")).toContainText(
    "Insufficient personal quota",
  );
  await quota.fill("0.5");
  await expect(create).toBeEnabled();
  await create.click();
  await expect(
    page.getByRole("heading", { name: "New team", exact: true }),
  ).toBeVisible();
  expect(submitted).toEqual({ name: "New team", quota: 1024 ** 3 / 2 });
});

async function setup(page: Page, role = "OWNER") {
  await page.addInitScript(() =>
    localStorage.setItem("cloudnest-locale", "en-US"),
  );
  const invitations: any[] = [
    {
      id: "used",
      file_role: "VIEWER",
      contributor: false,
      expires_at: Date.now() + 86400000,
      consumed_by: "reader",
      revoked: false,
    },
    {
      id: "expired",
      file_role: "VIEWER",
      contributor: false,
      expires_at: 1,
      consumed_by: null,
      revoked: false,
    },
  ];
  let permissions = [{ user_id: "reader", permission: "DENY" }];
  const mutations: { path: string; body: any }[] = [];
  await page.route(
    (url) => url.pathname.startsWith("/api/"),
    async (route) => {
      const request = route.request(),
        path = new URL(request.url()).pathname;
      let body: any = [];
      if (path === "/api/user/me")
        body = { id: "owner", rootId: "personal", nickname: "Owner" };
      else if (path === "/api/auth/csrf") body = { token: "fixture" };
      else if (path === "/api/teams")
        body = [
          {
            id: "team",
            name: "Demo",
            root_id: "root",
            file_role: role,
            storage_used: 0,
            storage_quota: 1024,
          },
        ];
      else if (path.endsWith("/files"))
        body = {
          items: [{ id: "folder", name: "Documents", kind: "FOLDER" }],
          parentId: "root",
          breadcrumbs: [],
          total: 1,
        };
      else if (path.endsWith("/members"))
        body = [
          {
            user_id: "owner",
            nickname: "Owner",
            file_role: "OWNER",
            contributor: false,
          },
          {
            user_id: "admin",
            nickname: "Administrator",
            file_role: "ADMIN",
            contributor: false,
          },
          {
            user_id: "reader",
            nickname: "Reader",
            file_role: "VIEWER",
            contributor: false,
          },
        ];
      else if (path.endsWith("/members/reader")) {
        mutations.push({ path, body: request.postDataJSON() });
        await route.fulfill({
          status: 409,
          json: { message: "Role update rejected" },
        });
        return;
      } else if (path.endsWith("/invitations")) {
        if (request.method() === "POST") {
          const data = request.postDataJSON();
          mutations.push({ path, body: data });
          invitations.unshift({
            id: "new",
            file_role: data.role,
            contributor: data.contributor,
            expires_at: Date.now() + data.hours * 3600000,
            consumed_by: null,
            revoked: false,
          });
          body = {
            id: "new",
            token: "one-time-token",
            expiresAt: invitations[0].expires_at,
          };
        } else body = invitations;
      } else if (
        path.endsWith("/invitations/new") &&
        request.method() === "DELETE"
      ) {
        mutations.push({ path, body: null });
        invitations[0].revoked = true;
      } else if (path.endsWith("/entries/folder/acl")) {
        if (request.method() === "PUT") {
          const data = request.postDataJSON();
          mutations.push({ path, body: data });
          permissions =
            data.permission === "INHERIT"
              ? []
              : [{ user_id: data.userId, permission: data.permission }];
        }
        body = permissions;
      }
      await route.fulfill({ json: body });
    },
  );
  await page.goto("/teams");
  await page
    .getByRole("button", {
      name: `Demo · ${role === "OWNER" ? "Owner" : role === "ADMIN" ? "Admin" : "Viewer"}`,
      exact: true,
    })
    .click();
  return mutations;
}

test("invitation expiry, status history and confirmed revocation", async ({
  page,
}) => {
  const mutations = await setup(page);
  await page.setViewportSize({ width: 390, height: 844 });
  await page.getByRole("tab", { name: "Members and access" }).click();
  const section = page.locator(".team-invitations");
  await expect(section.getByText("Used", { exact: true })).toBeVisible();
  await expect(section.getByText("Expired", { exact: true })).toBeVisible();
  await section.getByRole("spinbutton").fill("48");
  await section.getByRole("button", { name: "Create invitation" }).click();
  await expect(
    section.getByText("one-time-token", { exact: true }),
  ).toBeVisible();
  expect(mutations[0].body).toEqual({
    role: "VIEWER",
    contributor: false,
    hours: 48,
  });
  const revoke = section.getByRole("button", {
    name: "Revoke invitation",
    exact: true,
  });
  await expect(revoke).toHaveCount(1);
  await revoke.click();
  await page
    .getByRole("dialog")
    .getByRole("button", { name: "Cancel", exact: true })
    .click();
  expect(mutations).toHaveLength(1);
  await revoke.click();
  await page
    .getByRole("dialog")
    .getByRole("button", { name: "OK", exact: true })
    .click();
  await expect(section.getByText("Revoked", { exact: true })).toBeVisible();
  await expect(
    section.getByText("one-time-token", { exact: true }),
  ).toHaveCount(0);
  expect(mutations[1].path).toBe("/api/teams/team/invitations/new");
  expect(
    await page.evaluate(
      () => document.documentElement.scrollWidth <= innerWidth,
    ),
  ).toBeTruthy();
});

test("existing ACL is loaded and can be removed by restoring inheritance", async ({
  page,
}) => {
  const mutations = await setup(page);
  await page
    .getByRole("button", { name: "Folder access", exact: true })
    .click();
  const form = page.locator(".team-acl-form");
  await expect(form.getByRole("textbox").first()).toHaveValue("folder");
  await form.locator(".el-select").first().click();
  await page.getByRole("option", { name: "Reader", exact: true }).click();
  await expect(form.locator(".el-select").nth(1)).toContainText("Deny");
  await form.locator(".el-select").nth(1).click();
  await page.getByRole("option", { name: "Inherit role", exact: true }).click();
  await form.getByRole("button", { name: "Save", exact: true }).click();
  await expect(
    page.getByText(
      "No explicit settings. Member roles and ancestor restrictions apply.",
      { exact: true },
    ),
  ).toBeVisible();
  expect(mutations[0].body).toEqual({
    userId: "reader",
    permission: "INHERIT",
  });
});

test("admin controls respect owner-only roles and rejected updates preserve state", async ({
  page,
}) => {
  await setup(page, "ADMIN");
  await page.getByRole("tab", { name: "Members and access" }).click();
  const adminRow = page
    .locator(".team-members .el-table__row")
    .filter({ hasText: "Administrator" });
  await expect(adminRow.locator(".el-select")).toHaveCount(0);
  await expect(adminRow.getByRole("switch")).toBeDisabled();
  await expect(
    adminRow.getByRole("button", { name: "Remove member" }),
  ).toHaveCount(0);
  const reader = page
    .locator(".team-members .el-table__row")
    .filter({ hasText: "Reader" });
  await reader.locator(".el-select").click();
  await expect(
    page.getByRole("option", { name: "Admin", exact: true }),
  ).toHaveCount(0);
  await page.getByRole("option", { name: "Editor", exact: true }).click();
  await expect(
    page.getByText("Role update rejected", { exact: true }),
  ).toBeVisible();
  await expect(reader.locator(".el-select")).toContainText("Viewer");
  await reader.locator(".el-switch").click();
  await expect(reader.getByRole("switch")).not.toBeChecked();
});

test("viewers cannot access invitation or ACL management", async ({ page }) => {
  await setup(page, "VIEWER");
  await page.getByRole("tab", { name: "Members and access" }).click();
  await expect(page.locator(".team-invitations")).toHaveCount(0);
  await expect(page.locator(".team-acl-form")).toHaveCount(0);
  await expect(page.locator(".team-members .el-select")).toHaveCount(0);
});
