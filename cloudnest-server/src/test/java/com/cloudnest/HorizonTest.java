package com.cloudnest;

import static com.cloudnest.common.Db.*;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;

import com.cloudnest.common.*;
import com.cloudnest.node.*;
import com.cloudnest.storage.*;
import com.cloudnest.upload.UploadService;
import java.io.*;
import java.nio.file.*;
import java.util.*;
import java.util.concurrent.*;
import org.junit.jupiter.api.*;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.context.bean.override.mockito.MockitoSpyBean;
import org.springframework.test.web.servlet.MockMvc;

/** In-process provider fault injection; real Garage and production recovery are separate gates. */
@SpringBootTest(
    properties = {
      "spring.datasource.url=jdbc:h2:mem:horizon;MODE=MySQL;DATABASE_TO_LOWER=TRUE;DB_CLOSE_DELAY=-1",
      "cloudnest.index-enabled=false",
      "cloudnest.part-size=1024"
    })
@AutoConfigureMockMvc
class HorizonTest {
  static Path data;

  static {
    try {
      data = Files.createTempDirectory("horizon-");
    } catch (IOException e) {
      throw new ExceptionInInitializerError(e);
    }
  }

  @DynamicPropertySource
  static void props(DynamicPropertyRegistry r) {
    r.add("cloudnest.data-dir", () -> data.toString());
    if (System.getenv("CN_HORIZON_DATABASE_URL") != null) {
      r.add("spring.datasource.url", () -> System.getenv("CN_HORIZON_DATABASE_URL"));
      r.add("spring.datasource.username", () -> "root");
      r.add("spring.datasource.password", () -> "");
    }
  }

  @Autowired Db db;
  @Autowired NodeService nodes;
  @Autowired PlacementService placement;
  @Autowired UploadService uploads;
  @Autowired ReplicaService replicas;
  @Autowired MockMvc mvc;
  @MockitoSpyBean ObjectStorage storage;
  @MockitoSpyBean MigrationService jobs;
  final Map<String, byte[]> remote = new ConcurrentHashMap<>();
  String uid, root;

  @BeforeEach
  void setup() throws Exception {
    reset(jobs, storage);
    doNothing().when(jobs).work();
    doNothing().when(jobs).repair();
    remote.clear();
    db.update("DELETE FROM storage_job_item");
    db.update("DELETE FROM storage_reservation");
    db.update("DELETE FROM blob_replica");
    db.update("DELETE FROM file_version");
    db.update("DELETE FROM drive_entry");
    db.update("DELETE FROM upload_part");
    db.update("DELETE FROM upload_session");
    db.update("DELETE FROM file_blob");
    db.update("DELETE FROM storage_copy");
    db.update("DELETE FROM system_setting WHERE name='storage'");
    db.update("UPDATE storage_placement_policy SET mode='MANUAL',min_reliability=0");
    db.update(
        "UPDATE storage_node SET"
            + " enabled=TRUE,management='ACTIVE',allow_upload=TRUE,allow_migration=TRUE,budget_bytes=1000000,reliability=90,failure_domain=backend");
    nodes.check("local", true);
    db.update(
        "UPDATE storage_node_health SET state='ONLINE',validated=TRUE,checked_at=? WHERE"
            + " backend='minio'",
        System.currentTimeMillis());
    doAnswer(
            call -> {
              remote.put(call.getArgument(1), ((InputStream) call.getArgument(2)).readAllBytes());
              return null;
            })
        .when(storage)
        .put(eq("minio"), anyString(), any(), anyLong());
    doAnswer(
            call -> {
              var bytes = remote.get(call.<String>getArgument(1));
              if (bytes == null) throw new IOException("offline");
              return new ByteArrayInputStream(
                  bytes,
                  (int) (long) call.getArgument(2),
                  bytes.length - (int) (long) call.getArgument(2));
            })
        .when(storage)
        .open(eq("minio"), anyString(), anyLong(), anyLong());
    doAnswer(
            call -> {
              remote.remove(call.<String>getArgument(1));
              return null;
            })
        .when(storage)
        .delete(eq("minio"), anyString());
    doAnswer(call -> storage.open("minio", call.getArgument(1), 0, call.getArgument(2)))
        .when(storage)
        .openWhole(eq("minio"), anyString(), anyLong());
    uid = createUser();
    root = s(db.one("SELECT root_id FROM sys_user WHERE id=?", uid), "root_id");
  }

  String createUser() {
    String id = Util.id(), folder = Util.id();
    db.update(
        "INSERT INTO sys_user(id,username,password_hash,nickname,root_id,storage_quota,created_at)"
            + " VALUES(?,?,?,?,?,1000000,?)",
        id,
        id,
        "test",
        "test",
        folder,
        System.currentTimeMillis());
    db.update(
        "INSERT INTO drive_entry(id,user_id,kind,name,created_at,updated_at)"
            + " VALUES(?,?,'FOLDER','root',?,?)",
        folder,
        id,
        System.currentTimeMillis(),
        System.currentTimeMillis());
    return id;
  }

  String upload(String name, byte[] bytes) throws Exception {
    var job =
        uploads.init(uid, root, name, bytes.length, Util.hash(new ByteArrayInputStream(bytes)));
    if (!"COMPLETE".equals(s(job, "status"))) {
      uploads.part(uid, s(job, "id"), 0, new ByteArrayInputStream(bytes));
      job = uploads.complete(uid, s(job, "id"));
    }
    return s(job, "fileId");
  }

  Map<String, Object> blob(String file) {
    return db.one(
        "SELECT b.* FROM file_blob b JOIN drive_entry e ON e.blob_id=b.id WHERE e.id=?", file);
  }

  String enqueue(Map<String, Object> b, String kind) {
    return db.atomic(uid, () -> nodes.locked(() -> jobs.enqueue(uid, b, "minio", kind, null)));
  }

  @Test
  void placementFiltersExplainsAndFreezesSessions() throws Exception {
    db.update("UPDATE storage_placement_policy SET mode='CAPACITY'");
    db.update("UPDATE storage_node SET budget_bytes=21474836480 WHERE backend='minio'");
    var session = uploads.init(uid, root, "fixed", 20, "a".repeat(64));
    assertEquals("minio", s(session, "backend"));
    assertTrue(
        db.count(
                "SELECT COUNT(*) FROM storage_placement_log WHERE backend='minio' AND reason LIKE"
                    + " '%CAPACITY%' AND user_id=?",
                uid)
            > 0);
    db.update("UPDATE storage_node_health SET state='OFFLINE' WHERE backend='minio'");
    assertEquals("local", nodes.locked(() -> placement.select(uid, 20)).backend());
    assertEquals("minio", s(uploads.status(uid, s(session, "id")), "backend"));
    db.update("UPDATE storage_node SET reliability=1 WHERE backend='local'");
    db.update("UPDATE storage_placement_policy SET min_reliability=80");
    assertThrows(Problem.class, () -> nodes.locked(() -> placement.select(uid, 20)));
  }

  @Test
  void parallelPlacementReservesWithinOneBudget() throws Exception {
    String second = createUser();
    String secondRoot = s(db.one("SELECT root_id FROM sys_user WHERE id=?", second), "root_id");
    db.update("UPDATE storage_placement_policy SET mode='CAPACITY'");
    db.update("UPDATE storage_node SET budget_bytes=100 WHERE backend='local'");
    db.update("UPDATE storage_node_health SET state='OFFLINE' WHERE backend='minio'");
    var start = new CountDownLatch(1);
    try (var pool = Executors.newFixedThreadPool(2)) {
      var a =
          pool.submit(
              () -> {
                start.await();
                try {
                  uploads.init(uid, root, "a", 70, "b".repeat(64));
                  return true;
                } catch (Problem p) {
                  return false;
                }
              });
      var b =
          pool.submit(
              () -> {
                start.await();
                try {
                  uploads.init(second, secondRoot, "b", 70, "c".repeat(64));
                  return true;
                } catch (Problem p) {
                  return false;
                }
              });
      start.countDown();
      assertEquals(
          1, (a.get(10, TimeUnit.SECONDS) ? 1 : 0) + (b.get(10, TimeUnit.SECONDS) ? 1 : 0));
      assertEquals(70, nodes.reserved("local"));
    }
  }

  @Test
  void highAvailabilityReservesBothDomainsThenCopiesAndFailsOver() throws Exception {
    db.update("UPDATE storage_placement_policy SET mode='HIGH_AVAILABILITY'");
    byte[] content = "protected horizon".getBytes();
    String file = upload("important", content);
    var b = blob(file);
    String primary = s(b, "backend"), secondary = primary.equals("local") ? "minio" : "local";
    var task = db.one("SELECT * FROM storage_job_item WHERE blob_id=?", s(b, "id"));
    assertEquals(content.length, nodes.reserved(secondary));
    jobs.run(s(task, "id"));
    b = blob(file);
    assertEquals(
        2,
        db.count(
            "SELECT COUNT(*) FROM blob_replica WHERE blob_id=? AND state='VERIFIED'", s(b, "id")));
    db.update("UPDATE storage_node_health SET state='OFFLINE' WHERE backend=?", primary);
    try (var in = replicas.open(b, 2, 5)) {
      assertArrayEquals(Arrays.copyOfRange(content, 2, 7), in.readAllBytes());
    }
    db.update("UPDATE storage_node_health SET state='OFFLINE' WHERE backend=?", secondary);
    var current = b;
    Problem error = assertThrows(Problem.class, () -> replicas.open(current, 0, content.length));
    assertEquals("REPLICAS_UNAVAILABLE", error.code);
    assertEquals(0, nodes.tempReserved());
  }

  @Test
  void sameDomainCannotPretendToBeHighAvailability() {
    db.update("UPDATE storage_placement_policy SET mode='HIGH_AVAILABILITY'");
    db.update("UPDATE storage_node SET failure_domain='one-machine'");
    assertThrows(Problem.class, () -> uploads.init(uid, root, "invalid", 10, "a".repeat(64)));
    assertEquals(0, db.count("SELECT COUNT(*) FROM upload_session"));
    assertEquals(0, nodes.tempReserved());
  }

  @Test
  void fileMigrationIsScopedVerifiedRetryableAndPreservesSource() throws Exception {
    var b = blob(upload("one", "first".getBytes()));
    var other = blob(upload("two", "second".getBytes()));
    String id = enqueue(b, "MIGRATE");
    doAnswer(call -> new ByteArrayInputStream("wrong".getBytes()))
        .when(storage)
        .open(eq("minio"), anyString(), anyLong(), anyLong());
    jobs.run(id);
    assertEquals(
        "FAILED", s(db.one("SELECT status FROM storage_job_item WHERE id=?", id), "status"));
    assertEquals(
        "local", s(db.one("SELECT backend FROM file_blob WHERE id=?", s(b, "id")), "backend"));
    doAnswer(call -> new ByteArrayInputStream(remote.get(call.<String>getArgument(1))))
        .when(storage)
        .open(eq("minio"), anyString(), anyLong(), anyLong());
    String retry = jobs.retry(uid, id);
    jobs.run(retry);
    assertEquals(
        "COMPLETE", s(db.one("SELECT status FROM storage_job_item WHERE id=?", retry), "status"));
    assertEquals(
        "minio", s(db.one("SELECT backend FROM file_blob WHERE id=?", s(b, "id")), "backend"));
    assertEquals(
        "local", s(db.one("SELECT backend FROM file_blob WHERE id=?", s(other, "id")), "backend"));
    assertTrue(storage.exists("local", s(b, "object_key")));
    assertEquals(0, nodes.reserved("minio"));
  }

  @Test
  void corruptPrimaryIsNeverReturnedAndCanBeRepaired() throws Exception {
    byte[] bytes = "checksum".getBytes();
    String file = upload("corrupt", bytes);
    var b = blob(file);
    String id = enqueue(b, "REPLICATE");
    jobs.run(id);
    storage.put("local", s(b, "object_key"), new ByteArrayInputStream("corrupt!".getBytes()), 8);
    b = blob(file);
    try (var in = replicas.open(b, 0, bytes.length)) {
      assertArrayEquals(bytes, in.readAllBytes());
    }
    assertEquals(
        "CORRUPT",
        s(
            db.one(
                "SELECT state FROM blob_replica WHERE blob_id=? AND backend='local'", s(b, "id")),
            "state"));
    var current = b;
    String repair =
        db.atomic(
            uid, () -> nodes.locked(() -> jobs.enqueue(uid, current, "local", "REPLICATE", null)));
    jobs.run(repair);
    assertTrue(replicas.verify(b, "local", s(b, "object_key")));
  }

  @Test
  void queuedCancellationAndCrossUserAuthorization() throws Exception {
    String file = upload("private", "private".getBytes());
    var b = blob(file);
    String id = enqueue(b, "REPLICATE");
    mvc.perform(get("/api/storage/files/" + file + "/replicas").with(user("stranger")))
        .andExpect(r -> assertEquals(404, r.getResponse().getStatus()));
    mvc.perform(delete("/api/storage/jobs/" + id).with(user("stranger")).with(csrf()))
        .andExpect(r -> assertEquals(403, r.getResponse().getStatus()));
    mvc.perform(get("/api/admin/horizon").with(user(uid)))
        .andExpect(r -> assertEquals(403, r.getResponse().getStatus()));
    jobs.cancel(uid, id);
    jobs.run(id);
    assertEquals(
        "CANCELLED", s(db.one("SELECT status FROM storage_job_item WHERE id=?", id), "status"));
    assertEquals(0, nodes.reserved("minio"));
  }

  @Test
  void runningCancellationDoesNotWaitForProviderOrChangePrimary() throws Exception {
    var b = blob(upload("cancel-running", "cancel".getBytes()));
    String id = enqueue(b, "MIGRATE");
    var entered = new CountDownLatch(1);
    var resume = new CountDownLatch(1);
    doAnswer(
            call -> {
              entered.countDown();
              assertTrue(resume.await(10, TimeUnit.SECONDS));
              remote.put(call.getArgument(1), ((InputStream) call.getArgument(2)).readAllBytes());
              return null;
            })
        .when(storage)
        .put(eq("minio"), anyString(), any(), anyLong());
    try (var pool = Executors.newSingleThreadExecutor()) {
      var worker = pool.submit(() -> jobs.run(id));
      assertTrue(entered.await(5, TimeUnit.SECONDS));
      try {
        assertTimeoutPreemptively(java.time.Duration.ofSeconds(2), () -> jobs.cancel(uid, id));
        assertEquals(
            "CANCELLED", s(db.one("SELECT status FROM storage_job_item WHERE id=?", id), "status"));
      } finally {
        resume.countDown();
      }
      worker.get(10, TimeUnit.SECONDS);
      assertEquals(
          "local", s(db.one("SELECT backend FROM file_blob WHERE id=?", s(b, "id")), "backend"));
      assertTrue(nodes.used("minio") >= n(b, "size"));
    }
  }

  @Test
  void restartInterruptsRunningTasksAndRetainsPhysicalAccounting() throws Exception {
    var b = blob(upload("restart", "restart".getBytes()));
    String id = enqueue(b, "MIGRATE");
    nodes.copied("minio", s(b, "object_key"), n(b, "size"));
    db.update("UPDATE storage_job_item SET status='RUNNING' WHERE id=?", id);
    org.springframework.test.util.ReflectionTestUtils.invokeMethod(jobs, "recover");
    assertEquals(
        "INTERRUPTED", s(db.one("SELECT status FROM storage_job_item WHERE id=?", id), "status"));
    assertEquals(0, nodes.reserved("minio"));
    assertTrue(nodes.used("minio") >= n(b, "size"));
  }

  @Test
  void replicaManifestRoundTripsAndDetectsTampering() throws Exception {
    String file = upload("manifest", "manifest".getBytes());
    var b = blob(file);
    var export = new Maintenance(db, storage, null, "object-manifest");
    org.springframework.test.util.ReflectionTestUtils.setField(
        export, "dataDirectory", data.toString());
    org.springframework.test.util.ReflectionTestUtils.setField(
        export, "legacyEndpoint", "http://fixture.internal");
    export.perform();
    var verify = new Maintenance(db, storage, null, "verify-manifest");
    org.springframework.test.util.ReflectionTestUtils.setField(
        verify, "dataDirectory", data.toString());
    assertEquals(0, verify.perform().get("problems"));
    db.update("UPDATE blob_replica SET state='CORRUPT' WHERE blob_id=?", s(b, "id"));
    assertThrows(IllegalStateException.class, verify::perform);
  }

  @Test
  void disablingProtectionCancelsReplicaWorkWithoutDeletingData() throws Exception {
    String file = upload("disable", "disable".getBytes());
    var b = blob(file);
    String id = enqueue(b, "REPLICATE");
    mvc.perform(delete("/api/storage/files/" + file + "/protection").with(user(uid)).with(csrf()))
        .andExpect(r -> assertEquals(200, r.getResponse().getStatus()));
    assertEquals(1, db.count("SELECT desired_replicas FROM file_blob WHERE id=?", s(b, "id")));
    assertEquals(
        "CANCELLED", s(db.one("SELECT status FROM storage_job_item WHERE id=?", id), "status"));
    assertTrue(storage.exists("local", s(b, "object_key")));
    assertEquals(0, nodes.reserved("minio"));
  }

  @Test
  void capacityExhaustionAppearsInAdministratorAlerts() throws Exception {
    upload("budget", "budget".getBytes());
    db.update("UPDATE sys_user SET administrator=TRUE WHERE id=?", uid);
    db.update("UPDATE storage_node SET budget_bytes=7 WHERE backend='local'");
    var result = mvc.perform(get("/api/admin/horizon").with(user(uid))).andReturn();
    assertEquals(200, result.getResponse().getStatus());
    assertTrue(result.getResponse().getContentAsString().contains("CAPACITY_WARNING"));
  }

  @Test
  void emptyProtectedFileStillRequiresAnAvailableVerifiedObject() throws Exception {
    String file = upload("empty", new byte[0]);
    var b = blob(file);
    String task = enqueue(b, "REPLICATE");
    jobs.run(task);
    var response = mvc.perform(get("/api/files/" + file + "/download").with(user(uid))).andReturn();
    assertEquals(200, response.getResponse().getStatus());
    db.update("UPDATE storage_node_health SET state='OFFLINE'");
    response = mvc.perform(get("/api/files/" + file + "/download").with(user(uid))).andReturn();
    assertEquals(503, response.getResponse().getStatus());
  }
}
