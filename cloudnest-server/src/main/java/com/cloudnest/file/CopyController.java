package com.cloudnest.file;

import static com.cloudnest.common.Db.*;

import com.cloudnest.common.*;
import jakarta.validation.Valid;
import jakarta.validation.constraints.*;
import java.util.*;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/api/entries")
public class CopyController {
  private final Db db;
  private final DriveService drive;

  public CopyController(Db db, DriveService drive) {
    this.db = db;
    this.drive = drive;
  }

  public record Input(
      @NotEmpty @Size(max = 100) List<@NotBlank String> ids, @NotBlank String parentId) {}

  @PostMapping("/copy")
  public Map<String, Object> copy(Authentication auth, @Valid @RequestBody Input input) {
    String uid = auth.getName();
    return db.atomic(
        uid,
        () -> {
          drive.folder(uid, input.parentId());
          Set<String> roots = new LinkedHashSet<>(input.ids());
          var ancestors = drive.breadcrumbs(uid, input.parentId());
          for (String id : roots) {
            var source = drive.live(uid, id);
            if (source.get("parent_id") == null) throw Problem.invalid("不能复制根目录");
            if (ancestors.stream().anyMatch(p -> id.equals(p.get("id"))))
              throw Problem.invalid("不能复制目录到自身或其子目录");
          }
          Set<String> chosenRoots = Set.copyOf(roots);
          roots.removeIf(
              id ->
                  drive.breadcrumbs(uid, s(drive.live(uid, id), "parent_id")).stream()
                      .anyMatch(p -> chosenRoots.contains(p.get("id"))));
          record Item(Map<String, Object> entry, String parent, boolean root) {}
          ArrayDeque<Item> queue = new ArrayDeque<>();
          for (String id : roots) queue.add(new Item(drive.live(uid, id), input.parentId(), true));
          List<String> created = new ArrayList<>();
          Set<String> seen = new HashSet<>();
          long total = 0, now = System.currentTimeMillis();
          var user = db.one("SELECT * FROM sys_user WHERE id=?", uid);
          while (!queue.isEmpty()) {
            var item = queue.removeFirst();
            var e = item.entry();
            String source = s(e, "id"), kind = s(e, "kind"), name = s(e, "name");
            if (!seen.add(source) || seen.size() > 10000)
              throw Problem.invalid("复制目录过大或结构异常，单次最多 10000 项");
            String chosen = name;
            for (int n = 1;
                db.count(
                        "SELECT COUNT(*) FROM drive_entry WHERE user_id=? AND parent_id=? AND"
                            + " kind=? AND name=? AND deleted=FALSE",
                        uid,
                        item.parent(),
                        kind,
                        chosen)
                    > 0;
                n++) {
              if (!item.root() || n > 9999) throw Problem.conflict("复制时遇到重名");
              String suffix = " (副本 " + n + ")", stem = name, extension = "";
              int dot = name.lastIndexOf('.');
              if (kind.equals("FILE") && dot > 0 && name.length() - dot <= 40) {
                stem = name.substring(0, dot);
                extension = name.substring(dot);
              }
              chosen =
                  stem.substring(
                          0, Math.min(stem.length(), 180 - suffix.length() - extension.length()))
                      + suffix
                      + extension;
            }
            total = Math.addExact(total, n(e, "size"));
            if (total
                > n(user, "storage_quota") - n(user, "storage_used") - n(user, "storage_reserved"))
              throw new Problem(409, "QUOTA_EXCEEDED", "复制所需配额不足");
            String id = Util.id();
            db.update(
                "INSERT INTO"
                    + " drive_entry(id,user_id,parent_id,kind,name,blob_id,size,mime,created_at,updated_at)"
                    + " VALUES(?,?,?,?,?,?,?,?,?,?)",
                id,
                uid,
                item.parent(),
                kind,
                chosen,
                e.get("blob_id"),
                e.get("size"),
                e.get("mime"),
                now,
                now);
            db.update(
                "INSERT INTO entry_tag(entry_id,tag) SELECT ?,tag FROM entry_tag WHERE entry_id=?",
                id,
                source);
            if (item.root()) created.add(id);
            if (kind.equals("FOLDER")) {
              var children =
                  db.list(
                      "SELECT * FROM drive_entry WHERE user_id=? AND parent_id=? AND deleted=FALSE"
                          + " ORDER BY id LIMIT 10001",
                      uid,
                      source);
              if (children.size() + seen.size() + queue.size() > 10000)
                throw Problem.invalid("单次复制最多 10000 项");
              for (var child : children) queue.add(new Item(child, id, false));
            }
          }
          db.update("UPDATE sys_user SET storage_used=storage_used+? WHERE id=?", total, uid);
          db.update(
              "INSERT INTO activity_log(id,user_id,action,target_id,created_at) VALUES(?,?,?,?,?)",
              Util.id(),
              uid,
              "FILES_COPIED",
              input.parentId(),
              now);
          return Map.of("ids", created, "count", seen.size(), "size", total);
        });
  }
}
