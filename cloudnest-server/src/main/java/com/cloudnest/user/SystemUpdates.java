package com.cloudnest.user;

import com.cloudnest.common.*;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.io.IOException;
import java.nio.file.*;
import java.util.*;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.*;

/**
 * Sends fixed actions to an explicitly installed host service; no shell or Docker socket access.
 */
@RestController
@RequestMapping("/api/admin/updates")
public class SystemUpdates {
  private final Db db;
  private final ObjectMapper json;
  private final Path directory;

  public SystemUpdates(Db db, ObjectMapper json, @Value("${cloudnest.updater-dir:}") String dir) {
    this.db = db;
    this.json = json;
    directory = dir.isBlank() ? null : Path.of(dir).toAbsolutePath();
  }

  private void admin(Authentication auth) {
    if (db.count(
            "SELECT COUNT(*) FROM sys_user WHERE id=? AND administrator=TRUE AND enabled=TRUE",
            auth.getName())
        != 1) throw new Problem(403, "ADMIN_REQUIRED", "需要管理员权限");
  }

  private boolean available() {
    try {
      return directory != null
          && Files.getLastModifiedTime(directory.resolve("state/heartbeat")).toMillis()
              > System.currentTimeMillis() - 30000;
    } catch (IOException e) {
      return false;
    }
  }

  @GetMapping
  public Map<String, Object> status(Authentication auth) throws IOException {
    admin(auth);
    Map<String, Object> result = new LinkedHashMap<>();
    if (directory != null && Files.isRegularFile(directory.resolve("state/status.json")))
      result.putAll(
          json.readValue(
              directory.resolve("state/status.json").toFile(),
              new TypeReference<Map<String, Object>>() {}));
    result.put("enabled", available());
    if (directory != null && Files.isRegularFile(directory.resolve("state/backups.json")))
      result.put(
          "backups", json.readValue(directory.resolve("state/backups.json").toFile(), List.class));
    result.put(
        "pending", directory != null && Files.exists(directory.resolve("requests/request.json")));
    return result;
  }

  public record Action(String action) {}

  @PostMapping
  public synchronized Map<String, Object> request(Authentication auth, @RequestBody Action input)
      throws IOException {
    admin(auth);
    if (!Set.of("check", "update", "backup").contains(Objects.toString(input.action(), "")))
      throw Problem.invalid("无效的更新操作");
    if (!available()) throw new Problem(503, "UPDATER_UNAVAILABLE", "服务器尚未安装更新服务，或更新服务未运行");
    Map<String, Object> current = status(auth);
    if (Boolean.TRUE.equals(current.get("busy"))
        || Files.exists(directory.resolve("state/active.json"))) throw Problem.conflict("更新任务正在执行");
    Path request = directory.resolve("requests/request.json");
    // Write complete content before publishing the request; never expose a partially written JSON.
    Path temp = Files.createTempFile(directory.resolve("requests"), "request-", ".tmp");
    try {
      json.writeValue(temp.toFile(), Map.of("action", input.action()));
      try {
        Files.createLink(request, temp);
      } catch (FileAlreadyExistsException e) {
        throw Problem.conflict("已有待处理的更新任务");
      }
    } finally {
      Files.deleteIfExists(temp);
    }
    try {
      db.update(
          "INSERT INTO activity_log(id,user_id,action,created_at) VALUES(?,?,?,?)",
          Util.id(),
          auth.getName(),
          input.action().equals("update")
              ? "SYSTEM_UPDATE_REQUESTED"
              : input.action().equals("backup")
                  ? "SYSTEM_BACKUP_REQUESTED"
                  : "SYSTEM_UPDATE_CHECKED",
          System.currentTimeMillis());
    } catch (RuntimeException ignored) {
      // The request is already durable; do not report failure and invite a duplicate submission.
    }
    return Map.of("accepted", true);
  }
}
