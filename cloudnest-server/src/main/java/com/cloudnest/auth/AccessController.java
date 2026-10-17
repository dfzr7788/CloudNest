package com.cloudnest.auth;

import static com.cloudnest.common.Db.*;

import com.cloudnest.common.*;
import com.cloudnest.security.SecretCipher;
import jakarta.validation.Valid;
import jakarta.validation.constraints.*;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.util.*;
import org.springframework.security.core.Authentication;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/api/user/access")
public class AccessController {
  @org.springframework.beans.factory.annotation.Autowired
  private com.cloudnest.security.RateLimits limits;

  private final Db db;
  private final PasswordEncoder passwords;
  private final MfaService mfa;
  private final SecretCipher cipher;

  public AccessController(Db db, PasswordEncoder passwords, MfaService mfa, SecretCipher cipher) {
    this.db = db;
    this.passwords = passwords;
    this.mfa = mfa;
    this.cipher = cipher;
  }

  public record Proof(@NotBlank String password, String otp) {}

  private void proof(String uid, Proof in) {
    limits.check("access-proof:" + uid, 10);
    if (!passwords.matches(
        in.password(),
        s(db.one("SELECT password_hash FROM sys_user WHERE id=?", uid), "password_hash")))
      throw new Problem(401, "INVALID_PASSWORD", "密码错误");
  }

  @GetMapping
  public Map<String, Object> status(Authentication a) {
    var u =
        db.one("SELECT username,email_address,totp_secret FROM sys_user WHERE id=?", a.getName());
    return Map.of(
        "username",
        u.get("username"),
        "email",
        u.get("email_address") == null ? "" : u.get("email_address"),
        "mfa",
        u.get("totp_secret") != null,
        "credentials",
        db.list(
            "SELECT id,label,read_only,expires_at,created_at FROM dav_credential WHERE user_id=?"
                + " ORDER BY created_at DESC",
            a.getName()));
  }

  @PostMapping("/mfa/setup")
  public Map<String, Object> setup(Authentication a, @Valid @RequestBody Proof in) {
    proof(a.getName(), in);
    return db.atomic(
        a.getName(),
        () -> {
          if (db.one("SELECT totp_secret FROM sys_user WHERE id=?", a.getName()).get("totp_secret")
              != null) throw Problem.conflict("已启用双重验证");
          String secret = mfa.newSecret(), base32 = mfa.base32(secret);
          db.update(
              "UPDATE sys_user SET totp_pending=?,totp_pending_until=? WHERE id=?",
              cipher.encrypt(secret),
              System.currentTimeMillis() + 600000,
              a.getName());
          String username =
              s(db.one("SELECT username FROM sys_user WHERE id=?", a.getName()), "username");
          return Map.of(
              "secret",
              base32,
              "uri",
              "otpauth://totp/CloudNest:"
                  + URLEncoder.encode(username, StandardCharsets.UTF_8)
                  + "?secret="
                  + base32
                  + "&issuer=CloudNest&algorithm=SHA1&digits=6&period=30");
        });
  }

  public record Code(@NotBlank String code) {}

  @PostMapping("/mfa/confirm")
  public Map<String, Object> confirm(Authentication a, @Valid @RequestBody Code in) {
    limits.check("mfa-confirm:" + a.getName(), 5);
    return db.atomic(
        a.getName(),
        () -> {
          var u = db.one("SELECT * FROM sys_user WHERE id=?", a.getName());
          if (u.get("totp_secret") != null
              || u.get("totp_pending") == null
              || n(u, "totp_pending_until") < System.currentTimeMillis())
            throw Problem.conflict("请重新开始设置");
          long step = mfa.step(cipher.decrypt(s(u, "totp_pending")), in.code(), -1);
          if (step < 0) throw Problem.invalid("验证代码无效");
          db.update(
              "UPDATE sys_user SET totp_secret=totp_pending,totp_pending=NULL,totp_step=? WHERE"
                  + " id=?",
              step,
              a.getName());
          db.update("DELETE FROM mfa_recovery WHERE user_id=?", a.getName());
          List<String> codes = new ArrayList<>();
          for (int n = 0; n < 10; n++) {
            String code = "CNREC_" + Util.token().substring(0, 20);
            codes.add(code);
            db.update(
                "INSERT INTO mfa_recovery(user_id,token_hash) VALUES(?,?)",
                a.getName(),
                Util.hash(code));
          }
          db.update(
              "DELETE FROM auth_session WHERE user_id=? AND id<>?",
              a.getName(),
              a.getCredentials());
          return Map.of("recoveryCodes", codes);
        });
  }

  @DeleteMapping("/mfa")
  public void disable(Authentication a, @Valid @RequestBody Proof in) {
    proof(a.getName(), in);
    mfa.verify(a.getName(), in.otp());
    db.atomic(
        a.getName(),
        () -> {
          db.update(
              "UPDATE sys_user SET totp_secret=NULL,totp_pending=NULL,totp_step=-1 WHERE id=?",
              a.getName());
          db.update("DELETE FROM mfa_recovery WHERE user_id=?", a.getName());
          db.update(
              "DELETE FROM auth_session WHERE user_id=? AND id<>?",
              a.getName(),
              a.getCredentials());
          return null;
        });
  }

  public record Credential(
      @NotBlank @Size(max = 80) String label,
      boolean readOnly,
      @Min(1) @Max(365) int days,
      @NotBlank String password,
      String otp) {}

  @PostMapping("/dav")
  public Map<String, Object> credential(Authentication a, @Valid @RequestBody Credential in) {
    proof(a.getName(), new Proof(in.password(), in.otp()));
    mfa.verify(a.getName(), in.otp());
    return db.atomic(
        a.getName(),
        () -> {
          if (db.count("SELECT COUNT(*) FROM dav_credential WHERE user_id=?", a.getName()) >= 20)
            throw Problem.conflict("最多保留 20 个应用密码");
          String id = Util.id(), secret = "dav_" + Util.token();
          long now = System.currentTimeMillis(), expires = now + in.days() * 86400000L;
          db.update(
              "INSERT INTO"
                  + " dav_credential(id,user_id,label,token_hash,read_only,expires_at,created_at)"
                  + " VALUES(?,?,?,?,?,?,?)",
              id,
              a.getName(),
              in.label(),
              Util.hash(secret),
              in.readOnly(),
              expires,
              now);
          return Map.of("id", id, "password", secret, "expiresAt", expires);
        });
  }

  @DeleteMapping("/dav/{id}")
  public void revoke(Authentication a, @PathVariable String id) {
    db.update("DELETE FROM dav_credential WHERE id=? AND user_id=?", id, a.getName());
  }
}
