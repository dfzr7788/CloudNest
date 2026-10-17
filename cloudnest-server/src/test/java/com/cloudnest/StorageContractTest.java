package com.cloudnest;

import static org.junit.jupiter.api.Assertions.*;

import com.cloudnest.common.*;
import com.cloudnest.storage.*;
import com.sun.net.httpserver.*;
import java.io.*;
import java.net.*;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import org.junit.jupiter.api.*;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;

/** S3 HTTP contract fixture, explicitly NOT a real MinIO deployment test. */
@SpringBootTest(
    properties = {
      "spring.datasource.url=jdbc:h2:mem:storagecontract;MODE=MySQL;DATABASE_TO_LOWER=TRUE;DB_CLOSE_DELAY=-1"
    })
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class StorageContractTest {
  static final Map<String, byte[]> objects = new ConcurrentHashMap<>();
  static HttpServer server;
  static Path data;
  static volatile boolean corruptRead = false;

  static {
    try {
      data = Files.createTempDirectory("cloudnest-storage-");
      server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
      server.createContext(
          "/",
          exchange -> {
            try {
              String path = exchange.getRequestURI().getPath(),
                  query = exchange.getRequestURI().getQuery(),
                  method = exchange.getRequestMethod();
              exchange.getResponseHeaders().set("x-amz-request-id", "fixture-request");
              if (query != null && query.contains("location")) {
                byte[] xml =
                    "<LocationConstraint xmlns=\"http://s3.amazonaws.com/doc/2006-03-01/\">us-east-1</LocationConstraint>"
                        .getBytes(StandardCharsets.UTF_8);
                exchange.getResponseHeaders().set("Content-Type", "application/xml");
                exchange.sendResponseHeaders(200, xml.length);
                exchange.getResponseBody().write(xml);
                return;
              }
              if (path.equals("/cloudnest") && method.equals("HEAD")) {
                exchange.sendResponseHeaders(200, -1);
                return;
              }
              if (method.equals("PUT")) {
                objects.put(path, exchange.getRequestBody().readAllBytes());
                exchange.getResponseHeaders().set("ETag", "\"fixture-etag\"");
                exchange.sendResponseHeaders(200, -1);
                return;
              }
              if (method.equals("DELETE")) {
                objects.remove(path);
                exchange.sendResponseHeaders(204, -1);
                return;
              }
              byte[] bytes = objects.get(path);
              if (bytes == null) {
                exchange.getResponseHeaders().set("Content-Type", "application/xml");
                byte[] xml =
                    "<Error><Code>NoSuchKey</Code><Message>not found</Message><Resource>fixture</Resource><RequestId>fixture</RequestId></Error>"
                        .getBytes(StandardCharsets.UTF_8);
                exchange.sendResponseHeaders(404, method.equals("HEAD") ? -1 : xml.length);
                if (!method.equals("HEAD")) exchange.getResponseBody().write(xml);
                return;
              }
              exchange.getResponseHeaders().set("ETag", "\"fixture-etag\"");
              exchange.getResponseHeaders().set("Last-Modified", "Wed, 01 Jan 2025 00:00:00 GMT");
              exchange.getResponseHeaders().set("Content-Type", "application/octet-stream");
              if (method.equals("HEAD")) {
                exchange.getResponseHeaders().set("Content-Length", Integer.toString(bytes.length));
                exchange.sendResponseHeaders(200, -1);
                return;
              }
              if (corruptRead) {
                bytes = bytes.clone();
                if (bytes.length > 0) bytes[0] ^= 1;
              }
              String range = exchange.getRequestHeaders().getFirst("Range");
              int status = 200;
              if (range != null) {
                String[] p = range.substring(6).split("-");
                int start = Integer.parseInt(p[0]),
                    end =
                        p.length > 1
                            ? Math.min(bytes.length - 1, Integer.parseInt(p[1]))
                            : bytes.length - 1;
                exchange
                    .getResponseHeaders()
                    .set("Content-Range", "bytes " + start + "-" + end + "/" + bytes.length);
                bytes = Arrays.copyOfRange(bytes, start, end + 1);
                status = 206;
              }
              exchange.sendResponseHeaders(status, bytes.length == 0 ? -1 : bytes.length);
              exchange.getResponseBody().write(bytes);
            } finally {
              exchange.close();
            }
          });
      server.start();
    } catch (IOException e) {
      throw new ExceptionInInitializerError(e);
    }
  }

  @DynamicPropertySource
  static void properties(DynamicPropertyRegistry r) {
    r.add("cloudnest.data-dir", () -> data.toString());
    r.add("cloudnest.minio.endpoint", () -> "http://127.0.0.1:" + server.getAddress().getPort());
    r.add("cloudnest.minio.access-key", () -> "fixture-access");
    r.add("cloudnest.minio.secret-key", () -> "fixture-secret-key-only-for-test");
  }

  @Autowired Db db;
  @Autowired ObjectStorage storage;
  @Autowired com.cloudnest.storage.StorageController policies;

  @AfterAll
  void close() {
    server.stop(0);
  }

  @AfterEach
  void resetCorruption() {
    corruptRead = false;
  }

  String[] seed() throws Exception {
    String uid = Util.id(),
        root = Util.id(),
        blob = Util.id(),
        entry = Util.id(),
        key = "objects/" + Util.id();
    byte[] bytes = ("migration " + uid).getBytes(StandardCharsets.UTF_8);
    long now = System.currentTimeMillis();
    db.update(
        "INSERT INTO"
            + " sys_user(id,username,password_hash,nickname,root_id,storage_quota,storage_used,created_at)"
            + " VALUES(?,?,?,?,?,?,?,?)",
        uid,
        uid,
        "fixture",
        "fixture",
        root,
        1000000,
        bytes.length,
        now);
    db.update(
        "INSERT INTO drive_entry(id,user_id,kind,name,created_at,updated_at)"
            + " VALUES(?,?,'FOLDER','root',?,?)",
        root,
        uid,
        now,
        now);
    storage.put("local", key, new ByteArrayInputStream(bytes), bytes.length);
    db.update(
        "INSERT INTO file_blob(id,user_id,object_key,backend,size,sha256,mime,status,created_at)"
            + " VALUES(?,?,?,'local',?,?,'text/plain','READY',?)",
        blob,
        uid,
        key,
        bytes.length,
        Util.hash(new ByteArrayInputStream(bytes)),
        now);
    db.update(
        "INSERT INTO"
            + " drive_entry(id,user_id,parent_id,kind,name,blob_id,size,mime,created_at,updated_at)"
            + " VALUES(?,?,?,'FILE','test.txt',?,?,'text/plain',?,?)",
        entry,
        uid,
        root,
        blob,
        bytes.length,
        now,
        now);
    return new String[] {uid, blob, key};
  }

  @Test
  void M01_s3AdapterRoundTripRangeAndDelete() throws Exception {
    byte[] bytes = "s3 contract".getBytes(StandardCharsets.UTF_8);
    String key = "objects/" + Util.id();
    storage.put("minio", key, new ByteArrayInputStream(bytes), bytes.length);
    assertTrue(storage.exists("minio", key));
    try (var in = storage.open("minio", key, 3, 4)) {
      assertEquals("cont", new String(in.readAllBytes(), StandardCharsets.UTF_8));
    }
    storage.delete("minio", key);
    assertFalse(storage.exists("minio", key));
  }

  @Test
  void M02_migrationCopiesVerifiesSwitchesAndKeepsSource() throws Exception {
    String[] ids = seed();
    var output = new Maintenance(db, storage, null, "migrate-minio").perform();
    assertEquals(0, ((Number) output.get("problems")).intValue());
    assertEquals(
        "minio", Db.s(db.one("SELECT backend FROM file_blob WHERE id=?", ids[1]), "backend"));
    assertTrue(storage.exists("local", ids[2]));
    assertTrue(storage.exists("minio", ids[2]));
    assertEquals(
        0,
        ((Number) new Maintenance(db, storage, null, "migrate-minio").perform().get("problems"))
            .intValue());
  }

  @Test
  void M03_failedVerificationKeepsLocalReference() throws Exception {
    String[] ids = seed();
    corruptRead = true;
    var output = new Maintenance(db, storage, null, "migrate-minio").perform();
    assertTrue(((Number) output.get("problems")).intValue() > 0);
    assertEquals(
        "local", Db.s(db.one("SELECT backend FROM file_blob WHERE id=?", ids[1]), "backend"));
    assertTrue(storage.exists("local", ids[2]));
  }

  @Test
  void M04_recountRepairsLogicalCounters() throws Exception {
    String[] ids = seed();
    db.update("UPDATE sys_user SET storage_used=0 WHERE id=?", ids[0]);
    var before = new Maintenance(db, storage, null, "audit").perform();
    assertTrue(((Number) before.get("problems")).intValue() > 0);
    new Maintenance(db, storage, null, "recount").perform();
    assertEquals(
        0,
        ((Number) new Maintenance(db, storage, null, "audit").perform().get("problems"))
            .intValue());
  }

  @Test
  void M05_crashedLocalWriteCandidateCanBeCleaned() throws Exception {
    String id = Util.id(), key = "objects/" + id;
    Path directory = data.resolve("storage/objects");
    Files.createDirectories(directory);
    Path interrupted = directory.resolve(id + ".writing");
    Files.writeString(interrupted, "partial data");
    storage.delete("local", key);
    assertFalse(Files.exists(interrupted));
  }

  @Test
  void M06_configuredS3PolicyTestsAndMigratesWithVerification() throws Exception {
    String[] ids = seed();
    db.update("UPDATE sys_user SET administrator=TRUE WHERE id=?", ids[0]);
    var auth =
        new org.springframework.security.authentication.UsernamePasswordAuthenticationToken(
            ids[0], null);
    var policy =
        policies.policy(
            auth,
            new StorageController.Policy(
                null,
                "contract policy",
                "http://127.0.0.1:" + server.getAddress().getPort(),
                "cloudnest",
                "fixture-access",
                "fixture-secret-key-only-for-test",
                "us-east-1"));
    String backend = "s3:" + policy.get("id");
    assertEquals(true, policies.test(auth, new StorageController.Backend(backend)).get("ok"));
    String encrypted =
        Db.s(
            db.one("SELECT secret_key FROM storage_policy WHERE id=?", policy.get("id")),
            "secret_key");
    assertFalse(encrypted.contains("fixture-secret"));
    var migration = policies.migrate(auth, new StorageController.Migration("local", backend));
    String status = "";
    for (int n = 0; n < 200; n++) {
      status =
          Db.s(
              db.one("SELECT status FROM maintenance_job WHERE id=?", migration.get("id")),
              "status");
      if (Set.of("COMPLETE", "FAILED").contains(status)) break;
      Thread.sleep(25);
    }
    assertEquals("COMPLETE", status);
    assertEquals(
        backend, Db.s(db.one("SELECT backend FROM file_blob WHERE id=?", ids[1]), "backend"));
    assertTrue(storage.exists("local", ids[2]));
    assertThrows(Problem.class, () -> policies.remove(auth, policy.get("id").toString()));
  }

  @Test
  void M07_portableBackupRestoresRemoteObjectsWithoutOriginalBucket() throws Exception {
    seed();
    new Maintenance(db, storage, null, "migrate-minio").perform();
    var export = new Maintenance(db, storage, null, "export-objects");
    org.springframework.test.util.ReflectionTestUtils.setField(
        export, "dataDirectory", data.toString());
    assertEquals(0, ((Number) export.perform().get("problems")).intValue());
    objects.clear();
    var restore = new Maintenance(db, storage, null, "restore-local-objects");
    org.springframework.test.util.ReflectionTestUtils.setField(
        restore, "dataDirectory", data.toString());
    assertEquals(0, ((Number) restore.perform().get("problems")).intValue());
    assertEquals(0, db.count("SELECT COUNT(*) FROM file_blob WHERE backend<>'local'"));
    assertEquals(
        0,
        ((Number) new Maintenance(db, storage, null, "audit").perform().get("problems"))
            .intValue());
  }
}
