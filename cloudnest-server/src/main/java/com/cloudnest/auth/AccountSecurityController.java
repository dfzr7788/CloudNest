package com.cloudnest.auth;

import static com.cloudnest.common.Db.*;

import com.cloudnest.common.*;
import jakarta.validation.Valid;
import jakarta.validation.constraints.*;
import java.util.*;
import org.springframework.security.core.Authentication;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/api/user")
public class AccountSecurityController {
  private final Db db;
  private final PasswordEncoder passwords;
  private final com.cloudnest.security.RateLimits limits;

  public AccountSecurityController(
      Db db, PasswordEncoder passwords, com.cloudnest.security.RateLimits limits) {
    this.db = db;
    this.passwords = passwords;
    this.limits = limits;
  }

  public record PasswordInput(
      @NotNull String oldPassword, @NotNull @Size(min = 8, max = 64) String newPassword) {}

  @PostMapping("/password")
  public void change(Authentication a, @Valid @RequestBody PasswordInput in) {
    limits.check("password:" + a.getName(), 10);
    if (in.newPassword().getBytes(java.nio.charset.StandardCharsets.UTF_8).length > 72)
      throw Problem.invalid("密码的 UTF-8 编码不能超过 72 字节");
    db.atomic(
        a.getName(),
        () -> {
          var user = db.one("SELECT * FROM sys_user WHERE id=?", a.getName());
          if (!passwords.matches(in.oldPassword(), s(user, "password_hash")))
            throw new Problem(403, "WRONG_PASSWORD", "原密码错误");
          db.update(
              "UPDATE sys_user SET password_hash=? WHERE id=?",
              passwords.encode(in.newPassword()),
              a.getName());
          db.update("DELETE FROM auth_session WHERE user_id=?", a.getName());
          log(a.getName(), "PASSWORD_CHANGED", null);
          return null;
        });
  }

  @GetMapping("/sessions")
  public List<Map<String, Object>> sessions(Authentication a) {
    return db
        .list(
            "SELECT id,created_at,last_seen,client_label,expires_at FROM auth_session WHERE"
                + " user_id=? AND expires_at>? ORDER BY created_at DESC",
            a.getName(),
            System.currentTimeMillis())
        .stream()
        .map(
            s -> {
              var result = new LinkedHashMap<>(s);
              result.put("current", Objects.equals(s.get("id"), a.getCredentials()));
              return (Map<String, Object>) result;
            })
        .toList();
  }

  @DeleteMapping("/sessions/{id}")
  public void revoke(Authentication a, @PathVariable String id) {
    db.atomic(
        a.getName(),
        () -> {
          if (db.update("DELETE FROM auth_session WHERE id=? AND user_id=?", id, a.getName()) == 0)
            throw Problem.missing();
          log(a.getName(), "SESSION_REVOKED", id);
          return null;
        });
  }

  @DeleteMapping("/sessions")
  public void revokeOthers(Authentication a) {
    db.atomic(
        a.getName(),
        () -> {
          db.update(
              "DELETE FROM auth_session WHERE user_id=? AND id<>?",
              a.getName(),
              a.getCredentials());
          log(a.getName(), "OTHER_SESSIONS_REVOKED", null);
          return null;
        });
  }

  @GetMapping("/activity")
  public List<Map<String, Object>> activity(
      Authentication a, @RequestParam(defaultValue = "1") int page) {
    return db.list(
        "SELECT action,target_id,created_at FROM activity_log WHERE user_id=? ORDER BY created_at"
            + " DESC,id DESC LIMIT 50 OFFSET ?",
        a.getName(),
        (Math.max(1L, page) - 1) * 50);
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
}
