package com.cloudnest.storage;

import static com.cloudnest.common.Db.*;

import com.cloudnest.common.*;
import com.cloudnest.node.*;
import jakarta.annotation.PostConstruct;
import java.io.*;
import java.util.*;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;

/** Durable, single-worker file tasks. No file is removed during migration. */
@Service
public class MigrationService {
  @org.springframework.beans.factory.annotation.Autowired
  private com.cloudnest.WorkerLifecycle lifecycle;

  private final Db db;
  private final NodeService nodes;
  private final StorageService storage;
  private final ReplicaService replicas;

  @Value("${cloudnest.maintenance:false}")
  private boolean maintenance;

  private String repairCursor = "";

  public MigrationService(
      Db db, NodeService nodes, StorageService storage, ReplicaService replicas) {
    this.db = db;
    this.nodes = nodes;
    this.storage = storage;
    this.replicas = replicas;
  }

  @PostConstruct
  void recover() {
    if (!lifecycle.mayRecover()) return;
    db.update(
        "UPDATE storage_job_item SET status='INTERRUPTED',error='服务重启，请重试' WHERE status='RUNNING'");
    db.update(
        "DELETE FROM storage_reservation WHERE id LIKE 'job:%' AND id NOT IN (SELECT"
            + " CONCAT('job:',id) FROM storage_job_item WHERE status='QUEUED')");
  }

  /** Caller holds user then storage_guard locks. May adopt an upload's replica reservation. */
  public String enqueue(
      String uid, Map<String, Object> blob, String target, String kind, String adoption) {
    nodes.authorizeTarget(uid, target);
    if (!Set.of("MIGRATE", "REPLICATE").contains(kind)) throw Problem.invalid("任务类型无效");
    if (!uid.equals(s(blob, "user_id"))) throw Problem.missing();
    boolean repairingPrimary =
        target.equals(s(blob, "backend"))
            && kind.equals("REPLICATE")
            && db.count(
                    "SELECT COUNT(*) FROM blob_replica WHERE blob_id=? AND backend=? AND"
                        + " state<>'VERIFIED'",
                    s(blob, "id"),
                    target)
                > 0;
    if (target.equals(s(blob, "backend")) && !repairingPrimary) throw Problem.invalid("目标与主存储相同");
    if (db.count("SELECT COUNT(*) FROM maintenance_job WHERE status IN ('QUEUED','RUNNING')") > 0)
      throw Problem.conflict("请先等待整节点迁移完成");
    if (db.count(
            "SELECT COUNT(*) FROM storage_job_item WHERE blob_id=? AND status IN"
                + " ('QUEUED','RUNNING')",
            s(blob, "id"))
        > 0) throw Problem.conflict("该对象已有任务");
    var domainSource = nodes.node(s(blob, "backend"));
    if (repairingPrimary) {
      var alternatives =
          db.list(
              "SELECT backend FROM blob_replica WHERE blob_id=? AND backend<>? AND"
                  + " state='VERIFIED'",
              s(blob, "id"),
              target);
      if (alternatives.isEmpty()) throw Problem.conflict("没有可用于修复的已验证副本");
      domainSource = nodes.node(s(alternatives.getFirst(), "backend"));
    }
    if (kind.equals("REPLICATE") && !PlacementService.independent(domainSource, nodes.node(target)))
      throw Problem.conflict("双副本必须配置为两个不同且非 unassigned 的故障域");
    if (adoption != null) nodes.release(adoption);
    long additional =
        db.count(
                    "SELECT COUNT(*) FROM storage_copy WHERE backend=? AND object_key=?",
                    target,
                    s(blob, "object_key"))
                > 0
            ? 0
            : n(blob, "size");
    nodes.admit(target, true, additional);
    String id = Util.id();
    long now = System.currentTimeMillis();
    nodes.reserve("job:" + id, target, additional, 0);
    db.update(
        "INSERT INTO"
            + " storage_job_item(id,user_id,blob_id,kind,target_backend,status,created_at,updated_at)"
            + " VALUES(?,?,?,?,?,'QUEUED',?,?)",
        id,
        uid,
        s(blob, "id"),
        kind,
        target,
        now,
        now);
    if (kind.equals("REPLICATE"))
      db.update("UPDATE file_blob SET desired_replicas=2 WHERE id=?", s(blob, "id"));
    return id;
  }

  public void cancel(String uid, String id) {
    nodes.locked(
        () -> {
          var job = db.one("SELECT * FROM storage_job_item WHERE id=? AND user_id=?", id, uid);
          if (Set.of("QUEUED", "RUNNING").contains(s(job, "status"))) {
            db.update(
                "UPDATE storage_job_item SET status='CANCELLED',updated_at=? WHERE id=?",
                System.currentTimeMillis(),
                id);
            nodes.release("job:" + id);
          }
          return null;
        });
  }

  public String retry(String uid, String id) {
    return db.atomic(
        uid,
        () ->
            nodes.locked(
                () -> {
                  var old =
                      db.one("SELECT * FROM storage_job_item WHERE id=? AND user_id=?", id, uid);
                  if (!Set.of("FAILED", "INTERRUPTED", "CANCELLED").contains(s(old, "status")))
                    throw Problem.conflict("任务不能重试");
                  return enqueue(
                      uid,
                      db.one(
                          "SELECT * FROM file_blob WHERE id=? AND user_id=? AND status='READY'",
                          s(old, "blob_id"),
                          uid),
                      s(old, "target_backend"),
                      s(old, "kind"),
                      null);
                }));
  }

  @Scheduled(fixedDelay = 2000, initialDelay = 5000)
  public synchronized void work() {
    if (maintenance) return;
    var pending =
        db.list(
            "SELECT id FROM storage_job_item WHERE status='QUEUED' ORDER BY created_at LIMIT 1");
    if (!pending.isEmpty()) run(s(pending.getFirst(), "id"));
  }

  public void run(String id) {
    var job = db.one("SELECT * FROM storage_job_item WHERE id=?", id);
    String uid = s(job, "user_id"), target = s(job, "target_backend");
    try {
      if (db.update(
              "UPDATE storage_job_item SET status='RUNNING',attempts=attempts+1,updated_at=? WHERE"
                  + " id=? AND status='QUEUED'",
              System.currentTimeMillis(),
              id)
          == 0) return;
      var blob =
          db.atomic(
              uid,
              () ->
                  nodes.locked(
                      () -> {
                        var current =
                            db.one(
                                "SELECT * FROM file_blob WHERE id=? AND status='READY'",
                                s(job, "blob_id"));
                        // Commit the physical reference before I/O; cancelled and interrupted
                        // candidates stay counted.
                        nodes.copied(target, s(current, "object_key"), n(current, "size"));
                        db.update(
                            "UPDATE blob_replica SET state='UNVERIFIED',verified_at=0 WHERE"
                                + " blob_id=? AND backend=?",
                            s(current, "id"),
                            target);
                        nodes.release("job:" + id);
                        return current;
                      }));
      String key = s(blob, "object_key"), source = s(blob, "backend");
      try (var input = replicas.open(blob, 0, n(blob, "size"))) {
        storage.put(target, key, input, n(blob, "size"));
      }
      if (!replicas.verify(blob, target, key)) throw new IOException("Checksum mismatch");
      boolean sourceValid = false;
      if (!s(job, "kind").equals("MIGRATE")) {
        try {
          sourceValid = replicas.verify(blob, source, key);
        } catch (IOException unavailable) {
        }
      }
      final boolean checkedSource = sourceValid;
      db.atomic(
          uid,
          () ->
              nodes.locked(
                  () -> {
                    if (!"RUNNING"
                        .equals(
                            s(
                                db.one(
                                    "SELECT status FROM storage_job_item WHERE id=? FOR UPDATE",
                                    id),
                                "status"))) return null;
                    db.one(
                        "SELECT id FROM file_blob WHERE id=? AND status='READY' AND backend=?",
                        s(blob, "id"),
                        source);
                    replicas.verified(blob, target, key);
                    if (s(job, "kind").equals("MIGRATE")) {
                      nodes.copied(source, key, n(blob, "size"));
                      db.update("UPDATE file_blob SET backend=? WHERE id=?", target, s(blob, "id"));
                    } else if (checkedSource) replicas.verified(blob, source, key);
                    db.update(
                        "UPDATE storage_job_item SET status='COMPLETE',error=NULL,updated_at=?"
                            + " WHERE id=?",
                        System.currentTimeMillis(),
                        id);
                    return null;
                  }));
    } catch (Exception failure) {
      // The object may exist after a transaction rollback; retain its physical budget and endpoint
      // reference.
      nodes.locked(
          () -> {
            var blobs = db.list("SELECT * FROM file_blob WHERE id=?", s(job, "blob_id"));
            if (!blobs.isEmpty())
              nodes.copied(target, s(blobs.getFirst(), "object_key"), n(blobs.getFirst(), "size"));
            nodes.release("job:" + id);
            db.update(
                "UPDATE storage_job_item SET"
                    + " status='FAILED',error='复制或校验失败；来源保留，请检查节点后重试',updated_at=? WHERE id=? AND"
                    + " status<>'CANCELLED'",
                System.currentTimeMillis(),
                id);
            return null;
          });
    }
  }

  @Scheduled(fixedDelay = 60000, initialDelay = 60000)
  public void repair() {
    if (maintenance) return;
    var audit =
        db.list(
            "SELECT r.*,b.user_id,b.sha256,b.desired_replicas FROM blob_replica r JOIN file_blob b"
                + " ON b.id=r.blob_id JOIN storage_node_health h ON h.backend=r.backend WHERE"
                + " b.desired_replicas=2 AND b.status='READY' AND r.state<>'CORRUPT' AND"
                + " h.state='ONLINE' AND h.validated=TRUE AND h.checked_at>? ORDER BY r.verified_at"
                + " LIMIT 1",
            System.currentTimeMillis() - 120000);
    if (!audit.isEmpty()) {
      var r = audit.getFirst();
      var object = new LinkedHashMap<>(r);
      object.put("id", s(r, "blob_id"));
      try {
        boolean valid = replicas.verify(object, s(r, "backend"), s(r, "object_key"));
        db.atomic(
            s(r, "user_id"),
            () ->
                nodes.locked(
                    () -> {
                      if (db.count(
                              "SELECT COUNT(*) FROM file_blob WHERE id=? AND status='READY'",
                              s(r, "blob_id"))
                          == 0) return null;
                      if (valid) replicas.verified(object, s(r, "backend"), s(r, "object_key"));
                      else
                        db.update(
                            "UPDATE blob_replica SET state='CORRUPT' WHERE blob_id=? AND backend=?",
                            s(r, "blob_id"),
                            s(r, "backend"));
                      return null;
                    }));
      } catch (IOException | Problem unavailable) {
      }
    }
    // One bounded scan per minute; no new runtime service is needed on a 1 GiB host.
    var batch =
        db.list(
            "SELECT * FROM file_blob WHERE desired_replicas=2 AND status='READY' AND id>? ORDER BY"
                + " id LIMIT 100",
            repairCursor);
    if (batch.isEmpty()) repairCursor = "";
    for (var b : batch) {
      repairCursor = s(b, "id");
      try {
        db.atomic(
            s(b, "user_id"),
            () ->
                nodes.locked(
                    () -> {
                      if (db.count(
                              "SELECT COUNT(*) FROM storage_job_item WHERE blob_id=? AND status IN"
                                  + " ('QUEUED','RUNNING')",
                              s(b, "id"))
                          > 0) return null;
                      var valid =
                          db.list(
                              "SELECT r.backend FROM blob_replica r JOIN storage_node_health h ON"
                                  + " h.backend=r.backend WHERE r.blob_id=? AND r.state='VERIFIED'"
                                  + " AND h.state='ONLINE' AND h.validated=TRUE AND h.checked_at>?",
                              s(b, "id"),
                              System.currentTimeMillis() - 120000);
                      if (valid.isEmpty()) return null;
                      var base = nodes.node(s(valid.getFirst(), "backend"));
                      for (var row :
                          db.list(
                              "SELECT backend FROM storage_node ORDER BY priority DESC,backend")) {
                        String target = s(row, "backend");
                        if (!PlacementService.independent(base, nodes.node(target))) continue;
                        boolean protectedAlready =
                            valid.stream()
                                .anyMatch(
                                    v ->
                                        PlacementService.independent(
                                            base, nodes.node(s(v, "backend"))));
                        if (protectedAlready) return null;
                        // Rate-limit failed repairs; permit retry after health/configuration is
                        // fixed.
                        if (db.count(
                                "SELECT COUNT(*) FROM storage_job_item WHERE blob_id=? AND"
                                    + " created_at>?",
                                s(b, "id"),
                                System.currentTimeMillis() - 300000)
                            > 0) return null;
                        try {
                          enqueue(s(b, "user_id"), b, target, "REPLICATE", null);
                          return null;
                        } catch (Problem unavailable) {
                        }
                      }
                      return null;
                    }));
      } catch (Problem deleted) {
      }
    }
  }
}
