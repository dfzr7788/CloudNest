package com.cloudnest;

import static com.cloudnest.common.Db.*;
import static org.junit.jupiter.api.Assertions.*;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;

import com.cloudnest.common.*;
import com.cloudnest.file.*;
import com.cloudnest.resilience.*;
import com.cloudnest.storage.*;
import com.cloudnest.upload.*;
import com.cloudnest.workspace.*;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.io.*;
import java.nio.file.*;
import java.util.*;
import org.junit.jupiter.api.*;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.*;
import org.springframework.test.web.servlet.MockMvc;

@SpringBootTest(
    properties = {
      "spring.datasource.url=jdbc:h2:mem:resilience-workspace;MODE=MySQL;DATABASE_TO_LOWER=TRUE;DB_CLOSE_DELAY=-1",
      "cloudnest.worker.enabled=false",
      "cloudnest.index-enabled=false"
    })
@AutoConfigureMockMvc
class ResilienceWorkspaceTest {
  static final Path data;

  static {
    try {
      data = Files.createTempDirectory("cloudnest-next-");
    } catch (IOException e) {
      throw new ExceptionInInitializerError(e);
    }
  }

  @DynamicPropertySource
  static void properties(DynamicPropertyRegistry r) {
    r.add("cloudnest.data-dir", data::toString);
    String mysql = System.getenv("CN_NEXT_TEST_DATABASE_URL");
    if (mysql != null) {
      r.add("spring.datasource.url", () -> mysql);
      r.add("spring.datasource.username", () -> "root");
      r.add("spring.datasource.password", () -> "");
    }
  }

  @Autowired Db db;
  @Autowired DriveService drive;
  @Autowired UploadService uploads;
  @Autowired SnapshotService snapshots;
  @Autowired ResilienceTasks tasks;
  @Autowired TeamService teams;
  @Autowired ReplicaService replicas;
  @Autowired TransferPlanner transfers;
  @Autowired MockMvc mvc;
  @Autowired ObjectMapper json;
  @Autowired ContributionController contributions;
  @Autowired MigrationService migrations;
  @Autowired TeamStorage teamStorage;
  @Autowired com.cloudnest.node.NodeService nodes;
  String uid, root;

  String newUser() {
    String id = Util.id(), rid = Util.id();
    long now = System.currentTimeMillis();
    db.update(
        "INSERT INTO sys_user(id,username,password_hash,nickname,root_id,storage_quota,created_at)"
            + " VALUES(?,?,?,?,?,10000000,?)",
        id,
        id,
        "test",
        "test",
        rid,
        now);
    db.update(
        "INSERT INTO drive_entry(id,user_id,parent_id,kind,name,created_at,updated_at)"
            + " VALUES(?,?,NULL,'FOLDER','root',?,?)",
        rid,
        id,
        now,
        now);
    return id;
  }

  @BeforeEach
  void setup() {
    uid = newUser();
    root = drive.root(uid);
    db.update(
        "UPDATE storage_node_health SET state='ONLINE',validated=TRUE,checked_at=? WHERE"
            + " backend='local'",
        System.currentTimeMillis());
  }

  String file(String owner, String folder, String name, String body) throws Exception {
    byte[] bytes = body.getBytes(java.nio.charset.StandardCharsets.UTF_8);
    var session =
        uploads.init(owner, folder, name, bytes.length, Util.hash(new ByteArrayInputStream(bytes)));
    if (!s(session, "status").equals("COMPLETE")) {
      uploads.part(owner, s(session, "id"), 0, new ByteArrayInputStream(bytes));
      session = uploads.complete(owner, s(session, "id"));
    }
    return s(session, "fileId");
  }

  void run(String owner, String id) {
    for (int i = 0; i < 20010; i++) {
      var t = tasks.owned(owner, id);
      if (Set.of("COMPLETE", "FAILED", "PAUSED", "CANCELLED").contains(s(t, "status"))) return;
      tasks.step(id);
    }
    fail("Task failed to settle");
  }

  int request(
      String actor,
      org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder req,
      Object body)
      throws Exception {
    if (body != null) req.contentType("application/json").content(json.writeValueAsBytes(body));
    return mvc.perform(req.with(user(actor)).with(csrf())).andReturn().getResponse().getStatus();
  }

  @Test
  void thousandFileSnapshotSurvivesPurgeAndRestoresWithRetryWithoutOverwriting() throws Exception {
    String folder = s(drive.createFolder(uid, root, "project"), "id");
    String first = file(uid, folder, "file-0.txt", "historic bytes");
    var e = drive.file(uid, first);
    long now = System.currentTimeMillis();
    for (int i = 1; i < 1000; i++)
      db.update(
          "INSERT INTO"
              + " drive_entry(id,user_id,parent_id,kind,name,blob_id,size,mime,created_at,updated_at)"
              + " VALUES(?,?,?,'FILE',?,?,?,?,?,?)",
          Util.id(),
          uid,
          folder,
          "file-" + i + ".txt",
          e.get("blob_id"),
          e.get("size"),
          e.get("mime"),
          now,
          now);
    db.update(
        "UPDATE sys_user SET storage_used=storage_used+? WHERE id=?", 999 * n(e, "size"), uid);
    var snap = snapshots.create(uid, folder, "before changes");
    String sid = s(snap, "id"), bid = s(e, "blob_id");
    assertEquals(1001, n(snap, "entry_count"));
    drive.edit(uid, first, "renamed.txt", root, "FILE");
    assertTrue(
        json.valueToTree(snapshots.diff(uid, sid, null))
            .path("changes")
            .toString()
            .contains("MOVED"));
    drive.trash(uid, folder, "FOLDER");
    drive.purge(uid, folder, "FOLDER");
    drive.trash(uid, first, "FILE");
    drive.purge(uid, first, "FILE");
    assertEquals("READY", s(db.one("SELECT status FROM file_blob WHERE id=?", bid), "status"));
    assertTrue(drive.referenced(bid));
    var job = tasks.restore(uid, sid, root, null, "thousand_test");
    String id = s(job, "id");
    assertEquals(id, s(tasks.restore(uid, sid, root, null, "thousand_test"), "id"));
    tasks.step(id);
    tasks.action(uid, id, "pause");
    tasks.step(id);
    assertEquals(1, n(tasks.owned(uid, id), "progress"));
    assertEquals(409, assertThrows(Problem.class, () -> snapshots.delete(uid, sid)).status);
    tasks.action(uid, id, "resume");
    // A failed next step retains the prior checkpoint and can be retried with the same job id.
    db.update(
        "UPDATE resilience_task SET status='FAILED',error_code='TEST_INTERRUPTION' WHERE id=?", id);
    tasks.action(uid, id, "retry");
    run(uid, id);
    assertEquals("COMPLETE", s(tasks.owned(uid, id), "status"));
    String restored =
        s(
            db.one("SELECT restored_root FROM snapshot_restore_job WHERE task_id=?", id),
            "restored_root");
    assertEquals(1000, db.count("SELECT COUNT(*) FROM drive_entry WHERE parent_id=?", restored));
    var restoredFile =
        db.one("SELECT * FROM drive_entry WHERE parent_id=? AND name='file-0.txt'", restored);
    assertEquals(bid, s(restoredFile, "blob_id"));
    snapshots.delete(uid, sid);
    assertTrue(drive.referenced(bid));
    assertEquals(
        n(e, "size") * 1000, db.count("SELECT storage_used FROM sys_user WHERE id=?", uid));
  }

  @Test
  void singleFileRestorePreservesCurrentVersionAndDeniesCrossUserSnapshots() throws Exception {
    String fid = file(uid, root, "current.txt", "old");
    String sid = s(snapshots.create(uid, root, null), "id");
    var old = drive.file(uid, fid);
    var upload =
        uploads.init(uid, root, "current.txt", 3, Util.hash("new"), fid, n(old, "revision"));
    uploads.part(uid, s(upload, "id"), 0, new ByteArrayInputStream("new".getBytes()));
    uploads.complete(uid, s(upload, "id"));
    String stranger = newUser();
    assertEquals(
        404, request(stranger, get("/api/resilience/snapshots/" + sid + "/entries"), null));
    assertEquals(
        404,
        request(
            stranger,
            post("/api/resilience/snapshots/" + sid + "/restore"),
            Map.of("requestKey", "cross_user_test")));
    var preview = tasks.preview(uid, sid, root, fid);
    assertEquals(1, ((List<?>) preview.get("entries")).size());
    String job = s(tasks.restore(uid, sid, root, fid, "single_test"), "id");
    run(uid, job);
    assertEquals("new", read(drive.file(uid, fid)));
    String copy =
        s(
            db.one("SELECT restored_root FROM snapshot_restore_job WHERE task_id=?", job),
            "restored_root");
    assertEquals("old", read(drive.file(uid, copy)));
    assertEquals(1, db.count("SELECT COUNT(*) FROM file_version WHERE entry_id=?", fid));
  }

  String read(Map<String, Object> entry) throws Exception {
    var blob = db.one("SELECT * FROM file_blob WHERE id=?", entry.get("blob_id"));
    try (var in = replicas.open(blob, 0, n(blob, "size"))) {
      return new String(in.readAllBytes(), java.nio.charset.StandardCharsets.UTF_8);
    }
  }

  @Test
  void integrityMarksCorruptionWithoutDeletingAndBlocksUnprotectedBadReads() throws Exception {
    assertEquals(
        403,
        request(uid, post("/api/resilience/integrity"), Map.of("requestKey", "no_admin_test")));
    db.update("UPDATE sys_user SET administrator=TRUE WHERE id=?", uid);
    String fid = file(uid, root, "broken.txt", "expected");
    var entry = drive.file(uid, fid);
    var blob = db.one("SELECT * FROM file_blob WHERE id=?", entry.get("blob_id"));
    Files.writeString(data.resolve("storage").resolve(s(blob, "object_key")), "damaged!");
    String id = s(tasks.scan(uid, "local", uid, root, "corrupt_test"), "id");
    run(uid, id);
    var item = db.one("SELECT * FROM integrity_scan_item WHERE task_id=?", id);
    assertEquals("CORRUPT", s(item, "status"));
    assertEquals("NO_VALID_REPLICA", s(item, "error_code"));
    assertEquals(
        "READY", s(db.one("SELECT status FROM file_blob WHERE id=?", blob.get("id")), "status"));
    assertEquals(
        503, assertThrows(Problem.class, () -> replicas.open(blob, 0, n(blob, "size"))).status);
    assertTrue(Files.exists(data.resolve("storage").resolve(s(blob, "object_key"))));
  }

  @Test
  void quotaCountsSnapshotReferencesAndReleasesOnlyAfterLastReference() throws Exception {
    String fid = file(uid, root, "keep.txt", "12345678");
    String bid = s(drive.file(uid, fid), "blob_id");
    String sid = s(snapshots.create(uid, root, null), "id");
    assertEquals(16, db.count("SELECT storage_used FROM sys_user WHERE id=?", uid));
    drive.trash(uid, fid, "FILE");
    drive.purge(uid, fid, "FILE");
    assertEquals(8, db.count("SELECT storage_used FROM sys_user WHERE id=?", uid));
    uploads.cleanup();
    assertEquals("READY", s(db.one("SELECT status FROM file_blob WHERE id=?", bid), "status"));
    snapshots.delete(uid, sid);
    assertEquals("DELETING", s(db.one("SELECT status FROM file_blob WHERE id=?", bid), "status"));
    assertEquals(0, db.count("SELECT storage_used FROM sys_user WHERE id=?", uid));
  }

  @Test
  void teamQuotaInvitationRolesAclAndUploadRevocationAreIsolated() throws Exception {
    String viewer = newUser(), editor = newUser(), storageOnly = newUser(), stranger = newUser();
    String team = s(teams.create(uid, "Test team", 1000000), "id");
    assertEquals(9000000, db.count("SELECT storage_quota FROM sys_user WHERE id=?", uid));
    String
        storage =
            s(db.one("SELECT storage_user FROM team_workspace WHERE id=?", team), "storage_user"),
        teamRoot = drive.root(storage);
    for (var pair :
        List.of(
            Map.entry(viewer, "VIEWER"),
            Map.entry(editor, "EDITOR"),
            Map.entry(storageOnly, "NONE"))) {
      var invite = teams.invite(uid, team, pair.getValue(), pair.getKey().equals(storageOnly), 24);
      teams.accept(pair.getKey(), s(invite, "token"));
      assertThrows(Problem.class, () -> teams.accept(stranger, s(invite, "token")));
    }
    String fid = file(storage, teamRoot, "team.txt", "team secret");
    assertEquals(404, request(stranger, get("/api/teams/" + team + "/files"), null));
    assertEquals(403, request(storageOnly, get("/api/teams/" + team + "/files"), null));
    assertEquals(
        200, request(viewer, get("/api/teams/" + team + "/files/" + fid + "/download"), null));
    assertEquals(403, request(viewer, delete("/api/teams/" + team + "/entries/" + fid), null));
    assertEquals(404, request(editor, get("/api/files/" + fid + "/download"), null));
    String privateFile = file(uid, root, "personal.txt", "private");
    assertEquals(
        404,
        request(editor, get("/api/teams/" + team + "/files/" + privateFile + "/download"), null));
    teams.acl(uid, team, fid, viewer, "DENY");
    assertEquals(
        403, request(viewer, get("/api/teams/" + team + "/files/" + fid + "/download"), null));
    var result =
        mvc.perform(get("/api/teams/" + team + "/files").with(user(viewer)))
            .andReturn()
            .getResponse();
    assertFalse(result.getContentAsString().contains("team.txt"));
    var request =
        Map.of("folderId", teamRoot, "name", "later.txt", "size", 4, "sha256", Util.hash("data"));
    var response =
        mvc.perform(
                post("/api/teams/" + team + "/uploads/init")
                    .with(user(editor))
                    .with(csrf())
                    .contentType("application/json")
                    .content(json.writeValueAsBytes(request)))
            .andReturn()
            .getResponse();
    assertEquals(200, response.getStatus(), response.getContentAsString());
    String session = json.readTree(response.getContentAsByteArray()).path("id").asText();
    teams.role(uid, team, editor, "VIEWER", false, false);
    assertEquals(
        403,
        request(
            editor,
            put("/api/teams/" + team + "/uploads/" + session + "/parts/0").content("data"),
            null));
    assertEquals(
        403,
        request(editor, post("/api/teams/" + team + "/uploads/" + session + "/complete"), null));
    assertEquals(403, request(viewer, get("/api/teams/" + team + "/resilience/snapshots"), null));
    assertEquals(
        200,
        request(
            uid,
            post("/api/teams/" + team + "/resilience/snapshots"),
            Map.of("folderId", teamRoot)));
  }

  @Test
  void uploadPlannerRetainsProxyResumeForUnsupportedNodesAndOwnership() throws Exception {
    var session = uploads.init(uid, root, "resume.bin", 3, Util.hash("abc"));
    String id = s(session, "id");
    assertEquals("proxy", transfers.plan(uid, id).get("mode"));
    assertEquals(404, assertThrows(Problem.class, () -> transfers.plan(newUser(), id)).status);
    uploads.part(uid, id, 0, new ByteArrayInputStream("abc".getBytes()));
    uploads.complete(uid, id);
    assertThrows(Problem.class, () -> transfers.plan(uid, id));
  }

  @Test
  void retentionRollsBackPruningOnQuotaFailureAndLaterKeepsConfiguredCount() throws Exception {
    String folder = s(drive.createFolder(uid, root, "retention"), "id");
    String file = file(uid, folder, "a.txt", "1234");
    String snapshot = s(snapshots.create(uid, folder, null), "id");
    file(uid, folder, "b.txt", "5678");
    snapshots.policy(uid, folder, true, 1, 1, "IMPORTANT");
    assertEquals(
        2,
        db.count(
            "SELECT desired_replicas FROM file_blob WHERE id=?",
            drive.file(uid, file).get("blob_id")));
    db.update("UPDATE sys_user SET storage_quota=storage_used WHERE id=?", uid);
    db.update("UPDATE snapshot_retention_policy SET next_run=0 WHERE user_id=?", uid);
    var scheduler = new SnapshotService(db, drive, new WorkerLifecycle(true, false));
    scheduler.scheduled();
    assertEquals(snapshot, s(snapshots.list(uid, folder).getFirst(), "id"));
    assertEquals(
        "QUOTA_EXCEEDED",
        s(
            db.one("SELECT last_error FROM snapshot_retention_policy WHERE user_id=?", uid),
            "last_error"));
    assertEquals(12, db.count("SELECT storage_used FROM sys_user WHERE id=?", uid));
    db.update("UPDATE sys_user SET storage_quota=10000000 WHERE id=?", uid);
    db.update("UPDATE snapshot_retention_policy SET next_run=0 WHERE user_id=?", uid);
    scheduler.scheduled();
    assertEquals(1, snapshots.list(uid, folder).size());
    assertNotEquals(snapshot, s(snapshots.list(uid, folder).getFirst(), "id"));
    assertEquals(16, db.count("SELECT storage_used FROM sys_user WHERE id=?", uid));
  }

  @Test
  void scanIdempotencyBindsScopeAndRevokedAdministratorStopsBeforeNextObject() throws Exception {
    db.update("UPDATE sys_user SET administrator=TRUE WHERE id=?", uid);
    file(uid, root, "scan.txt", "scan");
    String id = s(tasks.scan(uid, "local", uid, root, "scoped_scan_key"), "id");
    assertEquals(id, s(tasks.scan(uid, "local", uid, root, "scoped_scan_key"), "id"));
    assertEquals(
        409,
        assertThrows(Problem.class, () -> tasks.scan(uid, null, uid, root, "scoped_scan_key"))
            .status);
    db.update("UPDATE sys_user SET administrator=FALSE WHERE id=?", uid);
    tasks.step(id);
    assertEquals("FAILED", s(tasks.owned(uid, id), "status"));
    assertEquals("ADMIN_REQUIRED", s(tasks.owned(uid, id), "error_code"));
    assertEquals(0, n(tasks.owned(uid, id), "progress"));
  }

  @Test
  void contributionRegistrationDoesNotProbeAndRequiresSeparateApprovals() throws Exception {
    String team = s(teams.create(uid, "Nodes", 1000000), "id"), contributor = newUser();
    teams.accept(contributor, s(teams.invite(uid, team, "NONE", true, 24), "token"));
    var input =
        Map.of(
            "name",
            "Scoped node",
            "endpoint",
            "http://127.0.0.1:1",
            "nodeToken",
            Util.token(),
            "capacityBytes",
            1048576);
    assertEquals(403, request(uid, post("/api/teams/" + team + "/contributions"), input));
    var response =
        mvc.perform(
                post("/api/teams/" + team + "/contributions")
                    .with(user(contributor))
                    .with(csrf())
                    .contentType("application/json")
                    .content(json.writeValueAsBytes(input)))
            .andReturn()
            .getResponse();
    assertEquals(200, response.getStatus(), response.getContentAsString());
    var node = json.readTree(response.getContentAsByteArray());
    String id = node.path("id").asText();
    assertEquals(0, db.count("SELECT COUNT(*) FROM storage_node WHERE backend=?", "peer:" + id));
    assertEquals(
        403,
        request(
            contributor, post("/api/teams/" + team + "/contributions/" + id + "/review"), null));
    assertEquals(
        200, request(uid, post("/api/teams/" + team + "/contributions/" + id + "/review"), null));
    assertEquals(
        403,
        request(
            uid,
            post("/api/admin/contributions/" + id + "/approve"),
            Map.of(
                "failureDomain",
                "member-machine",
                "reliability",
                50,
                "performanceClass",
                "STANDARD")));
    assertEquals(
        401,
        mvc.perform(
                post("/api/public/nodes/" + id + "/heartbeat")
                    .header("X-Node-Token", node.path("heartbeatToken").asText()))
            .andReturn()
            .getResponse()
            .getStatus());
    String saved =
        s(db.one("SELECT secret_key FROM storage_contribution WHERE id=?", id), "secret_key");
    assertFalse(saved.contains(input.get("nodeToken").toString()));
  }

  @Test
  void realScopedNodeMigrationSnapshotRestoreCorruptionRepairAndOfflineFallback() throws Exception {
    db.update("UPDATE sys_user SET administrator=TRUE WHERE id=?", uid);
    String team = s(teams.create(uid, "Peer integration", 1000000), "id"), contributor = newUser();
    teams.accept(contributor, s(teams.invite(uid, team, "NONE", true, 24), "token"));
    String
        storage =
            s(db.one("SELECT storage_user FROM team_workspace WHERE id=?", team), "storage_user"),
        teamRoot = drive.root(storage);
    Path directory = Files.createTempDirectory(data, "peer-"),
        nodeData = directory.resolve("contributed"),
        tokenFile = directory.resolve("token.txt");
    String token = Util.token();
    Files.writeString(tokenFile, token);
    int port;
    try (var socket = new java.net.ServerSocket(0)) {
      port = socket.getLocalPort();
    }
    Path script =
        Path.of("..").toAbsolutePath().normalize().resolve("clients/storage-node/node.py");
    var process =
        new ProcessBuilder(
                System.getenv().getOrDefault("CN_PYTHON", "python"),
                script.toString(),
                "--directory",
                nodeData.toString(),
                "--budget",
                "1048576",
                "--token-file",
                tokenFile.toString(),
                "--port",
                String.valueOf(port))
            .redirectErrorStream(true)
            .redirectOutput(directory.resolve("node.log").toFile())
            .start();
    try {
      boolean ready = false;
      for (int i = 0; i < 80; i++) {
        try {
          var connection =
              (java.net.HttpURLConnection)
                  java.net
                      .URI
                      .create("http://127.0.0.1:" + port + "/health")
                      .toURL()
                      .openConnection();
          connection.setConnectTimeout(100);
          connection.setReadTimeout(100);
          try {
            ready = connection.getResponseCode() == 401;
          } finally {
            connection.disconnect();
          }
          if (ready) break;
        } catch (IOException notYet) {
        }
        Thread.sleep(50);
      }
      assertTrue(
          ready,
          Files.exists(directory.resolve("node.log"))
              ? Files.readString(directory.resolve("node.log"))
              : "Node not ready");
      var actor =
          new org.springframework.security.authentication.UsernamePasswordAuthenticationToken(
              contributor, "test", List.of());
      var admin =
          new org.springframework.security.authentication.UsernamePasswordAuthenticationToken(
              uid, "test", List.of());
      var registered =
          (Map<?, ?>)
              contributions.register(
                  actor,
                  team,
                  new ContributionController.Register(
                      "Member disk", "http://127.0.0.1:" + port, token, 1048576));
      String node = registered.get("id").toString(), backend = "peer:" + node;
      contributions.review(admin, team, node);
      contributions.approve(
          admin,
          node,
          new ContributionController.Approve("member-physical-device", 70, "CAPACITY"));
      String fid = file(storage, teamRoot, "protected.txt", "cross node bytes");
      String sid = s(snapshots.create(storage, teamRoot, null), "id");
      var blob =
          db.one("SELECT * FROM file_blob WHERE id=?", drive.file(storage, fid).get("blob_id"));
      var submitted =
          (Map<?, ?>)
              teamStorage.submit(
                  admin, team, new TeamStorage.Submit(backend, "MIGRATE", List.of(fid)));
      String migration = ((List<?>) submitted.get("ids")).getFirst().toString();
      migrations.run(migration);
      assertEquals(
          "COMPLETE",
          s(db.one("SELECT status FROM storage_job_item WHERE id=?", migration), "status"));
      assertEquals("cross node bytes", read(drive.file(storage, fid)));
      assertEquals(
          403, assertThrows(Problem.class, () -> nodes.authorizeTarget(uid, backend)).status);
      Files.writeString(nodeData.resolve(s(blob, "object_key")), "corrupted peer");
      String scan = s(tasks.scan(uid, backend, storage, teamRoot, "peer_corruption"), "id");
      run(uid, scan);
      var item = db.one("SELECT * FROM integrity_scan_item WHERE task_id=?", scan);
      assertEquals("CORRUPT", s(item, "status"));
      assertNotNull(item.get("repair_task"));
      migrations.run(s(item, "repair_task"));
      assertEquals(
          "COMPLETE",
          s(
              db.one("SELECT status FROM storage_job_item WHERE id=?", item.get("repair_task")),
              "status"));
      drive.trash(storage, fid, "FILE");
      drive.purge(storage, fid, "FILE");
      String restore = s(tasks.restore(storage, sid, teamRoot, null, "peer_restore"), "id");
      run(storage, restore);
      assertEquals("COMPLETE", s(tasks.owned(storage, restore), "status"));
      String restored =
          s(
              db.one("SELECT restored_root FROM snapshot_restore_job WHERE task_id=?", restore),
              "restored_root");
      var restoredFile =
          db.one("SELECT * FROM drive_entry WHERE parent_id=? AND kind='FILE'", restored);
      assertEquals("cross node bytes", read(restoredFile));
      process.destroy();
      assertTrue(process.waitFor(5, java.util.concurrent.TimeUnit.SECONDS));
      assertEquals("cross node bytes", read(restoredFile));
      db.update("UPDATE storage_contribution SET last_heartbeat=0 WHERE id=?", node);
      assertEquals(
          "CONTRIBUTION_UNAVAILABLE",
          assertThrows(Problem.class, () -> nodes.admit(backend, false, 1)).code);
    } finally {
      if (process.isAlive()) {
        process.destroyForcibly();
        process.waitFor(5, java.util.concurrent.TimeUnit.SECONDS);
      }
    }
  }
}
