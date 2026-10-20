package com.cloudnest;

import static org.junit.jupiter.api.Assertions.*;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;

import com.cloudnest.common.*;
import com.cloudnest.file.DriveService;
import com.cloudnest.sync.*;
import com.fasterxml.jackson.databind.*;
import java.nio.file.*;
import java.util.*;
import org.junit.jupiter.api.*;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.*;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;

@SpringBootTest(
    properties = {
      "spring.datasource.url=jdbc:h2:mem:sync-dev;MODE=MySQL;DATABASE_TO_LOWER=TRUE;DB_CLOSE_DELAY=-1",
      "cloudnest.worker.enabled=false",
      "cloudnest.index-enabled=false"
    })
@ActiveProfiles("connected-dev")
@AutoConfigureMockMvc
class SyncIntegrationTest {
  static final Path data;

  static {
    try {
      data = Files.createTempDirectory("cloudnest-sync-");
    } catch (Exception e) {
      throw new ExceptionInInitializerError(e);
    }
  }

  @DynamicPropertySource
  static void properties(DynamicPropertyRegistry r) {
    r.add("cloudnest.data-dir", data::toString);
    String mysql = System.getenv("CN_SYNC_TEST_DATABASE_URL");
    if (mysql != null) {
      r.add("spring.datasource.url", () -> mysql);
      r.add("spring.datasource.username", () -> "root");
      r.add("spring.datasource.password", () -> "");
    }
  }

  @Autowired Db db;
  @Autowired MockMvc mvc;
  @Autowired ObjectMapper json;
  @Autowired DriveService drive;
  @Autowired SyncService sync;
  @Autowired SyncDevices devices;
  @Autowired com.cloudnest.agent.AgentApprovalService approvals;
  @Autowired com.cloudnest.upload.UploadService uploads;
  @Autowired com.cloudnest.control.IdentityAuthorization identity;
  @Autowired com.cloudnest.control.FileMetadata metadata;
  @Autowired com.cloudnest.control.StorageTaskCoordinator coordinator;
  String uid, root, deviceId, token;
  SyncDevices.Identity who;

  String user() {
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
    uid = user();
    root = drive.root(uid);
    var issued = devices.issue(uid, "Windows test");
    deviceId = issued.get("id").toString();
    token = issued.get("token").toString();
    who = new SyncDevices.Identity(uid, deviceId);
  }

  JsonNode call(MockHttpServletRequestBuilder request, Object body, int expected) throws Exception {
    request.header("Authorization", "SyncDevice " + token);
    if (body != null) request.contentType("application/json").content(json.writeValueAsBytes(body));
    var response = mvc.perform(request.with(csrf())).andReturn().getResponse();
    assertEquals(expected, response.getStatus(), response.getContentAsString());
    return response.getContentAsByteArray().length == 0
        ? json.createObjectNode()
        : json.readTree(response.getContentAsByteArray());
  }

  JsonNode op(String kind, Map<String, Object> values) {
    var input = new HashMap<String, Object>(values);
    input.put("kind", kind);
    input.put("operationId", Util.id());
    input.put("createdAt", System.currentTimeMillis());
    return json.valueToTree(input);
  }

  JsonNode firstEntry(Map<String, Object> snapshot) {
    return json.valueToTree(snapshot.get("entries")).path(0);
  }

  JsonNode approvalRequest() throws Exception {
    db.update("UPDATE sys_user SET administrator=TRUE WHERE id=?", uid);
    db.update(
        "UPDATE storage_node SET"
            + " enabled=TRUE,management='ACTIVE',allow_migration=TRUE,budget_bytes=1000000,failure_domain=backend");
    db.update(
        "UPDATE storage_node_health SET state='ONLINE',validated=TRUE,checked_at=?",
        System.currentTimeMillis());
    byte[] bytes = "approval source".getBytes(java.nio.charset.StandardCharsets.UTF_8);
    var session =
        uploads.init(
            uid,
            root,
            "source.txt",
            bytes.length,
            Util.hash(new java.io.ByteArrayInputStream(bytes)));
    String id = session.get("id").toString();
    uploads.part(uid, id, 0, new java.io.ByteArrayInputStream(bytes));
    var complete = uploads.complete(uid, id);
    return json.valueToTree(
        Map.of("kind", "MIGRATE", "target", "minio", "fileIds", List.of(complete.get("fileId"))));
  }

  @Test
  void independentControlBoundariesRecheckRoleOwnershipAndRevision() throws Exception {
    var input = approvalRequest();
    String fid = input.path("fileIds").path(0).asText();
    var file = metadata.ownedFile(uid, fid);
    assertEquals(fid, file.id());
    String other = user();
    var command =
        new com.cloudnest.control.StorageTaskCoordinator.Command(
            fid,
            file.revision(),
            "minio",
            com.cloudnest.control.StorageTaskCoordinator.Kind.MIGRATE);
    assertEquals(
        "ADMIN_REQUIRED",
        assertThrows(Problem.class, () -> coordinator.submit(other, command)).code);
    db.update("UPDATE sys_user SET administrator=TRUE WHERE id=?", other);
    assertEquals(404, assertThrows(Problem.class, () -> coordinator.submit(other, command)).status);
    assertEquals(404, assertThrows(Problem.class, () -> metadata.ownedFile(other, fid)).status);
    var stale =
        new com.cloudnest.control.StorageTaskCoordinator.Command(
            fid,
            file.revision() + 1,
            "minio",
            com.cloudnest.control.StorageTaskCoordinator.Kind.MIGRATE);
    assertEquals(
        "FILE_REVISION_CHANGED",
        assertThrows(Problem.class, () -> coordinator.submit(uid, stale)).code);
    assertEquals(0, db.count("SELECT COUNT(*) FROM storage_job_item WHERE user_id=?", uid));
    db.update("UPDATE sys_user SET enabled=FALSE WHERE id=?", uid);
    assertEquals(
        "ACCOUNT_DISABLED", assertThrows(Problem.class, () -> metadata.ownedFile(uid, fid)).code);
    assertEquals(
        "ADMIN_REQUIRED", assertThrows(Problem.class, () -> coordinator.submit(uid, command)).code);
    db.update("UPDATE sys_user SET enabled=TRUE WHERE id=?", uid);
    identity.requireAdministrator(uid);
    db.update("UPDATE drive_entry SET deleted=TRUE WHERE id=?", root);
    assertEquals(404, assertThrows(Problem.class, () -> coordinator.submit(uid, command)).status);
    db.update("UPDATE drive_entry SET deleted=FALSE WHERE id=?", root);
    String job = coordinator.submit(uid, command);
    assertEquals(
        "QUEUED", db.one("SELECT status FROM storage_job_item WHERE id=?", job).get("status"));
    assertEquals(
        "local", db.one("SELECT backend FROM file_blob WHERE id=?", file.blobId()).get("backend"));
  }

  @Test
  void deterministicSimulationAndHumanApprovalSubmitOnlyOnce() throws Exception {
    var input = approvalRequest();
    var plan = approvals.simulate(uid, input);
    assertEquals(0, db.count("SELECT COUNT(*) FROM storage_job_item WHERE user_id=?", uid));
    String key = Util.id();
    var proposal = approvals.propose(uid, key, input);
    assertEquals(plan.get("proposalHash"), proposal.get("proposal_hash"));
    assertEquals(proposal, approvals.propose(uid, key, input));
    assertEquals(0, db.count("SELECT COUNT(*) FROM storage_job_item WHERE user_id=?", uid));
    String id = proposal.get("id").toString(), hash = proposal.get("proposal_hash").toString();
    var approved = approvals.decide(uid, id, hash, true);
    assertEquals("SUBMITTED", approved.get("status"));
    assertEquals(approved, approvals.decide(uid, id, hash, true));
    assertEquals(
        1,
        db.count("SELECT COUNT(*) FROM storage_job_item WHERE user_id=? AND status='QUEUED'", uid));
    assertEquals(
        "local", db.one("SELECT backend FROM file_blob WHERE user_id=?", uid).get("backend"));
    assertEquals(
        404,
        assertThrows(Problem.class, () -> approvals.invoke(uid, "approve_storage_action", input))
            .status);
    String other = user();
    db.update("UPDATE sys_user SET administrator=TRUE WHERE id=?", other);
    assertEquals(
        404, assertThrows(Problem.class, () -> approvals.decide(other, id, hash, true)).status);
  }

  @Test
  void changedMetadataOrBudgetCannotBeApprovedAgainstOldHash() throws Exception {
    var input = approvalRequest();
    var proposal = approvals.propose(uid, Util.id(), input);
    String fid = input.path("fileIds").path(0).asText();
    drive.edit(uid, fid, "renamed.txt", null, "FILE");
    assertEquals(
        "AGENT_PROPOSAL_STALE",
        assertThrows(
                Problem.class,
                () ->
                    approvals.decide(
                        uid,
                        proposal.get("id").toString(),
                        proposal.get("proposal_hash").toString(),
                        true))
            .code);
    var newer = approvals.propose(uid, Util.id(), input);
    db.update("UPDATE storage_node SET budget_bytes=2000000 WHERE backend='minio'");
    assertEquals(
        "AGENT_PROPOSAL_STALE",
        assertThrows(
                Problem.class,
                () ->
                    approvals.decide(
                        uid,
                        newer.get("id").toString(),
                        newer.get("proposal_hash").toString(),
                        true))
            .code);
    assertEquals(0, db.count("SELECT COUNT(*) FROM storage_job_item WHERE user_id=?", uid));
  }

  @Test
  void expiredRejectedAndForeignFileProposalsCannotSubmit() throws Exception {
    var input = approvalRequest();
    var proposal = approvals.propose(uid, Util.id(), input);
    String id = proposal.get("id").toString(), hash = proposal.get("proposal_hash").toString();
    db.update("UPDATE agent_approval SET expires_at=0 WHERE id=?", id);
    assertEquals(
        "AGENT_APPROVAL_EXPIRED",
        assertThrows(Problem.class, () -> approvals.decide(uid, id, hash, true)).code);
    var rejected = approvals.propose(uid, Util.id(), input);
    approvals.decide(
        uid, rejected.get("id").toString(), rejected.get("proposal_hash").toString(), false);
    assertThrows(
        Problem.class,
        () ->
            approvals.decide(
                uid,
                rejected.get("id").toString(),
                rejected.get("proposal_hash").toString(),
                true));
    var foreign =
        json.valueToTree(
            Map.of("kind", "MIGRATE", "target", "minio", "fileIds", List.of(Util.id())));
    assertThrows(Problem.class, () -> approvals.propose(uid, Util.id(), foreign));
    assertEquals(0, db.count("SELECT COUNT(*) FROM storage_job_item WHERE user_id=?", uid));
  }

  @Test
  void renameMoveTrashRestoreAndCoalescedFeedPreserveIdentity() {
    var initial = sync.bootstrap(who);
    assertTrue(((List<?>) initial.get("entries")).isEmpty());
    String id = drive.createFolder(uid, root, "folder").get("id").toString();
    var created = sync.changes(who, initial.get("cursor").toString());
    assertEquals(
        id,
        json.valueToTree(created.get("events"))
            .path(0)
            .path("change")
            .path("entry")
            .path("id")
            .asText());
    var before = firstEntry(sync.bootstrap(who));
    drive.edit(uid, id, "renamed", null, "FOLDER");
    var after = firstEntry(sync.bootstrap(who));
    assertEquals(before.path("revision"), after.path("revision"));
    assertNotEquals(before.path("changeToken"), after.path("changeToken"));
    drive.trash(uid, id, "FOLDER");
    var deleted = sync.changes(who, sync.bootstrap(who).get("cursor").toString());
    assertTrue(((List<?>) deleted.get("events")).isEmpty());
    var feed = sync.changes(who, created.get("cursor").toString());
    assertTrue(json.valueToTree(feed).toString().contains("tombstone"));
    sync.operate(
        who,
        op("RESTORE", Map.of("entryId", id, "expectedRevision", after.path("revision").asLong())));
    assertEquals(id, firstEntry(sync.bootstrap(who)).path("id").asText());
  }

  @Test
  void operationsAreDurableIdempotentAndMetadataTokenPreventsLostRename() {
    var input = op("MKDIR", Map.of("parentId", root, "name", "one"));
    var a = sync.operate(who, input);
    assertEquals(a, sync.operate(who, input));
    assertEquals(
        1, db.count("SELECT COUNT(*) FROM drive_entry WHERE user_id=? AND parent_id=?", uid, root));
    ((com.fasterxml.jackson.databind.node.ObjectNode) input).put("name", "two");
    assertThrows(Problem.class, () -> sync.operate(who, input));
    var entry = firstEntry(sync.bootstrap(who));
    drive.edit(uid, a.path("id").asText(), "cloud rename", null, "FOLDER");
    var p =
        assertThrows(
            Problem.class,
            () ->
                sync.operate(
                    who,
                    op(
                        "TRASH",
                        Map.of(
                            "entryId",
                            a.path("id").asText(),
                            "expectedToken",
                            entry.path("changeToken").asText()))));
    assertEquals("SYNC_CONFLICT", p.code);
    assertEquals("cloud rename", drive.folder(uid, a.path("id").asText()).get("name"));
  }

  @Test
  void deviceScopeCsrfOwnershipAndRevocationAreEnforced() throws Exception {
    call(get("/api/sync/client/bootstrap"), null, 200);
    assertEquals(
        200,
        mvc.perform(
                get("/api/sync/client/bootstrap").header("Authorization", "syncdevice " + token))
            .andReturn()
            .getResponse()
            .getStatus());
    call(get("/api/admin/horizon"), null, 403);
    call(get("/api/sync/devices"), null, 403);
    assertEquals(
        403,
        mvc.perform(
                get("/api/sync/client/bootstrap")
                    .with(
                        org.springframework.security.test.web.servlet.request
                            .SecurityMockMvcRequestPostProcessors.user(uid)))
            .andReturn()
            .getResponse()
            .getStatus());
    assertEquals(
        403,
        mvc.perform(
                post("/api/sync/client/operations")
                    .header("Authorization", "SyncDevice " + token)
                    .contentType("application/json")
                    .content("{}"))
            .andReturn()
            .getResponse()
            .getStatus());
    String other = user();
    assertThrows(Problem.class, () -> devices.revoke(other, deviceId));
    devices.revoke(uid, deviceId);
    call(get("/api/sync/client/bootstrap"), null, 401);
    assertThrows(Problem.class, () -> sync.bootstrap(who));
  }

  @Test
  void expiredOrForeignCursorRequiresSnapshotWithoutSilentReset() {
    var old = sync.bootstrap(who);
    String other = user();
    var issued = devices.issue(other, "other");
    var otherDevice = new SyncDevices.Identity(other, issued.get("id").toString());
    assertEquals(
        "SYNC_CURSOR_EXPIRED",
        assertThrows(Problem.class, () -> sync.changes(otherDevice, old.get("cursor").toString()))
            .code);
    drive.createFolder(uid, root, "new");
    var latest = sync.bootstrap(who);
    db.update("UPDATE sync_clock SET floor_seq=seq WHERE user_id=?", uid);
    assertEquals(
        410,
        assertThrows(Problem.class, () -> sync.changes(who, old.get("cursor").toString())).status);
    assertTrue(
        ((List<?>) sync.changes(who, latest.get("cursor").toString()).get("events")).isEmpty());
  }

  @Test
  void scopedResumableUploadsDownloadAndConcurrentReplaceConflict() throws Exception {
    byte[] bytes = "hello sync".getBytes(java.nio.charset.StandardCharsets.UTF_8);
    var init =
        op(
            "INIT_UPLOAD",
            Map.of(
                "parentId",
                root,
                "name",
                "hello.txt",
                "size",
                bytes.length,
                "sha256",
                Util.hash(new java.io.ByteArrayInputStream(bytes))));
    var upload = call(post("/api/sync/client/operations"), init, 200);
    assertEquals(upload, call(post("/api/sync/client/operations"), init, 200));
    String id = upload.path("id").asText();
    call(put("/api/sync/client/uploads/" + id + "/parts/0").content(bytes), null, 200);
    assertEquals(
        1, call(get("/api/sync/client/uploads/" + id), null, 200).path("uploadedParts").size());
    var complete = call(post("/api/sync/client/uploads/" + id + "/complete"), null, 200);
    assertEquals(complete, call(post("/api/sync/client/uploads/" + id + "/complete"), null, 200));
    var response =
        mvc.perform(
                get("/api/sync/client/files/" + complete.path("fileId").asText() + "/download")
                    .header("Authorization", "SyncDevice " + token))
            .andReturn()
            .getResponse();
    assertEquals(200, response.getStatus());
    assertArrayEquals(bytes, response.getContentAsByteArray());
    var entry = firstEntry(sync.bootstrap(who));
    var second = devices.issue(uid, "second");
    String saved = token;
    token = second.get("token").toString();
    call(get("/api/sync/client/uploads/" + id), null, 404);
    token = saved;
    drive.edit(uid, complete.path("fileId").asText(), "changed.txt", null, "FILE");
    assertEquals(
        "SYNC_CONFLICT",
        assertThrows(
                Problem.class,
                () ->
                    sync.operate(
                        who,
                        op(
                            "INIT_UPLOAD",
                            Map.of(
                                "parentId",
                                root,
                                "name",
                                "hello.txt",
                                "size",
                                bytes.length,
                                "sha256",
                                entry.path("sha256").asText(),
                                "entryId",
                                entry.path("id").asText(),
                                "expectedToken",
                                entry.path("changeToken").asText()))))
            .code);
  }

  @Test
  void folderDeletionDoesNotSilentlyDeleteNewChildrenAndOperationsExpire() {
    String id = drive.createFolder(uid, root, "parent").get("id").toString();
    var before = firstEntry(sync.bootstrap(who));
    drive.createFolder(uid, id, "new child");
    assertEquals(
        "SYNC_FOLDER_NOT_EMPTY",
        assertThrows(
                Problem.class,
                () ->
                    sync.operate(
                        who,
                        op(
                            "TRASH",
                            Map.of(
                                "entryId",
                                id,
                                "expectedToken",
                                before.path("changeToken").asText()))))
            .code);
    var expired = op("MKDIR", Map.of("parentId", root, "name", "expired"));
    ((com.fasterxml.jackson.databind.node.ObjectNode) expired).put("createdAt", 0);
    assertEquals(410, assertThrows(Problem.class, () -> sync.operate(who, expired)).status);
  }
}
