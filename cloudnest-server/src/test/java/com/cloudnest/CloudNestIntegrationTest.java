package com.cloudnest;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;

import com.cloudnest.common.*;
import com.cloudnest.storage.ObjectStorage;
import com.cloudnest.upload.UploadService;
import com.fasterxml.jackson.databind.*;
import jakarta.servlet.http.Cookie;
import java.io.*;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.util.*;
import java.util.concurrent.*;
import org.junit.jupiter.api.*;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.mock.web.MockMultipartFile;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.context.bean.override.mockito.MockitoSpyBean;
import org.springframework.test.web.servlet.*;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;

@SpringBootTest(
    properties = {
      "spring.datasource.url=jdbc:h2:mem:test;MODE=MySQL;DATABASE_TO_LOWER=TRUE;DB_CLOSE_DELAY=-1",
      "cloudnest.part-size=1024",
      "cloudnest.index-enabled=false"
    })
@AutoConfigureMockMvc
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class CloudNestIntegrationTest {
  @Autowired MockMvc mvc;
  @Autowired ObjectMapper json;
  @Autowired Db db;
  @Autowired UploadService uploads;
  @MockitoSpyBean ObjectStorage storage;
  @MockitoSpyBean org.springframework.jdbc.core.JdbcTemplate jdbc;
  static Path data;

  static {
    try {
      data = Files.createTempDirectory("cloudnest-test-");
    } catch (IOException e) {
      throw new ExceptionInInitializerError(e);
    }
  }

  @DynamicPropertySource
  static void settings(DynamicPropertyRegistry r) {
    r.add("cloudnest.data-dir", () -> data.toString());
    r.add("cloudnest.updater-dir", () -> data.resolve("updater").toString());
    String url = System.getenv("CN_TEST_DATABASE_URL");
    if (url != null) {
      r.add("spring.datasource.url", () -> url);
      r.add("spring.datasource.username", () -> "root");
      r.add("spring.datasource.password", () -> "");
    }
  }

  record User(String id, String root, Cookie cookie) {}

  User a, b;

  @BeforeAll
  void setup() throws Exception {
    a = register("alice");
    b = register("bob");
  }

  User register(String prefix) throws Exception {
    String username = prefix + UUID.randomUUID().toString().substring(0, 8);
    var result =
        mvc.perform(
                post("/api/auth/register")
                    .with(csrf())
                    .with(
                        r -> {
                          r.setRemoteAddr(Util.id());
                          return r;
                        })
                    .contentType("application/json")
                    .content(
                        json.writeValueAsBytes(
                            Map.of("username", username, "password", "correct-password"))))
            .andReturn();
    assertEquals(200, result.getResponse().getStatus(), result.getResponse().getContentAsString());
    var u = read(result);
    return new User(
        u.path("id").asText(),
        u.path("rootId").asText(),
        result.getResponse().getCookie("CN_AUTH"));
  }

  JsonNode read(MvcResult result) throws Exception {
    String text = result.getResponse().getContentAsString(StandardCharsets.UTF_8);
    return text.isBlank() ? json.createObjectNode() : json.readTree(text);
  }

  MvcResult call(User u, MockHttpServletRequestBuilder request, Object body, int expected)
      throws Exception {
    if (u != null) request.cookie(u.cookie);
    if (body != null) request.contentType("application/json").content(json.writeValueAsBytes(body));
    var r = mvc.perform(request.with(csrf())).andReturn();
    assertEquals(
        expected,
        r.getResponse().getStatus(),
        r.getResponse().getContentAsString(StandardCharsets.UTF_8));
    return r;
  }

  String folder(User u, String parent, String name) throws Exception {
    return read(call(u, post("/api/folders"), Map.of("parentId", parent, "name", name), 200))
        .path("id")
        .asText();
  }

  String unique() {
    return Util.id().substring(0, 8);
  }

  JsonNode init(User u, String parent, String name, byte[] bytes) throws Exception {
    return read(
        call(
            u,
            post("/api/uploads/init"),
            Map.of(
                "folderId",
                parent,
                "name",
                name,
                "size",
                bytes.length,
                "sha256",
                Util.hash(new ByteArrayInputStream(bytes))),
            200));
  }

  String upload(User u, String parent, String name, byte[] bytes) throws Exception {
    var r = init(u, parent, name, bytes);
    String id = r.path("id").asText();
    if (!r.path("status").asText().equals("COMPLETE")) {
      int part = r.path("partSize").asInt(), count = r.path("partCount").asInt();
      for (int i = 0; i < count; i++) {
        byte[] p = Arrays.copyOfRange(bytes, i * part, Math.min(bytes.length, (i + 1) * part));
        call(
            u,
            put("/api/uploads/" + id + "/parts/" + i)
                .contentType("application/octet-stream")
                .content(p),
            null,
            200);
      }
      r = read(call(u, post("/api/uploads/" + id + "/complete"), null, 200));
    }
    return r.path("fileId").asText();
  }

  byte[] text(String v) {
    return v.getBytes(StandardCharsets.UTF_8);
  }

  @Test
  void CORE01_copyPreservesContentsTagsAndQuotaAndRejectsOtherUsers() throws Exception {
    var u = register("copy");
    String source = folder(u, u.root, "source"), target = folder(u, u.root, "target");
    String file = upload(u, source, "note.md", text("copy me"));
    call(
        u,
        put("/api/entries/" + file + "/tags"),
        Map.of("tags", List.of("Important", "important")),
        200);
    long before =
        Db.n(db.one("SELECT storage_used FROM sys_user WHERE id=?", u.id), "storage_used");
    var result =
        read(
            call(
                u,
                post("/api/entries/copy"),
                Map.of("ids", List.of(source, file), "parentId", target),
                200));
    assertEquals(2, result.path("count").asInt());
    assertEquals(
        before + 7,
        Db.n(db.one("SELECT storage_used FROM sys_user WHERE id=?", u.id), "storage_used"));
    var repeated =
        read(
            call(
                u,
                post("/api/entries/copy"),
                Map.of("ids", List.of(file), "parentId", source),
                200));
    String repeatedId = repeated.path("ids").get(0).asText();
    assertEquals(
        "note (副本 1).md",
        Db.s(db.one("SELECT name FROM drive_entry WHERE id=?", repeatedId), "name"));
    assertEquals(
        1,
        db.count(
            "SELECT COUNT(*) FROM entry_tag WHERE entry_id=? AND tag='important'", repeatedId));
    call(b, post("/api/entries/copy"), Map.of("ids", List.of(source), "parentId", b.root), 404);
    call(u, post("/api/entries/copy"), Map.of("ids", List.of(source), "parentId", source), 400);
    call(u, get("/api/search").param("tag", "important"), null, 200);
  }

  @Test
  void CORE02_overwriteKeepsEntryIdAndPreviousVersionAndRejectsStaleRevision() throws Exception {
    var u = register("overwrite");
    String file = upload(u, u.root, "keep.txt", text("old"));
    long revision = Db.n(db.one("SELECT revision FROM drive_entry WHERE id=?", file), "revision");
    byte[] bytes = text("new content");
    var job =
        read(
            call(
                u,
                post("/api/uploads/init"),
                Map.of(
                    "folderId",
                    u.root,
                    "name",
                    "keep.txt",
                    "size",
                    bytes.length,
                    "sha256",
                    Util.hash(new ByteArrayInputStream(bytes)),
                    "targetId",
                    file,
                    "expectedRevision",
                    revision),
                200));
    if (!job.path("status").asText().equals("COMPLETE")) {
      String id = job.path("id").asText();
      call(
          u,
          put("/api/uploads/" + id + "/parts/0")
              .contentType("application/octet-stream")
              .content(bytes),
          null,
          200);
      job = read(call(u, post("/api/uploads/" + id + "/complete"), null, 200));
    }
    assertEquals(file, job.path("fileId").asText());
    assertEquals(1, db.count("SELECT COUNT(*) FROM file_version WHERE entry_id=?", file));
    assertEquals(
        14, Db.n(db.one("SELECT storage_used FROM sys_user WHERE id=?", u.id), "storage_used"));
    call(
        u,
        post("/api/uploads/init"),
        Map.of(
            "folderId",
            u.root,
            "name",
            "keep.txt",
            "size",
            3,
            "sha256",
            Util.hash(new ByteArrayInputStream(text("old"))),
            "targetId",
            file,
            "expectedRevision",
            revision),
        409);
  }

  @Test
  void CORE03_webDavAppCredentialsAreIsolatedReadOnlyAndRevocable() throws Exception {
    var u = register("dav");
    String username = Db.s(db.one("SELECT username FROM sys_user WHERE id=?", u.id), "username");
    var credential =
        read(
            call(
                u,
                post("/api/user/access/dav"),
                Map.of(
                    "label",
                    "test reader",
                    "readOnly",
                    true,
                    "days",
                    1,
                    "password",
                    "correct-password"),
                200));
    String basic =
        "Basic "
            + Base64.getEncoder()
                .encodeToString(
                    (username + ":" + credential.path("password").asText())
                        .getBytes(StandardCharsets.UTF_8));
    var list =
        mvc.perform(
                request("PROPFIND", java.net.URI.create("/dav/"))
                    .header("Authorization", basic)
                    .header("Depth", "0"))
            .andReturn();
    assertEquals(207, list.getResponse().getStatus(), list.getResponse().getContentAsString());
    assertTrue(list.getResponse().getContentAsString().contains("multistatus"));
    var denied =
        mvc.perform(put("/dav/no.txt").header("Authorization", basic).content(text("no")))
            .andReturn();
    assertEquals(403, denied.getResponse().getStatus());
    assertEquals(
        401, mvc.perform(get("/dav/").cookie(u.cookie)).andReturn().getResponse().getStatus());
    call(u, delete("/api/user/access/dav/" + credential.path("id").asText()), null, 200);
    assertEquals(
        401,
        mvc.perform(get("/dav/").header("Authorization", basic))
            .andReturn()
            .getResponse()
            .getStatus());
  }

  @Test
  void CORE04_collectionShareCannotEscapeSelectedRootsAndCollectIsWriteOnly() throws Exception {
    var u = register("collection");
    String selected = folder(u, u.root, "selected"), secret = folder(u, u.root, "private");
    String visible = upload(u, selected, "yes.txt", text("yes"));
    var share =
        read(
            call(
                u,
                post("/api/shares/collection"),
                Map.of(
                    "fileIds",
                    List.of(selected),
                    "title",
                    "Selection",
                    "hours",
                    24,
                    "allowDownload",
                    true,
                    "collect",
                    false),
                200));
    String token = share.path("token").asText();
    if (token.isBlank()) token = share.path("url").asText().replaceAll(".*/", "");
    assertFalse(token.isBlank(), share.toString());
    call(null, get("/api/public/shares/" + token + "/entries"), null, 200);
    call(
        null, get("/api/public/shares/" + token + "/entries").param("parentId", secret), null, 404);
    call(null, get("/api/public/shares/" + token + "/entries/" + visible + "/download"), null, 200);
    var collection =
        read(
            call(
                u,
                post("/api/shares/collection"),
                Map.of(
                    "fileIds",
                    List.of(selected),
                    "title",
                    "Upload here",
                    "hours",
                    24,
                    "allowDownload",
                    false,
                    "collect",
                    true),
                200));
    String collectToken = collection.path("token").asText();
    call(null, get("/api/public/shares/" + collectToken + "/entries"), null, 403);
    var uploaded =
        mvc.perform(
                multipart("/api/public/shares/" + collectToken + "/collect")
                    .file(
                        new MockMultipartFile(
                            "file", "collected.txt", "text/plain", text("collected")))
                    .with(csrf()))
            .andReturn();
    assertEquals(
        200, uploaded.getResponse().getStatus(), uploaded.getResponse().getContentAsString());
    assertEquals(
        2,
        db.count("SELECT COUNT(*) FROM drive_entry WHERE parent_id=? AND deleted=FALSE", selected));
  }

  @Autowired com.cloudnest.security.SecretCipher secretCipher;
  @Autowired com.cloudnest.auth.MfaService mfa;
  @Autowired com.cloudnest.file.SearchController searchIndex;
  @Autowired com.cloudnest.user.RuntimeSettings runtimeSettings;

  @Test
  void CORE11_adminConfigurationMasksSecretsAndRejectsOrdinaryUsers() throws Exception {
    var u = register("config");
    db.update("UPDATE sys_user SET administrator=TRUE WHERE id=?", u.id);
    call(b, get("/api/admin/config/mail"), null, 403);
    call(null, get("/api/admin/config/mail"), null, 401);
    try {
      call(
          u,
          put("/api/admin/config/mail"),
          Map.of(
              "host",
              "smtp.example.test",
              "port",
              "587",
              "password",
              " secret with spaces ",
              "from",
              "test@example.test",
              "baseUrl",
              "http://localhost"),
          200);
      var config = read(call(u, get("/api/admin/config/mail"), null, 200));
      assertFalse(config.has("password"));
      assertEquals("true", config.path("passwordConfigured").asText());
      call(u, put("/api/admin/config/mail"), Map.of("password", ""), 200);
      assertEquals(" secret with spaces ", runtimeSettings.get("mail").get("password"));
      assertFalse(
          Db.s(
                  db.one("SELECT setting_value FROM system_setting WHERE name='mail'"),
                  "setting_value")
              .contains("secret with spaces"));
      call(u, put("/api/admin/config/storage"), Map.of("defaultBackend", "s3:000000000000"), 400);
      call(u, get("/api/admin/storage"), null, 200);
      call(b, get("/api/admin/storage"), null, 403);
    } finally {
      runtimeSettings.set("mail", Map.of());
    }
  }

  @Test
  void CORE12_cancelBuildingZipDoesNotReviveAndCannotCancelAnotherUsersJob() throws Exception {
    var u = register("cancelzip");
    String file = upload(u, u.root, "cancel.txt", text("cancel payload"));
    String key =
        Db.s(
            db.one(
                "SELECT b.object_key FROM file_blob b JOIN drive_entry e ON e.blob_id=b.id WHERE"
                    + " e.id=?",
                file),
            "object_key");
    var entered = new CountDownLatch(1);
    var release = new CountDownLatch(1);
    doAnswer(
            invocation -> {
              InputStream input = (InputStream) invocation.callRealMethod();
              return new FilterInputStream(input) {
                @Override
                public int read(byte[] bytes, int offset, int length) throws IOException {
                  entered.countDown();
                  try {
                    if (!release.await(5, TimeUnit.SECONDS))
                      throw new IOException("Test release timed out");
                  } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                    throw new IOException(e);
                  }
                  return super.read(bytes, offset, length);
                }
              };
            })
        .when(storage)
        .open(eq("local"), eq(key), anyLong(), anyLong());
    try {
      String id =
          read(call(u, post("/api/downloads"), Map.of("fileIds", List.of(file)), 200))
              .path("id")
              .asText();
      assertTrue(entered.await(5, TimeUnit.SECONDS));
      call(b, delete("/api/downloads/" + id), null, 404);
      call(u, delete("/api/downloads/" + id), null, 200);
      release.countDown();
      Path archive = data.resolve("downloads").resolve(id + ".zip");
      for (int n = 0; n < 200 && Files.exists(archive); n++) Thread.sleep(25);
      assertFalse(Files.exists(archive));
      assertEquals(
          "CANCELLED",
          read(call(u, get("/api/downloads/" + id), null, 200)).path("status").asText());
      call(u, get("/api/downloads/" + id + "/file"), null, 409);
    } finally {
      release.countDown();
      doCallRealMethod().when(storage).open(eq("local"), eq(key), anyLong(), anyLong());
    }
  }

  @Test
  void CORE09_verifiedEmailRecoveryRevokesSessionsAndTokenCannotBeReused() throws Exception {
    var u = register("mail");
    String username = Db.s(db.one("SELECT username FROM sys_user WHERE id=?", u.id), "username");
    try (var smtp = new SmtpFixture()) {
      runtimeSettings.set(
          "mail",
          Map.of(
              "host",
              "127.0.0.1",
              "port",
              Integer.toString(smtp.socket.getLocalPort()),
              "from",
              "noreply@example.test",
              "tls",
              "false",
              "ssl",
              "false",
              "baseUrl",
              "http://localhost"));
      call(
          u,
          post("/api/user/access/email"),
          Map.of("email", username + "@example.test", "password", "correct-password"),
          200);
      String verify = smtp.token("verifyEmail");
      call(null, post("/api/public/auth/verify-email"), Map.of("token", verify), 200);
      call(null, post("/api/public/auth/verify-email"), Map.of("token", verify), 404);
      call(null, post("/api/public/auth/forgot"), Map.of("account", username), 200);
      String reset = smtp.token("reset");
      call(
          null,
          post("/api/public/auth/reset"),
          Map.of("token", reset, "password", "new-correct-password"),
          200);
      call(u, get("/api/user/me"), null, 401);
      call(
          null,
          post("/api/public/auth/reset"),
          Map.of("token", reset, "password", "new-correct-password"),
          404);
      call(
          null,
          post("/api/auth/login"),
          Map.of("username", username, "password", "new-correct-password"),
          200);
    } finally {
      runtimeSettings.set("mail", Map.of());
    }
  }

  @Test
  void CORE10_largeDirectoryPagesAreStableAndZipLimitRejectsBeforeCreatingJob() throws Exception {
    var u = register("large");
    String parent = folder(u, u.root, "large directory");
    String file = upload(u, parent, "0000.txt", text("one"));
    var original = db.one("SELECT * FROM drive_entry WHERE id=?", file);
    var batch = new ArrayList<Object[]>();
    long now = System.currentTimeMillis();
    for (int n = 1; n <= 1000; n++)
      batch.add(
          new Object[] {
            Util.id(),
            u.id,
            parent,
            String.format(Locale.ROOT, "%04d.txt", n),
            original.get("blob_id"),
            3,
            now,
            now
          });
    jdbc.batchUpdate(
        "INSERT INTO"
            + " drive_entry(id,user_id,parent_id,kind,name,blob_id,size,mime,created_at,updated_at)"
            + " VALUES(?,?,?,'FILE',?,?,?,'text/plain',?,?)",
        batch);
    db.update("UPDATE sys_user SET storage_used=3003 WHERE id=?", u.id);
    Set<String> seen = new HashSet<>();
    for (int page = 1; page <= 21; page++) {
      var result =
          read(
              call(
                  u,
                  get("/api/entries")
                      .param("parentId", parent)
                      .param("page", Integer.toString(page))
                      .param("sort", "name"),
                  null,
                  200));
      for (var item : result.path("items")) assertTrue(seen.add(item.path("id").asText()));
    }
    assertEquals(1001, seen.size());
    call(u, post("/api/downloads/estimate"), Map.of("fileIds", List.of(parent)), 413);
    assertEquals(0, db.count("SELECT COUNT(*) FROM download_job WHERE user_id=?", u.id));
  }

  @Test
  void CORE07_officeSignedCallbacksSaveVersionsAndRejectUntrustedSources() throws Exception {
    var u = register("office");
    String file = upload(u, u.root, "document.docx", text("original document"));
    var server =
        com.sun.net.httpserver.HttpServer.create(new java.net.InetSocketAddress("127.0.0.1", 0), 0);
    server.createContext(
        "/cache/",
        exchange -> {
          byte[] bytes = text("edited document");
          exchange.sendResponseHeaders(200, bytes.length);
          exchange.getResponseBody().write(bytes);
          exchange.close();
        });
    server.start();
    String origin = "http://127.0.0.1:" + server.getAddress().getPort(),
        secret = "onlyoffice-test-secret-32-characters-minimum";
    try {
      runtimeSettings.set(
          "office", Map.of("url", origin, "baseUrl", "http://localhost", "secret", secret));
      var editor = call(u, get("/api/office/" + file + "/editor").param("mode", "edit"), null, 200);
      String html = editor.getResponse().getContentAsString(StandardCharsets.UTF_8);
      int start =
          html.indexOf("new DocsAPI.DocEditor('office',")
              + "new DocsAPI.DocEditor('office',".length();
      var config = json.readTree(html.substring(start, html.indexOf(");</script>", start)));
      String ticket = config.path("editorConfig").path("callbackUrl").asText().split("ticket=")[1];
      assertTrue(
          editor.getResponse().getHeader("Content-Security-Policy").contains("script-src 'nonce-"));
      assertEquals(
          401,
          mvc.perform(
                  post("/api/public/office/callback")
                      .param("ticket", ticket)
                      .contentType("application/json")
                      .content("{\"status\":2}"))
              .andReturn()
              .getResponse()
              .getStatus());
      for (String url :
          List.of(origin + "/cache/result.docx", "http://invalid.example/cache/document.docx")) {
        Map<String, Object> payload = Map.of("status", 2, "url", url);
        var jwt =
            new com.nimbusds.jwt.SignedJWT(
                new com.nimbusds.jose.JWSHeader(com.nimbusds.jose.JWSAlgorithm.HS256),
                com.nimbusds.jwt.JWTClaimsSet.parse(payload));
        jwt.sign(new com.nimbusds.jose.crypto.MACSigner(secret.getBytes(StandardCharsets.UTF_8)));
        var response =
            mvc.perform(
                    post("/api/public/office/callback")
                        .param("ticket", ticket)
                        .header("Authorization", "Bearer " + jwt.serialize())
                        .contentType("application/json")
                        .content(json.writeValueAsBytes(payload)))
                .andReturn();
        assertEquals(
            url.startsWith(origin) ? 200 : 403,
            response.getResponse().getStatus(),
            response.getResponse().getContentAsString());
      }
      assertEquals(1, db.count("SELECT COUNT(*) FROM file_version WHERE entry_id=?", file));
      assertEquals(15, Db.n(db.one("SELECT size FROM drive_entry WHERE id=?", file), "size"));
    } finally {
      server.stop(0);
      runtimeSettings.set("office", Map.of());
    }
  }

  @Test
  void CORE08_webDavWriteOverwriteAndCrossOriginDestination() throws Exception {
    var u = register("davwrite");
    String username = Db.s(db.one("SELECT username FROM sys_user WHERE id=?", u.id), "username");
    var credential =
        read(
            call(
                u,
                post("/api/user/access/dav"),
                Map.of(
                    "label",
                    "writer",
                    "readOnly",
                    false,
                    "days",
                    1,
                    "password",
                    "correct-password"),
                200));
    String basic =
        "Basic "
            + Base64.getEncoder()
                .encodeToString(
                    (username + ":" + credential.path("password").asText())
                        .getBytes(StandardCharsets.UTF_8));
    assertEquals(
        201,
        mvc.perform(put("/dav/test.txt").header("Authorization", basic).content(text("first")))
            .andReturn()
            .getResponse()
            .getStatus());
    assertEquals(
        204,
        mvc.perform(put("/dav/test.txt").header("Authorization", basic).content(text("second")))
            .andReturn()
            .getResponse()
            .getStatus());
    String file =
        Db.s(db.one("SELECT id FROM drive_entry WHERE user_id=? AND name='test.txt'", u.id), "id");
    assertEquals(1, db.count("SELECT COUNT(*) FROM file_version WHERE entry_id=?", file));
    assertEquals(
        412,
        mvc.perform(
                put("/dav/test.txt")
                    .header("Authorization", basic)
                    .header("If-None-Match", "*")
                    .content(text("denied")))
            .andReturn()
            .getResponse()
            .getStatus());
    assertEquals(
        400,
        mvc.perform(
                request("COPY", java.net.URI.create("/dav/test.txt"))
                    .header("Authorization", basic)
                    .header("Destination", "https://evil.example/dav/stolen.txt"))
            .andReturn()
            .getResponse()
            .getStatus());
    assertEquals(
        201,
        mvc.perform(
                request("COPY", java.net.URI.create("/dav/test.txt"))
                    .header("Authorization", basic)
                    .header("Destination", "https://localhost/dav/copied.txt"))
            .andReturn()
            .getResponse()
            .getStatus());
    assertEquals(
        2,
        db.count(
            "SELECT COUNT(*) FROM drive_entry WHERE user_id=? AND kind='FILE' AND deleted=FALSE",
            u.id));
  }

  @Test
  void CORE05_mfaEnrollmentRejectsReplayAndRecoveryCodeIsOneUse() throws Exception {
    var u = register("mfa");
    call(u, post("/api/user/access/mfa/setup"), Map.of("password", "correct-password"), 200);
    String secret =
        secretCipher.decrypt(
            Db.s(db.one("SELECT totp_pending FROM sys_user WHERE id=?", u.id), "totp_pending"));
    String otp = com.cloudnest.auth.MfaService.code(secret, System.currentTimeMillis() / 30000);
    var result = read(call(u, post("/api/user/access/mfa/confirm"), Map.of("code", otp), 200));
    assertEquals(10, result.path("recoveryCodes").size());
    assertThrows(Problem.class, () -> mfa.verify(u.id, otp));
    String recovery = result.path("recoveryCodes").get(0).asText();
    mfa.verify(u.id, recovery);
    assertThrows(Problem.class, () -> mfa.verify(u.id, recovery));
    call(
        u,
        delete("/api/user/access/mfa"),
        Map.of("password", "correct-password", "otp", result.path("recoveryCodes").get(1).asText()),
        200);
    assertNull(db.one("SELECT totp_secret FROM sys_user WHERE id=?", u.id).get("totp_secret"));
  }

  @Test
  void CORE06_indexAndMarkdownAttachmentsStayInsideOwnershipAndShareBoundary() throws Exception {
    var u = register("index");
    String folder = folder(u, u.root, "docs");
    String file =
        upload(u, folder, "note.md", text("# UniqueSearchNeedle\n![image](../private.txt)"));
    String attachment =
        upload(
            u,
            folder,
            "image.png",
            Base64.getDecoder()
                .decode(
                    "iVBORw0KGgoAAAANSUhEUgAAAAEAAAABCAQAAAC1HAwCAAAAC0lEQVR42mP8/x8AAwMCAO+jRZkAAAAASUVORK5CYII="));
    String privateFile = upload(u, u.root, "private.txt", text("secret"));
    for (int n = 0;
        n < 100 && db.count("SELECT COUNT(*) FROM entry_index WHERE entry_id=?", file) == 0;
        n++) searchIndex.indexBatch();
    var results =
        read(
            call(
                u,
                get("/api/search").param("q", "UniqueSearchNeedle").param("content", "true"),
                null,
                200));
    assertTrue(results.path("items").toString().contains(file));
    assertFalse(
        read(call(
                b,
                get("/api/search").param("q", "UniqueSearchNeedle").param("content", "true"),
                null,
                200))
            .path("items")
            .toString()
            .contains(file));
    call(u, get("/api/files/" + file + "/attachment").param("path", "image.png"), null, 200);
    call(b, get("/api/files/" + file + "/attachment").param("path", "image.png"), null, 404);
    var share =
        read(
            call(
                u,
                post("/api/shares"),
                Map.of("fileId", folder, "hours", 24, "allowDownload", true),
                200));
    call(
        null,
        get("/api/public/shares/"
                + share.path("token").asText()
                + "/entries/"
                + file
                + "/attachment")
            .param("path", "../private.txt"),
        null,
        404);
  }

  @Test
  void A01_registrationRootAndProfile() throws Exception {
    var p = read(call(a, get("/api/user/me"), null, 200));
    assertEquals(a.root, p.path("rootId").asText());
    assertFalse(p.has("passwordHash"));
    var edited = read(call(a, patch("/api/user/me"), Map.of("nickname", "测试用户"), 200));
    assertEquals("测试用户", edited.path("nickname").asText());
  }

  @Test
  void A02_loginAndLogoutRevokeOldCookie() throws Exception {
    User u = register("logout");
    String username = Db.s(db.one("SELECT username FROM sys_user WHERE id=?", u.id), "username");
    call(
        null,
        post("/api/auth/login"),
        Map.of("username", username, "password", "wrong-password"),
        401);
    User signed =
        new User(
            u.id,
            u.root,
            call(
                    null,
                    post("/api/auth/login"),
                    Map.of("username", username.toUpperCase(), "password", "correct-password"),
                    200)
                .getResponse()
                .getCookie("CN_AUTH"));
    call(signed, post("/api/auth/logout"), null, 200);
    call(signed, get("/api/user/me"), null, 401);
  }

  @Test
  void A03_csrfAndUnauthenticatedAccess() throws Exception {
    assertEquals(
        403,
        mvc.perform(
                post("/api/folders")
                    .cookie(a.cookie)
                    .contentType("application/json")
                    .content("{\"name\":\"bad\"}"))
            .andReturn()
            .getResponse()
            .getStatus());
    call(null, get("/api/entries"), null, 401);
    var r = mvc.perform(get("/api/auth/csrf")).andReturn();
    assertTrue(read(r).has("token"));
  }

  @Test
  void D01_nestedBreadcrumbsAndCycleProtection() throws Exception {
    String f = folder(a, a.root, unique()),
        child = folder(a, f, "child"),
        leaf = folder(a, child, "leaf");
    assertEquals(4, read(call(a, get("/api/folders/" + leaf + "/breadcrumbs"), null, 200)).size());
    call(a, patch("/api/folders/" + f), Map.of("parentId", leaf), 400);
    call(a, delete("/api/folders/" + a.root), null, 400);
  }

  @Test
  void D02_namesConflictsAndNormalization() throws Exception {
    String f = folder(a, a.root, unique());
    folder(a, f, "same");
    call(a, post("/api/folders"), Map.of("parentId", f, "name", "same"), 409);
    folder(a, f, "Same");
    call(a, post("/api/folders"), Map.of("parentId", f, "name", "../bad"), 400);
    folder(a, f, "é");
    call(a, post("/api/folders"), Map.of("parentId", f, "name", "e\u0301"), 409);
  }

  @Test
  void D03_crossUserIsolation() throws Exception {
    String f = folder(a, a.root, unique()),
        file = upload(a, f, "private.txt", text("private " + unique()));
    call(b, get("/api/entries").param("parentId", f), null, 404);
    call(b, patch("/api/files/" + file), Map.of("name", "stolen"), 404);
    call(b, get("/api/files/" + file + "/download"), null, 404);
    call(b, get("/api/files/" + file + "/preview"), null, 404);
    call(b, delete("/api/files/" + file), null, 404);
    call(b, post("/api/shares"), Map.of("fileId", file, "hours", 24, "allowDownload", true), 404);
    call(b, post("/api/downloads"), Map.of("fileIds", List.of(file)), 404);
  }

  @Test
  void D04_moveRenameKeepsPhysicalObject() throws Exception {
    String f = folder(a, a.root, unique()),
        target = folder(a, a.root, unique()),
        file = upload(a, f, "before.txt", text("move " + unique()));
    String key =
        Db.s(
            db.one(
                "SELECT b.object_key FROM file_blob b JOIN drive_entry e ON e.blob_id=b.id WHERE"
                    + " e.id=?",
                file),
            "object_key");
    call(a, patch("/api/files/" + file), Map.of("name", "after.txt", "parentId", target), 200);
    var result = read(call(a, get("/api/entries").param("parentId", target), null, 200));
    assertEquals("after.txt", result.path("items").get(0).path("name").asText());
    assertEquals(
        key,
        Db.s(
            db.one(
                "SELECT b.object_key FROM file_blob b JOIN drive_entry e ON e.blob_id=b.id WHERE"
                    + " e.id=?",
                file),
            "object_key"));
    call(
        a,
        patch("/api/folders/" + f),
        Map.of("name", "renamed-" + unique(), "parentId", target),
        200);
    assertEquals(3, read(call(a, get("/api/folders/" + f + "/breadcrumbs"), null, 200)).size());
  }

  @Test
  void F01_roundTripUnicodeAndZeroBytes() throws Exception {
    String f = folder(a, a.root, unique());
    byte[] bytes = text("你好，CloudNest " + unique());
    String file = upload(a, f, "中文文件.txt", bytes);
    var r = call(a, get("/api/files/" + file + "/download"), null, 200);
    assertArrayEquals(bytes, r.getResponse().getContentAsByteArray());
    assertTrue(r.getResponse().getHeader("Content-Disposition").contains("UTF-8"));
    String empty = upload(a, f, "空文件", new byte[0]);
    assertEquals(
        0,
        call(a, get("/api/files/" + empty + "/download"), null, 200)
            .getResponse()
            .getContentAsByteArray()
            .length);
  }

  @Test
  void F02_previewRangeAndUnsafeTypes() throws Exception {
    String f = folder(a, a.root, unique()), file = upload(a, f, "note.txt", text("0123456789"));
    var r = call(a, get("/api/files/" + file + "/preview").header("Range", "bytes=2-5"), null, 206);
    assertEquals("2345", r.getResponse().getContentAsString());
    assertEquals("bytes 2-5/10", r.getResponse().getHeader("Content-Range"));
    call(a, get("/api/files/" + file + "/download").header("Range", "bytes=100-"), null, 416);
    assertEquals(
        "789",
        call(a, get("/api/files/" + file + "/download").header("Range", "bytes=-3"), null, 206)
            .getResponse()
            .getContentAsString());
    String html =
        upload(a, f, "danger.html", text("<!doctype html><html><script>alert(1)</script></html>"));
    call(a, get("/api/files/" + html + "/preview"), null, 415);
  }

  @Test
  void F03_ordinaryUploadAndSearchPagination() throws Exception {
    String f = folder(a, a.root, unique());
    var file = new MockMultipartFile("file", "plain.txt", "text/plain", text("普通上传"));
    var r =
        mvc.perform(
                multipart("/api/files/upload")
                    .file(file)
                    .param("folderId", f)
                    .cookie(a.cookie)
                    .with(csrf()))
            .andReturn();
    assertEquals(200, r.getResponse().getStatus(), r.getResponse().getContentAsString());
    var list =
        read(
            call(
                a,
                get("/api/entries")
                    .param("parentId", f)
                    .param("q", "plain")
                    .param("scope", "current")
                    .param("pageSize", "1"),
                null,
                200));
    assertEquals(1, list.path("total").asInt());
    assertTrue(list.path("items").get(0).has("path"));
  }

  @Test
  void F04_imageAndPdfDetectedFromContent() throws Exception {
    String folder = folder(a, a.root, unique());
    byte[] png =
        Base64.getDecoder()
            .decode(
                "iVBORw0KGgoAAAANSUhEUgAAAAEAAAABCAQAAAC1HAwCAAAAC0lEQVR42mP8/x8AAusB9Wl6l1sAAAAASUVORK5CYII=");
    String image = upload(a, folder, "image.dat", png);
    var r = call(a, get("/api/files/" + image + "/preview"), null, 200);
    assertEquals("image/png", r.getResponse().getContentType());
    assertArrayEquals(png, r.getResponse().getContentAsByteArray());
    String pdf =
        upload(
            a,
            folder,
            "document.dat",
            text(
                "%PDF-1.4\n"
                    + "1 0 obj\n"
                    + "<< /Type /Catalog >>\n"
                    + "endobj\n"
                    + "trailer\n"
                    + "<< /Root 1 0 R >>\n"
                    + "%%EOF"));
    r = call(a, get("/api/files/" + pdf + "/preview").header("Range", "bytes=0-4"), null, 206);
    assertEquals("application/pdf", r.getResponse().getContentType());
    assertEquals("%PDF-", r.getResponse().getContentAsString());
  }

  @Test
  void T01_deleteBatchRestoresOnlyOwnItems() throws Exception {
    String f = folder(a, a.root, unique()),
        child = folder(a, f, "child"),
        old = upload(a, child, "old.txt", text(unique())),
        live = upload(a, child, "live.txt", text(unique()));
    call(a, delete("/api/files/" + old), null, 200);
    call(a, delete("/api/folders/" + f), null, 200);
    call(a, get("/api/files/" + live + "/download"), null, 404);
    call(a, post("/api/trash/folders/" + f + "/restore"), null, 200);
    call(a, get("/api/files/" + live + "/download"), null, 200);
    call(a, get("/api/files/" + old + "/download"), null, 404);
    call(a, post("/api/trash/files/" + old + "/restore"), null, 200);
  }

  @Test
  void T02_restoreConflictAndMissingParent() throws Exception {
    String f = folder(a, a.root, unique()), file = upload(a, f, "conflict.txt", text(unique()));
    call(a, delete("/api/files/" + file), null, 200);
    upload(a, f, "conflict.txt", text(unique()));
    var restored = read(call(a, post("/api/trash/files/" + file + "/restore"), null, 200));
    assertTrue(restored.path("name").asText().contains("恢复"));
    call(a, delete("/api/files/" + file), null, 200);
    call(a, delete("/api/folders/" + f), null, 200);
    restored = read(call(a, post("/api/trash/files/" + file + "/restore"), null, 200));
    assertTrue(restored.path("restoredToRoot").asBoolean());
    assertEquals(a.root, restored.path("parentId").asText());
  }

  @Test
  void T03_purgeReleasesLogicalQuotaButNotSharedBlob() throws Exception {
    User u = register("quota");
    String f = folder(u, u.root, unique());
    byte[] bytes = text("same content");
    String x = upload(u, f, "first.txt", bytes), y = upload(u, f, "second.txt", bytes);
    assertEquals(bytes.length * 2, db.count("SELECT storage_used FROM sys_user WHERE id=?", u.id));
    assertEquals(1, db.count("SELECT COUNT(*) FROM file_blob WHERE user_id=?", u.id));
    call(u, delete("/api/files/" + x), null, 200);
    assertEquals(bytes.length * 2, db.count("SELECT storage_used FROM sys_user WHERE id=?", u.id));
    call(u, delete("/api/trash/files/" + x), null, 200);
    uploads.cleanup();
    call(u, get("/api/files/" + y + "/download"), null, 200);
    assertEquals(bytes.length, db.count("SELECT storage_used FROM sys_user WHERE id=?", u.id));
    call(u, delete("/api/files/" + y), null, 200);
    call(u, delete("/api/trash/files/" + y), null, 200);
    uploads.cleanup();
    assertEquals(0, db.count("SELECT COUNT(*) FROM file_blob WHERE user_id=?", u.id));
  }

  @Test
  void T04_purgeDirectoryIncludesHistoricTrash() throws Exception {
    User u = register("purgetree");
    String f = folder(u, u.root, unique()),
        child = folder(u, f, "nested"),
        x = upload(u, child, "x", text("old")),
        y = upload(u, child, "y", text("new"));
    call(u, delete("/api/files/" + x), null, 200);
    call(u, delete("/api/folders/" + f), null, 200);
    call(u, delete("/api/trash/folders/" + f), null, 200);
    uploads.cleanup();
    assertEquals(0, db.count("SELECT storage_used FROM sys_user WHERE id=?", u.id));
    assertEquals(1, db.count("SELECT COUNT(*) FROM drive_entry WHERE user_id=?", u.id));
    assertEquals(0, db.count("SELECT COUNT(*) FROM file_blob WHERE user_id=?", u.id));
  }

  @Test
  void U01_partsResumeIdempotencyAndCompleteness() throws Exception {
    String f = folder(a, a.root, unique());
    byte[] bytes = new byte[2300];
    new Random(7).nextBytes(bytes);
    var s = init(a, f, "parts.bin", bytes);
    String id = s.path("id").asText();
    call(b, get("/api/uploads/" + id), null, 404);
    call(b, put("/api/uploads/" + id + "/parts/0").content(bytes), null, 404);
    call(a, put("/api/uploads/" + id + "/parts/3").content(new byte[0]), null, 400);
    byte[] p = Arrays.copyOfRange(bytes, 0, 1024);
    call(a, put("/api/uploads/" + id + "/parts/0").content(p), null, 200);
    call(a, put("/api/uploads/" + id + "/parts/0").content(p), null, 200);
    p[0]++;
    call(a, put("/api/uploads/" + id + "/parts/0").content(p), null, 409);
    call(a, post("/api/uploads/" + id + "/complete"), null, 409);
    assertEquals(
        1, read(call(a, get("/api/uploads/" + id), null, 200)).path("uploadedParts").size());
    call(
        a,
        put("/api/uploads/" + id + "/parts/2").content(Arrays.copyOfRange(bytes, 2048, 2300)),
        null,
        200);
    call(
        a,
        put("/api/uploads/" + id + "/parts/1").content(Arrays.copyOfRange(bytes, 1024, 2048)),
        null,
        200);
    var done = read(call(a, post("/api/uploads/" + id + "/complete"), null, 200));
    var again = read(call(a, post("/api/uploads/" + id + "/complete"), null, 200));
    assertEquals(done.path("fileId"), again.path("fileId"));
    assertArrayEquals(
        bytes,
        call(a, get("/api/files/" + done.path("fileId").asText() + "/download"), null, 200)
            .getResponse()
            .getContentAsByteArray());
  }

  @Test
  void U02_clientHashIsNotTrusted() throws Exception {
    var s = init(a, a.root, "bad-hash-" + unique(), text("expected"));
    String id = s.path("id").asText();
    call(a, put("/api/uploads/" + id + "/parts/0").content(text("tampered")), null, 200);
    call(a, post("/api/uploads/" + id + "/complete"), null, 422);
    call(a, delete("/api/uploads/" + id), null, 200);
  }

  @Test
  void U03_quotaReservationCancellationExpiry() throws Exception {
    User u = register("reserve");
    db.update("UPDATE sys_user SET storage_quota=10 WHERE id=?", u.id);
    var s = init(u, u.root, "one", text("12345678"));
    String id = s.path("id").asText();
    call(
        u,
        post("/api/uploads/init"),
        Map.of("name", "two", "size", 8, "sha256", Util.hash("different")),
        409);
    assertEquals(8, db.count("SELECT storage_reserved FROM sys_user WHERE id=?", u.id));
    call(u, delete("/api/uploads/" + id), null, 200);
    call(u, delete("/api/uploads/" + id), null, 200);
    assertEquals(0, db.count("SELECT storage_reserved FROM sys_user WHERE id=?", u.id));
    s = init(u, u.root, "expired", text("12345678"));
    db.update("UPDATE upload_session SET expires_at=0 WHERE id=?", s.path("id").asText());
    uploads.cleanup();
    assertEquals(0, db.count("SELECT storage_reserved FROM sys_user WHERE id=?", u.id));
  }

  @Test
  void U04_crossUserCannotInstantUpload() throws Exception {
    byte[] bytes = text("unique content " + unique());
    String f = folder(a, a.root, unique());
    upload(a, f, "owned", bytes);
    var s = init(b, b.root, "other-" + unique(), bytes);
    assertEquals("UPLOADING", s.path("status").asText());
    call(b, delete("/api/uploads/" + s.path("id").asText()), null, 200);
  }

  @Test
  void U05_storageFailureKeepsRetryableState() throws Exception {
    var s = init(a, a.root, "failure-" + unique(), text("retry " + unique()));
    String id = s.path("id").asText();
    byte[] bytes =
        text(
            "retry "
                + unique()); // use the persisted declaration for a second deterministic session
    call(a, delete("/api/uploads/" + id), null, 200);
    s = init(a, a.root, "failure-" + unique(), bytes);
    id = s.path("id").asText();
    call(a, put("/api/uploads/" + id + "/parts/0").content(bytes), null, 200);
    doThrow(new IOException("injected disk failure"))
        .when(storage)
        .put(anyString(), anyString(), any(), anyLong());
    try {
      call(a, post("/api/uploads/" + id + "/complete"), null, 500);
      assertEquals(
          "UPLOADING", read(call(a, get("/api/uploads/" + id), null, 200)).path("status").asText());
    } finally {
      doCallRealMethod().when(storage).put(anyString(), anyString(), any(), anyLong());
    }
    assertEquals(
        "COMPLETE",
        read(call(a, post("/api/uploads/" + id + "/complete"), null, 200)).path("status").asText());
  }

  @Test
  void U06_metadataConflictAfterStorageWriteIsCompensated() throws Exception {
    User u = register("compensate");
    byte[] content = text("candidate"), other = text("existing");
    String name = "same.txt";
    var session = init(u, u.root, name, content);
    String id = session.path("id").asText();
    call(u, put("/api/uploads/" + id + "/parts/0").content(content), null, 200);
    String winner = upload(u, u.root, name, other);
    var state = db.one("SELECT * FROM upload_session WHERE id=?", id);
    String key = Db.s(state, "object_key");
    call(u, post("/api/uploads/" + id + "/complete"), null, 409);
    assertTrue(storage.exists("local", key));
    call(u, delete("/api/uploads/" + id), null, 200);
    uploads.cleanup();
    assertFalse(storage.exists("local", key));
    assertArrayEquals(
        other,
        call(u, get("/api/files/" + winner + "/download"), null, 200)
            .getResponse()
            .getContentAsByteArray());
    assertEquals(other.length, db.count("SELECT storage_used FROM sys_user WHERE id=?", u.id));
    assertEquals(0, db.count("SELECT storage_reserved FROM sys_user WHERE id=?", u.id));
  }

  @Test
  void U07_invalidLengthAndExpiredSessionRejected() throws Exception {
    var session = init(a, a.root, "length-" + unique(), text("abcd"));
    String id = session.path("id").asText();
    call(a, put("/api/uploads/" + id + "/parts/0").content(text("ab")), null, 400);
    call(a, put("/api/uploads/" + id + "/parts/0").content(text("abcde")), null, 400);
    db.update("UPDATE upload_session SET expires_at=0 WHERE id=?", id);
    call(a, put("/api/uploads/" + id + "/parts/0").content(text("abcd")), null, 409);
    uploads.cleanup();
  }

  @Test
  void U08_databaseWriteFailureRollsBackAndRetries() throws Exception {
    User u = register("dbfail");
    byte[] bytes = text("database failure" + unique());
    var session = init(u, u.root, "recover.txt", bytes);
    String id = session.path("id").asText();
    call(u, put("/api/uploads/" + id + "/parts/0").content(bytes), null, 200);
    doThrow(
            new org.springframework.dao.TransientDataAccessResourceException(
                "injected database failure"))
        .doCallRealMethod()
        .when(jdbc)
        .update(startsWith("INSERT INTO drive_entry"), any(Object[].class));
    try {
      call(u, post("/api/uploads/" + id + "/complete"), null, 500);
      assertEquals(0, db.count("SELECT COUNT(*) FROM file_blob WHERE user_id=?", u.id));
      assertEquals(0, db.count("SELECT storage_used FROM sys_user WHERE id=?", u.id));
      assertEquals(
          bytes.length, db.count("SELECT storage_reserved FROM sys_user WHERE id=?", u.id));
    } finally {
      doCallRealMethod()
          .when(jdbc)
          .update(startsWith("INSERT INTO drive_entry"), any(Object[].class));
    }
    assertEquals(
        "COMPLETE",
        read(call(u, post("/api/uploads/" + id + "/complete"), null, 200)).path("status").asText());
  }

  @Test
  void S01_protectedSharePermissionsAndRevocation() throws Exception {
    String file = upload(a, a.root, "shared-" + unique() + ".txt", text("shared text"));
    var s =
        read(
            call(
                a,
                post("/api/shares"),
                Map.of("fileId", file, "hours", 1, "code", "1234", "allowDownload", false),
                200));
    String token = s.path("token").asText(), url = "/api/public/shares/" + token;
    assertTrue(read(call(null, get(url), null, 200)).path("locked").asBoolean());
    call(null, get(url + "/preview"), null, 403);
    call(null, post(url + "/verify"), Map.of("code", "wrong"), 403);
    var r = call(null, post(url + "/verify"), Map.of("code", "1234"), 200);
    Cookie grant = r.getResponse().getCookie("CN_SHARE_" + s.path("id").asText());
    call(null, get(url + "/preview").cookie(grant), null, 200);
    call(null, get(url + "/download").cookie(grant), null, 403);
    call(a, delete("/api/shares/" + s.path("id").asText()), null, 200);
    call(null, get(url + "/preview").cookie(grant), null, 410);
  }

  @Test
  void S02_deletedSourceAndExpiryInvalidateShare() throws Exception {
    String file = upload(a, a.root, "deleted-share-" + unique(), text("test"));
    var s =
        read(
            call(
                a,
                post("/api/shares"),
                Map.of("fileId", file, "hours", 1, "allowDownload", true),
                200));
    String url = "/api/public/shares/" + s.path("token").asText();
    call(a, delete("/api/files/" + file), null, 200);
    call(a, post("/api/trash/files/" + file + "/restore"), null, 200);
    call(null, get(url), null, 410);
    var exp =
        read(
            call(
                a,
                post("/api/shares"),
                Map.of("fileId", file, "hours", 1, "allowDownload", true),
                200));
    db.update("UPDATE file_share SET expires_at=0 WHERE id=?", exp.path("id").asText());
    call(null, get("/api/public/shares/" + exp.path("token").asText()), null, 410);
  }

  @Test
  void B01_zipAndOwnership() throws Exception {
    String f = folder(a, a.root, unique()),
        x = upload(a, f, "a.txt", text("A")),
        y = upload(a, f, "b.txt", text("B"));
    String id =
        read(call(a, post("/api/downloads"), Map.of("fileIds", List.of(x, y)), 200))
            .path("id")
            .asText();
    call(b, get("/api/downloads/" + id), null, 404);
    for (int n = 0; n < 100; n++) {
      if (read(call(a, get("/api/downloads/" + id), null, 200))
          .path("status")
          .asText()
          .equals("READY")) break;
      Thread.sleep(50);
    }
    var r = call(a, get("/api/downloads/" + id + "/file"), null, 200);
    Set<String> names = new HashSet<>();
    try (var zip =
        new java.util.zip.ZipInputStream(
            new ByteArrayInputStream(r.getResponse().getContentAsByteArray()))) {
      for (var e = zip.getNextEntry(); e != null; e = zip.getNextEntry()) {
        names.add(e.getName());
        assertEquals(1, zip.readAllBytes().length);
      }
    }
    assertEquals(Set.of("a.txt", "b.txt"), names);
    call(a, delete("/api/files/" + x), null, 200);
    call(a, get("/api/downloads/" + id + "/file"), null, 404);
  }

  @Test
  void C01_concurrentQuotaCannotOversubscribe() throws Exception {
    User u = register("parallel");
    db.update("UPDATE sys_user SET storage_quota=10 WHERE id=?", u.id);
    try (var pool = Executors.newFixedThreadPool(2)) {
      var jobs =
          List.<Callable<Integer>>of(
              () ->
                  mvc.perform(
                          post("/api/uploads/init")
                              .cookie(u.cookie)
                              .with(csrf())
                              .contentType("application/json")
                              .content(
                                  json.writeValueAsBytes(
                                      Map.of(
                                          "name", "one", "size", 8, "sha256", Util.hash("one")))))
                      .andReturn()
                      .getResponse()
                      .getStatus(),
              () ->
                  mvc.perform(
                          post("/api/uploads/init")
                              .cookie(u.cookie)
                              .with(csrf())
                              .contentType("application/json")
                              .content(
                                  json.writeValueAsBytes(
                                      Map.of(
                                          "name", "two", "size", 8, "sha256", Util.hash("two")))))
                      .andReturn()
                      .getResponse()
                      .getStatus());
      var result =
          pool.invokeAll(jobs).stream()
              .map(
                  f -> {
                    try {
                      return f.get();
                    } catch (Exception e) {
                      throw new RuntimeException(e);
                    }
                  })
              .sorted()
              .toList();
      assertEquals(List.of(200, 409), result);
      assertEquals(8, db.count("SELECT storage_reserved FROM sys_user WHERE id=?", u.id));
    }
  }

  @Test
  void C02_concurrentDirectoryMovesCannotCreateCycle() throws Exception {
    String x = folder(a, a.root, unique()), y = folder(a, a.root, unique());
    try (var pool = Executors.newFixedThreadPool(2)) {
      var jobs =
          List.<Callable<Integer>>of(
              () ->
                  mvc.perform(
                          patch("/api/folders/" + x)
                              .cookie(a.cookie)
                              .with(csrf())
                              .contentType("application/json")
                              .content(json.writeValueAsBytes(Map.of("parentId", y))))
                      .andReturn()
                      .getResponse()
                      .getStatus(),
              () ->
                  mvc.perform(
                          patch("/api/folders/" + y)
                              .cookie(a.cookie)
                              .with(csrf())
                              .contentType("application/json")
                              .content(json.writeValueAsBytes(Map.of("parentId", x))))
                      .andReturn()
                      .getResponse()
                      .getStatus());
      var result =
          pool.invokeAll(jobs).stream()
              .map(
                  f -> {
                    try {
                      return f.get();
                    } catch (Exception e) {
                      throw new RuntimeException(e);
                    }
                  })
              .sorted()
              .toList();
      assertEquals(List.of(200, 400), result);
    }
    call(a, get("/api/folders/" + x + "/breadcrumbs"), null, 200);
    call(a, get("/api/folders/" + y + "/breadcrumbs"), null, 200);
  }

  @Test
  void C03_concurrentCompletePublishesOnce() throws Exception {
    User u = register("complete");
    byte[] bytes = text("parallel completion" + unique());
    var session = init(u, u.root, "complete.txt", bytes);
    String id = session.path("id").asText();
    call(u, put("/api/uploads/" + id + "/parts/0").content(bytes), null, 200);
    try (var pool = Executors.newFixedThreadPool(2)) {
      var jobs =
          List.<Callable<String>>of(
              () ->
                  read(call(u, post("/api/uploads/" + id + "/complete"), null, 200))
                      .path("fileId")
                      .asText(),
              () ->
                  read(call(u, post("/api/uploads/" + id + "/complete"), null, 200))
                      .path("fileId")
                      .asText());
      var result = pool.invokeAll(jobs);
      assertEquals(result.get(0).get(), result.get(1).get());
    }
    assertEquals(bytes.length, db.count("SELECT storage_used FROM sys_user WHERE id=?", u.id));
    assertEquals(0, db.count("SELECT storage_reserved FROM sys_user WHERE id=?", u.id));
  }

  @Test
  void IMP_I01_passwordRevokesEverySessionAndChecksOldPassword() throws Exception {
    User u = register("security");
    call(
        u,
        post("/api/user/password"),
        Map.of("oldPassword", "wrong", "newPassword", "updated-password"),
        403);
    call(u, get("/api/user/me"), null, 200);
    var sessions = read(call(u, get("/api/user/sessions"), null, 200));
    assertTrue(sessions.get(0).path("current").asBoolean());
    call(b, delete("/api/user/sessions/" + sessions.get(0).path("id").asText()), null, 404);
    call(
        u,
        post("/api/user/password"),
        Map.of("oldPassword", "correct-password", "newPassword", "updated-password"),
        200);
    call(u, get("/api/user/me"), null, 401);
    assertEquals(0, db.count("SELECT COUNT(*) FROM auth_session WHERE user_id=?", u.id));
  }

  @Test
  void IMP_I03_batchPartialFailureAncestorsAndReplay() throws Exception {
    User u = register("batch");
    String parent = folder(u, u.root, "parent"),
        child = folder(u, parent, "child"),
        target = folder(u, u.root, "target");
    folder(u, target, "parent");
    var r =
        read(
            call(
                u,
                post("/api/entries/batch"),
                Map.of("ids", List.of(parent, child, target), "action", "move", "parentId", target),
                200));
    assertEquals(3, r.path("failed").asInt());
    for (int i = 0; i < 2; i++)
      call(
          u,
          post("/api/entries/batch"),
          Map.of("ids", List.of(child, parent), "action", "trash"),
          200);
    assertEquals(
        2, db.count("SELECT COUNT(*) FROM drive_entry WHERE user_id=? AND deleted=TRUE", u.id));
    for (int i = 0; i < 2; i++)
      call(u, post("/api/entries/batch"), Map.of("ids", List.of(parent), "action", "restore"), 200);
    call(b, post("/api/entries/batch"), Map.of("ids", List.of(parent), "action", "trash"), 200);
    call(u, get("/api/folders/" + child + "/breadcrumbs"), null, 200);
  }

  @Test
  void IMP_I02_resolveFolderPathAndConflictPolicies() throws Exception {
    User u = register("paths");
    var input = Map.of("parentId", u.root, "segments", List.of("资料", "章节"));
    String id = read(call(u, post("/api/folders/resolve-path"), input, 200)).path("id").asText();
    assertEquals(
        id, read(call(u, post("/api/folders/resolve-path"), input, 200)).path("id").asText());
    call(
        u,
        post("/api/folders/resolve-path"),
        Map.of("parentId", u.root, "segments", List.of("safe", "..")),
        400);
    assertEquals(
        0, db.count("SELECT COUNT(*) FROM drive_entry WHERE user_id=? AND name='safe'", u.id));
    init(u, id, "notes.txt", text("pending"));
    assertTrue(
        read(call(
                u,
                post("/api/entries/upload-name"),
                Map.of("parentId", id, "name", "notes.txt", "policy", "skip"),
                200))
            .path("skipped")
            .asBoolean());
    assertEquals(
        "notes.txt (1)",
        read(call(
                u,
                post("/api/entries/upload-name"),
                Map.of("parentId", id, "name", "notes.txt", "policy", "keep"),
                200))
            .path("name")
            .asText());
    call(
        b,
        post("/api/folders/resolve-path"),
        Map.of("parentId", id, "segments", List.of("bad")),
        404);
  }

  @Test
  void IMP_I04_favoritesRecentFiltersAndIsolation() throws Exception {
    User u = register("find");
    String parent = folder(u, u.root, "bookmarks");
    call(u, patch("/api/entries/" + parent + "/favorite"), Map.of("favorite", true), 200);
    call(u, patch("/api/folders/" + parent), Map.of("name", "renamed"), 200);
    var favorites = read(call(u, get("/api/entries").param("collection", "favorites"), null, 200));
    assertEquals("renamed", favorites.path("items").get(0).path("name").asText());
    assertEquals(
        0,
        read(call(
                b,
                get("/api/entries").param("collection", "favorites").param("q", "renamed"),
                null,
                200))
            .path("total")
            .asInt());
    call(b, post("/api/entries/" + parent + "/opened"), null, 404);
    call(u, get("/api/entries").param("minSize", "10").param("maxSize", "1"), null, 400);
    call(u, delete("/api/folders/" + parent), null, 200);
    assertEquals(
        0,
        read(call(u, get("/api/entries").param("collection", "favorites"), null, 200))
            .path("total")
            .asInt());
    call(u, post("/api/trash/folders/" + parent + "/restore"), null, 200);
    assertEquals(
        1,
        read(call(u, get("/api/entries").param("collection", "favorites"), null, 200))
            .path("total")
            .asInt());
    call(u, get("/api/user/storage-breakdown"), null, 200);
  }

  @Test
  void IMP_I07_folderShareCannotEscapeAndMovingOutRevokesAccess() throws Exception {
    User u = register("foldershare");
    String root = folder(u, u.root, "shared"), sub = folder(u, root, "sub");
    String file = upload(u, sub, "public.txt", text("inside")),
        outside = upload(u, u.root, "private.txt", text("outside"));
    String token =
        read(call(
                u,
                post("/api/shares"),
                Map.of("fileId", root, "hours", 1, "allowDownload", true),
                200))
            .path("token")
            .asText();
    String base = "/api/public/shares/" + token;
    assertEquals(
        "FOLDER", read(call(null, get(base), null, 200)).path("file").path("kind").asText());
    call(null, get(base + "/entries").param("parentId", u.root), null, 404);
    call(null, get(base + "/entries/" + outside + "/download"), null, 404);
    assertEquals(
        "inside",
        call(null, get(base + "/entries/" + file + "/download"), null, 200)
            .getResponse()
            .getContentAsString());
    call(u, patch("/files/" + file), Map.of("parentId", u.root), 404);
    call(u, patch("/api/files/" + file), Map.of("parentId", u.root), 200);
    call(null, get(base + "/entries/" + file + "/download"), null, 404);
    call(u, delete("/api/folders/" + root), null, 200);
    call(null, get(base), null, 410);
  }

  @Test
  void IMP_I08_versionsPinnedShareRestoreCleanupAndQuota() throws Exception {
    User u = register("versions");
    String file = upload(u, u.root, "v.txt", text("old")),
        source = upload(u, u.root, "new.txt", text("new-content"));
    String token =
        read(call(
                u,
                post("/api/shares"),
                Map.of("fileId", file, "hours", 1, "allowDownload", true),
                200))
            .path("token")
            .asText();
    call(
        u,
        post("/api/files/" + file + "/versions"),
        Map.of("sourceId", source, "expectedRevision", 1),
        200);
    assertEquals(14, db.count("SELECT storage_used FROM sys_user WHERE id=?", u.id));
    assertEquals(
        "new-content",
        call(u, get("/api/files/" + file + "/download"), null, 200)
            .getResponse()
            .getContentAsString());
    assertEquals(
        "old",
        call(null, get("/api/public/shares/" + token + "/download"), null, 200)
            .getResponse()
            .getContentAsString());
    var history = read(call(u, get("/api/files/" + file + "/versions"), null, 200));
    String version = history.path("items").get(0).path("id").asText();
    call(b, get("/api/files/" + file + "/versions"), null, 404);
    call(
        u,
        post("/api/files/" + file + "/versions/" + version + "/restore"),
        Map.of("expectedRevision", 1),
        409);
    call(
        u,
        post("/api/files/" + file + "/versions/" + version + "/restore"),
        Map.of("expectedRevision", 2),
        200);
    assertEquals(
        "old",
        call(u, get("/api/files/" + file + "/download"), null, 200)
            .getResponse()
            .getContentAsString());
    assertEquals(14, db.count("SELECT storage_used FROM sys_user WHERE id=?", u.id));
    String other =
        read(call(u, get("/api/files/" + file + "/versions"), null, 200))
            .path("items")
            .get(0)
            .path("id")
            .asText();
    call(u, delete("/api/files/" + file + "/versions/" + other), null, 200);
    uploads.cleanup();
    assertEquals(3, db.count("SELECT storage_used FROM sys_user WHERE id=?", u.id));
    call(u, delete("/api/files/" + file), null, 200);
    call(u, delete("/api/trash/files/" + file), null, 200);
    uploads.cleanup();
    assertEquals(0, db.count("SELECT storage_used FROM sys_user WHERE id=?", u.id));
    assertEquals(0, db.count("SELECT COUNT(*) FROM file_blob WHERE user_id=?", u.id));
  }

  @Test
  void IMP_I08_pinnedBlobSurvivesHistoryDeletion() throws Exception {
    User u = register("pinned");
    String file = upload(u, u.root, "p.txt", text("old")),
        source = upload(u, u.root, "source", text("new"));
    String token =
        read(call(
                u,
                post("/api/shares"),
                Map.of("fileId", file, "hours", 1, "allowDownload", true),
                200))
            .path("token")
            .asText();
    call(
        u,
        post("/api/files/" + file + "/versions"),
        Map.of("sourceId", source, "expectedRevision", 1),
        200);
    String version =
        read(call(u, get("/api/files/" + file + "/versions"), null, 200))
            .path("items")
            .get(0)
            .path("id")
            .asText();
    call(u, delete("/api/files/" + file + "/versions/" + version), null, 200);
    uploads.cleanup();
    assertEquals(
        "old",
        call(null, get("/api/public/shares/" + token + "/download"), null, 200)
            .getResponse()
            .getContentAsString());
  }

  @Test
  void IMP_I10_retentionSkipsNewerDescendantAndIsIdempotent() throws Exception {
    User u = register("retention");
    String parent = folder(u, u.root, "old"), child = folder(u, parent, "newer");
    call(u, delete("/api/folders/" + child), null, 200);
    call(u, delete("/api/folders/" + parent), null, 200);
    long old = System.currentTimeMillis() - 40L * 86400000;
    db.update("UPDATE drive_entry SET deleted_at=? WHERE id=?", old, parent);
    call(u, put("/api/user/trash-policy"), Map.of("days", 30), 200);
    var cleaner =
        new com.cloudnest.file.TrashRetention(db, new com.cloudnest.file.DriveService(db));
    cleaner.cleanup();
    assertEquals(3, db.count("SELECT COUNT(*) FROM drive_entry WHERE user_id=?", u.id));
    db.update("UPDATE drive_entry SET deleted_at=? WHERE id=?", old, child);
    cleaner.cleanup();
    cleaner.cleanup();
    assertEquals(1, db.count("SELECT COUNT(*) FROM drive_entry WHERE user_id=?", u.id));
    call(u, put("/api/user/trash-policy"), Map.of("days", 366), 400);
  }

  @Test
  void IMP_I11_adminPrivilegesQuotaAndDisableRevokeSessions() throws Exception {
    User admin = register("operator"), u = register("managed");
    call(u, get("/api/admin/users"), null, 403);
    db.update("UPDATE sys_user SET administrator=TRUE WHERE id=?", admin.id);
    call(admin, get("/api/admin/users"), null, 200);
    init(u, u.root, "reserved", text("reserve"));
    call(admin, patch("/api/admin/users/" + u.id), Map.of("quota", 1, "enabled", true), 409);
    call(admin, patch("/api/admin/users/" + u.id), Map.of("quota", 1024, "enabled", false), 200);
    call(u, get("/api/user/me"), null, 401);
    assertEquals(0, db.count("SELECT COUNT(*) FROM auth_session WHERE user_id=?", u.id));
    assertEquals(
        0,
        read(call(b, get("/api/user/activity"), null, 200))
            .findValuesAsText("USER_UPDATED")
            .size());
  }

  @Test
  void IMP_I09_thumbnailIsPrivateBoundedAndRemovedAfterPurge() throws Exception {
    User u = register("thumb");
    var image =
        new java.awt.image.BufferedImage(600, 300, java.awt.image.BufferedImage.TYPE_INT_RGB);
    var out = new ByteArrayOutputStream();
    javax.imageio.ImageIO.write(image, "png", out);
    String file = upload(u, u.root, "picture.png", out.toByteArray());
    call(b, get("/api/files/" + file + "/thumbnail"), null, 404);
    byte[] png = null;
    for (int i = 0; i < 50; i++) {
      var result =
          mvc.perform(get("/api/files/" + file + "/thumbnail").cookie(u.cookie)).andReturn();
      if (result.getResponse().getStatus() == 200) {
        png = result.getResponse().getContentAsByteArray();
        break;
      }
      assertEquals(202, result.getResponse().getStatus());
      Thread.sleep(20);
    }
    assertNotNull(png);
    var thumb = javax.imageio.ImageIO.read(new ByteArrayInputStream(png));
    assertEquals(256, thumb.getWidth());
    assertEquals(128, thumb.getHeight());
    call(u, delete("/api/files/" + file), null, 200);
    call(u, get("/api/files/" + file + "/thumbnail"), null, 404);
    call(u, delete("/api/trash/files/" + file), null, 200);
    uploads.cleanup();
    new com.cloudnest.file.Thumbnails(
            new com.cloudnest.file.DriveService(db), null, db, data.toString())
        .cleanup();
  }

  @Test
  void IMP_I07_publicZipBoundToShareAndRevision() throws Exception {
    User u = register("zipshare");
    String folder = folder(u, u.root, "collection"),
        file = upload(u, folder, "item.txt", text("first"));
    var share =
        read(
            call(
                u,
                post("/api/shares"),
                Map.of("fileId", folder, "hours", 1, "allowDownload", true),
                200));
    String base = "/api/public/shares/" + share.path("token").asText();
    String job =
        read(call(null, post(base + "/downloads"), Map.of("fileIds", List.of(file)), 200))
            .path("id")
            .asText();
    for (int i = 0; i < 100; i++) {
      var status = read(call(null, get(base + "/downloads/" + job), null, 200));
      if (status.path("status").asText().equals("READY")) break;
      Thread.sleep(20);
    }
    call(null, get(base + "/downloads/" + job + "/file"), null, 200);
    String replacement = upload(u, folder, "new.txt", text("second"));
    call(
        u,
        post("/api/files/" + file + "/versions"),
        Map.of("sourceId", replacement, "expectedRevision", 1),
        200);
    call(null, get(base + "/downloads/" + job + "/file"), null, 409);
    call(u, delete("/api/shares/" + share.path("id").asText()), null, 200);
    call(null, get(base + "/downloads/" + job), null, 410);
  }

  @Test
  void IMP_I11_invitationIsSingleUseAndCarriesQuota() throws Exception {
    User admin = register("inviteadmin");
    db.update("UPDATE sys_user SET administrator=TRUE WHERE id=?", admin.id);
    call(b, post("/api/admin/invitations"), Map.of("quota", 1048576), 403);
    var invite = read(call(admin, post("/api/admin/invitations"), Map.of("quota", 1048576), 200));
    String token = invite.path("token").asText(), name = "invited" + unique();
    var user =
        read(
            call(
                null,
                post("/api/auth/register"),
                Map.of("username", name, "password", "invited-password", "invitation", token),
                200));
    assertEquals(1048576, user.path("quota").asLong());
    call(
        null,
        post("/api/auth/register"),
        Map.of(
            "username", "second" + unique(), "password", "invited-password", "invitation", token),
        409);
    assertEquals(
        1,
        db.count(
            "SELECT COUNT(*) FROM invitation WHERE token_hash=? AND used_by IS NOT NULL",
            Util.hash(token)));
  }

  @Test
  void IMP_I07_editCodeAndPermissionRevokesOldGrants() throws Exception {
    User u = register("shareedit");
    String file = upload(u, u.root, "code.txt", text("private-code"));
    var share =
        read(
            call(
                u,
                post("/api/shares"),
                Map.of("fileId", file, "hours", 1, "code", "first-code", "allowDownload", true),
                200));
    String base = "/api/public/shares/" + share.path("token").asText(),
        id = share.path("id").asText();
    Cookie grant =
        call(null, post(base + "/verify"), Map.of("code", "first-code"), 200)
            .getResponse()
            .getCookie("CN_SHARE_" + id);
    call(null, get(base + "/preview").cookie(grant), null, 200);
    call(
        u,
        patch("/api/shares/" + id),
        Map.of("hours", 2, "allowDownload", false, "code", "second-code"),
        200);
    call(null, get(base + "/preview").cookie(grant), null, 403);
    call(null, post(base + "/verify"), Map.of("code", "first-code"), 403);
    Cookie next =
        call(null, post(base + "/verify"), Map.of("code", "second-code"), 200)
            .getResponse()
            .getCookie("CN_SHARE_" + id);
    call(null, get(base + "/download").cookie(next), null, 403);
    call(null, get(base + "/preview").cookie(next), null, 200);
    call(u, patch("/api/shares/" + id), Map.of("hours", 2, "allowDownload", true, "code", ""), 200);
    call(null, get(base + "/download"), null, 200);
    call(b, patch("/api/shares/" + id), Map.of("hours", 1, "allowDownload", true), 404);
  }

  @Test
  void IMP_I05_downloadHistoryIsOwnedAndSurvivesRequests() throws Exception {
    User u = register("joblist"), other = register("nojob");
    String file = upload(u, u.root, "archive.txt", text("archive"));
    String id =
        read(call(u, post("/api/downloads"), Map.of("fileIds", List.of(file)), 200))
            .path("id")
            .asText();
    var list = read(call(u, get("/api/downloads"), null, 200));
    assertEquals(id, list.get(0).path("id").asText());
    assertEquals(file, list.get(0).path("fileIds").get(0).asText());
    assertEquals(0, read(call(other, get("/api/downloads"), null, 200)).size());
  }

  @Test
  void IMP_I11_deniedResourceAttemptIsPrivateAuditEvent() throws Exception {
    User owner = register("auditowner"), visitor = register("auditvisitor");
    String folder = folder(owner, owner.root, "private");
    call(visitor, get("/api/folders/" + folder + "/breadcrumbs"), null, 404);
    assertEquals(
        1,
        db.count(
            "SELECT COUNT(*) FROM activity_log WHERE user_id=? AND action='ACCESS_DENIED'",
            visitor.id));
    assertEquals(
        0,
        db.count(
            "SELECT COUNT(*) FROM activity_log WHERE user_id=? AND action='ACCESS_DENIED'",
            owner.id));
    var event = read(call(visitor, get("/api/user/activity"), null, 200)).get(0);
    assertEquals("ACCESS_DENIED", event.path("action").asText());
    assertTrue(event.path("target_id").isNull());
  }

  @Test
  void UPD01_adminQueueRequiresLiveWorkerAndRejectsDuplicateActions() throws Exception {
    User admin = register("updateradmin");
    db.update("UPDATE sys_user SET administrator=TRUE WHERE id=?", admin.id);
    call(b, get("/api/admin/updates"), null, 403);
    call(b, post("/api/admin/updates"), Map.of("action", "update"), 403);
    assertFalse(
        read(call(admin, get("/api/admin/updates"), null, 200)).path("enabled").asBoolean());
    call(admin, post("/api/admin/updates"), Map.of("action", "update"), 503);
    Path updater = data.resolve("updater");
    Files.createDirectories(updater.resolve("state"));
    Files.createDirectories(updater.resolve("requests"));
    Path heartbeat = updater.resolve("state/heartbeat"),
        request = updater.resolve("requests/request.json");
    Files.writeString(heartbeat, "");
    try {
      call(admin, post("/api/admin/updates"), Map.of("action", "shell"), 400);
      call(admin, post("/api/admin/updates"), Map.of("action", "check"), 200);
      assertEquals("check", json.readTree(Files.readString(request)).path("action").asText());
      call(admin, post("/api/admin/updates"), Map.of("action", "update"), 409);
      Files.delete(request);
      Files.writeString(
          updater.resolve("state/status.json"), "{\"busy\":true,\"phase\":\"BUILDING\"}");
      call(admin, post("/api/admin/updates"), Map.of("action", "update"), 409);
      Files.setLastModifiedTime(heartbeat, java.nio.file.attribute.FileTime.fromMillis(0));
      call(admin, post("/api/admin/updates"), Map.of("action", "check"), 503);
    } finally {
      Files.deleteIfExists(request);
      Files.deleteIfExists(heartbeat);
      Files.deleteIfExists(updater.resolve("state/status.json"));
    }
  }

  String readyArchive(User user, List<String> ids) throws Exception {
    String id =
        read(call(user, post("/api/downloads"), Map.of("fileIds", ids), 200)).path("id").asText();
    for (int i = 0; i < 100; i++) {
      String status =
          read(call(user, get("/api/downloads/" + id), null, 200)).path("status").asText();
      if (status.equals("READY")) return id;
      assertNotEquals("FAILED", status);
      Thread.sleep(50);
    }
    fail("ZIP task did not finish");
    return id;
  }

  Map<String, String> archiveEntries(byte[] bytes) throws Exception {
    Map<String, String> result = new LinkedHashMap<>();
    try (var zip = new java.util.zip.ZipInputStream(new ByteArrayInputStream(bytes))) {
      for (var entry = zip.getNextEntry(); entry != null; entry = zip.getNextEntry()) {
        assertFalse(result.containsKey(entry.getName()), "Duplicate archive path");
        result.put(entry.getName(), new String(zip.readAllBytes(), StandardCharsets.UTF_8));
      }
    }
    return result;
  }

  @Test
  void ZIP01_foldersPreserveStructureEmptyDirectoriesAndDeduplicateSelections() throws Exception {
    User u = register("folderzip");
    String algo = folder(u, u.root, "algo"),
        en = folder(u, u.root, "en"),
        chapter = folder(u, algo, "算法"),
        empty = folder(u, chapter, "空目录");
    String note = upload(u, chapter, "笔记.txt", text("中文内容"));
    upload(u, en, "words.txt", text("vocabulary"));
    String removed = upload(u, algo, "deleted.txt", text("private trash"));
    call(u, delete("/api/files/" + removed), null, 200);
    call(b, post("/api/downloads"), Map.of("fileIds", List.of(algo)), 404);
    String job = readyArchive(u, List.of(note, empty, en, algo, algo));
    var entries =
        archiveEntries(
            call(u, get("/api/downloads/" + job + "/file"), null, 200)
                .getResponse()
                .getContentAsByteArray());
    assertEquals(
        Set.of("algo/", "algo/算法/", "algo/算法/空目录/", "algo/算法/笔记.txt", "en/", "en/words.txt"),
        entries.keySet());
    assertEquals("中文内容", entries.get("algo/算法/笔记.txt"));
    assertEquals("vocabulary", entries.get("en/words.txt"));
    var listing = read(call(u, get("/api/downloads"), null, 200)).get(0);
    assertEquals(2, listing.path("fileCount").asInt());
    assertEquals(4, listing.path("folderCount").asInt());
  }

  @Test
  void ZIP02_snapshotRejectsMovedOrChangedDescendantsAndSharedScopeEscape() throws Exception {
    User u = register("zipsnapshot");
    String root = folder(u, u.root, "shared"),
        child = folder(u, root, "child"),
        file = upload(u, child, "note.txt", text("v1"));
    String job = readyArchive(u, List.of(root));
    call(u, patch("/api/files/" + file), Map.of("name", "renamed.txt"), 200);
    call(u, get("/api/downloads/" + job + "/file"), null, 409);
    job = readyArchive(u, List.of(root));
    db.update("UPDATE drive_entry SET revision=revision+1 WHERE id=?", file);
    call(u, get("/api/downloads/" + job + "/file"), null, 409);
    var share =
        read(
            call(
                u,
                post("/api/shares"),
                Map.of("fileId", root, "hours", 1, "allowDownload", true),
                200));
    String base = "/api/public/shares/" + share.path("token").asText();
    String sharedJob =
        read(call(null, post(base + "/downloads"), Map.of("fileIds", List.of(child)), 200))
            .path("id")
            .asText();
    for (int i = 0; i < 100; i++) {
      if (read(call(null, get(base + "/downloads/" + sharedJob), null, 200))
          .path("status")
          .asText()
          .equals("READY")) break;
      Thread.sleep(50);
    }
    call(null, get(base + "/downloads/" + sharedJob + "/file"), null, 200);
    call(u, patch("/api/files/" + file), Map.of("parentId", u.root), 200);
    call(null, get(base + "/downloads/" + sharedJob + "/file"), null, 404);
  }

  @Test
  void ZIP03_archiveNamesAvoidExtractionCollisionsAndUnsafeWindowsNames() throws Exception {
    User u = register("zipnames");
    String left = folder(u, u.root, "left"),
        right = folder(u, u.root, "right"),
        a = folder(u, left, "same"),
        b = folder(u, right, "same");
    upload(u, a, "CON.txt", text("reserved"));
    upload(u, b, "CON.txt", text("other"));
    upload(u, a, "bad:name.txt", text("colon"));
    upload(u, a, "bad_name.txt", text("underscore"));
    String job = readyArchive(u, List.of(a, b));
    var entries =
        archiveEntries(
            call(u, get("/api/downloads/" + job + "/file"), null, 200)
                .getResponse()
                .getContentAsByteArray());
    assertTrue(entries.containsKey("same/_CON.txt"));
    assertTrue(entries.containsKey("(1) same/_CON.txt"));
    assertEquals(4, entries.entrySet().stream().filter(e -> !e.getKey().endsWith("/")).count());
    assertTrue(
        entries.keySet().stream()
            .noneMatch(path -> path.contains(":") || path.contains("..") || path.startsWith("/")));
  }

  @Test
  void ZIP04_recursiveEntryLimitIsEnforcedBeforeQueueing() throws Exception {
    User u = register("ziplimit");
    String root = folder(u, u.root, "limit");
    long now = System.currentTimeMillis();
    db.transaction(
        () -> {
          for (int i = 0; i < 2000; i++)
            db.update(
                "INSERT INTO drive_entry(id,user_id,parent_id,kind,name,created_at,updated_at)"
                    + " VALUES(?,?,?,'FOLDER',?,?,?)",
                Util.id(),
                u.id,
                root,
                "empty-" + i,
                now,
                now);
          return null;
        });
    call(u, post("/api/downloads"), Map.of("fileIds", List.of(root)), 413);
    assertEquals(0, db.count("SELECT COUNT(*) FROM download_job WHERE user_id=?", u.id));
  }

  @Test
  void P01_wordAndMarkdownPreviewPreserveAuthorizationAndDownload() throws Exception {
    byte[] bytes;
    try (var doc = new org.apache.poi.xwpf.usermodel.XWPFDocument();
        var out = new ByteArrayOutputStream()) {
      doc.createParagraph().createRun().setText("Private Word preview");
      doc.write(out);
      bytes = out.toByteArray();
    }
    String file = upload(a, a.root, unique() + ".docx", bytes);
    assertTrue(
        read(call(a, get("/api/files/" + file + "/preview"), null, 200))
            .path("html")
            .asText()
            .contains("Private Word preview"));
    call(b, get("/api/files/" + file + "/preview"), null, 404);
    assertArrayEquals(
        bytes,
        call(a, get("/api/files/" + file + "/download"), null, 200)
            .getResponse()
            .getContentAsByteArray());
    String token =
        read(call(
                a,
                post("/api/shares"),
                Map.of("fileId", file, "hours", 1, "allowDownload", false),
                200))
            .path("token")
            .asText();
    assertTrue(
        read(call(null, get("/api/public/shares/" + token + "/preview"), null, 200))
            .path("html")
            .asText()
            .contains("Private Word preview"));
    call(null, get("/api/public/shares/" + token + "/download"), null, 403);
    String md = upload(a, a.root, unique() + ".md", text("# Markdown heading"));
    assertEquals(
        "# Markdown heading",
        call(a, get("/api/files/" + md + "/preview"), null, 200)
            .getResponse()
            .getContentAsString());
    String htmlMd =
        upload(
            a,
            a.root,
            unique() + ".markdown",
            text("<html><script>alert(1)</script></html>\n\n# Markdown"));
    assertTrue(
        call(a, get("/api/files/" + htmlMd + "/preview"), null, 200)
            .getResponse()
            .getContentType()
            .startsWith("text/plain"));
    String bad = upload(a, a.root, unique() + ".doc", text("broken document"));
    call(a, get("/api/files/" + bad + "/preview"), null, 422);
  }
}
