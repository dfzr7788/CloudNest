import { test, expect } from "@playwright/test";

test("AI 配置保存不调用模型，预览显式生成、恢复、净化和标签合并", async ({
  page,
}) => {
  const errors: string[] = [];
  page.on("pageerror", (e) => errors.push(e.message));
  await page.goto("/login");
  await page.getByRole("button", { name: "创建账号", exact: true }).click();
  await page.getByLabel("账号", { exact: true }).fill("aiui_" + Date.now());
  await page.getByLabel("密码", { exact: true }).fill("Browser-test-123");
  await page.getByRole("button", { name: "注册并进入云盘" }).click();
  await page.getByRole("button", { name: "账号与安全", exact: true }).click();
  await page.getByRole("tab", { name: "AI 设置", exact: true }).click();
  await page
    .getByRole("button", { name: "添加 API 连接", exact: true })
    .click();
  const editor = page.getByRole("dialog", { name: "API 连接", exact: true });
  await editor
    .locator(".el-form-item")
    .filter({ hasText: "显示名称" })
    .locator("input")
    .fill("UI fixture");
  await editor
    .locator(".el-form-item")
    .filter({ hasText: "API 根地址" })
    .locator("input")
    .fill("https://model.example/v1");
  await editor.locator("input[type=password]").fill("ui-fake-key");
  await editor.getByRole("button", { name: "保存连接", exact: true }).click();
  await expect(editor).not.toBeVisible();
  await expect(
    page.locator(".connection-card").filter({ hasText: "UI fixture" }),
  ).toContainText("密钥已设置");
  await page
    .getByRole("button", { name: "查看并确认数据发送", exact: true })
    .click();
  await page.getByRole("button", { name: "同意此目的地", exact: true }).click();
  await page.getByRole("button", { name: "添加模型", exact: true }).click();
  const model = page.getByRole("dialog", { name: "聊天模型", exact: true });
  await model
    .locator(".el-form-item")
    .filter({ hasText: "显示名称" })
    .locator("input")
    .fill("UI model");
  await model
    .locator(".el-form-item")
    .filter({ hasText: "供应商模型 ID" })
    .locator("input")
    .fill("fixture");
  await model.getByRole("button", { name: "保存模型", exact: true }).click();
  await expect(model).not.toBeVisible();
  await expect(
    page.getByRole("button", { name: "测试聊天模型（可能收费）", exact: true }),
  ).toBeVisible();
  const usage = await page.request.get("/api/ai/usage");
  expect((await usage.json()).summary.calls).toBe(0);
  const storage = await page.evaluate(() =>
    JSON.stringify({
      local: { ...localStorage },
      session: { ...sessionStorage },
    }),
  );
  expect(storage).not.toContain("ui-fake-key");
  await page
    .getByRole("dialog", { name: "账号与安全", exact: true })
    .locator(".el-dialog__headerbtn")
    .click();
  const csrf = (await (await page.request.get("/api/auth/csrf")).json()).token;
  const rootId = (await (await page.request.get("/api/user/me")).json()).rootId;
  const uploaded = await page.request.post("/api/files/upload", {
    headers: { "X-XSRF-TOKEN": csrf },
    multipart: {
      folderId: rootId,
      file: {
        name: "AI资料.md",
        mimeType: "text/markdown",
        buffer: Buffer.from("# 主题\n这是测试资料，不包含私人信息。"),
      },
    },
  });
  expect(uploaded.ok(), await uploaded.text()).toBeTruthy();
  await page.getByRole("button", { name: "刷新", exact: true }).click();
  await expect(
    page.getByRole("button", { name: "AI资料.md", exact: true }),
  ).toBeVisible({ timeout: 30000 });
  await page.getByRole("button", { name: "AI资料.md", exact: true }).click();
  await page.getByRole("button", { name: "AI 阅读助手", exact: true }).click();
  await page
    .getByRole("button", { name: "检查文档范围（不调用模型）", exact: true })
    .click();
  await expect(page.getByText(/摘要预计 1 次调用/)).toBeVisible();
  let calls = 0;
  let task: Record<string, unknown> | null = null;
  let fileId = "";
  const coverage = {
    revision: 1,
    status: "COMPLETE",
    partial: false,
    reason: "",
    selectedCharacters: 36,
    sources: [{ sourceId: "S1", locator: "行 1–2" }],
  };
  await page.route("**/api/ai/files/*/summaries", async (route) => {
    calls++;
    fileId = route.request().url().split("/").at(-2)!;
    expect(route.request().headers()["idempotency-key"]).toBeTruthy();
    task = {
      taskId: "ui-task",
      id: "ui-task",
      kind: "SUMMARY",
      status: "RUNNING",
      stage: "GENERATING",
      model_name: "UI model",
      completed_calls: 0,
      total_calls: 1,
      created_at: Date.now(),
      result: null,
      coverage,
    };
    await route.fulfill({ status: 202, json: task });
  });
  await page.route("**/api/ai/tasks/ui-task", (route) =>
    route.fulfill({ json: task }),
  );
  await page.route("**/api/ai/tasks?*", async (route) => {
    if (task) await route.fulfill({ json: [task] });
    else await route.continue();
  });
  await page.getByRole("button", { name: "生成摘要", exact: true }).click();
  await page.getByRole("button", { name: "确认并开始", exact: true }).click();
  await expect(
    page.getByText("生成中 · UI model", { exact: false }),
  ).toBeVisible();
  task = {
    ...task!,
    status: "SUCCEEDED",
    completed_calls: 1,
    result: {
      text: '## 测试摘要\n事实要点。\n<img src="https://tracker.invalid/a" onerror="alert(1)"><script>alert(1)</script>',
    },
  };
  await expect(page.locator(".answer")).toContainText("事实要点。", {
    timeout: 10000,
  });
  await expect(page.locator(".answer img, .answer script")).toHaveCount(0);
  await page.screenshot({ path: test.info().outputPath("ai-desktop.png") });
  expect(calls).toBe(1);
  const drawer = page.getByRole("dialog", { name: "AI 阅读助手", exact: true });
  await drawer.locator(".el-drawer__close-btn").click();
  await page.getByRole("button", { name: "AI 阅读助手", exact: true }).click();
  await expect(page.locator(".answer")).toContainText("事实要点。");
  expect(calls).toBe(1);
  await page.route("**/api/ai/files/*/tag-suggestions", (route) => {
    calls++;
    task = { ...task!, kind: "TAGS", result: { tags: ["阅读", "资料"] } };
    return route.fulfill({ status: 202, json: task });
  });
  await page
    .getByRole("button", { name: "检查文档范围（不调用模型）", exact: true })
    .click();
  await page.getByRole("button", { name: "建议标签", exact: true }).click();
  await page.getByRole("button", { name: "确认并开始", exact: true }).click();
  await page
    .locator(".ai-result .el-checkbox")
    .filter({ hasText: "阅读" })
    .click();
  await page
    .getByRole("button", { name: "确认合并所选标签", exact: true })
    .click();
  await page.getByRole("button", { name: "保存合并结果", exact: true }).click();
  await expect(page.getByText("标签已合并保存", { exact: true })).toBeVisible();
  expect(
    await (await page.request.get(`/api/entries/${fileId}/tags`)).json(),
  ).toEqual(["阅读"]);
  await page.setViewportSize({ width: 390, height: 844 });
  await expect(page.locator(".ai-panel")).toBeVisible();
  await expect
    .poll(async () => {
      const box = await page.locator(".el-drawer").boundingBox();
      return !!box && box.x >= 0 && box.x + box.width <= 391;
    })
    .toBeTruthy();
  expect(
    await page
      .locator(".ai-panel")
      .evaluate((e) => e.scrollWidth <= e.clientWidth + 2),
  ).toBeTruthy();
  await page.screenshot({ path: test.info().outputPath("ai-mobile.png") });
  expect(errors).toEqual([]);
});

test("AI 测试任务的停止、失败和显式重试不自动重发", async ({ page }) => {
  await page.goto("/login");
  await page.getByRole("button", { name: "创建账号", exact: true }).click();
  await page.getByLabel("账号", { exact: true }).fill("aistop_" + Date.now());
  await page.getByLabel("密码", { exact: true }).fill("Browser-test-123");
  await page.getByRole("button", { name: "注册并进入云盘" }).click();
  await expect(
    page.getByRole("button", { name: "账号与安全", exact: true }),
  ).toBeVisible();
  const csrf = (await (await page.request.get("/api/auth/csrf")).json()).token;
  const headers = { "X-XSRF-TOKEN": csrf };
  const c = await (
    await page.request.post("/api/ai/connections", {
      headers,
      data: {
        name: "Test fixture",
        baseUrl: "https://model.example/v1",
        apiKey: "fake-key",
        enabled: true,
      },
    })
  ).json();
  await page.request.post("/api/ai/consents", {
    headers,
    data: {
      connectionId: c.id,
      destinationFingerprint: c.destinationFingerprint,
    },
  });
  await page.request.post("/api/ai/models", {
    headers,
    data: {
      connectionId: c.id,
      name: "Fixture model",
      modelId: "mock",
      contextLimit: 32768,
      maxOutput: 1024,
      outputParamMode: "max_tokens",
      enabled: true,
    },
  });
  let calls = 0;
  let task = {
    taskId: "test-ui-task",
    id: "test-ui-task",
    kind: "TEST",
    status: "RUNNING",
    stage: "GENERATING",
    cancel_requested: false,
    result: null,
    error_message: "",
  };
  await page.route("**/api/ai/models/*/test", (route) => {
    calls++;
    return route.fulfill({ status: 202, json: task });
  });
  await page.route("**/api/ai/tasks/test-ui-task", (route) =>
    route.fulfill({ json: task }),
  );
  await page.route("**/api/ai/tasks/test-ui-task/cancel", (route) => {
    task = { ...task, cancel_requested: true };
    return route.fulfill({ status: 202, json: task });
  });
  await page.getByRole("button", { name: "账号与安全", exact: true }).click();
  await page.getByRole("tab", { name: "AI 设置", exact: true }).click();
  await page
    .getByRole("button", { name: "测试聊天模型（可能收费）", exact: true })
    .click();
  await page.getByRole("button", { name: "确定", exact: true }).click();
  await expect(page.getByText("测试：生成中", { exact: false })).toBeVisible();
  await page.getByRole("button", { name: "停止测试", exact: true }).click();
  await expect(
    page.getByText("测试：正在停止", { exact: false }),
  ).toBeVisible();
  task = { ...task, status: "CANCELLED" };
  await expect(page.getByText("测试：已停止", { exact: false })).toBeVisible({
    timeout: 10000,
  });
  expect(calls).toBe(1);
  task = {
    ...task,
    status: "FAILED",
    cancel_requested: false,
    error_message: "模型认证失败，请检查 API Key",
  };
  await page
    .getByRole("button", { name: "测试聊天模型（可能收费）", exact: true })
    .click();
  await page.getByRole("button", { name: "确定", exact: true }).click();
  await expect(
    page.getByText("测试：失败 模型认证失败，请检查 API Key", { exact: false }),
  ).toBeVisible();
  expect(calls).toBe(2);
});
