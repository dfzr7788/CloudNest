package com.cloudnest.file;

import static com.cloudnest.common.Db.*;

import com.cloudnest.common.*;
import jakarta.validation.Valid;
import jakarta.validation.constraints.*;
import java.util.*;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/api/user/trash-policy")
public class TrashRetention {
  private final Db db;
  private final DriveService drive;

  public TrashRetention(Db db, DriveService drive) {
    this.db = db;
    this.drive = drive;
  }

  public record Policy(@Min(0) @Max(365) int days) {}

  @GetMapping
  public Map<String, Object> get(Authentication a) {
    return Map.of(
        "days", n(db.one("SELECT trash_days FROM sys_user WHERE id=?", a.getName()), "trash_days"));
  }

  @PutMapping
  public void set(Authentication a, @Valid @RequestBody Policy in) {
    db.update("UPDATE sys_user SET trash_days=? WHERE id=?", in.days(), a.getName());
  }

  @Scheduled(fixedDelay = 3600000, initialDelay = 3600000)
  public void cleanup() {
    for (var user : db.list("SELECT id,trash_days FROM sys_user WHERE trash_days>0")) {
      String uid = s(user, "id");
      long cutoff = System.currentTimeMillis() - n(user, "trash_days") * 86400000;
      for (var e : drive.trashList(uid))
        if (n(e, "deletedAt") <= cutoff) {
          try {
            db.atomic(
                uid,
                () -> {
                  // Do not delete younger independently trashed descendants together with an older
                  // root.
                  if (drive.tree(uid, s(e, "id")).stream()
                      .anyMatch(node -> !b(node, "deleted") || n(node, "deleted_at") > cutoff))
                    return null;
                  drive.purge(uid, s(e, "id"), s(e, "kind"));
                  db.update(
                      "INSERT INTO activity_log(id,user_id,action,target_id,created_at)"
                          + " VALUES(?,?,?,?,?)",
                      Util.id(),
                      uid,
                      "TRASH_AUTO_PURGED",
                      e.get("id"),
                      System.currentTimeMillis());
                  return null;
                });
          } catch (Problem ignored) {
          }
        }
    }
  }
}
