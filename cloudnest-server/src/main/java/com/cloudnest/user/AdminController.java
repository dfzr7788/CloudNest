package com.cloudnest.user;

import static com.cloudnest.common.Db.*;

import com.cloudnest.common.*;
import jakarta.validation.Valid;
import jakarta.validation.constraints.*;
import java.io.IOException;
import java.nio.file.*;
import java.util.*;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/api/admin")
public class AdminController {
  private final Db db;
  private final Path data;

  public AdminController(Db db, @Value("${cloudnest.data-dir}") String dir) {
    this.db = db;
    data = Path.of(dir).toAbsolutePath();
  }

  private void admin(Authentication a) {
    if (db.count(
            "SELECT COUNT(*) FROM sys_user WHERE id=? AND administrator=TRUE AND enabled=TRUE",
            a.getName())
        != 1) throw new Problem(403, "ADMIN_REQUIRED", "需要管理员权限");
  }

  @GetMapping("/users")
  public List<Map<String, Object>> users(
      Authentication a, @RequestParam(defaultValue = "1") int page) {
    admin(a);
    return db.list(
        "SELECT"
            + " id,username,nickname,storage_quota,storage_used,storage_reserved,enabled,administrator"
            + " FROM sys_user ORDER BY created_at,id LIMIT 50 OFFSET ?",
        (Math.max(1L, page) - 1) * 50);
  }

  public record Invite(@Min(1048576) long quota) {}

  @PostMapping("/invitations")
  public Map<String, Object> invite(Authentication a, @Valid @RequestBody Invite in) {
    admin(a);
    return db.atomic(
        a.getName(),
        () -> {
          if (db.count(
                  "SELECT COUNT(*) FROM invitation WHERE created_by=? AND used_by IS NULL AND"
                      + " expires_at>?",
                  a.getName(),
                  System.currentTimeMillis())
              >= 20) throw Problem.conflict("最多保留 20 个未使用的邀请码");
          String token = Util.token();
          long expires = System.currentTimeMillis() + 48L * 3600000;
          db.update(
              "INSERT INTO invitation(id,token_hash,created_by,quota,expires_at) VALUES(?,?,?,?,?)",
              Util.id(),
              Util.hash(token),
              a.getName(),
              in.quota(),
              expires);
          return Map.of("token", token, "expiresAt", expires);
        });
  }

  public record Update(@Min(0) long quota, boolean enabled) {}

  @PatchMapping("/users/{id}")
  public void update(Authentication a, @PathVariable String id, @Valid @RequestBody Update in) {
    admin(a);
    db.atomic(
        id,
        () -> {
          var user = db.one("SELECT * FROM sys_user WHERE id=?", id);
          if (b(user, "administrator") && !in.enabled()) throw Problem.invalid("管理员账号禁用须通过本机维护处理");
          if (in.quota() < n(user, "storage_used") + n(user, "storage_reserved"))
            throw Problem.conflict("配额不能低于已用与上传预留之和");
          db.update(
              "UPDATE sys_user SET storage_quota=?,enabled=? WHERE id=?",
              in.quota(),
              in.enabled(),
              id);
          if (!in.enabled()) db.update("DELETE FROM auth_session WHERE user_id=?", id);
          db.update(
              "INSERT INTO activity_log(id,user_id,action,target_id,created_at) VALUES(?,?,?,?,?)",
              Util.id(),
              a.getName(),
              "USER_UPDATED",
              id,
              System.currentTimeMillis());
          return null;
        });
  }

  @GetMapping("/status")
  public Map<String, Object> status(Authentication a) throws IOException {
    admin(a);
    return Map.of(
        "freeBytes",
        Files.getFileStore(data).getUsableSpace(),
        "users",
        db.count("SELECT COUNT(*) FROM sys_user"),
        "pendingUploads",
        db.count("SELECT COUNT(*) FROM upload_session WHERE status IN ('UPLOADING','ASSEMBLING')"),
        "pendingDeletes",
        db.count("SELECT COUNT(*) FROM file_blob WHERE status='DELETING'"));
  }
}
