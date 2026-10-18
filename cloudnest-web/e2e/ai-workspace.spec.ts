import { test, expect } from "@playwright/test";

test("知识库预览、费用确认、语义片段、引用与移动端；整理默认不勾选", async ({
  page,
}) => {
  const errors: string[] = [];
  page.on("pageerror", (e) => errors.push(e.message));
  await page.goto("/login");
  await page.getByRole("button", { name: "创建账号", exact: true }).click();
  await page.getByLabel("账号", { exact: true }).fill("kbui_" + Date.now());
  await page.getByLabel("密码", { exact: true }).fill("Browser-test-123");
  await page.getByRole("button", { name: "注册并进入云盘" }).click();
  await expect(
    page.getByRole("button", { name: "账号与安全", exact: true }),
  ).toBeVisible();
  const headers = {
    "X-XSRF-TOKEN": (await (await page.request.get("/api/auth/csrf")).json())
      .token,
  };
  const rootId = (await (await page.request.get("/api/user/me")).json()).rootId;
  const c = await (
    await page.request.post("/api/ai/connections", {
      headers,
      data: {
        name: "Workspace fixture",
        baseUrl: "https://model.example/v1",
        apiKey: "workspace-fake-key",
        enabled: true,
      },
    })
  ).json();
  expect(
    (
      await page.request.post("/api/ai/consents", {
        headers,
        data: {
          connectionId: c.id,
          destinationFingerprint: c.destinationFingerprint,
        },
      })
    ).ok(),
  ).toBeTruthy();
  const chat = await (
    await page.request.post("/api/ai/models", {
      headers,
      data: {
        connectionId: c.id,
        name: "Workspace chat",
        modelId: "chat-fixture",
        contextLimit: 32768,
        maxOutput: 1024,
        outputParamMode: "max_tokens",
        enabled: true,
      },
    })
  ).json();
  const vector = await (
    await page.request.post("/api/ai/models", {
      headers,
      data: {
        connectionId: c.id,
        name: "Workspace vector",
        modelId: "vector-fixture",
        purpose: "EMBEDDING",
        contextLimit: 8192,
        embeddingDimensions: 3,
        maxBatchSize: 16,
        enabled: true,
      },
    })
  ).json();
  const file = await (
    await page.request.post("/api/files/upload", {
      headers,
      multipart: {
        folderId: rootId,
        file: {
          name: "知识资料.md",
          mimeType: "text/markdown",
          buffer: Buffer.from("# 资料\n负责人是小王。"),
        },
      },
    })
  ).json();
  await page.getByRole("button", { name: "账号与安全", exact: true }).click();
  await page.getByRole("tab", { name: "AI 知识库", exact: true }).click();
  const pane = page.locator(".knowledge");
  await pane.getByRole("button", { name: "新建知识库", exact: true }).click();
  const editor = page.getByRole("dialog", { name: "新建知识库", exact: true });
  await editor.getByLabel("知识库名称", { exact: true }).fill("工作资料");
  await editor
    .getByRole("button", { name: "选择文件或文件夹", exact: true })
    .click();
  const picker = page.getByRole("dialog", {
    name: "选择 AI 处理范围",
    exact: true,
  });
  await picker
    .locator(".el-checkbox")
    .filter({ hasText: "知识资料.md" })
    .click();
  await picker.getByRole("button", { name: "完成选择" }).click();
  await editor.getByRole("button", { name: "保存知识库", exact: true }).click();
  await expect(editor).not.toBeVisible();
  const bases = await (
    await page.request.get("/api/ai/knowledge-bases")
  ).json();
  const base = bases[0];
  expect(base.model_id).toBe(vector.id);
  let ready = false,
    indexCalls = 0,
    searchCalls = 0,
    answerCalls = 0;
  const jobs: Record<string, any> = {};
  const source = {
    sourceId: "source-ui",
    file_id: file.fileId,
    revision: 1,
    name: "知识资料.md",
    locator: "行 1–2",
    text: "负责人是小王。",
    score: 1,
  };
  await page.route("**/api/ai/knowledge-bases", (route) =>
    route.request().method() === "GET"
      ? route.fulfill({
          json: [
            {
              ...base,
              ready,
              chunks: ready ? 1 : 0,
              needsUpdate: !ready,
              active_generation: ready ? "ui-gen" : null,
            },
          ],
        })
      : route.continue(),
  );
  function job(id: string, kind: string, result: any) {
    return {
      id,
      taskId: id,
      workspace_id: base.id,
      kind,
      model_id: kind === "KB_ANSWER" ? chat.id : vector.id,
      model_name: "fixture",
      status: "SUCCEEDED",
      stage: "FINISHED",
      created_at: Date.now(),
      completed_calls: 1,
      total_calls: 1,
      cancel_requested: false,
      result,
      error_message: null,
      error_code: null,
      question:
        kind === "KB_SEARCH" || kind === "KB_ANSWER"
          ? "负责人是谁？"
          : undefined,
    };
  }
  await page.route(
    `**/api/ai/knowledge-bases/${base.id}/index-jobs`,
    async (route) => {
      indexCalls++;
      ready = true;
      const t = job("ui-index", "KB_INDEX", {
        text: "索引已完成",
        remainingChunks: 0,
        sources: [],
      });
      jobs[t.id] = t;
      await route.fulfill({
        status: 202,
        json: { ...t, status: "RUNNING", result: null },
      });
    },
  );
  await page.route("**/api/ai/tasks/ui-*", (route) =>
    route.fulfill({ json: jobs[route.request().url().split("/").pop()!] }),
  );
  await pane.getByRole("button", { name: "预览索引范围", exact: true }).click();
  await expect(pane).toContainText("1 块");
  expect(indexCalls).toBe(0);
  await pane
    .getByRole("button", { name: "确认 / 继续索引（可能收费）", exact: true })
    .click();
  expect(indexCalls).toBe(0);
  await page
    .getByRole("button", { name: "确认并开始索引", exact: true })
    .click();
  await expect(pane).toContainText("索引已完成", { timeout: 10000 });
  expect(indexCalls).toBe(1);
  await page.route(
    `**/api/ai/knowledge-bases/${base.id}/semantic-search`,
    async (route) => {
      searchCalls++;
      expect(route.request().headers()["idempotency-key"]).toBeTruthy();
      const t = job("ui-search", "KB_SEARCH", {
        text: "语义相关片段",
        sources: [source],
        generationId: "ui-gen",
      });
      jobs[t.id] = t;
      await route.fulfill({
        status: 202,
        json: { ...t, status: "RUNNING", result: null },
      });
    },
  );
  await pane.getByLabel("语义搜索问题", { exact: true }).fill("负责人是谁？");
  await pane
    .getByRole("button", { name: "检索相关片段（可能收费）", exact: true })
    .click();
  expect(searchCalls).toBe(0);
  await page.getByRole("button", { name: "确认并检索", exact: true }).click();
  await expect(pane).toContainText("负责人是小王。", { timeout: 10000 });
  expect(searchCalls).toBe(1);
  await pane
    .getByRole("button", { name: "知识资料.md · 行 1–2", exact: true })
    .click();
  const citation = page.getByRole("dialog", { name: "知识库来源原文" });
  await expect(citation).toContainText("修订 1");
  await citation.getByRole("button", { name: "关闭此对话框" }).click();
  await page.route(
    `**/api/ai/knowledge-bases/${base.id}/sessions`,
    async (route) => {
      if (route.request().method() === "POST")
        await route.fulfill({
          json: {
            id: "ui-session",
            model_id: chat.id,
            generation_id: "ui-gen",
            updated_at: Date.now(),
          },
        });
      else
        await route.fulfill({
          json: [
            {
              id: "ui-session",
              model_id: chat.id,
              generation_id: "ui-gen",
              updated_at: Date.now(),
            },
          ],
        });
    },
  );
  await page.route("**/api/ai/knowledge-sessions/ui-session", (route) =>
    route.fulfill({ json: jobs["ui-answer"] ? [jobs["ui-answer"]] : [] }),
  );
  await page.route(
    `**/api/ai/knowledge-bases/${base.id}/answers`,
    async (route) => {
      answerCalls++;
      const t = job("ui-answer", "KB_ANSWER", {
        text: "负责人是小王。<script>不执行</script>",
        sources: [source],
        generationId: "ui-gen",
      });
      jobs[t.id] = t;
      await route.fulfill({
        status: 202,
        json: { ...t, status: "RUNNING", result: null },
      });
    },
  );
  await pane
    .getByRole("button", { name: "基于这些片段回答（可能收费）", exact: true })
    .click();
  expect(answerCalls).toBe(0);
  await page
    .getByRole("button", { name: "确认并生成回答", exact: true })
    .click();
  await expect(pane).toContainText("<script>不执行</script>", {
    timeout: 10000,
  });
  expect(answerCalls).toBe(1);
  expect(await pane.locator("script").count()).toBe(0);
  await page.setViewportSize({ width: 390, height: 844 });
  await page.screenshot({
    path: test.info().outputPath("knowledge-mobile.png"),
  });
  await page.setViewportSize({ width: 1440, height: 1000 });
  await page.getByRole("tab", { name: "智能整理", exact: true }).click();
  const organize = page.locator(".organize");
  await organize.getByRole("button", { name: "选择文件", exact: true }).click();
  await page
    .getByRole("dialog", { name: "选择 AI 处理范围" })
    .locator(".el-checkbox")
    .filter({ hasText: "知识资料.md" })
    .click();
  await page
    .getByRole("dialog", { name: "选择 AI 处理范围" })
    .getByRole("button", { name: "完成选择" })
    .click();
  await organize
    .getByRole("button", { name: "选择目标文件夹", exact: true })
    .click();
  await page
    .getByRole("dialog", { name: "选择 AI 处理范围" })
    .getByRole("button", { name: "选择当前文件夹" })
    .click();
  await page
    .getByRole("dialog", { name: "选择 AI 处理范围" })
    .getByRole("button", { name: "完成选择" })
    .click();
  await organize
    .getByRole("button", { name: "预览整理范围（不调用模型）", exact: true })
    .click();
  let organizeCalls = 0,
    applyCalls = 0;
  await page.route("**/api/ai/organization-jobs", async (route) => {
    organizeCalls++;
    const t = job("ui-organize", "ORGANIZE", {
      text: "整理建议已生成；尚未修改任何文件",
      suggestions: [
        {
          fileId: file.fileId,
          originalName: "知识资料.md",
          name: "项目资料.md",
          tags: ["项目"],
          targetFolderId: rootId,
          reason: "根据部分资料建议",
        },
      ],
    });
    jobs[t.id] = t;
    await route.fulfill({
      status: 202,
      json: { ...t, status: "RUNNING", result: null },
    });
  });
  await page.route(
    "**/api/ai/tasks/ui-organize/apply-organization",
    async (route) => {
      applyCalls++;
      expect(route.request().postDataJSON().selections).toEqual([
        {
          fileId: file.fileId,
          applyName: true,
          applyTags: false,
          applyMove: false,
        },
      ]);
      await route.fulfill({
        json: [{ fileId: file.fileId, status: "APPLIED" }],
      });
    },
  );
  await organize
    .getByRole("button", { name: "生成整理建议（可能收费）", exact: true })
    .click();
  expect(organizeCalls).toBe(0);
  await page
    .getByRole("button", { name: "确认并生成建议", exact: true })
    .click();
  await expect(organize).toContainText("项目资料.md", { timeout: 10000 });
  for (const checkbox of await organize
    .locator(".suggestion")
    .getByRole("checkbox")
    .all())
    await expect(checkbox).not.toBeChecked();
  await organize
    .locator(".suggestion .el-checkbox")
    .filter({ hasText: "改名为" })
    .click();
  await organize
    .getByRole("button", { name: "确认应用勾选项", exact: true })
    .click();
  expect(applyCalls).toBe(0);
  await page.getByRole("button", { name: "确认应用", exact: true }).click();
  await expect(organize.getByRole("status")).toContainText("已应用");
  expect(applyCalls).toBe(1);
  await page
    .getByRole("dialog", { name: "账号与安全", exact: true })
    .getByRole("button", { name: "关闭此对话框" })
    .click();
  const replacement = await (
    await page.request.post("/api/files/upload", {
      headers,
      multipart: {
        folderId: rootId,
        file: {
          name: "新资料.md",
          mimeType: "text/markdown",
          buffer: Buffer.from("# 资料\n负责人是小李。"),
        },
      },
    })
  ).json();
  expect(
    (
      await page.request.post(`/api/files/${file.fileId}/versions`, {
        headers,
        data: { sourceId: replacement.fileId, expectedRevision: 1 },
      })
    ).ok(),
  ).toBeTruthy();
  await page.getByRole("button", { name: "刷新", exact: true }).click();
  await page.getByRole("button", { name: "知识资料.md", exact: true }).click();
  await page.getByRole("button", { name: "AI 阅读助手", exact: true }).click();
  const diff = page.locator(".version-diff");
  await diff
    .getByRole("button", { name: "查看版本文本差异", exact: true })
    .click();
  await expect(diff).toContainText("小王");
  await expect(diff).toContainText("小李");
  let diffCalls = 0;
  await page.route(
    `**/api/ai/files/${file.fileId}/change-summaries`,
    async (route) => {
      diffCalls++;
      const t = job("ui-diff", "VERSION_DIFF", {
        text: "负责人由小王改为小李",
      });
      jobs[t.id] = t;
      await route.fulfill({
        status: 202,
        json: { ...t, status: "RUNNING", result: null },
      });
    },
  );
  await diff
    .getByRole("button", { name: "生成版本变化摘要（可能收费）", exact: true })
    .click();
  expect(diffCalls).toBe(0);
  await page
    .getByRole("button", { name: "确认并解释差异", exact: true })
    .click();
  await expect(diff).toContainText("负责人由小王改为小李", { timeout: 10000 });
  expect(diffCalls).toBe(1);
  expect(errors).toEqual([]);
  const storage = await page.evaluate(() =>
    JSON.stringify({ ...localStorage, ...sessionStorage }),
  );
  expect(storage).not.toContain("workspace-fake-key");
});
