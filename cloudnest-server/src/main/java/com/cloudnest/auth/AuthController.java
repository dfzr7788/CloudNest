package com.cloudnest.auth;

import com.baomidou.mybatisplus.core.conditions.query.QueryWrapper;
import com.cloudnest.common.*;
import com.cloudnest.security.RateLimits;
import com.cloudnest.user.*;
import jakarta.servlet.http.*;
import jakarta.validation.Valid;
import jakarta.validation.constraints.*;
import java.time.*;
import java.util.*;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.*;
import org.springframework.security.core.Authentication;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.security.oauth2.jose.jws.MacAlgorithm;
import org.springframework.security.oauth2.jwt.*;
import org.springframework.security.web.csrf.CsrfToken;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/api")
public class AuthController {
  private final Db db;
  private final AccountMapper accounts;
  private final PasswordEncoder passwords;
  private final JwtEncoder jwt;
  private final RateLimits limits;
  private final long quota;
  private final int hours;
  private final boolean secure;

  @Value("${cloudnest.registration-open:true}")
  private boolean registrationOpen;

  public AuthController(
      Db db,
      AccountMapper accounts,
      PasswordEncoder passwords,
      JwtEncoder jwt,
      RateLimits limits,
      @Value("${cloudnest.quota}") long quota,
      @Value("${cloudnest.session-hours}") int hours,
      @Value("${cloudnest.secure-cookies}") boolean secure) {
    this.db = db;
    this.accounts = accounts;
    this.passwords = passwords;
    this.jwt = jwt;
    this.limits = limits;
    this.quota = quota;
    this.hours = hours;
    this.secure = secure;
  }

  public record Credentials(
      @NotBlank @Pattern(regexp = "[A-Za-z0-9_]{3,40}") String username,
      @NotNull @Size(min = 8, max = 64) String password,
      @Size(max = 100) String invitation) {}

  public record Profile(@NotBlank @Size(max = 80) String nickname) {}

  @GetMapping("/auth/csrf")
  public Map<String, String> csrf(CsrfToken token) {
    return Map.of("token", token.getToken(), "headerName", token.getHeaderName());
  }

  @PostMapping("/auth/register")
  public Map<String, Object> register(
      @Valid @RequestBody Credentials input, HttpServletRequest req, HttpServletResponse res) {
    if (!registrationOpen && (input.invitation() == null || input.invitation().isBlank()))
      throw new Problem(403, "REGISTRATION_CLOSED", "当前未开放注册，请使用管理员提供的邀请码");
    if (input.password.getBytes(java.nio.charset.StandardCharsets.UTF_8).length > 72)
      throw Problem.invalid("密码的 UTF-8 编码不能超过 72 字节");
    limits.check("register:" + req.getRemoteAddr(), 10);
    String username = input.username.toLowerCase(Locale.ROOT),
        uid = Util.id(),
        root = Util.id(),
        hash = passwords.encode(input.password);
    db.transaction(
        () -> {
          long now = System.currentTimeMillis();
          String invitationId = null;
          long effectiveQuota = quota;
          if (input.invitation() != null && !input.invitation().isBlank()) {
            var invite =
                db.one(
                    "SELECT * FROM invitation WHERE token_hash=? FOR UPDATE",
                    Util.hash(input.invitation()));
            if (invite.get("used_by") != null
                || com.cloudnest.common.Db.n(invite, "expires_at") <= now)
              throw Problem.conflict("邀请码已使用或已过期");
            invitationId = com.cloudnest.common.Db.s(invite, "id");
            effectiveQuota = com.cloudnest.common.Db.n(invite, "quota");
          }
          db.update(
              "INSERT INTO"
                  + " sys_user(id,username,password_hash,nickname,root_id,storage_quota,created_at)"
                  + " VALUES(?,?,?,?,?,?,?)",
              uid,
              username,
              hash,
              input.username,
              root,
              effectiveQuota,
              now);
          db.update(
              "INSERT INTO drive_entry(id,user_id,parent_id,kind,name,created_at,updated_at)"
                  + " VALUES(?,?,NULL,'FOLDER','我的云盘',?,?)",
              root,
              uid,
              now,
              now);
          if (invitationId != null)
            db.update("UPDATE invitation SET used_by=? WHERE id=?", uid, invitationId);
          return null;
        });
    issue(uid, req, res);
    return profile(uid);
  }

  @PostMapping("/auth/login")
  public Map<String, Object> login(
      @Valid @RequestBody Credentials input, HttpServletRequest req, HttpServletResponse res) {
    if (input.password.getBytes(java.nio.charset.StandardCharsets.UTF_8).length > 72)
      throw Problem.invalid("密码的 UTF-8 编码不能超过 72 字节");
    limits.check("login:" + req.getRemoteAddr(), 20);
    Account a =
        accounts.selectOne(
            new QueryWrapper<Account>().eq("username", input.username.toLowerCase(Locale.ROOT)));
    if (a == null
        || !passwords.matches(input.password, a.passwordHash)
        || db.count("SELECT COUNT(*) FROM sys_user WHERE id=? AND enabled=TRUE", a.id) != 1)
      throw new Problem(401, "INVALID_CREDENTIALS", "账号或密码错误");
    issue(a.id, req, res);
    return profile(a.id);
  }

  private void issue(String uid, HttpServletRequest req, HttpServletResponse res) {
    clearCsrf(res);
    String sid = Util.id();
    Instant now = Instant.now(), expires = now.plus(Duration.ofHours(hours));
    db.update(
        "INSERT INTO auth_session(id,user_id,expires_at,created_at,last_seen,client_label)"
            + " VALUES(?,?,?,?,?,?)",
        sid,
        uid,
        expires.toEpochMilli(),
        now.toEpochMilli(),
        now.toEpochMilli(),
        Optional.ofNullable(req.getHeader("User-Agent"))
            .orElse("未知客户端")
            .replaceAll("[\\p{Cntrl}]", "")
            .substring(
                0,
                Math.min(
                    180,
                    Optional.ofNullable(req.getHeader("User-Agent"))
                        .orElse("未知客户端")
                        .replaceAll("[\\p{Cntrl}]", "")
                        .length())));
    String token =
        jwt.encode(
                JwtEncoderParameters.from(
                    JwsHeader.with(MacAlgorithm.HS256).build(),
                    JwtClaimsSet.builder()
                        .subject(uid)
                        .issuedAt(now)
                        .expiresAt(expires)
                        .claim("sid", sid)
                        .build()))
            .getTokenValue();
    res.addHeader(
        HttpHeaders.SET_COOKIE,
        ResponseCookie.from("CN_AUTH", token)
            .httpOnly(true)
            .secure(secure)
            .sameSite("Lax")
            .path("/api")
            .maxAge(Duration.ofHours(hours))
            .build()
            .toString());
  }

  @PostMapping("/auth/logout")
  public void logout(Authentication auth, HttpServletResponse res) {
    clearCsrf(res);
    db.update(
        "DELETE FROM auth_session WHERE id=? AND user_id=?", auth.getCredentials(), auth.getName());
    res.addHeader(
        HttpHeaders.SET_COOKIE,
        ResponseCookie.from("CN_AUTH", "")
            .httpOnly(true)
            .secure(secure)
            .sameSite("Lax")
            .path("/api")
            .maxAge(0)
            .build()
            .toString());
  }

  @GetMapping("/user/me")
  public Map<String, Object> me(Authentication auth) {
    return profile(auth.getName());
  }

  @PatchMapping("/user/me")
  public Map<String, Object> edit(Authentication auth, @Valid @RequestBody Profile input) {
    db.update(
        "UPDATE sys_user SET nickname=? WHERE id=?", Util.name(input.nickname), auth.getName());
    return profile(auth.getName());
  }

  @GetMapping("/user/storage")
  public Map<String, Object> storage(Authentication auth) {
    var m = profile(auth.getName());
    return Map.of("quota", m.get("quota"), "used", m.get("used"), "reserved", m.get("reserved"));
  }

  private Map<String, Object> profile(String uid) {
    Account a = accounts.selectById(uid);
    if (a == null) throw Problem.missing();
    return Map.of(
        "id",
        a.id,
        "administrator",
        db.count("SELECT COUNT(*) FROM sys_user WHERE id=? AND administrator=TRUE", uid) == 1,
        "username",
        a.username,
        "nickname",
        a.nickname,
        "rootId",
        a.rootId,
        "quota",
        a.storageQuota,
        "used",
        a.storageUsed,
        "reserved",
        a.storageReserved);
  }

  private void clearCsrf(HttpServletResponse res) {
    res.addHeader(
        HttpHeaders.SET_COOKIE,
        ResponseCookie.from("XSRF-TOKEN", "")
            .path("/")
            .maxAge(0)
            .secure(secure)
            .sameSite("Lax")
            .build()
            .toString());
  }
}
