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

test("单文档问答：创建、确认发送、引用、历史恢复、只读和删除", async ({
  page,
}) => {
  const errors: string[] = [];
  page.on("pageerror", (e) => errors.push(e.message));
  await page.goto("/login");
  await page.getByRole("button", { name: "创建账号", exact: true }).click();
  await page.getByLabel("账号", { exact: true }).fill("qaui_" + Date.now());
  await page.getByLabel("密码", { exact: true }).fill("Browser-test-123");
  await page.getByRole("button", { name: "注册并进入云盘" }).click();
  await expect(
    page.getByRole("button", { name: "账号与安全", exact: true }),
  ).toBeVisible();
  const csrf = (await (await page.request.get("/api/auth/csrf")).json()).token;
  const headers = { "X-XSRF-TOKEN": csrf };
  const rootId = (await (await page.request.get("/api/user/me")).json()).rootId;
  const connection = await (
    await page.request.post("/api/ai/connections", {
      headers,
      data: {
        name: "QA fixture",
        baseUrl: "https://model.example/v1",
        apiKey: "qa-ui-fake-key",
        enabled: true,
      },
    })
  ).json();
  expect(
    (
      await page.request.post("/api/ai/consents", {
        headers,
        data: {
          connectionId: connection.id,
          destinationFingerprint: connection.destinationFingerprint,
        },
      })
    ).ok(),
  ).toBeTruthy();
  const model = await (
    await page.request.post("/api/ai/models", {
      headers,
      data: {
        connectionId: connection.id,
        name: "QA model",
        modelId: "fixture",
        contextLimit: 32768,
        maxOutput: 1024,
        outputParamMode: "max_tokens",
        enabled: true,
      },
    })
  ).json();
  expect(
    (
      await page.request.put("/api/ai/preferences", {
        headers,
        data: { defaultChatModelId: model.id },
      })
    ).ok(),
  ).toBeTruthy();
  expect(
    (
      await page.request.post("/api/files/upload", {
        headers,
        multipart: {
          folderId: rootId,
          file: {
            name: "问答资料.md",
            mimeType: "text/markdown",
            buffer: Buffer.from("# 项目\n负责人是小王。"),
          },
        },
      })
    ).ok(),
  ).toBeTruthy();
  await page.getByRole("button", { name: "刷新", exact: true }).click();
  await page.getByRole("button", { name: "问答资料.md", exact: true }).click();
  await page.getByRole("button", { name: "AI 阅读助手", exact: true }).click();
  await page.getByText("单文档问答与会话历史", { exact: true }).click();
  const qa = page.locator(".document-qa");
  await qa
    .getByRole("button", { name: "新建文档会话（不调用模型）", exact: true })
    .click();
  await expect(
    qa.getByRole("button", { name: "删除会话", exact: true }),
  ).toBeVisible();
  const sessions = await (
    await page.request.get("/api/ai/conversations")
  ).json();
  expect(sessions).toHaveLength(1);
  const chat = { ...sessions[0], messages: [], activeTaskId: null };
  let sends = 0;
  let finished = false;
  await page.route(`**/api/ai/conversations/${chat.id}`, (route) =>
    route.request().method() === "GET"
      ? route.fulfill({ json: chat })
      : route.continue(),
  );
  await page.route(
    `**/api/ai/conversations/${chat.id}/messages`,
    async (route) => {
      sends++;
      expect(route.request().postDataJSON().question).toBe("负责人是谁？");
      expect(route.request().headers()["idempotency-key"]).toBeTruthy();
      chat.activeTaskId = "qa-ui";
      chat.messages = [
        {
          id: "u1",
          task_id: "qa-ui",
          role: "user",
          content: "负责人是谁？",
          status: "RUNNING",
          citations: [],
        },
      ];
      await route.fulfill({
        status: 202,
        json: {
          taskId: "qa-ui",
          id: "qa-ui",
          kind: "QA",
          status: "RUNNING",
          stage: "GENERATING",
          completed_calls: 0,
          total_calls: 1,
          result: null,
        },
      });
    },
  );
  await page.route("**/api/ai/tasks/qa-ui", (route) => {
    if (!finished) {
      chat.activeTaskId = null;
      chat.messages.push({
        id: "a1",
        task_id: "qa-ui",
        role: "assistant",
        content: "负责人是小王。<script>alert(1)</script>",
        status: "SUCCEEDED",
        scope: { partial: true, selectedCharacters: 12 },
        citations: [{ id: "ref1", source_id: "S1", locator: "行 1–2" }],
      });
      chat.messages[0].status = "SUCCEEDED";
      finished = true;
    }
    return route.fulfill({
      json: {
        taskId: "qa-ui",
        id: "qa-ui",
        kind: "QA",
        status: "SUCCEEDED",
        stage: "FINISHED",
        completed_calls: 1,
        total_calls: 1,
        result: { text: "负责人是小王。" },
        coverage: { partial: true, selectedCharacters: 12 },
      },
    });
  });
  await page.route(
    `**/api/ai/conversations/${chat.id}/citations/ref1`,
    (route) =>
      route.fulfill({
        json: {
          id: "ref1",
          source_id: "S1",
          locator: "行 1–2",
          excerpt: "负责人是小王。",
          revision: 1,
          readOnly: chat.readOnly,
          fileId: chat.file_id,
        },
      }),
  );
  await qa
    .getByRole("textbox", { name: "文档问题", exact: true })
    .fill("负责人是谁？");
  expect(sends).toBe(0);
  await qa
    .getByRole("button", { name: "发送问题（可能收费）", exact: true })
    .click();
  await page.getByRole("button", { name: "确认发送", exact: true }).click();
  await expect(qa.locator(".qa-transcript")).toContainText("负责人是小王。", {
    timeout: 10000,
  });
  await expect(qa.locator("script,img,a")).toHaveCount(0);
  expect(sends).toBe(1);
  await qa.getByRole("button", { name: "[S1] 行 1–2", exact: true }).click();
  const cite = page.getByRole("dialog", { name: "引用原文", exact: true });
  await expect(cite.locator("pre")).toHaveText("负责人是小王。");
  await cite.locator(".el-dialog__headerbtn").click();
  const drawer = page.getByRole("dialog", { name: "AI 阅读助手", exact: true });
  await drawer.locator(".el-drawer__close-btn").click();
  await page.getByRole("button", { name: "AI 阅读助手", exact: true }).click();
  await page.getByText("单文档问答与会话历史", { exact: true }).click();
  await expect(qa.locator(".qa-transcript")).toContainText("负责人是小王。");
  chat.messages.push({
    id: "failed-user",
    task_id: "qa-failed",
    role: "user",
    content: "重新询问负责人",
    status: "FAILED",
    error_message: "供应商暂时不可用",
    citations: [],
  });
  await qa.getByRole("button", { name: "刷新会话", exact: true }).click();
  await expect(qa).toContainText("供应商暂时不可用");
  await qa.getByRole("button", { name: "重新填写此问题", exact: true }).click();
  await expect(
    qa.getByRole("textbox", { name: "文档问题", exact: true }),
  ).toHaveValue("重新询问负责人");
  let stops = 0;
  const active = {
    taskId: "qa-running",
    id: "qa-running",
    kind: "QA",
    status: "RUNNING",
    stage: "GENERATING",
    completed_calls: 0,
    total_calls: 1,
    result: null,
  };
  chat.activeTaskId = "qa-running";
  await page.route("**/api/ai/tasks/qa-running", (route) =>
    route.fulfill({ json: active }),
  );
  await page.route("**/api/ai/tasks/qa-running/cancel", (route) => {
    stops++;
    chat.activeTaskId = null;
    active.status = "CANCELLED";
    active.stage = "FINISHED";
    return route.fulfill({ status: 202, json: active });
  });
  await qa.getByRole("button", { name: "刷新会话", exact: true }).click();
  await qa.getByRole("button", { name: "停止回答", exact: true }).click();
  await expect(
    qa.getByRole("button", { name: "停止回答", exact: true }),
  ).not.toBeVisible();
  expect(stops).toBe(1);
  expect(sends).toBe(1);
  chat.readOnly = true;
  await qa.getByRole("button", { name: "刷新会话", exact: true }).click();
  await expect(
    qa.getByRole("textbox", { name: "文档问题", exact: true }),
  ).toBeDisabled();
  await page.setViewportSize({ width: 390, height: 844 });
  await expect
    .poll(async () => {
      const box = await qa.boundingBox();
      return !!box && box.x >= -1 && box.x + box.width <= 391;
    })
    .toBeTruthy();
  await page.screenshot({ path: test.info().outputPath("qa-mobile.png") });
  await qa.getByRole("button", { name: "删除会话", exact: true }).click();
  await page.getByRole("button", { name: "确定", exact: true }).click();
  await expect(
    qa.getByRole("button", { name: "删除会话", exact: true }),
  ).not.toBeVisible();
  expect(sends).toBe(1);
  expect(errors).toEqual([]);
});

test("向量模型独立配置、确认测试、错误提示和聊天用途隔离", async ({ page }) => {
  await page.goto("/login");
  await page.getByRole("button", { name: "创建账号", exact: true }).click();
  await page.getByLabel("账号", { exact: true }).fill("embui_" + Date.now());
  await page.getByLabel("密码", { exact: true }).fill("Browser-test-123");
  await page.getByRole("button", { name: "注册并进入云盘" }).click();
  await expect(
    page.getByRole("button", { name: "账号与安全", exact: true }),
  ).toBeVisible();
  const csrf = (await (await page.request.get("/api/auth/csrf")).json()).token;
  const headers = { "X-XSRF-TOKEN": csrf };
  const connection = await (
    await page.request.post("/api/ai/connections", {
      headers,
      data: {
        name: "Embedding fixture",
        baseUrl: "https://model.example/v1",
        apiKey: "emb-ui-fake-key",
        enabled: true,
      },
    })
  ).json();
  expect(
    (
      await page.request.post("/api/ai/consents", {
        headers,
        data: {
          connectionId: connection.id,
          destinationFingerprint: connection.destinationFingerprint,
        },
      })
    ).ok(),
  ).toBeTruthy();
  const chat = await (
    await page.request.post("/api/ai/models", {
      headers,
      data: {
        connectionId: connection.id,
        name: "Chat only",
        modelId: "chat-fixture",
        contextLimit: 32768,
        maxOutput: 1024,
        outputParamMode: "max_tokens",
        enabled: true,
      },
    })
  ).json();
  expect(
    (
      await page.request.put("/api/ai/preferences", {
        headers,
        data: { defaultChatModelId: chat.id },
      })
    ).ok(),
  ).toBeTruthy();
  await page.getByRole("button", { name: "账号与安全", exact: true }).click();
  await page.getByRole("tab", { name: "AI 设置", exact: true }).click();
  await page.getByRole("button", { name: "添加向量模型", exact: true }).click();
  const editor = page.getByRole("dialog", { name: "向量模型", exact: true });
  await editor
    .locator(".el-form-item")
    .filter({ hasText: "显示名称" })
    .locator("input")
    .fill("Vector only");
  await editor
    .locator(".el-form-item")
    .filter({ hasText: "供应商模型 ID" })
    .locator("input")
    .fill("vector-fixture");
  await editor
    .locator(".el-form-item")
    .filter({ hasText: "预期向量维度" })
    .locator("input")
    .fill("3");
  await expect(
    editor.getByText("最大输出 token", { exact: true }),
  ).not.toBeVisible();
  await expect(
    editor.getByRole("checkbox", {
      name: "按此维度请求降维（需供应商明确支持）",
    }),
  ).not.toBeChecked();
  await editor.getByRole("button", { name: "保存模型", exact: true }).click();
  await expect(editor).not.toBeVisible();
  const models = await (await page.request.get("/api/ai/models")).json();
  const vector = models.find(
    (m: Record<string, unknown>) => m.purpose === "EMBEDDING",
  );
  expect(vector.embedding_dimensions).toBe(3);
  expect(vector.request_dimensions).toBe(false);
  const card = page.locator(".embedding-card");
  await expect(card).toContainText("Vector only");
  await page
    .getByRole("combobox", { name: "默认聊天模型", exact: true })
    .locator("xpath=ancestor::div[contains(@class,'el-select__wrapper')]")
    .click();
  await expect(
    page.getByRole("option", { name: "Chat only", exact: true }),
  ).toBeVisible();
  await expect(
    page.getByRole("option", { name: "Vector only", exact: true }),
  ).toHaveCount(0);
  await page.keyboard.press("Escape");
  let sends = 0;
  let result: Record<string, unknown> = {};
  await page.route(
    `**/api/ai/models/${vector.id}/embedding-test`,
    async (route) => {
      sends++;
      expect(route.request().postDataJSON()).toEqual({});
      expect(route.request().headers()["idempotency-key"]).toBeTruthy();
      result = {
        taskId: "embedding-ui",
        id: "embedding-ui",
        kind: "EMBEDDING_TEST",
        status: sends === 1 ? "SUCCEEDED" : "FAILED",
        stage: "FINISHED",
        created_at: Date.now(),
        completed_calls: sends === 1 ? 1 : 0,
        total_calls: 1,
        result:
          sends === 1
            ? {
                text: "向量接口校验通过",
                dimensions: 3,
                vectorCount: 2,
                notice: "不代表语义质量或知识库可用",
              }
            : null,
        error_message:
          sends === 1 ? null : "向量维度与配置不一致，请核对供应商规格",
        error_code: sends === 1 ? null : "AI_EMBEDDING_DIMENSION_MISMATCH",
      };
      await route.fulfill({
        status: 202,
        json: {
          ...result,
          status: "RUNNING",
          stage: "GENERATING",
          result: null,
        },
      });
    },
  );
  await page.route("**/api/ai/tasks/embedding-ui", (route) =>
    route.fulfill({ json: result }),
  );
  await page.route("**/api/ai/usage?*", (route) =>
    route.fulfill({
      json: {
        items: [
          {
            id: "emb-call",
            model_name: "Vector only",
            purpose: "EMBEDDING",
            status: "SUCCEEDED",
            created_at: Date.now(),
            input_tokens: 12,
            total_tokens: 12,
            output_tokens: null,
          },
        ],
        summary: { calls: 1, known_usage_calls: 1, total_tokens: 12 },
      },
    }),
  );
  await card
    .getByRole("button", { name: "测试向量模型（可能收费）", exact: true })
    .click();
  expect(sends).toBe(0);
  await page.getByRole("button", { name: "确定", exact: true }).click();
  await expect(page.getByRole("status")).toContainText("2 个向量", {
    timeout: 10000,
  });
  expect(sends).toBe(1);
  await expect(page.locator(".ai-usage")).toContainText("不适用");
  await card
    .getByRole("button", { name: "测试向量模型（可能收费）", exact: true })
    .click();
  await page.getByRole("button", { name: "确定", exact: true }).click();
  await expect(page.getByRole("status")).toContainText("向量维度与配置不一致", {
    timeout: 10000,
  });
  expect(sends).toBe(2);
  await page.setViewportSize({ width: 390, height: 844 });
  await page.screenshot({
    path: test.info().outputPath("embedding-settings-mobile.png"),
  });
  const storage = JSON.stringify(
    await page.evaluate(() => ({
      local: { ...localStorage },
      session: { ...sessionStorage },
    })),
  );
  expect(storage).not.toContain("emb-ui-fake-key");
});
