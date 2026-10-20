package com.cloudnest.resilience;

import static com.cloudnest.common.Db.*;

import com.cloudnest.common.*;
import com.cloudnest.file.DriveService;
import java.util.*;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;

/** Immutable metadata references. Quota charges each retained logical file reference. */
@Service
public class SnapshotService {
  private final Db db;
  private final DriveService drive;
  private final com.cloudnest.WorkerLifecycle lifecycle;

  public SnapshotService(Db db, DriveService drive, com.cloudnest.WorkerLifecycle lifecycle) {
    this.db = db;
    this.drive = drive;
    this.lifecycle = lifecycle;
  }

  public Map<String, Object> owned(String uid, String id) {
    return db.one("SELECT * FROM folder_snapshot WHERE id=? AND user_id=?", id, uid);
  }

  public List<Map<String, Object>> list(String uid, String folder) {
    if (folder == null)
      return db.list(
          "SELECT * FROM folder_snapshot WHERE user_id=? ORDER BY created_at DESC LIMIT 500", uid);
    // Historical folders can have been purged; authorization comes from snapshot ownership.
    return db.list(
        "SELECT * FROM folder_snapshot WHERE user_id=? AND folder_id=? ORDER BY created_at DESC"
            + " LIMIT 500",
        uid,
        folder);
  }

  public List<Map<String, Object>> entries(String uid, String id) {
    owned(uid, id);
    return db.list("SELECT * FROM folder_snapshot_entry WHERE snapshot_id=? ORDER BY ordinal", id);
  }

  public Map<String, Object> create(String uid, String folder, String input) {
    return db.atomic(
        uid,
        () -> {
          var root = drive.folder(uid, folder);
          var rows = liveTree(uid, s(root, "id"));
          if (rows.size() > 20000) throw Problem.invalid("一次快照最多 20000 项，请拆分目录");
          if (db.count("SELECT COUNT(*) FROM folder_snapshot WHERE user_id=?", uid) >= 500)
            throw Problem.conflict("快照数量已达 500，请先清理");
          long bytes = rows.stream().mapToLong(e -> n(e, "size")).reduce(0L, Math::addExact);
          charge(uid, bytes);
          String id = Util.id(),
              name = input == null || input.isBlank() ? s(root, "name") : Util.name(input);
          long now = System.currentTimeMillis();
          db.update(
              "INSERT INTO"
                  + " folder_snapshot(id,user_id,folder_id,name,created_at,total_bytes,entry_count)"
                  + " VALUES(?,?,?,?,?,?,?)",
              id,
              uid,
              s(root, "id"),
              name,
              now,
              bytes,
              rows.size());
          int ordinal = 0;
          for (var e : rows) {
            if (e.get("blob_id") != null)
              db.one(
                  "SELECT id FROM file_blob WHERE id=? AND user_id=? AND status='READY'",
                  e.get("blob_id"),
                  uid);
            db.update(
                "INSERT INTO"
                    + " folder_snapshot_entry(snapshot_id,entry_id,parent_entry_id,ordinal,kind,name,blob_id,size,mime,revision)"
                    + " VALUES(?,?,?,?,?,?,?,?,?,?)",
                id,
                e.get("id"),
                ordinal == 0 ? null : e.get("parent_id"),
                ordinal++,
                e.get("kind"),
                e.get("name"),
                e.get("blob_id"),
                e.get("size"),
                e.get("mime"),
                e.get("revision"));
          }
          if (db.count(
                  "SELECT COUNT(*) FROM snapshot_retention_policy WHERE user_id=? AND folder_id=?"
                      + " AND protection IN ('IMPORTANT','ARCHIVE')",
                  uid,
                  s(root, "id"))
              > 0)
            db.update(
                "UPDATE file_blob SET desired_replicas=2 WHERE id IN (SELECT blob_id FROM"
                    + " folder_snapshot_entry WHERE snapshot_id=?)",
                id);
          audit(uid, "SNAPSHOT_CREATED", id);
          return owned(uid, id);
        });
  }

  public List<Map<String, Object>> liveTree(String uid, String root) {
    var first = drive.folder(uid, root);
    var queue = new ArrayDeque<String>();
    var result = new ArrayList<Map<String, Object>>();
    var seen = new HashSet<String>();
    result.add(first);
    seen.add(root);
    queue.add(root);
    while (!queue.isEmpty()) {
      var parents = new ArrayList<String>();
      while (!queue.isEmpty() && parents.size() < 128) parents.add(queue.remove());
      var args = new ArrayList<Object>();
      args.add(uid);
      args.addAll(parents);
      args.add(20001 - result.size());
      for (var e :
          db.list(
              "SELECT * FROM drive_entry WHERE user_id=? AND deleted=FALSE AND parent_id IN ("
                  + String.join(",", Collections.nCopies(parents.size(), "?"))
                  + ") ORDER BY parent_id,kind DESC,name,id LIMIT ?",
              args.toArray())) {
        String id = s(e, "id");
        if (!seen.add(id) || result.size() >= 20000) throw Problem.invalid("目录过大或结构异常");
        result.add(e);
        if ("FOLDER".equals(s(e, "kind"))) queue.add(id);
      }
    }
    return result;
  }

  public void charge(String uid, long bytes) {
    var user = db.one("SELECT * FROM sys_user WHERE id=?", uid);
    if (bytes < 0
        || bytes > n(user, "storage_quota") - n(user, "storage_used") - n(user, "storage_reserved"))
      throw new Problem(409, "QUOTA_EXCEEDED", "快照和恢复副本需要足够的逻辑配额");
    db.update("UPDATE sys_user SET storage_used=storage_used+? WHERE id=?", bytes, uid);
  }

  public void delete(String uid, String id) {
    db.atomic(
        uid,
        () -> {
          var snapshot = owned(uid, id);
          if (db.count(
                  "SELECT COUNT(*) FROM snapshot_restore_job j JOIN resilience_task t ON"
                      + " t.id=j.task_id WHERE j.snapshot_id=? AND t.status IN"
                      + " ('QUEUED','RUNNING','PAUSED','FAILED')",
                  id)
              > 0) throw Problem.conflict("快照仍被可恢复任务引用，请先取消任务");
          var blobs =
              db.list(
                  "SELECT DISTINCT blob_id FROM folder_snapshot_entry WHERE snapshot_id=? AND"
                      + " blob_id IS NOT NULL",
                  id);
          // Keep completed task metadata, detach its no-longer-needed plan.
          db.update(
              "DELETE FROM snapshot_restore_entry WHERE task_id IN (SELECT task_id FROM"
                  + " snapshot_restore_job WHERE snapshot_id=?)",
              id);
          db.update("DELETE FROM snapshot_restore_job WHERE snapshot_id=?", id);
          db.update("DELETE FROM folder_snapshot_entry WHERE snapshot_id=?", id);
          db.update("DELETE FROM folder_snapshot WHERE id=?", id);
          db.update(
              "UPDATE sys_user SET storage_used=storage_used-? WHERE id=?",
              snapshot.get("total_bytes"),
              uid);
          for (var b : blobs) drive.releaseBlob(s(b, "blob_id"));
          audit(uid, "SNAPSHOT_DELETED", id);
          return null;
        });
  }

  public Map<String, Object> diff(String uid, String id, String against) {
    var snapshot = owned(uid, id);
    var previous = entries(uid, id);
    List<Map<String, Object>> current;
    if (against != null) {
      if (!Objects.equals(owned(uid, against).get("folder_id"), snapshot.get("folder_id")))
        throw Problem.invalid("只能比较同一目录的快照");
      current = entries(uid, against);
    } else {
      var ids = previous.stream().map(e -> s(e, "entry_id")).toList();
      var args = new ArrayList<Object>();
      args.add(uid);
      args.addAll(ids);
      var scoped = new LinkedHashMap<String, Map<String, Object>>();
      for (var e :
          db.list(
              "SELECT * FROM drive_entry WHERE user_id=? AND deleted=FALSE AND id IN ("
                  + String.join(",", Collections.nCopies(ids.size(), "?"))
                  + ")",
              args.toArray())) scoped.put(s(e, "id"), e);
      try {
        for (var e : liveTree(uid, s(snapshot, "folder_id"))) scoped.put(s(e, "id"), e);
      } catch (Problem missing) {
        if (missing.status != 404) throw missing;
      }
      current = new ArrayList<>();
      for (var e : scoped.values()) {
        var view = new LinkedHashMap<>(e);
        view.put("entry_id", e.get("id"));
        view.put("parent_entry_id", e.get("parent_id"));
        current.add(view);
      }
    }
    var now = new HashMap<String, Map<String, Object>>();
    for (var e : current) now.put(s(e, "entry_id"), e);
    var changes = new ArrayList<Map<String, Object>>();
    var oldIds = new HashSet<String>();
    for (var e : previous) {
      String eid = s(e, "entry_id");
      oldIds.add(eid);
      var c = now.get(eid);
      if (c == null) changes.add(change(e, "DELETED"));
      else {
        if (!Objects.equals(e.get("blob_id"), c.get("blob_id"))) changes.add(change(e, "MODIFIED"));
        if (!Objects.equals(e.get("name"), c.get("name"))
            || (n(e, "ordinal") != 0
                && !Objects.equals(e.get("parent_entry_id"), c.get("parent_entry_id"))))
          changes.add(change(e, "MOVED"));
      }
    }
    // For a live comparison, include only reachable descendants of this historical root.
    Set<String> scope = new HashSet<>();
    scope.add(s(snapshot, "folder_id"));
    var children = new HashMap<String, List<String>>();
    for (var e : current)
      children
          .computeIfAbsent(s(e, "parent_entry_id"), k -> new ArrayList<>())
          .add(s(e, "entry_id"));
    var queue = new ArrayDeque<String>();
    queue.add(s(snapshot, "folder_id"));
    while (!queue.isEmpty())
      for (String child : children.getOrDefault(queue.remove(), List.of()))
        if (scope.add(child)) queue.add(child);
    for (var e : current)
      if (!oldIds.contains(s(e, "entry_id"))
          && (against != null || scope.contains(s(e, "entry_id")))) changes.add(change(e, "ADDED"));
    return Map.of("changes", changes, "restoreMode", "COPY", "preservesCurrentFiles", true);
  }

  private Map<String, Object> change(Map<String, Object> entry, String kind) {
    return Map.of("entryId", s(entry, "entry_id"), "name", s(entry, "name"), "kind", kind);
  }

  public List<Map<String, Object>> policies(String uid) {
    return db.list("SELECT * FROM snapshot_retention_policy WHERE user_id=?", uid);
  }

  public void policy(
      String uid, String folder, boolean enabled, int hours, int keep, String protection) {
    if (hours < 1
        || hours > 8760
        || keep < 1
        || keep > 365
        || !Set.of("NORMAL", "IMPORTANT", "ARCHIVE").contains(protection))
      throw Problem.invalid("快照策略无效");
    db.atomic(
        uid,
        () -> {
          String fid = s(drive.folder(uid, folder), "id");
          db.update(
              "DELETE FROM snapshot_retention_policy WHERE user_id=? AND folder_id=?", uid, fid);
          db.update(
              "INSERT INTO"
                  + " snapshot_retention_policy(user_id,folder_id,enabled,interval_hours,keep_count,next_run,protection)"
                  + " VALUES(?,?,?,?,?,?,?)",
              uid,
              fid,
              enabled,
              hours,
              keep,
              System.currentTimeMillis() + hours * 3600000L,
              protection);
          if (!protection.equals("NORMAL")) {
            for (var e : liveTree(uid, fid))
              if (e.get("blob_id") != null)
                db.update("UPDATE file_blob SET desired_replicas=2 WHERE id=?", e.get("blob_id"));
          }
          audit(uid, "SNAPSHOT_POLICY_UPDATED", fid);
          return null;
        });
  }

  @Scheduled(fixedDelay = 60000, initialDelay = 60000)
  public void scheduled() {
    if (!lifecycle.mayRecover()) return;
    for (var p :
        db.list(
            "SELECT * FROM snapshot_retention_policy WHERE enabled=TRUE AND next_run<=? LIMIT 20",
            System.currentTimeMillis())) {
      String uid = s(p, "user_id"), fid = s(p, "folder_id");
      try {
        db.atomic(
            uid,
            () -> {
              // Recheck under owner lock to prevent a manual policy update racing this tick.
              var policy =
                  db.one(
                      "SELECT * FROM snapshot_retention_policy WHERE user_id=? AND folder_id=?",
                      uid,
                      fid);
              if (!b(policy, "enabled") || n(policy, "next_run") > System.currentTimeMillis())
                return null;
              // Prune before charging a new snapshot. Active restore references are never removed.
              var retained = list(uid, fid);
              for (int i = (int) n(policy, "keep_count") - 1; i < retained.size(); i++) {
                String sid = s(retained.get(i), "id");
                if (db.count(
                        "SELECT COUNT(*) FROM snapshot_restore_job j JOIN resilience_task t ON"
                            + " t.id=j.task_id WHERE j.snapshot_id=? AND t.status IN"
                            + " ('QUEUED','RUNNING','PAUSED','FAILED')",
                        sid)
                    == 0) delete(uid, sid);
              }
              create(uid, fid, null);
              db.update(
                  "UPDATE snapshot_retention_policy SET next_run=?,last_error=NULL WHERE user_id=?"
                      + " AND folder_id=?",
                  System.currentTimeMillis() + n(policy, "interval_hours") * 3600000L,
                  uid,
                  fid);
              return null;
            });
      } catch (Problem failure) {
        db.update(
            "UPDATE snapshot_retention_policy SET last_error=?,next_run=? WHERE user_id=? AND"
                + " folder_id=?",
            failure.code,
            System.currentTimeMillis() + 3600000L,
            uid,
            fid);
      }
    }
  }

  public void audit(String uid, String action, String target) {
    db.update(
        "INSERT INTO activity_log(id,user_id,action,target_id,created_at) VALUES(?,?,?,?,?)",
        Util.id(),
        uid,
        action,
        target,
        System.currentTimeMillis());
  }
}
