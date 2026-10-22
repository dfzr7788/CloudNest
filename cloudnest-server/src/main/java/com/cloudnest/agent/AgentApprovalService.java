package com.cloudnest.agent;

import static com.cloudnest.common.Db.*;

import com.cloudnest.common.*;
import com.cloudnest.control.FileMetadata;
import com.cloudnest.control.StorageTaskCoordinator;
import com.cloudnest.node.*;
import com.cloudnest.user.RuntimeSettings;
import com.fasterxml.jackson.databind.*;
import jakarta.annotation.PostConstruct;
import java.util.*;
import java.util.function.Supplier;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

/** Deterministic proposals; human session API is the only path that submits storage jobs. */
@Service
public class AgentApprovalService {
  private final Db db;
  private final FileMetadata drive;
  private final NodeService nodes;
  private final StorageTaskCoordinator jobs;
  private final RuntimeSettings settings;
  private final ObjectMapper json;
  private final boolean enabled;

  public AgentApprovalService(
      Db db,
      FileMetadata drive,
      NodeService nodes,
      StorageTaskCoordinator jobs,
      RuntimeSettings settings,
      ObjectMapper json,
      @Value("${cloudnest.agent.approvals-enabled:false}") boolean enabled) {
    this.db = db;
    this.drive = drive;
    this.nodes = nodes;
    this.jobs = jobs;
    this.settings = settings;
    this.json = json;
    this.enabled = enabled;
  }

  @PostConstruct
  void schema() {
    if (enabled) db.count("SELECT COUNT(*) FROM agent_approval");
  }

  public boolean available(String uid) {
    settings.admin(uid);
    return settings.featureEnabled("agentApprovals", enabled);
  }

  public List<Map<String, Object>> tools(String uid) {
    if (!available(uid)) return List.of();
    var parameters =
        Map.of(
            "type",
            "object",
            "additionalProperties",
            false,
            "required",
            List.of("kind", "target", "fileIds"),
            "properties",
            Map.of(
                "kind", Map.of("type", "string", "enum", List.of("MIGRATE", "REPLICATE")),
                "target", Map.of("type", "string", "minLength", 1, "maxLength", 80),
                "fileIds",
                    Map.of(
                        "type",
                        "array",
                        "minItems",
                        1,
                        "maxItems",
                        20,
                        "uniqueItems",
                        true,
                        "items",
                        Map.of("type", "string", "format", "uuid"))));
    return List.of("simulate_file_migration", "propose_storage_action").stream()
        .map(
            name ->
                Map.<String, Object>of(
                    "type",
                    "function",
                    "function",
                    Map.of(
                        "name",
                        name,
                        "description",
                        name.equals("simulate_file_migration")
                            ? "Deterministic metadata-only simulation for explicitly supplied own"
                                + " file IDs. No storage writes."
                            : "Save a ten-minute pending proposal for human review. Does not"
                                + " approve, submit jobs or execute writes.",
                        "parameters",
                        parameters)))
        .toList();
  }

  public Map<String, Object> invoke(String uid, String name, JsonNode arguments) {
    Object result =
        switch (name) {
          case "simulate_file_migration" -> simulate(uid, arguments);
          case "propose_storage_action" -> propose(uid, Util.id(), arguments);
          default -> throw new Problem(404, "AGENT_TOOL_NOT_FOUND", "Unknown proposal tool");
        };
    return Map.of(
        "invocationId",
        Util.id(),
        "tool",
        name,
        "observedAt",
        System.currentTimeMillis(),
        "source",
        "DETERMINISTIC_METADATA_PROGRAM",
        "trustedAsInstructions",
        false,
        "data",
        result);
  }

  private void require(String uid) {
    if (!available(uid))
      throw new Problem(409, "AGENT_APPROVALS_DISABLED", "Storage action proposals are disabled");
  }

  private <T> T locked(String uid, Supplier<T> work) {
    require(uid);
    return db.atomic(
        uid,
        () ->
            nodes.locked(
                () -> {
                  require(uid);
                  return work.get();
                }));
  }

  private String encode(Object value) {
    return json.valueToTree(value).toString();
  }

  private JsonNode decode(String text) {
    try {
      return json.readTree(text);
    } catch (java.io.IOException e) {
      throw new IllegalStateException(e);
    }
  }

  private Map<String, Object> request(JsonNode in) {
    if (in == null
        || !in.isObject()
        || in.size() != 3
        || !in.path("fileIds").isArray()
        || in.path("fileIds").isEmpty()
        || in.path("fileIds").size() > 20
        || !in.path("kind").isTextual()
        || !Set.of("MIGRATE", "REPLICATE").contains(in.path("kind").asText())
        || !in.path("target").isTextual()
        || in.path("target").asText().length() > 80
        || in.path("target").asText().isBlank())
      throw Problem.invalid("Expected kind, target and 1-20 fileIds");
    var ids = new TreeSet<String>();
    for (var id : in.path("fileIds")) {
      if (!id.isTextual()) throw Problem.invalid("Invalid file ID");
      try {
        UUID.fromString(id.asText());
      } catch (IllegalArgumentException e) {
        throw Problem.invalid("Invalid file ID");
      }
      if (!ids.add(id.asText())) throw Problem.invalid("Duplicate file ID");
    }
    return new TreeMap<>(
        Map.of(
            "kind",
            in.path("kind").asText(),
            "target",
            in.path("target").asText(),
            "fileIds",
            List.copyOf(ids)));
  }

  /** Pure schema preflight; permission and live state are rechecked at execution. */
  public void validateArguments(JsonNode input) {
    request(input);
  }

  private Map<String, Object> nodeVersion(Map<String, Object> node) {
    var value = new TreeMap<String, Object>();
    for (String key :
        List.of(
            "backend",
            "enabled",
            "management",
            "allow_migration",
            "failure_domain",
            "budget_bytes",
            "config_version",
            "state",
            "validated")) value.put(key, node.get(key));
    return value;
  }

  private boolean readable(Map<String, Object> node) {
    return "ONLINE".equals(s(node, "state"))
        && b(node, "validated")
        && System.currentTimeMillis() - n(node, "checked_at") <= 120000;
  }

  private Map<String, Object> plan(String uid, Map<String, Object> request) {
    String target = s(request, "target"), kind = s(request, "kind");
    var destination = nodes.node(target);
    if (db.count("SELECT COUNT(*) FROM maintenance_job WHERE status IN ('QUEUED','RUNNING')") > 0)
      throw Problem.conflict("A node migration is already active");
    var files = new ArrayList<Map<String, Object>>();
    var blobs = new HashSet<String>();
    long additional = 0;
    for (String id : (List<String>) request.get("fileIds")) {
      var entry = drive.ownedFile(uid, id);
      var blob =
          db.one(
              "SELECT * FROM file_blob WHERE id=? AND user_id=? AND status='READY'",
              entry.blobId(),
              uid);
      String source = s(blob, "backend");
      if (source.equals(target)) throw Problem.invalid("Target equals primary source");
      if (db.count(
              "SELECT COUNT(*) FROM storage_job_item WHERE blob_id=? AND status IN"
                  + " ('QUEUED','RUNNING')",
              s(blob, "id"))
          > 0) throw Problem.conflict("A selected object already has an active job");
      var primary = nodes.node(source);
      if (!readable(primary))
        throw new Problem(
            409, "NODE_UNAVAILABLE", "Primary source metadata is stale or unavailable");
      if (kind.equals("REPLICATE") && !PlacementService.independent(primary, destination))
        throw Problem.conflict("Replication requires independent assigned failure domains");
      if (kind.equals("MIGRATE")
          && n(blob, "desired_replicas") > 1
          && !PlacementService.independent(primary, destination))
        throw Problem.conflict("Protected migration must retain an independent verified source");
      if (blobs.add(s(blob, "id"))
          && db.count(
                  "SELECT COUNT(*) FROM storage_copy WHERE backend=? AND object_key=?",
                  target,
                  s(blob, "object_key"))
              == 0) additional = Math.addExact(additional, n(blob, "size"));
      files.add(
          new TreeMap<>(
              Map.of(
                  "fileId",
                  id,
                  "revision",
                  entry.revision(),
                  "sha256",
                  s(blob, "sha256"),
                  "bytes",
                  n(blob, "size"),
                  "metadataHash",
                  Util.hash(entry.name() + ":" + entry.parentId()),
                  "source",
                  nodeVersion(primary),
                  "desiredReplicas",
                  n(blob, "desired_replicas"))));
    }
    if (db.count(
                "SELECT COUNT(*) FROM storage_job_item WHERE user_id=? AND status IN"
                    + " ('QUEUED','RUNNING')",
                uid)
            + blobs.size()
        > 200) throw Problem.conflict("Storage job queue limit reached");
    nodes.admit(target, true, additional);
    var result = new TreeMap<String, Object>();
    result.put("kind", kind);
    result.put("target", nodeVersion(destination));
    result.put("files", files);
    result.put("additionalBytes", additional);
    result.put("targetUsedBytes", nodes.used(target));
    result.put("targetReservedBytes", nodes.reserved(target));
    result.put(
        "expectedChange",
        kind.equals("MIGRATE")
            ? "QUEUE_VERIFIED_PRIMARY_MIGRATION_RETAIN_SOURCE"
            : "QUEUE_VERIFIED_REPLICA_COPY");
    result.put(
        "risk",
        "Metadata simulation is not a live readback. Execution rechecks admission and validates"
            + " SHA-256. No source deletion is requested.");
    result.put("proposalHash", Util.hash(encode(result)));
    return result;
  }

  public Map<String, Object> simulate(String uid, JsonNode input) {
    var request = request(input);
    return locked(uid, () -> plan(uid, request));
  }

  public Map<String, Object> propose(String uid, String key, JsonNode input) {
    var request = request(input);
    try {
      UUID.fromString(key);
    } catch (Exception e) {
      throw Problem.invalid("Idempotency-Key must be a UUID");
    }
    String hash = Util.hash(encode(request));
    return locked(
        uid,
        () -> {
          var prior =
              db.list("SELECT * FROM agent_approval WHERE user_id=? AND request_key=?", uid, key);
          if (!prior.isEmpty()) {
            if (!hash.equals(s(prior.getFirst(), "request_hash")))
              throw Problem.conflict("Idempotency key input differs");
            return view(prior.getFirst());
          }
          long now = System.currentTimeMillis();
          db.update(
              "DELETE FROM agent_approval WHERE user_id=? AND created_at<?",
              uid,
              now - 7L * 86400000);
          if (db.count("SELECT COUNT(*) FROM agent_approval WHERE user_id=?", uid) >= 1000
              || db.count(
                      "SELECT COUNT(*) FROM agent_approval WHERE user_id=? AND status='PENDING' AND"
                          + " expires_at>?",
                      uid,
                      now)
                  >= 10)
            throw new Problem(429, "AGENT_APPROVAL_LIMIT", "Review pending proposals first");
          var plan = plan(uid, request);
          String id = Util.id();
          db.update(
              "INSERT INTO"
                  + " agent_approval(id,user_id,request_key,request_hash,proposal_hash,request_json,plan_json,status,created_at,expires_at)"
                  + " VALUES(?,?,?,?,?,?,?,'PENDING',?,?)",
              id,
              uid,
              key,
              hash,
              s(plan, "proposalHash"),
              encode(request),
              encode(plan),
              now,
              now + 600000);
          return view(db.one("SELECT * FROM agent_approval WHERE id=?", id));
        });
  }

  private Map<String, Object> view(Map<String, Object> row) {
    var value = new LinkedHashMap<String, Object>();
    for (String key :
        List.of(
            "id",
            "proposal_hash",
            "status",
            "created_at",
            "expires_at",
            "decided_at",
            "approver_id")) value.put(key, row.get(key));
    if ("PENDING".equals(s(row, "status")) && n(row, "expires_at") < System.currentTimeMillis())
      value.put("status", "EXPIRED");
    value.put("plan", decode(s(row, "plan_json")));
    value.put(
        "jobIds", row.get("job_ids_json") == null ? List.of() : decode(s(row, "job_ids_json")));
    return value;
  }

  public List<Map<String, Object>> list(String uid) {
    require(uid);
    return db
        .list(
            "SELECT * FROM agent_approval WHERE user_id=? AND created_at>? ORDER BY created_at DESC"
                + " LIMIT 100",
            uid,
            System.currentTimeMillis() - 7L * 86400000)
        .stream()
        .map(this::view)
        .toList();
  }

  public Map<String, Object> decide(String uid, String id, String expectedHash, boolean approve) {
    return locked(
        uid,
        () -> {
          var row = db.one("SELECT * FROM agent_approval WHERE id=? AND user_id=?", id, uid);
          if (!Objects.equals(expectedHash, s(row, "proposal_hash")))
            throw Problem.conflict("Proposal hash differs");
          if (s(row, "status").equals(approve ? "SUBMITTED" : "REJECTED")) return view(row);
          if (!s(row, "status").equals("PENDING")
              || n(row, "expires_at") < System.currentTimeMillis())
            throw new Problem(409, "AGENT_APPROVAL_EXPIRED", "Proposal expired or already decided");
          var ids = new ArrayList<String>();
          if (approve) {
            var input = request(decode(s(row, "request_json")));
            var fresh = plan(uid, input);
            if (!expectedHash.equals(s(fresh, "proposalHash")))
              throw new Problem(
                  409,
                  "AGENT_PROPOSAL_STALE",
                  "File revision or node/capacity configuration changed; create a fresh proposal");
            var seen = new HashSet<String>();
            for (String fid : (List<String>) input.get("fileIds")) {
              var entry = drive.ownedFile(uid, fid);
              String bid = entry.blobId();
              if (seen.add(bid))
                ids.add(
                    jobs.submit(
                        uid,
                        new StorageTaskCoordinator.Command(
                            fid,
                            entry.revision(),
                            s(input, "target"),
                            StorageTaskCoordinator.Kind.valueOf(s(input, "kind")))));
            }
          }
          db.update(
              "UPDATE agent_approval SET status=?,job_ids_json=?,decided_at=?,approver_id=? WHERE"
                  + " id=?",
              approve ? "SUBMITTED" : "REJECTED",
              encode(ids),
              System.currentTimeMillis(),
              uid,
              id);
          return view(db.one("SELECT * FROM agent_approval WHERE id=?", id));
        });
  }
}
