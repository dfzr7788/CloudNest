package com.cloudnest;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;

import com.cloudnest.ai.*;
import com.cloudnest.common.*;
import com.cloudnest.file.*;
import com.fasterxml.jackson.databind.*;
import jakarta.servlet.http.Cookie;
import java.io.*;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.util.*;
import java.util.concurrent.*;
import java.util.function.Consumer;
import okhttp3.Call;
import org.junit.jupiter.api.*;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.mock.web.MockMultipartFile;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.*;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;

@SpringBootTest(
    properties = {
      "spring.datasource.url=jdbc:h2:mem:ai;MODE=MySQL;DATABASE_TO_LOWER=TRUE;DB_CLOSE_DELAY=-1",
      "cloudnest.ai.enabled=true",
      "cloudnest.index-enabled=false"
    })
@AutoConfigureMockMvc
class AiIntegrationTest {
  @Autowired MockMvc mvc;
  @Autowired ObjectMapper json;
  @Autowired Db db;
  @Autowired AiTaskService tasks;
  @Autowired AiConnectionService configs;
  @Autowired DocumentExtractionService extraction;
  @MockitoBean ModelGateway gateway;
  @MockitoBean EmbeddingGateway embeddings;
  static final Path data;

  static {
    try {
      data = Files.createTempDirectory("cloudnest-ai-test-");
    } catch (IOException e) {
      throw new ExceptionInInitializerError(e);
    }
  }

  @DynamicPropertySource
  static void settings(DynamicPropertyRegistry r) {
    r.add("cloudnest.data-dir", data::toString);
    String url = System.getenv("CN_AI_TEST_DATABASE_URL");
    if (url != null) {
      r.add("spring.datasource.url", () -> url);
      r.add("spring.datasource.username", () -> "root");
      r.add("spring.datasource.password", () -> "");
    }
  }

  record User(String id, String root, Cookie cookie) {}

  record Setup(User user, String connection, String model) {}

  JsonNode call(User user, MockHttpServletRequestBuilder request, Object body, int expected)
      throws Exception {
    if (user != null) request.cookie(user.cookie());
    if (body != null) request.contentType("application/json").content(json.writeValueAsBytes(body));
    var response = mvc.perform(request.with(csrf())).andReturn().getResponse();
    String content = response.getContentAsString(StandardCharsets.UTF_8);
    assertEquals(expected, response.getStatus(), content);
    return content.isBlank() ? json.createObjectNode() : json.readTree(content);
  }

  User user() throws Exception {
    // Independent fixtures represent independent clients; do not exhaust the production signup
    // limit.
    String fixtureIp =
        "198.18."
            + java.util.concurrent.ThreadLocalRandom.current().nextInt(1, 255)
            + "."
            + java.util.concurrent.ThreadLocalRandom.current().nextInt(1, 255);
    var r =
        mvc.perform(
                post("/api/auth/register")
                    .with(
                        request -> {
                          request.setRemoteAddr(fixtureIp);
                          return request;
                        })
                    .with(csrf())
                    .contentType("application/json")
                    .content(
                        json.writeValueAsBytes(
                            Map.of(
                                "username",
                                "ai"
                                    + UUID.randomUUID()
                                        .toString()
                                        .replace("-", "")
                                        .substring(0, 12),
                                "password",
                                "correct-password"))))
            .andReturn()
            .getResponse();
    assertEquals(200, r.getStatus(), r.getContentAsString());
    var n = json.readTree(r.getContentAsString());
    return new User(n.path("id").asText(), n.path("rootId").asText(), r.getCookie("CN_AUTH"));
  }

  Setup setup() throws Exception {
    User u = user();
    var c =
        call(
            u,
            post("/api/ai/connections"),
            Map.of(
                "name",
                "Fixture",
                "baseUrl",
                "https://model.example/v1/",
                "apiKey",
                "test-secret-do-not-expose",
                "enabled",
                true),
            200);
    assertFalse(c.toString().contains("test-secret"));
    assertFalse(c.has("secret_cipher"));
    String cid = c.path("id").asText();
    call(
        u,
        post("/api/ai/consents"),
        Map.of(
            "connectionId",
            cid,
            "destinationFingerprint",
            c.path("destinationFingerprint").asText()),
        200);
    var m =
        call(
            u,
            post("/api/ai/models"),
            Map.of(
                "connectionId",
                cid,
                "name",
                "Mock model",
                "modelId",
                "fixture-model",
                "contextLimit",
                32768,
                "maxOutput",
                1024,
                "outputParamMode",
                "max_tokens",
                "enabled",
                true),
            200);
    when(gateway.chat(any(), anyString(), anyString(), any()))
        .thenReturn(new ModelGateway.Answer("资料摘要", 12L, 8L, 20L));
    return new Setup(u, cid, m.path("id").asText());
  }

  String upload(User u, String name, byte[] content) throws Exception {
    var multipart = new MockMultipartFile("file", name, "application/octet-stream", content);
    return call(
            u,
            multipart("/api/files/upload").file(multipart).param("folderId", u.root()),
            null,
            200)
        .path("fileId")
        .asText();
  }

  JsonNode waitFor(User u, String id) throws Exception {
    long end = System.nanoTime() + TimeUnit.SECONDS.toNanos(15);
    while (System.nanoTime() < end) {
      var t = call(u, get("/api/ai/tasks/" + id), null, 200);
      if (Set.of("SUCCEEDED", "FAILED", "CANCELLED", "STALE").contains(t.path("status").asText()))
        return t;
      Thread.sleep(40);
    }
    fail("Task did not terminate");
    return null;
  }

  @Test
  void summaryIsolationIdempotencyAndUsage() throws Exception {
    var s = setup();
    var other = user();
    String file = upload(s.user(), "hello.md", "# 资料\n私人文档内容".getBytes(StandardCharsets.UTF_8));
    var analysis =
        call(
            s.user(),
            get("/api/ai/files/" + file + "/analysis").param("modelId", s.model()),
            null,
            200);
    assertEquals("COMPLETE", analysis.path("status").asText());
    assertTrue(analysis.path("sources").size() > 0);
    var input =
        Map.of("modelId", s.model(), "expectedRevision", analysis.path("revision").asLong());
    String key = Util.id();
    var t =
        call(
            s.user(),
            post("/api/ai/files/" + file + "/summaries").header("Idempotency-Key", key),
            input,
            202);
    String tid = t.path("taskId").asText();
    var duplicate =
        call(
            s.user(),
            post("/api/ai/files/" + file + "/summaries").header("Idempotency-Key", key),
            input,
            202);
    assertEquals(tid, duplicate.path("taskId").asText());
    call(
        s.user(),
        post("/api/ai/files/" + file + "/summaries").header("Idempotency-Key", key),
        Map.of(
            "modelId",
            s.model(),
            "expectedRevision",
            analysis.path("revision").asLong(),
            "language",
            "original"),
        409);
    assertEquals("SUCCEEDED", waitFor(s.user(), tid).path("status").asText());
    assertEquals(1, db.count("SELECT COUNT(*) FROM ai_call WHERE task_id=?", tid));
    var usage = call(s.user(), get("/api/ai/usage"), null, 200);
    assertEquals(20, usage.path("summary").path("total_tokens").asLong());
    call(other, get("/api/ai/tasks/" + tid), null, 404);
    call(
        other,
        post("/api/ai/models/" + s.model() + "/test").header("Idempotency-Key", Util.id()),
        null,
        404);
    call(other, delete("/api/ai/connections/" + s.connection()), null, 404);
    String cacheKey = Util.id();
    var cached =
        call(
            s.user(),
            post("/api/ai/files/" + file + "/summaries").header("Idempotency-Key", cacheKey),
            input,
            202);
    assertTrue(cached.path("cached").asBoolean());
    call(
        s.user(),
        post("/api/ai/files/" + file + "/summaries").header("Idempotency-Key", cacheKey),
        Map.of(
            "modelId",
            s.model(),
            "expectedRevision",
            analysis.path("revision").asLong(),
            "forceRefresh",
            true),
        409);
    call(s.user(), delete("/api/files/" + file), null, 200);
    call(s.user(), get("/api/ai/tasks/" + tid), null, 404);
  }

  @Test
  void configurationSecretConsentAndVersionRules() throws Exception {
    var s = setup();
    assertEquals(
        "https://model.example/v1", configs.connections(s.user().id()).getFirst().get("base_url"));
    var encrypted = Db.s(configs.connection(s.user().id(), s.connection()), "secret_cipher");
    assertTrue(encrypted.startsWith("ai:v1:"));
    assertFalse(encrypted.contains("test-secret"));
    call(
        s.user(),
        patch("/api/ai/connections/" + s.connection()),
        Map.of("configVersion", 1, "baseUrl", "https://other.example/v1"),
        400);
    call(
        s.user(),
        patch("/api/ai/connections/" + s.connection()),
        Map.of("configVersion", 1, "apiKey", ""),
        400);
    call(
        s.user(),
        patch("/api/ai/connections/" + s.connection()),
        Map.of("configVersion", 2, "name", "Changed"),
        409);
    call(
        s.user(),
        patch("/api/ai/connections/" + s.connection()),
        Map.of("configVersion", 1, "name", "Changed"),
        200);
    assertEquals(
        encrypted, Db.s(configs.connection(s.user().id(), s.connection()), "secret_cipher"));
    call(
        s.user(),
        patch("/api/ai/connections/" + s.connection()),
        Map.of("configVersion", 2, "baseUrl", "https://other.example/v1", "apiKey", "replacement"),
        200);
    var error =
        call(
            s.user(),
            post("/api/ai/models/" + s.model() + "/test").header("Idempotency-Key", Util.id()),
            null,
            403);
    assertEquals("AI_CONSENT_REQUIRED", error.path("code").asText());
    call(s.user(), delete("/api/ai/connections/" + s.connection() + "/credential"), null, 200);
    assertNull(configs.connection(s.user().id(), s.connection()).get("secret_cipher"));
  }

  @Test
  void disablingConnectionClearsDefaultModel() throws Exception {
    var s = setup();
    call(s.user(), put("/api/ai/preferences"), Map.of("defaultChatModelId", s.model()), 200);
    call(
        s.user(),
        patch("/api/ai/connections/" + s.connection()),
        Map.of("configVersion", 1, "enabled", false),
        200);
    assertTrue(
        call(s.user(), get("/api/ai/preferences"), null, 200)
            .path("default_chat_model_id")
            .isNull());
    call(
        s.user(),
        post("/api/ai/models/" + s.model() + "/test").header("Idempotency-Key", Util.id()),
        null,
        403);
    call(
        s.user(),
        patch("/api/ai/connections/" + s.connection()),
        Map.of("configVersion", 2, "enabled", true),
        200);
    assertTrue(
        call(s.user(), get("/api/ai/preferences"), null, 200)
            .path("default_chat_model_id")
            .isNull());
  }

  @Test
  void interruptedTasksAreNotReplayedAndRetentionPreservesSource() throws Exception {
    var s = setup();
    String file =
        upload(s.user(), "retention.txt", "source remains".getBytes(StandardCharsets.UTF_8));
    long revision = extraction.extract(s.user().id(), file).revision();
    var t =
        call(
            s.user(),
            post("/api/ai/files/" + file + "/summaries").header("Idempotency-Key", Util.id()),
            Map.of("modelId", s.model(), "expectedRevision", revision),
            202);
    String id = t.path("taskId").asText();
    assertEquals("SUCCEEDED", waitFor(s.user(), id).path("status").asText());
    // Simulate the persisted state left by process termination after a charged request.
    db.update("UPDATE ai_task SET status='RUNNING',result_json=NULL WHERE id=?", id);
    org.springframework.test.util.ReflectionTestUtils.invokeMethod(tasks, "recover");
    assertEquals(
        "AI_INTERRUPTED",
        call(s.user(), get("/api/ai/tasks/" + id), null, 200).path("error_code").asText());
    assertEquals(1, db.count("SELECT COUNT(*) FROM ai_call WHERE task_id=?", id));
    db.update("UPDATE ai_task SET expires_at=? WHERE id=?", System.currentTimeMillis() - 1, id);
    tasks.retention();
    call(s.user(), get("/api/ai/tasks/" + id), null, 404);
    assertTrue(extraction.extract(s.user().id(), file).text().contains("source remains"));
    assertEquals(1, db.count("SELECT COUNT(*) FROM ai_call WHERE task_id=?", id));
  }

  @Test
  void maintenanceCommandsDoNotRecoverOrDispatchNormalServerTasks() throws Exception {
    var s = setup();
    org.springframework.test.util.ReflectionTestUtils.setField(tasks, "maintenance", true);
    try {
      var t =
          call(
              s.user(),
              post("/api/ai/models/" + s.model() + "/test").header("Idempotency-Key", Util.id()),
              null,
              202);
      String id = t.path("taskId").asText();
      org.springframework.test.util.ReflectionTestUtils.invokeMethod(tasks, "recover");
      tasks.dispatch();
      assertEquals(
          "QUEUED", call(s.user(), get("/api/ai/tasks/" + id), null, 200).path("status").asText());
      assertEquals(0, db.count("SELECT COUNT(*) FROM ai_call WHERE task_id=?", id));
      tasks.cancel(s.user().id(), id);
    } finally {
      org.springframework.test.util.ReflectionTestUtils.setField(tasks, "maintenance", false);
    }
  }

  @Test
  void extractionEmptyPartialPdfDocxAndConcurrentTags() throws Exception {
    var s = setup();
    String empty = upload(s.user(), "empty.txt", new byte[0]);
    assertEquals("NO_EXTRACTABLE_TEXT", extraction.extract(s.user().id(), empty).status());
    String longFile =
        upload(s.user(), "long.md", ("内容\n".repeat(10000)).getBytes(StandardCharsets.UTF_8));
    var in =
        Map.of(
            "modelId",
            s.model(),
            "expectedRevision",
            extraction.extract(s.user().id(), longFile).revision(),
            "rangeChars",
            100);
    var t =
        call(
            s.user(),
            post("/api/ai/files/" + longFile + "/summaries").header("Idempotency-Key", Util.id()),
            in,
            202);
    assertEquals(
        "AI_PARTIAL_CONTENT",
        waitFor(s.user(), t.path("taskId").asText()).path("error_code").asText());
    try (var pdf = new org.apache.pdfbox.pdmodel.PDDocument();
        var out = new ByteArrayOutputStream()) {
      pdf.addPage(new org.apache.pdfbox.pdmodel.PDPage());
      pdf.save(out);
      String file = upload(s.user(), "scanned.pdf", out.toByteArray());
      assertEquals("NO_EXTRACTABLE_TEXT", extraction.extract(s.user().id(), file).status());
    }
    try (var doc = new org.apache.poi.xwpf.usermodel.XWPFDocument();
        var out = new ByteArrayOutputStream()) {
      doc.createParagraph().createRun().setText("中文段落");
      var table = doc.createTable(1, 2);
      table.getRow(0).getCell(0).setText("名称");
      table.getRow(0).getCell(1).setText("数量");
      doc.write(out);
      String file = upload(s.user(), "table.docx", out.toByteArray());
      var extracted = extraction.extract(s.user().id(), file);
      assertTrue(extracted.text().contains("名称 | 数量"));
      assertTrue(extracted.text().contains("段落 1"));
    }
    String invalid = upload(s.user(), "broken.docx", "bad zip".getBytes(StandardCharsets.UTF_8));
    assertEquals(
        "AI_EXTRACTION_FAILED",
        assertThrows(Problem.class, () -> extraction.extract(s.user().id(), invalid)).code);
    call(
        s.user(),
        put("/api/entries/" + longFile + "/tags"),
        Map.of("tags", List.of("current")),
        200);
    call(
        s.user(),
        put("/api/entries/" + longFile + "/tags"),
        Map.of("tags", List.of("suggested"), "expectedTags", List.of()),
        409);
    assertEquals(
        "current",
        call(s.user(), get("/api/entries/" + longFile + "/tags"), null, 200).path(0).asText());
  }

  @Test
  void cancellationCannotPublishSuccessfulResult() throws Exception {
    var s = setup();
    CountDownLatch started = new CountDownLatch(1), release = new CountDownLatch(1);
    when(gateway.chat(any(), anyString(), anyString(), any()))
        .thenAnswer(
            invocation -> {
              Call call = mock(Call.class);
              when(call.timeout()).thenReturn(new okio.Timeout());
              @SuppressWarnings("unchecked")
              Consumer<Call> callback = invocation.getArgument(3, Consumer.class);
              callback.accept(call);
              doAnswer(
                      i -> {
                        release.countDown();
                        return null;
                      })
                  .when(call)
                  .cancel();
              started.countDown();
              release.await(10, TimeUnit.SECONDS);
              return new ModelGateway.Answer("late answer", null, null, null);
            });
    var t =
        call(
            s.user(),
            post("/api/ai/models/" + s.model() + "/test").header("Idempotency-Key", Util.id()),
            null,
            202);
    assertTrue(started.await(5, TimeUnit.SECONDS));
    call(s.user(), post("/api/ai/tasks/" + t.path("taskId").asText() + "/cancel"), null, 202);
    var finalTask = waitFor(s.user(), t.path("taskId").asText());
    assertEquals("CANCELLED", finalTask.path("status").asText());
    assertTrue(finalTask.path("result").isNull());
    assertNull(
        db.one("SELECT total_tokens FROM ai_call WHERE task_id=?", t.path("taskId").asText())
            .get("total_tokens"));
  }

  @Test
  void completionAndCancellationRaceAlwaysLeavesATerminalState() throws Exception {
    var s = setup();
    try (var executor = Executors.newSingleThreadExecutor()) {
      for (int round = 0; round < 8; round++) {
        var started = new CountDownLatch(1);
        var release = new CountDownLatch(1);
        when(gateway.chat(any(), anyString(), anyString(), any()))
            .thenAnswer(
                i -> {
                  started.countDown();
                  assertTrue(release.await(5, TimeUnit.SECONDS));
                  return new ModelGateway.Answer("OK", 1L, 1L, 2L);
                });
        var created =
            call(
                s.user(),
                post("/api/ai/models/" + s.model() + "/test").header("Idempotency-Key", Util.id()),
                null,
                202);
        String id = created.path("taskId").asText();
        assertTrue(started.await(5, TimeUnit.SECONDS));
        var cancelling =
            executor.submit(
                () -> {
                  release.await();
                  return tasks.cancel(s.user().id(), id);
                });
        release.countDown();
        cancelling.get(5, TimeUnit.SECONDS);
        String status = waitFor(s.user(), id).path("status").asText();
        assertTrue(Set.of("SUCCEEDED", "CANCELLED").contains(status), status);
      }
    }
  }

  @Test
  void quotaUnknownUsageAndInvalidTagsAreExplicit() throws Exception {
    var s = setup();
    when(gateway.chat(any(), anyString(), anyString(), any()))
        .thenReturn(new ModelGateway.Answer("OK", null, null, null));
    var t =
        call(
            s.user(),
            post("/api/ai/models/" + s.model() + "/test").header("Idempotency-Key", Util.id()),
            null,
            202);
    assertEquals("SUCCEEDED", waitFor(s.user(), t.path("taskId").asText()).path("status").asText());
    var usage = call(s.user(), get("/api/ai/usage"), null, 200);
    assertTrue(usage.path("summary").path("total_tokens").isNull());
    assertEquals(0, usage.path("summary").path("known_usage_calls").asInt());
    String file = upload(s.user(), "tags.txt", "Some document".getBytes(StandardCharsets.UTF_8));
    long revision = extraction.extract(s.user().id(), file).revision();
    var tags =
        call(
            s.user(),
            post("/api/ai/files/" + file + "/tag-suggestions").header("Idempotency-Key", Util.id()),
            Map.of("modelId", s.model(), "expectedRevision", revision),
            202);
    assertEquals(
        "AI_INVALID_TAGS",
        waitFor(s.user(), tags.path("taskId").asText()).path("error_code").asText());
    assertEquals(0, call(s.user(), get("/api/entries/" + file + "/tags"), null, 200).size());
    int count = (int) db.count("SELECT COUNT(*) FROM ai_call WHERE user_id=?", s.user().id());
    for (int i = count; i < 100; i++)
      db.update(
          "INSERT INTO"
              + " ai_call(id,user_id,task_id,call_index,model_name,status,estimated_input,created_at)"
              + " VALUES(?,?,?,?,?,'UNKNOWN',1,?)",
          Util.id(),
          s.user().id(),
          s.user().id(),
          i,
          "fixture",
          System.currentTimeMillis());
    var limited =
        call(
            s.user(),
            post("/api/ai/models/" + s.model() + "/test").header("Idempotency-Key", Util.id()),
            null,
            202);
    assertEquals(
        "AI_QUOTA_EXCEEDED",
        waitFor(s.user(), limited.path("taskId").asText()).path("error_code").asText());
    assertEquals(100, db.count("SELECT COUNT(*) FROM ai_call WHERE user_id=?", s.user().id()));
  }

  @Test
  void failedFinalAnswerKeepsMeasuredUsageAndDoesNotRetry() throws Exception {
    var s = setup();
    when(gateway.chat(any(), anyString(), anyString(), any()))
        .thenThrow(
            new ModelGateway.ResponseFailure(
                "AI_OUTPUT_LIMIT",
                "模型达到输出上限；不会自动重试",
                new ModelGateway.Answer("", 10L, 256L, 266L)));
    var t =
        call(
            s.user(),
            post("/api/ai/models/" + s.model() + "/test").header("Idempotency-Key", Util.id()),
            null,
            202);
    var finished = waitFor(s.user(), t.path("taskId").asText());
    assertEquals("FAILED", finished.path("status").asText());
    assertEquals("AI_OUTPUT_LIMIT", finished.path("error_code").asText());
    var usage = call(s.user(), get("/api/ai/usage"), null, 200);
    assertEquals(266, usage.path("summary").path("total_tokens").asInt());
    assertEquals(1, usage.path("summary").path("known_usage_calls").asInt());
    var config = org.mockito.ArgumentCaptor.forClass(ModelGateway.Configuration.class);
    verify(gateway, times(1)).chat(config.capture(), anyString(), anyString(), any());
    assertEquals(256, config.getValue().maxOutput());
  }

  @Test
  void revisionChangesAndCredentialRevocationPreventFurtherSending() throws Exception {
    var s = setup();
    String file = upload(s.user(), "revision.md", "source text".getBytes(StandardCharsets.UTF_8));
    long revision = extraction.extract(s.user().id(), file).revision();
    var t =
        call(
            s.user(),
            post("/api/ai/files/" + file + "/summaries").header("Idempotency-Key", Util.id()),
            Map.of("modelId", s.model(), "expectedRevision", revision),
            202);
    assertEquals("SUCCEEDED", waitFor(s.user(), t.path("taskId").asText()).path("status").asText());
    db.update("UPDATE drive_entry SET revision=revision+1 WHERE id=?", file);
    assertEquals(
        "AI_REVISION_CHANGED",
        call(s.user(), get("/api/ai/tasks/" + t.path("taskId").asText()), null, 409)
            .path("code")
            .asText());
    call(
        s.user(),
        post("/api/ai/files/" + file + "/summaries").header("Idempotency-Key", Util.id()),
        Map.of("modelId", s.model(), "expectedRevision", revision),
        409);
    call(s.user(), delete("/api/ai/connections/" + s.connection() + "/credential"), null, 200);
    call(
        s.user(),
        post("/api/ai/models/" + s.model() + "/test").header("Idempotency-Key", Util.id()),
        null,
        403);
    assertEquals(1, db.count("SELECT COUNT(*) FROM ai_call WHERE user_id=?", s.user().id()));
  }

  String conversation(Setup s, String file) throws Exception {
    return call(
            s.user(),
            post("/api/ai/conversations"),
            Map.of(
                "fileId",
                file,
                "modelId",
                s.model(),
                "expectedRevision",
                extraction.extract(s.user().id(), file).revision()),
            200)
        .path("id")
        .asText();
  }

  JsonNode ask(Setup s, String cid, String question, String key, int status) throws Exception {
    return call(
        s.user(),
        post("/api/ai/conversations/" + cid + "/messages").header("Idempotency-Key", key),
        Map.of("question", question),
        status);
  }

  @Test
  void documentQaHistoryCitationsIsolationAndIdempotency() throws Exception {
    var s = setup();
    var other = user();
    String file =
        upload(s.user(), "qa.md", "# 项目\n项目负责人是小王。\n发布日期是六月。".getBytes(StandardCharsets.UTF_8));
    String cid = conversation(s, file);
    verifyNoInteractions(gateway);
    when(gateway.chat(any(), anyString(), anyString(), any()))
        .thenReturn(
            new ModelGateway.Answer(
                "{\"answer\":\"负责人是小王。\",\"citations\":[\"S1\",\"S1\",\"forged-file-id\"]}",
                12L,
                8L,
                20L));
    String key = Util.id();
    var t = ask(s, cid, "负责人是谁？", key, 202);
    String tid = t.path("taskId").asText();
    assertEquals(tid, ask(s, cid, "负责人是谁？", key, 202).path("taskId").asText());
    ask(s, cid, "发布日期？", key, 409);
    assertEquals("SUCCEEDED", waitFor(s.user(), tid).path("status").asText());
    var chat = call(s.user(), get("/api/ai/conversations/" + cid), null, 200);
    assertEquals(2, chat.path("messages").size());
    var answer = chat.path("messages").get(1);
    assertTrue(answer.path("content").asText().contains("小王"));
    assertEquals(1, answer.path("citations").size());
    String ref = answer.path("citations").get(0).path("id").asText();
    var citation =
        call(s.user(), get("/api/ai/conversations/" + cid + "/citations/" + ref), null, 200);
    assertTrue(citation.path("excerpt").asText().contains("小王"));
    assertEquals(file, citation.path("fileId").asText());
    call(null, get("/api/ai/conversations/" + cid), null, 401);
    String different = conversation(s, file);
    call(s.user(), get("/api/ai/conversations/" + different + "/citations/" + ref), null, 404);
    call(other, get("/api/ai/conversations/" + cid), null, 404);
    call(other, get("/api/ai/conversations/" + cid + "/citations/" + ref), null, 404);
    call(other, delete("/api/ai/conversations/" + cid), null, 404);
    call(s.user(), get("/api/ai/conversations/" + cid + "/citations/forged-file-id"), null, 404);
    var second = ask(s, cid, "再说一次\n负责人是谁？", Util.id(), 202);
    assertEquals(
        "SUCCEEDED", waitFor(s.user(), second.path("taskId").asText()).path("status").asText());
    var inputs = org.mockito.ArgumentCaptor.forClass(String.class);
    verify(gateway, times(2)).chat(any(), anyString(), inputs.capture(), any());
    assertTrue(json.readTree(inputs.getAllValues().get(1)).path("history").size() > 0);
    assertTrue(json.readTree(inputs.getAllValues().getFirst()).path("sources").size() > 0);
    assertEquals(2, call(s.user(), get("/api/ai/tasks").param("fileId", file), null, 200).size());
    assertEquals(
        0,
        call(
                s.user(),
                get("/api/ai/tasks").param("fileId", file).param("analysisOnly", "true"),
                null,
                200)
            .size());
    call(s.user(), delete("/api/ai/conversations/" + cid), null, 200);
    call(s.user(), get("/api/ai/tasks/" + tid), null, 404);
    assertEquals(0, db.count("SELECT COUNT(*) FROM ai_message WHERE conversation_id=?", cid));
    assertEquals(0, db.count("SELECT COUNT(*) FROM ai_citation WHERE id=?", ref));
    assertEquals(
        "{}", db.one("SELECT request_json FROM ai_task WHERE id=?", tid).get("request_json"));
    assertEquals(2, db.count("SELECT COUNT(*) FROM ai_call WHERE user_id=?", s.user().id()));
    assertNotNull(extraction.extract(s.user().id(), file));
  }

  @Test
  void qaOldRevisionReadOnlyTrashRestoreAndPermanentPurge() throws Exception {
    var s = setup();
    String file = upload(s.user(), "old.txt", "版本资料".getBytes(StandardCharsets.UTF_8));
    String cid = conversation(s, file);
    when(gateway.chat(any(), anyString(), anyString(), any()))
        .thenReturn(
            new ModelGateway.Answer("{\"answer\":\"版本资料\",\"citations\":[\"S1\"]}", 1L, 1L, 2L));
    String tid = ask(s, cid, "资料？", Util.id(), 202).path("taskId").asText();
    waitFor(s.user(), tid);
    call(s.user(), delete("/api/files/" + file), null, 200);
    call(s.user(), get("/api/ai/conversations/" + cid), null, 404);
    call(s.user(), get("/api/ai/tasks/" + tid), null, 404);
    call(s.user(), post("/api/trash/files/" + file + "/restore"), null, 200);
    assertFalse(
        call(s.user(), get("/api/ai/conversations/" + cid), null, 200)
            .path("readOnly")
            .asBoolean());
    db.update("UPDATE drive_entry SET revision=revision+1 WHERE id=?", file);
    var old = call(s.user(), get("/api/ai/conversations/" + cid), null, 200);
    assertTrue(old.path("readOnly").asBoolean());
    assertEquals(2, old.path("messages").size());
    call(s.user(), get("/api/ai/tasks/" + tid), null, 200);
    ask(s, cid, "新问题", Util.id(), 409);
    verify(gateway, times(1)).chat(any(), anyString(), anyString(), any());
    call(s.user(), delete("/api/files/" + file), null, 200);
    call(s.user(), delete("/api/trash/files/" + file), null, 200);
    assertEquals(0, db.count("SELECT COUNT(*) FROM ai_conversation WHERE id=?", cid));
    assertEquals(0, db.count("SELECT COUNT(*) FROM ai_message WHERE conversation_id=?", cid));
    assertNull(db.one("SELECT result_json FROM ai_task WHERE id=?", tid).get("result_json"));
    assertEquals(1, db.count("SELECT COUNT(*) FROM ai_call WHERE task_id=?", tid));
  }

  @Test
  void qaSingleActiveTaskDeleteDuringGenerationCannotPublish() throws Exception {
    var s = setup();
    String file = upload(s.user(), "cancel.txt", "source".getBytes(StandardCharsets.UTF_8));
    String cid = conversation(s, file);
    var started = new CountDownLatch(1);
    var release = new CountDownLatch(1);
    when(gateway.chat(any(), anyString(), anyString(), any()))
        .thenAnswer(
            i -> {
              started.countDown();
              release.await(10, TimeUnit.SECONDS);
              return new ModelGateway.Answer(
                  "{\"answer\":\"late\",\"citations\":[\"S1\"]}", 1L, 1L, 2L);
            });
    try {
      String tid = ask(s, cid, "source?", Util.id(), 202).path("taskId").asText();
      assertTrue(started.await(5, TimeUnit.SECONDS));
      ask(s, cid, "second", Util.id(), 409);
      call(s.user(), delete("/api/ai/conversations/" + cid), null, 200);
      release.countDown();
      long end = System.nanoTime() + TimeUnit.SECONDS.toNanos(5);
      while (db.count("SELECT COUNT(*) FROM ai_call WHERE task_id=? AND status='STARTED'", tid) > 0
          && System.nanoTime() < end) Thread.sleep(30);
      assertEquals("CANCELLED", db.one("SELECT status FROM ai_task WHERE id=?", tid).get("status"));
      assertNull(db.one("SELECT result_json FROM ai_task WHERE id=?", tid).get("result_json"));
      assertEquals(0, db.count("SELECT COUNT(*) FROM ai_message WHERE conversation_id=?", cid));
    } finally {
      release.countDown();
    }
  }

  @Test
  void qaInvalidAnswerNoEvidenceAndRetentionNeverResend() throws Exception {
    var s = setup();
    String file = upload(s.user(), "answer.txt", "文档原文".getBytes(StandardCharsets.UTF_8));
    String cid = conversation(s, file);
    when(gateway.chat(any(), anyString(), anyString(), any()))
        .thenReturn(
            new ModelGateway.Answer("{\"answer\":\"捏造答案\",\"citations\":[\"FAKE\"]}", 1L, 1L, 2L));
    var t = ask(s, cid, "问题", Util.id(), 202);
    var done = waitFor(s.user(), t.path("taskId").asText());
    assertTrue(done.path("result").path("text").asText().contains("在本次检索的内容中未找到依据"));
    assertTrue(done.path("result").path("sources").isEmpty());
    when(gateway.chat(any(), anyString(), anyString(), any()))
        .thenReturn(new ModelGateway.Answer("not json", 1L, 1L, 2L));
    String previous = t.path("taskId").asText();
    db.update(
        "UPDATE ai_task SET expires_at=? WHERE id=?", System.currentTimeMillis() + 1000, previous);
    t = ask(s, cid, "再问", Util.id(), 202);
    assertTrue(
        ((Number) db.one("SELECT expires_at FROM ai_task WHERE id=?", previous).get("expires_at"))
                .longValue()
            > System.currentTimeMillis() + 29L * 86400000);
    assertEquals(
        "AI_INVALID_ANSWER",
        waitFor(s.user(), t.path("taskId").asText()).path("error_code").asText());
    verify(gateway, times(2)).chat(any(), anyString(), anyString(), any());
    db.update(
        "UPDATE ai_conversation SET updated_at=? WHERE id=?",
        System.currentTimeMillis() - 31L * 86400000,
        cid);
    tasks.retention();
    assertEquals(0, db.count("SELECT COUNT(*) FROM ai_conversation WHERE id=?", cid));
    assertEquals(0, db.count("SELECT COUNT(*) FROM ai_message WHERE conversation_id=?", cid));
    assertNotNull(extraction.extract(s.user().id(), file));
  }

  @Test
  void qaLongDocumentRetrievalNoMatchAndBoundedHistory() throws Exception {
    var s = setup();
    StringBuilder content = new StringBuilder();
    for (int i = 0; i < 1600; i++) content.append("背景资料和普通段落内容第 ").append(i).append(" 行。\n");
    content.append("Aurora launch code is BLUE.\n");
    String file =
        upload(s.user(), "longqa.txt", content.toString().getBytes(StandardCharsets.UTF_8));
    String cid = conversation(s, file);
    when(gateway.chat(any(), anyString(), anyString(), any()))
        .thenAnswer(
            i -> {
              var input = json.readTree((String) i.getArgument(2));
              String source = input.path("sources").get(0).path("sourceId").asText();
              return new ModelGateway.Answer(
                  json.writeValueAsString(Map.of("answer", "BLUE", "citations", List.of(source))),
                  1L,
                  1L,
                  2L);
            });
    var t = ask(s, cid, "Aurora code?", Util.id(), 202);
    var done = waitFor(s.user(), t.path("taskId").asText());
    assertEquals("SUCCEEDED", done.path("status").asText());
    assertTrue(done.path("coverage").path("partial").asBoolean());
    var input = org.mockito.ArgumentCaptor.forClass(String.class);
    verify(gateway).chat(any(), anyString(), input.capture(), any());
    assertTrue(input.getValue().contains("BLUE"));
    assertTrue(input.getValue().length() < 24000);
    assertTrue(done.path("coverage").path("selectedCharacters").asInt() < content.length());
    String noMatch = conversation(s, file);
    t = ask(s, noMatch, "zxqvnomatch?", Util.id(), 202);
    done = waitFor(s.user(), t.path("taskId").asText());
    assertTrue(done.path("result").path("text").asText().contains("在本次检索的内容中未找到依据"));
    assertEquals(
        0, db.count("SELECT COUNT(*) FROM ai_call WHERE task_id=?", t.path("taskId").asText()));
    for (int i = 0; i < 7; i++) {
      t = ask(s, cid, "Aurora code " + i, Util.id(), 202);
      assertEquals(
          "SUCCEEDED", waitFor(s.user(), t.path("taskId").asText()).path("status").asText());
    }
    input = org.mockito.ArgumentCaptor.forClass(String.class);
    verify(gateway, times(8)).chat(any(), anyString(), input.capture(), any());
    assertEquals(6, json.readTree(input.getAllValues().getLast()).path("history").size());
    db.update("UPDATE ai_model SET config_version=config_version+1 WHERE id=?", s.model());
    assertTrue(
        call(s.user(), get("/api/ai/conversations/" + cid), null, 200)
            .path("readOnly")
            .asBoolean());
    ask(s, cid, "more", Util.id(), 409);
  }

  String embeddingModel(Setup s) throws Exception {
    return call(
            s.user(),
            post("/api/ai/models"),
            Map.of(
                "connectionId",
                s.connection(),
                "name",
                "Vector fixture",
                "modelId",
                "vector-fixture",
                "purpose",
                "EMBEDDING",
                "contextLimit",
                8192,
                "embeddingDimensions",
                3,
                "maxBatchSize",
                16,
                "enabled",
                true),
            200)
        .path("id")
        .asText();
  }

  @Test
  void embeddingModelsAreSeparateAndTestIsPrivateIdempotentAndMetered() throws Exception {
    var s = setup();
    var other = user();
    String mid = embeddingModel(s);
    verifyNoInteractions(gateway, embeddings);
    call(s.user(), put("/api/ai/preferences"), Map.of("defaultChatModelId", mid), 400);
    call(
        s.user(),
        post("/api/ai/models/" + mid + "/test").header("Idempotency-Key", Util.id()),
        null,
        400);
    call(
        s.user(),
        post("/api/ai/models/" + s.model() + "/embedding-test")
            .header("Idempotency-Key", Util.id()),
        null,
        400);
    call(
        other,
        post("/api/ai/models/" + mid + "/embedding-test").header("Idempotency-Key", Util.id()),
        null,
        404);
    String file =
        upload(s.user(), "embedding-purpose.txt", "original text".getBytes(StandardCharsets.UTF_8));
    call(s.user(), get("/api/ai/files/" + file + "/analysis").param("modelId", mid), null, 400);
    when(embeddings.embed(any(), anyList(), any()))
        .thenReturn(
            new EmbeddingGateway.Answer(
                List.of(new float[] {1, 0, 0}, new float[] {0, 1, 0}), 12L, 12L));
    String key = Util.id();
    var t =
        call(
            s.user(),
            post("/api/ai/models/" + mid + "/embedding-test").header("Idempotency-Key", key),
            null,
            202);
    String tid = t.path("taskId").asText();
    assertEquals(
        tid,
        call(
                s.user(),
                post("/api/ai/models/" + mid + "/embedding-test").header("Idempotency-Key", key),
                null,
                202)
            .path("taskId")
            .asText());
    var done = waitFor(s.user(), tid);
    assertEquals("SUCCEEDED", done.path("status").asText());
    assertEquals(3, done.path("result").path("dimensions").asInt());
    assertEquals(2, done.path("result").path("vectorCount").asInt());
    assertFalse(done.path("result").has("vectors"));
    call(other, get("/api/ai/tasks/" + tid), null, 404);
    var usage = call(s.user(), get("/api/ai/usage"), null, 200);
    assertEquals("EMBEDDING", usage.path("items").get(0).path("purpose").asText());
    assertTrue(usage.path("items").get(0).path("output_tokens").isNull());
    assertEquals(12, usage.path("summary").path("total_tokens").asInt());
    assertEquals("SUCCEEDED", configs.model(s.user().id(), mid).get("last_test_status"));
    assertEquals(
        "UNTESTED", configs.connection(s.user().id(), s.connection()).get("last_test_status"));
    var input = org.mockito.ArgumentCaptor.forClass(EmbeddingGateway.Configuration.class);
    verify(embeddings, times(1))
        .embed(
            input.capture(),
            eq(
                List.of(
                    "CloudNest vector test: a blue notebook.",
                    "CloudNest vector test: a green folder.")),
            any());
    assertFalse(input.getValue().requestDimensions());
    verifyNoInteractions(gateway);
    call(
        s.user(),
        patch("/api/ai/models/" + mid),
        Map.of("configVersion", 1, "purpose", "CHAT"),
        400);
    call(
        s.user(),
        patch("/api/ai/models/" + mid),
        Map.of("configVersion", 1, "embeddingDimensions", 4),
        200);
    assertEquals("UNTESTED", configs.model(s.user().id(), mid).get("last_test_status"));
  }

  @Test
  void invalidEmbeddingResponsePreservesUsageAndRevocationCancelsLateTest() throws Exception {
    var s = setup();
    String mid = embeddingModel(s);
    when(embeddings.embed(any(), anyList(), any()))
        .thenThrow(
            new ModelGateway.ResponseFailure(
                "AI_EMBEDDING_DIMENSION_MISMATCH",
                "维度不匹配",
                new ModelGateway.Answer("", 9L, null, 9L)));
    var t =
        call(
            s.user(),
            post("/api/ai/models/" + mid + "/embedding-test").header("Idempotency-Key", Util.id()),
            null,
            202);
    var done = waitFor(s.user(), t.path("taskId").asText());
    assertEquals("AI_EMBEDDING_DIMENSION_MISMATCH", done.path("error_code").asText());
    assertEquals("FAILED", configs.model(s.user().id(), mid).get("last_test_status"));
    assertEquals(
        9,
        call(s.user(), get("/api/ai/usage"), null, 200)
            .path("summary")
            .path("total_tokens")
            .asInt());
    var started = new CountDownLatch(1);
    var release = new CountDownLatch(1);
    doAnswer(
            i -> {
              Call call = mock(Call.class);
              when(call.timeout()).thenReturn(new okio.Timeout());
              doAnswer(
                      x -> {
                        release.countDown();
                        return null;
                      })
                  .when(call)
                  .cancel();
              ((Consumer<Call>) i.getArgument(2)).accept(call);
              started.countDown();
              release.await(10, TimeUnit.SECONDS);
              return new EmbeddingGateway.Answer(
                  List.of(new float[] {1, 0, 0}, new float[] {0, 1, 0}), 2L, 2L);
            })
        .when(embeddings)
        .embed(any(), anyList(), any());
    try {
      t =
          call(
              s.user(),
              post("/api/ai/models/" + mid + "/embedding-test")
                  .header("Idempotency-Key", Util.id()),
              null,
              202);
      assertTrue(started.await(5, TimeUnit.SECONDS));
      call(s.user(), delete("/api/ai/connections/" + s.connection() + "/credential"), null, 200);
      done = waitFor(s.user(), t.path("taskId").asText());
      assertEquals("CANCELLED", done.path("status").asText());
      assertTrue(done.path("result").isNull());
      assertEquals("UNTESTED", configs.model(s.user().id(), mid).get("last_test_status"));
    } finally {
      release.countDown();
    }
    verifyNoInteractions(gateway);
  }

  @Test
  void cancelledKnowledgeBatchAndRestartNeverPublishOrRepeatPaidWork() throws Exception {
    var s = setup();
    String mid = embeddingModel(s);
    db.update("UPDATE ai_model SET max_batch_size=1 WHERE id=?", mid);
    String file =
        upload(s.user(), "cancel-index.txt", "资料".repeat(1000).getBytes(StandardCharsets.UTF_8));
    String id = kb(s, mid, List.of(file)).path("id").asText();
    var started = new CountDownLatch(1);
    var release = new CountDownLatch(1);
    var count = new java.util.concurrent.atomic.AtomicInteger();
    doAnswer(
            i -> {
              int number = count.incrementAndGet();
              Call upstream = mock(Call.class);
              when(upstream.timeout()).thenReturn(new okio.Timeout());
              doAnswer(
                      x -> {
                        release.countDown();
                        return null;
                      })
                  .when(upstream)
                  .cancel();
              ((Consumer<Call>) i.getArgument(2)).accept(upstream);
              if (number == 2) {
                started.countDown();
                release.await(10, TimeUnit.SECONDS);
              }
              return new EmbeddingGateway.Answer(List.of(new float[] {1, 0, 0}), 3L, 3L);
            })
        .when(embeddings)
        .embed(any(), anyList(), any());
    var preview =
        call(s.user(), get("/api/ai/knowledge-bases/" + id + "/index-preview"), null, 200);
    var job =
        call(
            s.user(),
            post("/api/ai/knowledge-bases/" + id + "/index-jobs")
                .header("Idempotency-Key", Util.id()),
            Map.of("modelId", mid, "expectedDigest", preview.path("digest").asText()),
            202);
    String tid = job.path("id").asText();
    try {
      assertTrue(started.await(5, TimeUnit.SECONDS));
      call(s.user(), post("/api/ai/tasks/" + tid + "/cancel"), null, 202);
      assertEquals("CANCELLED", waitFor(s.user(), tid).path("status").asText());
      assertEquals(
          1,
          db.count(
              "SELECT COUNT(*) FROM ai_index_chunk WHERE kb_id=? AND vector_data IS NOT NULL", id));
      assertNull(
          db.one("SELECT active_generation FROM ai_knowledge_base WHERE id=?", id)
              .get("active_generation"));
      assertEquals(2, count.get());
    } finally {
      release.countDown();
    }
    db.update("UPDATE ai_task SET status='RUNNING',cancel_requested=FALSE WHERE id=?", tid);
    db.update(
        "UPDATE ai_index_batch SET status='SENDING' WHERE task_id=? AND status='UNKNOWN'", tid);
    org.springframework.test.util.ReflectionTestUtils.invokeMethod(tasks, "recover");
    assertEquals(
        "AI_INTERRUPTED",
        db.one("SELECT error_code FROM ai_task WHERE id=?", tid).get("error_code"));
    assertEquals(
        1,
        db.count("SELECT COUNT(*) FROM ai_index_batch WHERE task_id=? AND status='UNKNOWN'", tid));
    assertEquals(2, count.get());
    mockVectors();
    clearInvocations(embeddings);
    assertEquals("SUCCEEDED", index(s, id, mid).path("status").asText());
    verify(embeddings, times(2)).embed(any(), anyList(), any());
  }

  @Test
  void knowledgeRetentionAndDisabledAiLeaveSourceFilesIntact() throws Exception {
    var s = setup();
    String mid = embeddingModel(s);
    mockVectors();
    String file = upload(s.user(), "retention-kb.md", "保留源文件".getBytes(StandardCharsets.UTF_8));
    String id = kb(s, mid, List.of(file)).path("id").asText();
    assertEquals("SUCCEEDED", index(s, id, mid).path("status").asText());
    String sid =
        call(
                s.user(),
                post("/api/ai/knowledge-bases/" + id + "/sessions"),
                Map.of("modelId", s.model()),
                200)
            .path("id")
            .asText();
    db.update(
        "UPDATE ai_kb_session SET updated_at=? WHERE id=?",
        System.currentTimeMillis() - 31L * 86400000,
        sid);
    tasks.retention();
    assertEquals(0, db.count("SELECT COUNT(*) FROM ai_kb_session WHERE id=?", sid));
    try {
      org.springframework.test.util.ReflectionTestUtils.setField(configs, "enabled", false);
      call(
          s.user(),
          post("/api/ai/knowledge-bases/" + id + "/semantic-search")
              .header("Idempotency-Key", Util.id()),
          Map.of("modelId", mid, "question", "问题"),
          403);
      call(s.user(), delete("/api/ai/knowledge-bases/" + id), null, 200);
      assertEquals(
          1, db.count("SELECT COUNT(*) FROM drive_entry WHERE id=? AND deleted=FALSE", file));
    } finally {
      org.springframework.test.util.ReflectionTestUtils.setField(configs, "enabled", true);
    }
  }

  void mockVectors() {
    doAnswer(
            i -> {
              List<String> texts = i.getArgument(1);
              return new EmbeddingGateway.Answer(
                  texts.stream().map(t -> new float[] {1, 0, 0}).toList(), 5L, 5L);
            })
        .when(embeddings)
        .embed(any(), anyList(), any());
  }

  JsonNode kb(Setup s, String mid, List<String> scopes) throws Exception {
    return call(
        s.user(),
        post("/api/ai/knowledge-bases"),
        Map.of("name", "资料库", "embeddingModelId", mid, "scopeIds", scopes),
        200);
  }

  JsonNode kbJob(Setup s, String id, String action, Map<String, Object> input) throws Exception {
    var job =
        call(
            s.user(),
            post("/api/ai/knowledge-bases/" + id + "/" + action)
                .header("Idempotency-Key", Util.id()),
            input,
            202);
    return waitFor(s.user(), job.path("id").asText());
  }

  JsonNode index(Setup s, String id, String mid) throws Exception {
    var preview =
        call(s.user(), get("/api/ai/knowledge-bases/" + id + "/index-preview"), null, 200);
    return kbJob(
        s,
        id,
        "index-jobs",
        Map.of(
            "modelId",
            mid,
            "expectedDigest",
            preview.path("digest").asText(),
            "allowPartial",
            true));
  }

  @Test
  void knowledgeIndexSearchAnswerAndIncrementalReuseAreScoped() throws Exception {
    var s = setup();
    String mid = embeddingModel(s);
    mockVectors();
    String file = upload(s.user(), "blue.md", "蓝色笔记本位于书桌。".getBytes(StandardCharsets.UTF_8));
    String otherFile = upload(s.user(), "green.md", "绿色文件夹位于柜子。".getBytes(StandardCharsets.UTF_8));
    String id = kb(s, mid, List.of(file, otherFile)).path("id").asText();
    var foreign = user();
    call(foreign, get("/api/ai/knowledge-bases/" + id + "/index-preview"), null, 404);
    verifyNoInteractions(embeddings);
    assertEquals("SUCCEEDED", index(s, id, mid).path("status").asText());
    assertEquals(
        2,
        db.count(
            "SELECT COUNT(*) FROM ai_index_chunk WHERE kb_id=? AND vector_data IS NOT NULL", id));
    clearInvocations(embeddings);
    assertEquals("SUCCEEDED", index(s, id, mid).path("status").asText());
    verifyNoInteractions(embeddings);
    var search = kbJob(s, id, "semantic-search", Map.of("modelId", mid, "question", "笔记本在哪里"));
    assertEquals("SUCCEEDED", search.path("status").asText());
    assertEquals(2, search.path("result").path("sources").size());
    String source = search.path("result").path("sources").get(0).path("sourceId").asText();
    var session =
        call(
            s.user(),
            post("/api/ai/knowledge-bases/" + id + "/sessions"),
            Map.of("modelId", s.model()),
            200);
    when(gateway.chat(any(), anyString(), anyString(), any()))
        .thenReturn(
            new ModelGateway.Answer(
                "{\"answer\":\"请核对资料中的位置\",\"citations\":[\"" + source + "\",\"forged\"]}",
                10L,
                4L,
                14L));
    var answer =
        kbJob(
            s,
            id,
            "answers",
            Map.of(
                "modelId",
                s.model(),
                "question",
                "笔记本在哪里",
                "searchTaskId",
                search.path("id").asText(),
                "sessionId",
                session.path("id").asText()));
    assertEquals("SUCCEEDED", answer.path("status").asText());
    assertTrue(answer.path("result").path("invalidCitations").asBoolean());
    assertEquals(1, answer.path("result").path("sources").size());
    assertEquals(
        1,
        call(s.user(), get("/api/ai/knowledge-sessions/" + session.path("id").asText()), null, 200)
            .size());
    call(foreign, get("/api/ai/tasks/" + answer.path("id").asText()), null, 404);
    db.update("UPDATE ai_model SET config_version=config_version+1 WHERE id=?", s.model());
    call(
        s.user(),
        post("/api/ai/knowledge-bases/" + id + "/answers").header("Idempotency-Key", Util.id()),
        Map.of(
            "modelId",
            s.model(),
            "question",
            "笔记本在哪里",
            "searchTaskId",
            search.path("id").asText(),
            "sessionId",
            session.path("id").asText()),
        409);
    assertTrue(
        call(s.user(), get("/api/ai/knowledge-bases/" + id + "/sessions"), null, 200)
            .get(0)
            .path("readOnly")
            .asBoolean());

    db.update("UPDATE drive_entry SET revision=revision+1 WHERE id=?", file);
    call(s.user(), get("/api/ai/tasks/" + search.path("id").asText()), null, 409);
    clearInvocations(embeddings);
    assertEquals("SUCCEEDED", index(s, id, mid).path("status").asText());
    verify(embeddings, times(1)).embed(any(), argThat(texts -> texts.size() == 1), any());
    call(s.user(), delete("/api/ai/knowledge-bases/" + id), null, 200);
    assertEquals(0, db.count("SELECT COUNT(*) FROM ai_index_chunk WHERE kb_id=?", id));
    assertEquals(
        2, db.count("SELECT COUNT(*) FROM drive_entry WHERE id IN (?,?)", file, otherFile));
  }

  @Test
  void knowledgeModelChangesAndMovedOrDeletedFilesCannotLeakOldVectors() throws Exception {
    var s = setup();
    String mid = embeddingModel(s);
    mockVectors();
    String folder =
        call(s.user(), post("/api/folders"), Map.of("name", "资料", "parentId", s.user().root()), 200)
            .path("id")
            .asText();
    String file = upload(s.user(), "move.md", "文件夹范围".getBytes(StandardCharsets.UTF_8));
    call(s.user(), patch("/api/files/" + file), Map.of("parentId", folder), 200);
    String id = kb(s, mid, List.of(folder)).path("id").asText();
    assertEquals("SUCCEEDED", index(s, id, mid).path("status").asText());
    call(s.user(), patch("/api/files/" + file), Map.of("parentId", s.user().root()), 200);
    var empty = kbJob(s, id, "semantic-search", Map.of("modelId", mid, "question", "资料"));
    assertTrue(empty.path("result").path("sources").isEmpty());
    call(s.user(), patch("/api/files/" + file), Map.of("parentId", folder), 200);
    var change = new LinkedHashMap<String, Object>();
    change.put("connectionId", s.connection());
    change.put("name", "Changed");
    change.put("modelId", "vector-new");
    change.put("purpose", "EMBEDDING");
    change.put("contextLimit", 8192);
    change.put("embeddingDimensions", 3);
    change.put("maxBatchSize", 16);
    change.put("enabled", true);
    change.put("configVersion", 1);
    call(s.user(), patch("/api/ai/models/" + mid), change, 200);
    call(
        s.user(),
        post("/api/ai/knowledge-bases/" + id + "/semantic-search")
            .header("Idempotency-Key", Util.id()),
        Map.of("modelId", mid, "question", "资料"),
        409);
    assertEquals("SUCCEEDED", index(s, id, mid).path("status").asText());
    call(s.user(), delete("/api/files/" + file), null, 200);
    var hidden = kbJob(s, id, "semantic-search", Map.of("modelId", mid, "question", "资料"));
    assertTrue(hidden.path("result").path("sources").isEmpty());
    call(s.user(), delete("/api/trash/files/" + file), null, 200);
    assertEquals(0, db.count("SELECT COUNT(*) FROM ai_index_chunk WHERE file_id=?", file));
  }

  @Test
  void knowledgeFailedBatchKeepsCompletedWorkWithoutAutomaticPaidRetry() throws Exception {
    var s = setup();
    String mid = embeddingModel(s);
    db.update("UPDATE ai_model SET max_batch_size=1 WHERE id=?", mid);
    String file =
        upload(s.user(), "batches.txt", ("分块内容".repeat(500)).getBytes(StandardCharsets.UTF_8));
    String id = kb(s, mid, List.of(file)).path("id").asText();
    var count = new java.util.concurrent.atomic.AtomicInteger();
    doAnswer(
            i -> {
              if (count.incrementAndGet() == 2)
                throw new Problem(502, "AI_UPSTREAM_UNAVAILABLE", "模拟失败");
              return new EmbeddingGateway.Answer(List.of(new float[] {1, 0, 0}), 3L, 3L);
            })
        .when(embeddings)
        .embed(any(), anyList(), any());
    assertEquals("FAILED", index(s, id, mid).path("status").asText());
    assertEquals(2, count.get());
    assertEquals(
        1,
        db.count(
            "SELECT COUNT(*) FROM ai_index_chunk WHERE kb_id=? AND vector_data IS NOT NULL", id));
    assertEquals(
        1,
        db.count(
            "SELECT COUNT(*) FROM ai_index_batch WHERE generation_id IN (SELECT id FROM"
                + " ai_index_generation WHERE kb_id=?) AND status='UNKNOWN'",
            id));
    mockVectors();
    clearInvocations(embeddings);
    assertEquals("SUCCEEDED", index(s, id, mid).path("status").asText());
    verify(embeddings, times(2)).embed(any(), anyList(), any());
  }

  @Test
  void versionSummaryUsesDeterministicDiffAndRechecksDeletedVersions() throws Exception {
    var s = setup();
    String file = upload(s.user(), "history.md", "负责人：小王\n时间：六月".getBytes(StandardCharsets.UTF_8));
    String replacement =
        upload(s.user(), "new.md", "负责人：小李\n时间：六月".getBytes(StandardCharsets.UTF_8));
    String vector = embeddingModel(s);
    mockVectors();
    String consumedKb = kb(s, vector, List.of(replacement)).path("id").asText();
    String consumedTask = index(s, consumedKb, vector).path("id").asText();
    call(
        s.user(),
        post("/api/files/" + file + "/versions"),
        Map.of("sourceId", replacement, "expectedRevision", 1),
        200);
    assertEquals(0, db.count("SELECT COUNT(*) FROM ai_index_chunk WHERE file_id=?", replacement));
    assertNull(
        db.one("SELECT result_json FROM ai_task WHERE id=?", consumedTask).get("result_json"));
    String version =
        call(s.user(), get("/api/files/" + file + "/versions"), null, 200)
            .path("items")
            .get(0)
            .path("id")
            .asText();
    var preview =
        call(
            s.user(),
            get("/api/ai/files/" + file + "/change-preview")
                .param("fromVersion", version)
                .param("toVersion", "current"),
            null,
            200);
    assertEquals(2, preview.path("changes").size());
    verifyNoInteractions(gateway);
    var job =
        call(
            s.user(),
            post("/api/ai/files/" + file + "/change-summaries")
                .header("Idempotency-Key", Util.id()),
            Map.of(
                "modelId",
                s.model(),
                "expectedRevision",
                2,
                "fromVersion",
                version,
                "toVersion",
                "current",
                "expectedDigest",
                preview.path("digest").asText()),
            202);
    assertEquals("SUCCEEDED", waitFor(s.user(), job.path("id").asText()).path("status").asText());
    verify(gateway)
        .chat(
            any(),
            contains("确定性版本差异"),
            argThat(t -> t.contains("小王") && t.contains("小李") && !t.contains("六月")),
            any());
    call(s.user(), delete("/api/files/" + file + "/versions/" + version), null, 200);
    call(s.user(), get("/api/ai/tasks/" + job.path("id").asText()), null, 404);
  }

  @Test
  void organizationRequiresSelectedActionsAndHandlesConflictsPerFile() throws Exception {
    var s = setup();
    String one = upload(s.user(), "one.md", "第一份资料".getBytes(StandardCharsets.UTF_8));
    String two = upload(s.user(), "two.md", "第二份资料".getBytes(StandardCharsets.UTF_8));
    String target =
        call(s.user(), post("/api/folders"), Map.of("name", "目标目录"), 200).path("id").asText();
    var body =
        Map.<String, Object>of("fileIds", List.of(one, two), "targetFolderIds", List.of(target));
    var preview = call(s.user(), post("/api/ai/organization-preview"), body, 200);
    when(gateway.chat(any(), anyString(), anyString(), any()))
        .thenReturn(
            new ModelGateway.Answer(
                json.writeValueAsString(
                    List.of(
                        Map.of(
                            "fileId",
                            one,
                            "name",
                            "renamed.md",
                            "tags",
                            List.of("new"),
                            "targetFolderId",
                            target,
                            "reason",
                            "部分资料建议"),
                        Map.of(
                            "fileId",
                            two,
                            "name",
                            "other.md",
                            "tags",
                            List.of("tag"),
                            "targetFolderId",
                            target,
                            "reason",
                            "第二份建议"))),
                8L,
                8L,
                16L));
    var request = new LinkedHashMap<>(body);
    request.put("modelId", s.model());
    request.put("expectedDigest", preview.path("digest").asText());
    request.put("allowPartial", true);
    var job =
        call(
            s.user(),
            post("/api/ai/organization-jobs").header("Idempotency-Key", Util.id()),
            request,
            202);
    String tid = job.path("id").asText();
    assertEquals("SUCCEEDED", waitFor(s.user(), tid).path("status").asText());
    assertEquals("one.md", db.one("SELECT name FROM drive_entry WHERE id=?", one).get("name"));
    call(s.user(), put("/api/entries/" + two + "/tags"), Map.of("tags", List.of("changed")), 200);
    var apply =
        Map.of(
            "selections",
            List.of(
                Map.of("fileId", one, "applyName", true, "applyTags", true, "applyMove", false),
                Map.of("fileId", two, "applyMove", true)));
    var results = call(s.user(), post("/api/ai/tasks/" + tid + "/apply-organization"), apply, 200);
    assertEquals("APPLIED", results.get(0).path("status").asText());
    assertEquals("FAILED", results.get(1).path("status").asText());
    assertEquals("renamed.md", db.one("SELECT name FROM drive_entry WHERE id=?", one).get("name"));
    assertEquals(
        s.user().root(),
        db.one("SELECT parent_id FROM drive_entry WHERE id=?", one).get("parent_id"));
    assertEquals(
        "ALREADY_APPLIED",
        call(s.user(), post("/api/ai/tasks/" + tid + "/apply-organization"), apply, 200)
            .get(0)
            .path("status")
            .asText());
    call(user(), post("/api/ai/tasks/" + tid + "/apply-organization"), apply, 404);
  }
}
