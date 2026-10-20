package com.cloudnest.file;

import static com.cloudnest.common.Db.*;

import com.cloudnest.common.*;
import com.cloudnest.node.NodeService;
import com.cloudnest.storage.StorageService;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import java.util.*;
import org.springframework.stereotype.Service;

@Service
public class DirectDownloadService {
  private final NodeService nodes;
  private final StorageService storage;

  public DirectDownloadService(NodeService nodes, StorageService storage) {
    this.nodes = nodes;
    this.storage = storage;
  }

  /** Call only after authorizing the entry and the actual (possibly pinned) blob. */
  public Map<String, Object> authorize(
      Map<String, Object> entry,
      Map<String, Object> blob,
      String proxy,
      long accessExpires,
      HttpServletResponse response) {
    response.setHeader("Cache-Control", "no-store, private");
    response.setHeader("Pragma", "no-cache");
    response.setHeader("Referrer-Policy", "no-referrer");
    long now = System.currentTimeMillis();
    int seconds = (int) Math.min(60, (accessExpires - now) / 1000);
    if (seconds < 1) throw new Problem(410, "DOWNLOAD_EXPIRED", "授权已过期，请重新获取");
    String backend = s(blob, "backend");
    // Unknown legacy backends retain proxy behavior and appear in the admin diagnostics.
    Map<String, Object> node;
    try {
      node = nodes.node(backend);
    } catch (Problem missing) {
      return Map.of("mode", "proxy", "url", proxy, "expiresAt", now + seconds * 1000L);
    }
    if (b(node, "direct_download")
        && !"local".equals(backend)
        && "ONLINE".equals(s(node, "state"))
        && b(node, "validated")
        && now - n(node, "checked_at") <= 120000
        && !(blob.get("desired_replicas") instanceof Number count && count.intValue() > 1)) {
      try {
        var signed =
            storage.presign(
                backend,
                s(blob, "object_key"),
                s(node, "public_endpoint"),
                s(entry, "name"),
                seconds);
        if (signed.isPresent())
          return Map.of("mode", "direct", "url", signed.get(), "expiresAt", now + seconds * 1000L,
              "fallbackUrl",proxy,"supportsRange",b(node,"supports_range"));
      } catch (IOException ignored) {
        /* Provider error details and signed URLs must never enter logs. */
      }
    }
    return Map.of("mode", "proxy", "url", proxy, "expiresAt", now + seconds * 1000L);
  }
}
