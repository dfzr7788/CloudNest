package com.cloudnest.resilience;

import static com.cloudnest.common.Db.*;

import com.cloudnest.common.*;
import com.cloudnest.file.DriveService;
import com.cloudnest.node.NodeService;
import com.cloudnest.storage.*;
import jakarta.annotation.*;
import java.io.*;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicBoolean;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;

/** One durable queue for restoration and integrity, with transactional per-item checkpoints. */
@Service
public class ResilienceTasks {
  private final Db db;
  private final SnapshotService snapshots;
  private final DriveService drive;
  private final ReplicaService replicas;
  private final StorageService storage;
  private final MigrationService migrations;
  private final NodeService nodes;
  private final com.cloudnest.WorkerLifecycle lifecycle;
  private final ExecutorService worker =
      Executors.newSingleThreadExecutor(
          r -> {
            var t = new Thread(r, "resilience-worker");
            t.setDaemon(true);
            return t;
          });
  private final AtomicBoolean busy = new AtomicBoolean();

  public ResilienceTasks(
      Db db,
      SnapshotService snapshots,
      DriveService drive,
      ReplicaService replicas,
      StorageService storage,
      MigrationService migrations,
      NodeService nodes,
      com.cloudnest.WorkerLifecycle lifecycle) {
    this.db = db;
    this.snapshots = snapshots;
    this.drive = drive;
    this.replicas = replicas;
    this.storage = storage;
    this.migrations = migrations;
    this.nodes = nodes;
    this.lifecycle = lifecycle;
  }

  @PostConstruct
  public void recover() {
    if (lifecycle.mayRecover())
      db.update(
          "UPDATE resilience_task SET status='QUEUED',error_code='WORKER_RESTARTED' WHERE"
              + " status='RUNNING'");
  }

  @PreDestroy
  void close() {
    worker.shutdownNow();
  }

  public Map<String, Object> owned(String uid, String id) {
    return db.one("SELECT * FROM resilience_task WHERE id=? AND user_id=?", id, uid);
  }

  public List<Map<String, Object>> list(String uid) {
    return db.list(
        "SELECT t.*,j.restored_root FROM resilience_task t LEFT JOIN snapshot_restore_job j ON"
            + " j.task_id=t.id WHERE t.user_id=? ORDER BY t.created_at DESC LIMIT 200",
        uid);
  }

  private String enqueue(String uid, String kind, String key, int total) {
    if (key == null || !key.matches("[a-zA-Z0-9_-]{8,80}")) throw Problem.invalid("需要 8～80 字符幂等标识");
    String id = Util.id();
    long now = System.currentTimeMillis();
    if (db.count(
            "SELECT COUNT(*) FROM resilience_task WHERE user_id=? AND status IN"
                + " ('QUEUED','RUNNING','PAUSED')",
            uid)
        >= 20) throw Problem.conflict("待处理任务过多");
    db.update(
        "INSERT INTO resilience_task(id,user_id,kind,request_key,total,created_at,updated_at)"
            + " VALUES(?,?,?,?,?,?,?)",
        id,
        uid,
        kind,
        key,
        total,
        now,
        now);
    snapshots.audit(uid, kind + "_QUEUED", id);
    return id;
  }

  public Map<String, Object> preview(String uid, String snapshot, String target, String selected) {
    var entries = selected(uid, snapshot, selected);
    var folder = drive.folder(uid, target);
    return Map.of(
        "snapshotId",
        snapshot,
        "targetFolder",
        s(folder, "id"),
        "entries",
        entries,
        "totalBytes",
        entries.stream().mapToLong(e -> n(e, "size")).sum(),
        "mode",
        "COPY",
        "preservesCurrentFiles",
        true);
  }

  private List<Map<String, Object>> selected(String uid, String snapshot, String selected) {
    var entries = snapshots.entries(uid, snapshot);
    if (selected == null || selected.isBlank()) return entries;
    var ids = new HashSet<String>();
    for (var e : entries) if (selected.equals(s(e, "entry_id"))) ids.add(selected);
    if (ids.isEmpty()) throw Problem.missing();
    // Ordinals are breadth first; a single pass selects a subtree.
    for (var e : entries) if (ids.contains(s(e, "parent_entry_id"))) ids.add(s(e, "entry_id"));
    return entries.stream().filter(e -> ids.contains(s(e, "entry_id"))).toList();
  }

  public Map<String, Object> restore(
      String uid, String snapshot, String target, String selected, String key) {
    return db.atomic(
        uid,
        () -> {
          var previous =
              db.list(
                  "SELECT t.*,j.snapshot_id,j.target_folder,j.selected_entry FROM resilience_task t"
                      + " JOIN snapshot_restore_job j ON j.task_id=t.id WHERE t.user_id=? AND"
                      + " t.kind='RESTORE' AND t.request_key=?",
                  uid,
                  key);
          String fid = s(drive.folder(uid, target), "id");
          if (!previous.isEmpty()) {
            var p = previous.getFirst();
            if (!snapshot.equals(s(p, "snapshot_id"))
                || !fid.equals(s(p, "target_folder"))
                || !Objects.equals(selected, s(p, "selected_entry")))
              throw Problem.conflict("幂等标识已用于另一个恢复请求");
            return owned(uid, s(p, "id"));
          }
          if (db.count(
                  "SELECT COUNT(*) FROM resilience_task WHERE user_id=? AND kind='RESTORE' AND"
                      + " request_key=?",
                  uid,
                  key)
              > 0) throw Problem.conflict("此恢复标识的计划已被清理，请使用新的幂等标识");
          var entries = selected(uid, snapshot, selected);
          long bytes = entries.stream().mapToLong(e -> n(e, "size")).sum();
          var u = db.one("SELECT * FROM sys_user WHERE id=?", uid);
          if (bytes > n(u, "storage_quota") - n(u, "storage_used") - n(u, "storage_reserved"))
            throw new Problem(409, "QUOTA_EXCEEDED", "恢复副本配额不足");
          String id = enqueue(uid, "RESTORE", key, entries.size());
          db.update(
              "INSERT INTO snapshot_restore_job(task_id,snapshot_id,target_folder,selected_entry)"
                  + " VALUES(?,?,?,?)",
              id,
              snapshot,
              fid,
              selected);
          return owned(uid, id);
        });
  }

  public Map<String, Object> scan(
      String actor, String backend, String owner, String folder, String key) {
    return db.atomic(
        actor,
        () -> {
          requireScanAdministrator(actor);
          String fingerprint =
              Util.hash(
                  Objects.toString(backend, "")
                      + "\n"
                      + Objects.toString(owner, "")
                      + "\n"
                      + Objects.toString(folder, ""));
          var previous =
              db.list(
                  "SELECT * FROM resilience_task WHERE user_id=? AND kind='INTEGRITY' AND"
                      + " request_key=?",
                  actor,
                  key);
          if (!previous.isEmpty()) {
            if (!fingerprint.equals(s(previous.getFirst(), "request_scope")))
              throw Problem.conflict("巡检标识已用于另一个范围");
            return previous.getFirst();
          }
          Set<String> scope = null;
          if (folder != null) {
            if (owner == null) throw Problem.invalid("目录巡检需要资源所属用户");
            scope = new HashSet<>();
            for (var e : snapshots.liveTree(owner, folder))
              if (e.get("blob_id") != null) scope.add(s(e, "blob_id"));
          }
          String sql = "SELECT b.id,b.backend,b.object_key FROM file_blob b WHERE b.status='READY'";
          List<Object> args = new ArrayList<>();
          if (owner != null) {
            sql += " AND b.user_id=?";
            args.add(owner);
          }
          if (backend != null) {
            sql +=
                " AND (b.backend=? OR EXISTS(SELECT 1 FROM blob_replica r WHERE r.blob_id=b.id AND"
                    + " r.backend=?))";
            args.add(backend);
            args.add(backend);
          }
          if (scope != null) {
            if (scope.isEmpty()) sql += " AND 1=0";
            else {
              sql +=
                  " AND b.id IN (" + String.join(",", Collections.nCopies(scope.size(), "?")) + ")";
              args.addAll(scope);
            }
          }
          var candidates = db.list(sql + " ORDER BY b.id LIMIT 20001", args.toArray());
          var items = new ArrayList<Map<String, Object>>();
          for (var b : candidates) {
            if (scope != null && !scope.contains(s(b, "id"))) continue;
            var copies =
                db.list("SELECT backend,object_key FROM blob_replica WHERE blob_id=?", b.get("id"));
            if (copies.stream().noneMatch(r -> Objects.equals(r.get("backend"), b.get("backend"))))
              copies.add(b);
            for (var r : copies)
              if (backend == null || backend.equals(s(r, "backend")))
                items.add(
                    Map.of(
                        "blob_id",
                        b.get("id"),
                        "backend",
                        r.get("backend"),
                        "object_key",
                        r.get("object_key")));
          }
          if (candidates.size() > 20000 || items.size() > 40000)
            throw Problem.invalid("巡检批次过大，请按节点或目录拆分");
          String id = enqueue(actor, "INTEGRITY", key, items.size());
          db.update("UPDATE resilience_task SET request_scope=? WHERE id=?", fingerprint, id);
          int ordinal = 0;
          for (var item : items)
            db.update(
                "INSERT INTO integrity_scan_item(task_id,ordinal,blob_id,backend,object_key)"
                    + " VALUES(?,?,?,?,?)",
                id,
                ordinal++,
                item.get("blob_id"),
                item.get("backend"),
                item.get("object_key"));
          return owned(actor, id);
        });
  }

  public void action(String uid, String id, String action) {
    db.atomic(
        uid,
        () -> {
          var task = owned(uid, id);
          String old = s(task, "status"), next;
          switch (action) {
            case "pause" -> {
              if (!Set.of("RUNNING", "QUEUED").contains(old)) throw Problem.conflict("任务不可暂停");
              next = "PAUSED";
            }
            case "resume" -> {
              if (!old.equals("PAUSED")) throw Problem.conflict("任务不可继续");
              next = "QUEUED";
            }
            case "retry" -> {
              if (!old.equals("FAILED")) throw Problem.conflict("任务不可重试");
              next = "QUEUED";
            }
            case "cancel" -> {
              if (old.equals("COMPLETE")) throw Problem.conflict("任务已完成");
              next = "CANCELLED";
            }
            default -> throw Problem.invalid("任务操作无效");
          }
          db.update(
              "UPDATE resilience_task SET status=?,error_code=NULL,updated_at=? WHERE id=?",
              next,
              System.currentTimeMillis(),
              id);
          snapshots.audit(uid, "TASK_" + action.toUpperCase(Locale.ROOT), id);
          return null;
        });
  }

  @Scheduled(fixedDelay = 1000, initialDelay = 10000)
  public void tick() {
    if (!lifecycle.mayRecover() || !busy.compareAndSet(false, true)) return;
    worker.submit(
        () -> {
          try {
            var queue =
                db.list(
                    "SELECT id FROM resilience_task WHERE status IN ('QUEUED','RUNNING') ORDER BY"
                        + " updated_at,created_at LIMIT 1");
            if (!queue.isEmpty()) step(s(queue.getFirst(), "id"));
          } finally {
            busy.set(false);
          }
        });
  }

  /** Public deterministic step for integration tests and offline controlled execution. */
  public synchronized void step(String id) {
    var task = db.one("SELECT * FROM resilience_task WHERE id=?", id);
    String uid = s(task, "user_id");
    if (db.update(
            "UPDATE resilience_task SET status='RUNNING',attempts=attempts+CASE WHEN"
                + " status='QUEUED' THEN 1 ELSE 0 END WHERE id=? AND status IN"
                + " ('QUEUED','RUNNING')",
            id)
        == 0) return;
    try {
      if (n(task, "progress") >= n(task, "total")) {
        finish(uid, id);
        return;
      }
      if (s(task, "kind").equals("RESTORE")) restoreStep(task);
      else scanStep(task);
      if (db.count(
              "SELECT COUNT(*) FROM resilience_task WHERE id=? AND progress>=total AND"
                  + " status='RUNNING'",
              id)
          > 0) finish(uid, id);
    } catch (Stopped stopped) {
      /* Pause/cancel leaves the last committed checkpoint. */
    } catch (Exception error) {
      String code = error instanceof Problem p ? p.code : "OBJECT_IO_FAILED";
      db.update(
          "UPDATE resilience_task SET status='FAILED',error_code=?,updated_at=? WHERE id=? AND"
              + " status='RUNNING'",
          code,
          System.currentTimeMillis(),
          id);
      snapshots.audit(uid, "TASK_FAILED", id);
    }
  }

  private void finish(String uid, String id) {
    db.atomic(
        uid,
        () -> {
          if (db.update(
                  "UPDATE resilience_task SET status='COMPLETE',error_code=NULL,updated_at=? WHERE"
                      + " id=? AND status='RUNNING'",
                  System.currentTimeMillis(),
                  id)
              > 0) snapshots.audit(uid, "TASK_COMPLETE", id);
          return null;
        });
  }

  private void running(String id) throws Stopped {
    if (Thread.currentThread().isInterrupted()
        || !s(db.one("SELECT status FROM resilience_task WHERE id=?", id), "status")
            .equals("RUNNING")) throw new Stopped();
  }

  private static class Stopped extends IOException {}

  private void restoreStep(Map<String, Object> task) throws IOException {
    String uid = s(task, "user_id"), id = s(task, "id");
    var job = db.one("SELECT * FROM snapshot_restore_job WHERE task_id=?", id);
    var entries = selected(uid, s(job, "snapshot_id"), s(job, "selected_entry"));
    var entry = entries.get((int) n(task, "progress"));
    if (entry.get("blob_id") != null) {
      var blob =
          db.one(
              "SELECT * FROM file_blob WHERE id=? AND user_id=? AND status='READY'",
              entry.get("blob_id"),
              uid);
      var digest = Util.digest();
      long size = 0;
      byte[] buffer = new byte[65536];
      try (var in = replicas.open(blob, 0, n(blob, "size"))) {
        for (int count; (count = in.read(buffer)) != -1; ) {
          running(id);
          size += count;
          digest.update(buffer, 0, count);
        }
      }
      if (size != n(blob, "size")
          || !HexFormat.of().formatHex(digest.digest()).equals(s(blob, "sha256")))
        throw new Problem(409, "RESTORE_CHECKSUM_MISMATCH", "历史对象校验失败，已保留当前数据");
    }
    db.atomic(
        uid,
        () -> {
          var current = owned(uid, id);
          if (!s(current, "status").equals("RUNNING")
              || n(current, "progress") != n(task, "progress")) return null;
          String parent;
          boolean root = n(task, "progress") == 0;
          if (root) parent = s(drive.folder(uid, s(job, "target_folder")), "id");
          else
            parent =
                s(
                    db.one(
                        "SELECT restored_id FROM snapshot_restore_entry WHERE task_id=? AND"
                            + " entry_id=?",
                        id,
                        entry.get("parent_entry_id")),
                    "restored_id");
          drive.folder(uid, parent);
          String name = s(entry, "name");
          if (root) name = fit(name, " (恢复)");
          String original = name;
          int suffix = 0;
          while (db.count(
                  "SELECT COUNT(*) FROM drive_entry WHERE user_id=? AND parent_id=? AND kind=? AND"
                      + " name=? AND deleted=FALSE",
                  uid,
                  parent,
                  entry.get("kind"),
                  name)
              > 0) name = fit(original, " (" + (++suffix) + ")");
          snapshots.charge(uid, n(entry, "size"));
          if (entry.get("blob_id") != null)
            db.one("SELECT id FROM file_blob WHERE id=? AND status='READY'", entry.get("blob_id"));
          String restored = Util.id();
          long now = System.currentTimeMillis();
          db.update(
              "INSERT INTO"
                  + " drive_entry(id,user_id,parent_id,kind,name,blob_id,size,mime,created_at,updated_at)"
                  + " VALUES(?,?,?,?,?,?,?,?,?,?)",
              restored,
              uid,
              parent,
              entry.get("kind"),
              name,
              entry.get("blob_id"),
              entry.get("size"),
              entry.get("mime"),
              now,
              now);
          db.update(
              "INSERT INTO snapshot_restore_entry(task_id,entry_id,restored_id) VALUES(?,?,?)",
              id,
              entry.get("entry_id"),
              restored);
          if (root)
            db.update(
                "UPDATE snapshot_restore_job SET restored_root=? WHERE task_id=?", restored, id);
          db.update(
              "UPDATE resilience_task SET progress=progress+1,updated_at=? WHERE id=?", now, id);
          return null;
        });
  }

  private String fit(String name, String suffix) {
    return name.substring(0, Math.min(name.length(), 180 - suffix.length())) + suffix;
  }

  private void scanStep(Map<String, Object> task) throws IOException {
    requireScanAdministrator(s(task, "user_id"));
    String id = s(task, "id");
    var item =
        db.one(
            "SELECT * FROM integrity_scan_item WHERE task_id=? AND ordinal=?",
            id,
            n(task, "progress"));
    var blobs =
        db.list("SELECT * FROM file_blob WHERE id=? AND status='READY'", item.get("blob_id"));
    String result = "SKIPPED", error = null, repair = null;
    if (!blobs.isEmpty()) {
      var blob = blobs.getFirst();
      String backend = s(item, "backend"), key = s(item, "object_key");
      // Serialize final state changes with migration and deletion. I/O itself holds no global lock.
      var before = nodes.node(backend);
      long version = n(before, "config_version");
      try {
        long rate =
            n(
                db.one("SELECT bytes_per_second FROM integrity_scan_policy WHERE id=1"),
                "bytes_per_second");
        var digest = Util.digest();
        long total = 0, start = System.nanoTime();
        byte[] buffer = new byte[65536];
        try (var in = storage.openWhole(backend, key, n(blob, "size"))) {
          for (int count; (count = in.read(buffer)) != -1; ) {
            running(id);
            total += count;
            if (total > n(blob, "size")) break;
            digest.update(buffer, 0, count);
            long wait = total * 1000000000L / rate - (System.nanoTime() - start);
            while (wait > 0) {
              running(id);
              try {
                Thread.sleep(Math.min(50, Math.max(1, wait / 1000000)));
              } catch (InterruptedException interrupted) {
                Thread.currentThread().interrupt();
                throw new Stopped();
              }
              wait = total * 1000000000L / rate - (System.nanoTime() - start);
            }
          }
        }
        boolean valid =
            total == n(blob, "size")
                && HexFormat.of().formatHex(digest.digest()).equals(s(blob, "sha256"));
        result = valid ? "VERIFIED" : "CORRUPT";
        final boolean checked = valid;
        db.atomic(
            s(blob, "user_id"),
            () ->
                nodes.locked(
                    () -> {
                      if (db.count(
                                  "SELECT COUNT(*) FROM file_blob WHERE id=? AND status='READY'",
                                  blob.get("id"))
                              == 0
                          || n(nodes.node(backend), "config_version") != version) return null;
                      if (checked) replicas.verified(blob, backend, key);
                      else {
                        db.update(
                            "DELETE FROM blob_replica WHERE blob_id=? AND backend=?",
                            blob.get("id"),
                            backend);
                        db.update(
                            "INSERT INTO blob_replica(blob_id,backend,object_key,size,state)"
                                + " VALUES(?,?,?,?,'CORRUPT')",
                            blob.get("id"),
                            backend,
                            key,
                            blob.get("size"));
                      }
                      return null;
                    }));
        if (!valid) {
          // Request repair only after independently checking another physical copy now.
          boolean good = false;
          for (var other :
              db.list(
                  "SELECT * FROM blob_replica WHERE blob_id=? AND backend<>? AND state='VERIFIED'",
                  blob.get("id"),
                  backend)) {
            if (replicas.verify(blob, s(other, "backend"), s(other, "object_key"))) {
              good = true;
              break;
            }
          }
          if (good) {
            try {
              repair =
                  db.atomic(
                      s(blob, "user_id"),
                      () ->
                          nodes.locked(
                              () ->
                                  migrations.enqueue(
                                      s(blob, "user_id"),
                                      db.one(
                                          "SELECT * FROM file_blob WHERE id=? AND status='READY'",
                                          blob.get("id")),
                                      backend,
                                      "REPLICATE",
                                      null)));
            } catch (Problem unavailable) {
              error = "REPAIR_REQUIRES_REVIEW";
            }
          } else error = "NO_VALID_REPLICA";
          snapshots.audit(
              s(task, "user_id"),
              error == null ? "INTEGRITY_REPAIR_REQUESTED" : error,
              s(blob, "id"));
        }
      } catch (Stopped stopped) {
        throw stopped;
      } catch (IOException unavailable) {
        result = "UNAVAILABLE";
        error = "OBJECT_UNAVAILABLE";
      }
    }
    final String state = result, code = error, repairId = repair;
    db.atomic(
        s(task, "user_id"),
        () -> {
          var current = owned(s(task, "user_id"), id);
          if (!s(current, "status").equals("RUNNING")
              || n(current, "progress") != n(task, "progress")) return null;
          db.update(
              "UPDATE integrity_scan_item SET status=?,error_code=?,repair_task=?,checked_at=?"
                  + " WHERE task_id=? AND ordinal=?",
              state,
              code,
              repairId,
              System.currentTimeMillis(),
              id,
              n(task, "progress"));
          db.update(
              "UPDATE resilience_task SET progress=progress+1,updated_at=? WHERE id=?",
              System.currentTimeMillis(),
              id);
          return null;
        });
  }

  private void requireScanAdministrator(String actor) {
    if (db.count(
            "SELECT COUNT(*) FROM sys_user WHERE id=? AND administrator=TRUE AND enabled=TRUE AND"
                + " workspace_account=FALSE",
            actor)
        != 1) throw new Problem(403, "ADMIN_REQUIRED", "完整性巡检需要有效管理员权限");
  }

  @Scheduled(fixedDelay = 60000, initialDelay = 60000)
  public void scheduledScan() {
    if (!lifecycle.mayRecover()) return;
    var p = db.one("SELECT * FROM integrity_scan_policy WHERE id=1");
    if (!b(p, "enabled") || n(p, "next_run") > System.currentTimeMillis()) return;
    String admin = s(p, "administrator_id");
    try {
      if (db.count(
              "SELECT COUNT(*) FROM sys_user WHERE id=? AND administrator=TRUE AND enabled=TRUE",
              admin)
          != 1) throw new Problem(403, "ADMIN_REQUIRED", "巡检管理员权限失效");
      scan(admin, null, null, null, "scheduled_" + n(p, "next_run"));
      db.update(
          "UPDATE integrity_scan_policy SET next_run=?,last_error=NULL WHERE id=1",
          System.currentTimeMillis() + n(p, "interval_hours") * 3600000L);
    } catch (Problem failure) {
      db.update(
          "UPDATE integrity_scan_policy SET last_error=?,next_run=? WHERE id=1",
          failure.code,
          System.currentTimeMillis() + 3600000L);
    }
  }
}
