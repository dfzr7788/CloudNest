package com.cloudnest.sync;

import static com.cloudnest.common.Db.*;

import com.cloudnest.common.*;
import com.cloudnest.security.RateLimits;
import jakarta.annotation.PostConstruct;
import jakarta.servlet.http.HttpServletRequest;
import java.util.*;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.Authentication;
import org.springframework.stereotype.Service;

@Service
public class SyncDevices {
  public record Identity(String userId, String deviceId) {}

  private final Db db;
  private final RateLimits limits;
  private final boolean enabled;

  public SyncDevices(
      Db db, RateLimits limits, @Value("${cloudnest.sync.enabled:false}") boolean enabled) {
    this.db = db;
    this.limits = limits;
    this.enabled = enabled;
  }

  @PostConstruct
  void schema() {
    if (enabled) db.count("SELECT COUNT(*) FROM sync_device");
  }

  public boolean enabled() {
    return enabled;
  }

  public void require() {
    if (!enabled) throw new Problem(409, "SYNC_DISABLED", "Sync development profile is disabled");
  }

  public Map<String, Object> issue(String uid, String name) {
    require();
    if (name == null
        || name.isBlank()
        || name.length() > 80
        || name.chars().anyMatch(Character::isISOControl))
      throw Problem.invalid("Device name must contain 1-80 characters");
    return db.atomic(
        uid,
        () -> {
          db.update(
              "DELETE FROM sync_device WHERE user_id=? AND (revoked=TRUE OR expires_at<?) AND"
                  + " created_at<?",
              uid,
              System.currentTimeMillis(),
              System.currentTimeMillis() - 90L * 86400000);
          if (db.count("SELECT COUNT(*) FROM sync_device WHERE user_id=?", uid) >= 100)
            throw new Problem(429, "SYNC_DEVICE_LIMIT", "Device history limit reached");
          if (db.count(
                  "SELECT COUNT(*) FROM sync_device WHERE user_id=? AND revoked=FALSE AND"
                      + " expires_at>?",
                  uid,
                  System.currentTimeMillis())
              >= 20)
            throw new Problem(
                429, "SYNC_DEVICE_LIMIT", "Revoke an old device before registering another");
          String id = Util.id(), token = "sync_" + Util.token();
          long now = System.currentTimeMillis(), expires = now + 90L * 86400000;
          db.update(
              "INSERT INTO sync_device(id,user_id,name,token_hash,created_at,expires_at)"
                  + " VALUES(?,?,?,?,?,?)",
              id,
              uid,
              name.trim(),
              Util.hash(token),
              now,
              expires);
          return Map.of("id", id, "token", token, "expiresAt", expires);
        });
  }

  public List<Map<String, Object>> list(String uid) {
    require();
    return db.list(
        "SELECT id,name,created_at,expires_at,last_seen_at,revoked FROM sync_device WHERE user_id=?"
            + " ORDER BY created_at DESC LIMIT 100",
        uid);
  }

  public void revoke(String uid, String id) {
    require();
    db.atomic(
        uid,
        () -> {
          db.one("SELECT id FROM sync_device WHERE id=? AND user_id=?", id, uid);
          db.update("UPDATE sync_device SET revoked=TRUE WHERE id=?", id);
          return null;
        });
  }

  public void check(Identity identity) {
    require();
    if (db.count(
            "SELECT COUNT(*) FROM sync_device d JOIN sys_user u ON u.id=d.user_id WHERE d.id=? AND"
                + " d.user_id=? AND d.revoked=FALSE AND d.expires_at>? AND u.enabled=TRUE",
            identity.deviceId(),
            identity.userId(),
            System.currentTimeMillis())
        != 1)
      throw new Problem(401, "SYNC_DEVICE_REVOKED", "Device expired, revoked or account disabled");
  }

  public Identity identity(Authentication auth) {
    require();
    if (!(auth.getCredentials() instanceof Identity id))
      throw new Problem(403, "SYNC_DEVICE_REQUIRED", "Use a scoped SyncDevice credential");
    check(id);
    return id;
  }

  public Authentication authenticate(HttpServletRequest request) {
    require();
    String path = request.getRequestURI();
    if (!(path.startsWith("/api/sync/client/")
        || (path.equals("/api/auth/csrf") && request.getMethod().equals("GET"))))
      throw new Problem(
          403, "SYNC_SCOPE_DENIED", "Device credential is limited to sync client APIs");
    String token = request.getHeader("Authorization").substring("SyncDevice ".length());
    if (!token.matches("sync_[A-Za-z0-9_-]{32,100}"))
      throw new Problem(401, "SYNC_DEVICE_REVOKED", "Invalid credential");
    var found = db.list("SELECT id,user_id FROM sync_device WHERE token_hash=?", Util.hash(token));
    if (found.isEmpty()) {
      limits.check("sync-invalid:" + request.getRemoteAddr(), 20);
      throw new Problem(401, "SYNC_DEVICE_REVOKED", "Invalid credential");
    }
    var row = found.getFirst();
    var id = new Identity(s(row, "user_id"), s(row, "id"));
    check(id);
    limits.check("sync:" + id.deviceId(), 1000);
    long now = System.currentTimeMillis();
    db.update(
        "UPDATE sync_device SET last_seen_at=? WHERE id=? AND last_seen_at<?",
        now,
        id.deviceId(),
        now - 60000);
    return new UsernamePasswordAuthenticationToken(id.userId(), id, List.of());
  }
}
