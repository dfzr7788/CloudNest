package com.cloudnest.file;

import static com.cloudnest.common.Db.*;

import com.cloudnest.common.*;
import jakarta.validation.Valid;
import jakarta.validation.constraints.*;
import java.util.*;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/api")
public class OrganizeController {
  private final Db db;
  private final DriveService drive;

  public OrganizeController(Db db, DriveService drive) {
    this.db = db;
    this.drive = drive;
  }

  public record Favorite(boolean favorite) {}

  @PatchMapping("/entries/{id}/favorite")
  public void favorite(Authentication a, @PathVariable String id, @RequestBody Favorite in) {
    db.atomic(
        a.getName(),
        () -> {
          drive.live(a.getName(), id);
          db.update("UPDATE drive_entry SET favorite=? WHERE id=?", in.favorite(), id);
          return null;
        });
  }

  @PostMapping("/entries/{id}/opened")
  public void opened(Authentication a, @PathVariable String id) {
    db.atomic(
        a.getName(),
        () -> {
          drive.file(a.getName(), id);
          db.update(
              "UPDATE drive_entry SET last_opened=? WHERE id=?", System.currentTimeMillis(), id);
          return null;
        });
  }

  public record Batch(
      @NotEmpty @Size(max = 100) List<@NotBlank String> ids,
      @NotBlank String action,
      String parentId) {}

  @PostMapping("/entries/batch")
  public Map<String, Object> batch(Authentication a, @Valid @RequestBody Batch in) {
    if (!Set.of("move", "trash", "restore", "purge").contains(in.action()))
      throw Problem.invalid("批量操作错误");
    String uid = a.getName();
    Set<String> requested = new LinkedHashSet<>(in.ids());
    List<Map<String, Object>> results = new ArrayList<>();
    var ordered = new ArrayList<>(requested);
    ordered.sort(
        Comparator.comparingInt(
            id -> {
              int depth = 0;
              String cursor = id;
              Set<String> seen = new HashSet<>();
              while (cursor != null && seen.add(cursor)) {
                var rows =
                    db.list(
                        "SELECT parent_id FROM drive_entry WHERE id=? AND user_id=?", cursor, uid);
                if (rows.isEmpty()) break;
                cursor = s(rows.getFirst(), "parent_id");
                depth++;
              }
              return depth;
            }));
    for (String id : ordered) {
      try {
        String status =
            db.atomic(
                uid,
                () -> {
                  var rows = db.list("SELECT * FROM drive_entry WHERE id=? AND user_id=?", id, uid);
                  if (rows.isEmpty()) {
                    if (in.action().equals("purge")) return "unchanged";
                    throw Problem.missing();
                  }
                  var e = rows.getFirst();
                  // An ancestor selected in this request owns the subtree operation.
                  if (!in.action().equals("restore")) {
                    String p = s(e, "parent_id");
                    Set<String> seen = new HashSet<>();
                    while (p != null && seen.add(p)) {
                      if (requested.contains(p)) {
                        String ancestor = p;
                        var outcome =
                            results.stream().filter(r -> r.get("id").equals(ancestor)).findFirst();
                        if (outcome.isEmpty() || outcome.get().get("status").equals("failed"))
                          throw Problem.conflict("所选父目录未能完成操作，请单独选择子项重试");
                        return "covered";
                      }
                      p =
                          s(
                              db.one(
                                  "SELECT parent_id FROM drive_entry WHERE id=? AND user_id=?",
                                  p,
                                  uid),
                              "parent_id");
                    }
                  }
                  String kind = s(e, "kind");
                  boolean deleted = b(e, "deleted");
                  switch (in.action()) {
                    case "move" -> {
                      if (in.parentId() == null) throw Problem.invalid("请选择目标目录");
                      drive.edit(uid, id, null, in.parentId(), kind);
                    }
                    case "trash" -> {
                      if (deleted) return "unchanged";
                      drive.trash(uid, id, kind);
                    }
                    case "restore" -> {
                      if (!deleted) return "unchanged";
                      drive.restore(uid, id, kind);
                    }
                    case "purge" -> drive.purge(uid, id, kind);
                  }
                  return "success";
                });
        results.add(Map.of("id", id, "status", status));
      } catch (Problem p) {
        results.add(Map.of("id", id, "status", "failed", "message", p.getMessage()));
      }
    }
    long failed = results.stream().filter(r -> r.get("status").equals("failed")).count();
    return Map.of("results", results, "succeeded", results.size() - failed, "failed", failed);
  }

  public record PathInput(
      String parentId, @NotNull @Size(max = 32) List<@NotBlank String> segments) {}

  @PostMapping("/folders/resolve-path")
  public Map<String, Object> resolve(Authentication a, @Valid @RequestBody PathInput in) {
    return db.atomic(
        a.getName(),
        () -> {
          String parent = s(drive.folder(a.getName(), in.parentId()), "id");
          int depth = drive.breadcrumbs(a.getName(), parent).size() + in.segments().size();
          if (depth > 33) throw Problem.invalid("目录最多支持 32 层");
          for (String raw : in.segments()) {
            String name = Util.name(raw);
            var rows =
                db.list(
                    "SELECT * FROM drive_entry WHERE user_id=? AND parent_id=? AND kind='FOLDER'"
                        + " AND name=? AND deleted=FALSE",
                    a.getName(),
                    parent,
                    name);
            parent =
                rows.isEmpty()
                    ? (String) drive.createFolder(a.getName(), parent, name).get("id")
                    : s(rows.getFirst(), "id");
          }
          return Map.of("id", parent);
        });
  }

  public record UploadName(String parentId, @NotBlank String name, @NotBlank String policy) {}

  @PostMapping("/entries/upload-name")
  public Map<String, Object> uploadName(Authentication a, @Valid @RequestBody UploadName in) {
    return db.atomic(
        a.getName(),
        () -> {
          String parent = s(drive.folder(a.getName(), in.parentId()), "id"),
              name = Util.name(in.name());
          if (!Set.of("skip", "keep", "replace").contains(in.policy()))
            throw Problem.invalid("冲突策略错误");
          if (in.policy().equals("replace")) {
            var matches =
                db.list(
                    "SELECT * FROM drive_entry WHERE user_id=? AND parent_id=? AND kind='FILE' AND"
                        + " name=? AND deleted=FALSE",
                    a.getName(),
                    parent,
                    name);
            if (!matches.isEmpty())
              return Map.of(
                  "name",
                  name,
                  "skipped",
                  false,
                  "targetId",
                  matches.getFirst().get("id"),
                  "expectedRevision",
                  matches.getFirst().get("revision"));
            return Map.of("name", name, "skipped", false);
          }
          for (int i = 0; i < 10000; i++) {
            String suffix = " (" + i + ")";
            String chosen =
                i == 0
                    ? name
                    : name.substring(0, Math.min(name.length(), 180 - suffix.length())) + suffix;
            long count =
                db.count(
                        "SELECT COUNT(*) FROM drive_entry WHERE user_id=? AND parent_id=? AND"
                            + " name=? AND kind='FILE' AND deleted=FALSE",
                        a.getName(),
                        parent,
                        chosen)
                    + db.count(
                        "SELECT COUNT(*) FROM upload_session WHERE user_id=? AND folder_id=? AND"
                            + " name=? AND status IN ('UPLOADING','ASSEMBLING') AND expires_at>?",
                        a.getName(),
                        parent,
                        chosen,
                        System.currentTimeMillis());
            if (count == 0) return Map.of("name", chosen, "skipped", false);
            if (in.policy().equals("skip")) return Map.of("name", name, "skipped", true);
          }
          throw Problem.conflict("同名副本过多，请重命名后上传");
        });
  }

  @GetMapping("/user/storage-breakdown")
  public Map<String, Object> space(Authentication a) {
    return db.atomic(
        a.getName(),
        () -> {
          var u = db.one("SELECT * FROM sys_user WHERE id=?", a.getName());
          long trash =
              db.count(
                  "SELECT COALESCE(SUM(size),0) FROM drive_entry WHERE user_id=? AND deleted=TRUE",
                  a.getName());
          long versions =
              db.count(
                  "SELECT COALESCE(SUM(v.size),0) FROM file_version v JOIN drive_entry e ON"
                      + " e.id=v.entry_id WHERE e.user_id=?",
                  a.getName());
          return Map.of(
              "live",
              n(u, "storage_used") - trash - versions,
              "trash",
              trash,
              "versions",
              versions,
              "reserved",
              n(u, "storage_reserved"),
              "quota",
              n(u, "storage_quota"));
        });
  }
}
