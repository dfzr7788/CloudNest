package com.cloudnest.file;

import static com.cloudnest.common.Db.*;

import com.cloudnest.common.*;
import java.util.*;
import org.springframework.stereotype.Service;

@Service
public class DriveService {
  private final Db db;

  public DriveService(Db db) {
    this.db = db;
  }

  public String root(String uid) {
    return s(db.one("SELECT root_id FROM sys_user WHERE id=?", uid), "root_id");
  }

  public Map<String, Object> entry(String uid, String id, boolean deleted) {
    var e = db.one("SELECT * FROM drive_entry WHERE id=? AND user_id=?", id, uid);
    if (b(e, "deleted") != deleted) throw Problem.missing();
    return e;
  }

  public Map<String, Object> live(String uid, String id) {
    var e = entry(uid, id, false);
    String parent = s(e, "parent_id");
    Set<String> seen = new HashSet<>();
    seen.add(id);
    while (parent != null) {
      if (!seen.add(parent)) throw Problem.conflict("目录结构异常");
      var p = entry(uid, parent, false);
      parent = s(p, "parent_id");
    }
    return e;
  }

  public Map<String, Object> folder(String uid, String id) {
    var e = live(uid, id == null || id.isBlank() ? root(uid) : id);
    if (!s(e, "kind").equals("FOLDER")) throw Problem.invalid("目标必须是文件夹");
    return e;
  }

  public Map<String, Object> file(String uid, String id) {
    var e = live(uid, id);
    if (!s(e, "kind").equals("FILE")) throw Problem.invalid("目标必须是文件");
    return e;
  }

  public void available(String uid, String parent, String kind, String name, String except) {
    if (db.count(
            "SELECT COUNT(*) FROM drive_entry WHERE user_id=? AND parent_id=? AND kind=? AND name=?"
                + " AND deleted=FALSE AND id<>?",
            uid,
            parent,
            kind,
            name,
            except == null ? "" : except)
        > 0) throw Problem.conflict("目标目录已存在同名" + (kind.equals("FILE") ? "文件" : "文件夹"));
  }

  public Map<String, Object> view(Map<String, Object> e) {
    Map<String, Object> v = new LinkedHashMap<>();
    for (String k : List.of("id", "kind", "name", "size", "mime")) v.put(k, e.get(k));
    v.put("parentId", e.get("parent_id"));
    v.put("createdAt", e.get("created_at"));
    v.put("updatedAt", e.get("updated_at"));
    v.put("deletedAt", e.get("deleted_at"));
    v.put("favorite", e.get("favorite"));
    v.put("lastOpened", e.get("last_opened"));
    v.put("revision", e.get("revision"));
    return v;
  }

  public Map<String, Object> list(
      String uid,
      String parent,
      String q,
      String scope,
      String sort,
      String direction,
      int page,
      int size) {
    return list(
        uid,
        parent,
        q,
        scope,
        sort,
        direction,
        page,
        size,
        "all",
        "all",
        0,
        Long.MAX_VALUE,
        0,
        Long.MAX_VALUE);
  }

  public Map<String, Object> list(
      String uid,
      String parent,
      String q,
      String scope,
      String sort,
      String direction,
      int page,
      int size,
      String collection,
      String type,
      long minSize,
      long maxSize,
      long after,
      long before) {
    if (minSize < 0 || maxSize < minSize || after < 0 || before < after)
      throw Problem.invalid("筛选范围无效");
    page = Math.max(1, page);
    size = Math.clamp(size, 1, 100);
    String fid = s(folder(uid, parent), "id");
    String order =
        switch (sort) {
          case "size" -> "size";
          case "updatedAt" -> "updated_at";
          case "createdAt" -> "created_at";
          case "lastOpened" -> "last_opened";
          default -> "name";
        };
    String dir = "desc".equalsIgnoreCase(direction) ? "DESC" : "ASC";
    List<Object> args = new ArrayList<>(List.of(uid));
    String where = "user_id=? AND deleted=FALSE AND parent_id IS NOT NULL";
    boolean search = q != null && !q.isBlank();
    if ("all".equals(collection) && (!search || !"all".equals(scope))) {
      where += " AND parent_id=?";
      args.add(fid);
    }
    switch (collection) {
      case "favorites" -> where += " AND favorite=TRUE";
      case "opened" -> where += " AND last_opened IS NOT NULL";
      case "uploaded" -> where += " AND kind='FILE'";
      case "all" -> {}
      default -> throw Problem.invalid("集合类型错误");
    }
    switch (type) {
      case "image" -> where += " AND mime LIKE 'image/%'";
      case "document" -> where += " AND (mime LIKE 'text/%' OR mime='application/pdf')";
      case "folder" -> where += " AND kind='FOLDER'";
      case "all" -> {}
      default -> throw Problem.invalid("文件类型错误");
    }
    where += " AND size>=? AND size<=? AND updated_at>=? AND updated_at<=?";
    args.addAll(List.of(minSize, maxSize, after, before));
    if (search) {
      if (q.length() > 180) throw Problem.invalid("搜索关键词过长");
      where += " AND LOWER(name) LIKE ? ESCAPE '!'";
      args.add(
          "%"
              + q.toLowerCase(Locale.ROOT).replace("!", "!!").replace("%", "!%").replace("_", "!_")
              + "%");
    }
    long total = db.count("SELECT COUNT(*) FROM drive_entry WHERE " + where, args.toArray());
    args.add(size);
    args.add(((long) page - 1) * size);
    var rows =
        db.list(
            "SELECT * FROM drive_entry WHERE "
                + where
                + " ORDER BY kind DESC,"
                + order
                + " "
                + dir
                + ",id ASC LIMIT ? OFFSET ?",
            args.toArray());
    List<Map<String, Object>> items = new ArrayList<>();
    for (var e : rows) {
      var v = view(e);
      if (search || !collection.equals("all")) v.put("path", path(uid, s(e, "parent_id")));
      items.add(v);
    }
    return Map.of("items", items, "total", total, "page", page, "pageSize", size, "parentId", fid);
  }

  public List<Map<String, Object>> breadcrumbs(String uid, String id) {
    var e = folder(uid, id);
    List<Map<String, Object>> result = new ArrayList<>();
    Set<String> seen = new HashSet<>();
    while (e != null) {
      String eid = s(e, "id");
      if (!seen.add(eid)) throw Problem.conflict("目录结构异常");
      result.add(Map.of("id", eid, "name", s(e, "name")));
      String p = s(e, "parent_id");
      e = p == null ? null : live(uid, p);
    }
    Collections.reverse(result);
    return result;
  }

  private String path(String uid, String folder) {
    return String.join(" / ", breadcrumbs(uid, folder).stream().map(e -> s(e, "name")).toList());
  }

  public Map<String, Object> createFolder(String uid, String parent, String input) {
    return db.atomic(
        uid,
        () -> {
          String p = s(folder(uid, parent), "id"), name = Util.name(input);
          available(uid, p, "FOLDER", name, null);
          String id = Util.id();
          long now = System.currentTimeMillis();
          db.update(
              "INSERT INTO drive_entry(id,user_id,parent_id,kind,name,created_at,updated_at)"
                  + " VALUES(?,?,?,'FOLDER',?,?,?)",
              id,
              uid,
              p,
              name,
              now,
              now);
          log(uid, "FOLDER_CREATED", id);
          return view(live(uid, id));
        });
  }

  public Map<String, Object> edit(
      String uid, String id, String input, String target, String expectedKind) {
    return db.atomic(
        uid,
        () -> {
          var e = live(uid, id);
          if (!s(e, "kind").equals(expectedKind)) throw Problem.missing();
          if (s(e, "parent_id") == null) throw Problem.invalid("不能修改根目录");
          String name = input == null ? s(e, "name") : Util.name(input),
              parent = target == null ? s(e, "parent_id") : s(folder(uid, target), "id");
          if (expectedKind.equals("FOLDER")) {
            String p = parent;
            while (p != null) {
              if (p.equals(id)) throw Problem.invalid("不能移动到自身或子文件夹");
              p = s(live(uid, p), "parent_id");
            }
          }
          available(uid, parent, expectedKind, name, id);
          db.update(
              "UPDATE drive_entry SET name=?,parent_id=?,updated_at=? WHERE id=?",
              name,
              parent,
              System.currentTimeMillis(),
              id);
          log(uid, "ENTRY_UPDATED", id);
          return view(live(uid, id));
        });
  }

  public List<Map<String, Object>> tree(String uid, String id) {
    var all = db.list("SELECT * FROM drive_entry WHERE user_id=?", uid);
    Map<String, List<Map<String, Object>>> byParent = new HashMap<>();
    for (var e : all) byParent.computeIfAbsent(s(e, "parent_id"), k -> new ArrayList<>()).add(e);
    List<Map<String, Object>> result = new ArrayList<>();
    ArrayDeque<String> queue = new ArrayDeque<>();
    Set<String> seen = new HashSet<>();
    queue.add(id);
    while (!queue.isEmpty()) {
      String current = queue.remove();
      if (!seen.add(current)) throw Problem.conflict("目录结构异常");
      result.add(db.one("SELECT * FROM drive_entry WHERE id=? AND user_id=?", current, uid));
      for (var e : byParent.getOrDefault(current, List.of())) queue.add(s(e, "id"));
    }
    return result;
  }

  public void trash(String uid, String id, String kind) {
    db.atomic(
        uid,
        () -> {
          var e = live(uid, id);
          if (!s(e, "kind").equals(kind)) throw Problem.missing();
          if (s(e, "parent_id") == null) throw Problem.invalid("不能删除根目录");
          String batch = Util.id();
          long now = System.currentTimeMillis();
          for (var node : tree(uid, id))
            if (!b(node, "deleted")) {
              db.update(
                  "UPDATE drive_entry SET deleted=TRUE,delete_batch=?,deleted_at=?,updated_at=?"
                      + " WHERE id=?",
                  batch,
                  now,
                  now,
                  s(node, "id"));
              db.update(
                  "UPDATE file_share SET revoked=TRUE,pinned_blob=NULL WHERE file_id=?",
                  s(node, "id"));
            }
          log(uid, "TRASH", id);
          return null;
        });
  }

  public List<Map<String, Object>> trashList(String uid) {
    // Show deletion roots; children of the same batch restore/purge with their root.
    return db
        .list(
            "SELECT e.* FROM drive_entry e LEFT JOIN drive_entry p ON p.id=e.parent_id WHERE"
                + " e.user_id=? AND e.deleted=TRUE AND (p.id IS NULL OR p.deleted=FALSE OR"
                + " p.delete_batch<>e.delete_batch) ORDER BY e.deleted_at DESC",
            uid)
        .stream()
        .map(this::view)
        .toList();
  }

  public Map<String, Object> restore(String uid, String id, String kind) {
    return db.atomic(
        uid,
        () -> {
          var e = entry(uid, id, true);
          if (!s(e, "kind").equals(kind)) throw Problem.missing();
          String batch = s(e, "delete_batch"), parent = s(e, "parent_id");
          boolean fallback = false;
          try {
            folder(uid, parent);
          } catch (Problem p) {
            parent = root(uid);
            fallback = true;
          }
          db.update("UPDATE drive_entry SET parent_id=? WHERE id=?", parent, id);
          for (var node : tree(uid, id))
            if (b(node, "deleted") && batch.equals(s(node, "delete_batch"))) {
              String n = s(node, "name"), p = s(node, "parent_id"), nid = s(node, "id");
              int i = 0;
              String chosen = n;
              while (db.count(
                      "SELECT COUNT(*) FROM drive_entry WHERE user_id=? AND parent_id=? AND kind=?"
                          + " AND name=? AND deleted=FALSE",
                      uid,
                      p,
                      s(node, "kind"),
                      chosen)
                  > 0) {
                String suffix = " (恢复 " + (++i) + ")";
                chosen = n.substring(0, Math.min(n.length(), 180 - suffix.length())) + suffix;
              }
              db.update(
                  "UPDATE drive_entry SET"
                      + " name=?,deleted=FALSE,delete_batch=NULL,deleted_at=NULL,updated_at=? WHERE"
                      + " id=?",
                  chosen,
                  System.currentTimeMillis(),
                  nid);
            }
          var result = view(live(uid, id));
          result.put("restoredToRoot", fallback);
          log(uid, "RESTORE", id);
          return result;
        });
  }

  public void purge(String uid, String id, String kind) {
    db.atomic(
        uid,
        () -> {
          var e = entry(uid, id, true);
          if (!s(e, "kind").equals(kind)) throw Problem.missing();
          var nodes = tree(uid, id);
          Collections.reverse(nodes);
          long bytes = 0;
          Set<String> blobs = new HashSet<>();
          for (var node : nodes) {
            if (!b(node, "deleted")) throw Problem.conflict("目录仍包含有效资源");
            String nid = s(node, "id"), blob = s(node, "blob_id");
            for (var v : db.list("SELECT * FROM file_version WHERE entry_id=?", nid)) {
              bytes += n(v, "size");
              blobs.add(s(v, "blob_id"));
            }
            db.update("DELETE FROM file_version WHERE entry_id=?", nid);
            if (blob != null) {
              blobs.add(blob);
              bytes += n(node, "size");
            }
            db.update(
                "DELETE FROM share_grant WHERE share_id IN (SELECT id FROM file_share WHERE"
                    + " file_id=?)",
                nid);
            db.update("DELETE FROM file_share WHERE file_id=?", nid);
            // Derived AI bodies must disappear in the same transaction as permanent deletion.
            db.update(
                "DELETE FROM ai_citation WHERE message_id IN (SELECT m.id FROM ai_message m JOIN"
                    + " ai_conversation c ON c.id=m.conversation_id WHERE c.file_id=? AND"
                    + " c.user_id=?)",
                nid,
                uid);
            db.update(
                "DELETE FROM ai_message WHERE conversation_id IN (SELECT id FROM ai_conversation"
                    + " WHERE file_id=? AND user_id=?)",
                nid,
                uid);
            db.update("DELETE FROM ai_conversation WHERE file_id=? AND user_id=?", nid, uid);
            db.update(
                "UPDATE ai_task SET"
                    + " cancel_requested=TRUE,status='CANCELLED',stage='FINISHED',request_json='{}',result_json=NULL,coverage_json=NULL,error_code='AI_ARTIFACT_DELETED',error_message=NULL,expires_at=?"
                    + " WHERE file_id=? AND user_id=?",
                System.currentTimeMillis(),
                nid,
                uid);
            db.update("DELETE FROM drive_entry WHERE id=?", nid);
          }
          db.update("UPDATE sys_user SET storage_used=storage_used-? WHERE id=?", bytes, uid);
          for (String blob : blobs) releaseBlob(blob);
          log(uid, "PURGE", id);
          return null;
        });
  }

  public boolean referenced(String blob) {
    return db.count("SELECT COUNT(*) FROM drive_entry WHERE blob_id=?", blob) > 0
        || db.count("SELECT COUNT(*) FROM file_version WHERE blob_id=?", blob) > 0
        || db.count("SELECT COUNT(*) FROM file_share WHERE pinned_blob=?", blob) > 0;
  }

  private void log(String uid, String action, String target) {
    db.update(
        "INSERT INTO activity_log(id,user_id,action,target_id,created_at) VALUES(?,?,?,?,?)",
        Util.id(),
        uid,
        action,
        target,
        System.currentTimeMillis());
  }

  public void releaseBlob(String blob) {
    if (!referenced(blob)) db.update("UPDATE file_blob SET status='DELETING' WHERE id=?", blob);
  }
}
