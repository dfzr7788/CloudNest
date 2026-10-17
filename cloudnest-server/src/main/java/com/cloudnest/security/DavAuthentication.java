package com.cloudnest.security;

import static com.cloudnest.common.Db.*;

import com.cloudnest.common.*;
import jakarta.servlet.http.HttpServletRequest;
import java.nio.charset.StandardCharsets;
import java.util.*;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.stereotype.Component;

@Component
public class DavAuthentication {
  private final Db db;
  private final RateLimits limits;

  public DavAuthentication(Db db, RateLimits limits) {
    this.db = db;
    this.limits = limits;
  }

  public Authentication authenticate(HttpServletRequest req) {
    String header = req.getHeader("Authorization");
    if (header == null || !header.startsWith("Basic ")) return null;
    try {
      String[] parts =
          new String(Base64.getDecoder().decode(header.substring(6)), StandardCharsets.UTF_8)
              .split(":", 2);
      if (parts.length != 2 || parts[1].length() > 100 || !parts[1].startsWith("dav_")) return null;
      var rows =
          db.list(
              "SELECT c.*,u.enabled FROM dav_credential c JOIN sys_user u ON u.id=c.user_id WHERE"
                  + " u.username=? AND c.token_hash=? AND c.expires_at>? AND u.enabled=TRUE",
              parts[0].toLowerCase(Locale.ROOT),
              Util.hash(parts[1]),
              System.currentTimeMillis());
      if (rows.isEmpty()) {
        limits.check("dav-invalid:" + req.getRemoteAddr(), 20);
        return null;
      }
      var c = rows.getFirst();
      limits.check("dav:" + c.get("id"), 1000);
      return new UsernamePasswordAuthenticationToken(
          s(c, "user_id"),
          s(c, "id"),
          List.of(new SimpleGrantedAuthority(b(c, "read_only") ? "DAV_READ" : "DAV_WRITE")));
    } catch (IllegalArgumentException e) {
      return null;
    }
  }
}
