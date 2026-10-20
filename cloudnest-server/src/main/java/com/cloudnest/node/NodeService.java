package com.cloudnest.node;

import static com.cloudnest.common.Db.*;

import com.cloudnest.common.*;
import com.cloudnest.storage.StorageService;
import com.cloudnest.user.RuntimeSettings;
import jakarta.annotation.PostConstruct;
import java.io.*;
import java.nio.file.*;
import java.util.*;
import java.util.function.Supplier;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;

/** One fixed destination. All admission/configuration changes share a database lock. */
@Service
public class NodeService {
  @org.springframework.beans.factory.annotation.Autowired
  private com.cloudnest.WorkerLifecycle lifecycle;

  private final Db db;
  private final StorageService storage;
  private final RuntimeSettings settings;
  private final Path data;
  private final String environment;
  private final long diskReserve;

  public NodeService(
      Db db,
      StorageService storage,
      RuntimeSettings settings,
      @Value("${cloudnest.data-dir}") String dir,
      @Value("${cloudnest.storage:local}") String environment,
      @Value("${cloudnest.node.disk-reserve-bytes:268435456}") long diskReserve) {
    this.db = db;
    this.storage = storage;
    this.settings = settings;
    this.data = Path.of(dir).toAbsolutePath();
    this.environment = environment;
    this.diskReserve = diskReserve;
    if (diskReserve < 0) throw new IllegalArgumentException("Negative disk reserve");
  }

  public <T> T locked(Supplier<T> work) {
    return db.transaction(
        () -> {
          db.one("SELECT id FROM storage_guard WHERE id=1 FOR UPDATE");
          return work.get();
        });
  }

  @PostConstruct
  void initialize() {
    // Old nodes are never silently mapped to local. Unknown references are reported by list().
    locked(
        () -> {
          for (var p : db.list("SELECT id,name,enabled FROM storage_policy"))
            ensure("s3:" + s(p, "id"), s(p, "name"), b(p, "enabled"));
          return null;
        });
    recover();
    check("local", true);
  }

  void recover() {
    if (!lifecycle.mayRecover()) return;
    db.update("DELETE FROM storage_reservation WHERE id LIKE 'ingest:%'");
  }

  public void ensure(String backend, String name, boolean enabled) {
    if (db.count("SELECT COUNT(*) FROM storage_node WHERE backend=?", backend) == 0) {
      db.update(
          "INSERT INTO storage_node(backend,name,enabled) VALUES(?,?,?)", backend, name, enabled);
      db.update("INSERT INTO storage_node_health(backend) VALUES(?)", backend);
    }
  }

  public String defaultBackend() {
    return settings.get("storage").getOrDefault("defaultBackend", environment);
  }

  public Map<String, Object> node(String backend) {
    return db.one(
        "SELECT"
            + " n.*,h.state,h.config_version,h.validated,h.failures,h.successes,h.checked_at,h.latency_ms"
            + " FROM storage_node n JOIN storage_node_health h ON n.backend=h.backend WHERE"
            + " n.backend=?",
        backend);
  }

  public long used(String backend) {
    return Math.addExact(
        db.count("SELECT COALESCE(SUM(size),0) FROM file_blob WHERE backend=?", backend),
        db.count(
            "SELECT COALESCE(SUM(c.size),0) FROM storage_copy c WHERE c.backend=? AND NOT"
                + " EXISTS(SELECT 1 FROM file_blob b WHERE b.backend=c.backend AND"
                + " b.object_key=c.object_key)",
            backend));
  }

  public long reserved(String backend) {
    return db.count(
        "SELECT COALESCE(SUM(bytes),0) FROM storage_reservation WHERE backend=?", backend);
  }

  public void admit(String backend, boolean migration, long bytes) {
    if (backend.startsWith("peer:") && db.count(
        "SELECT COUNT(*) FROM storage_contribution c JOIN team_member m ON m.team_id=c.team_id AND m.user_id=c.contributor_id JOIN sys_user u ON u.id=m.user_id WHERE c.backend=? AND c.status='APPROVED' AND c.last_heartbeat>? AND m.contributor=TRUE AND u.enabled=TRUE",
        backend, System.currentTimeMillis() - 120000) != 1)
      throw new Problem(409, "CONTRIBUTION_UNAVAILABLE", "贡献节点离线、暂停或授权已撤销");
    var n = node(backend);
    if (!b(n, "enabled")
        || !"ACTIVE".equals(s(n, "management"))
        || !b(n, migration ? "allow_migration" : "allow_upload"))
      throw new Problem(409, "NODE_WRITE_PAUSED", "节点停用、暂停写入或维护中；已有任务继续排空");
    if (!b(n, "validated")
        || !"ONLINE".equals(s(n, "state"))
        || System.currentTimeMillis() - n(n, "checked_at") > 120000)
      throw new Problem(409, "NODE_UNAVAILABLE", "节点未验证、健康异常或检测过期，请联系管理员检测");
    if (backend.startsWith("s3:")
        && db.count(
                "SELECT COUNT(*) FROM storage_policy WHERE id=? AND enabled=TRUE",
                backend.substring(3))
            != 1) throw Problem.conflict("存储策略不可用");
    long budget = n(n, "budget_bytes");
    if (bytes < 0 || (budget > 0 && bytes > budget - used(backend) - reserved(backend)))
      throw new Problem(409, "NODE_CAPACITY", "节点管理员预算不足");
  }

  public void authorizeTarget(String uid, String backend) {
    if (backend.startsWith("peer:") && db.count(
        "SELECT COUNT(*) FROM storage_contribution c JOIN team_workspace w ON w.id=c.team_id WHERE c.backend=? AND w.storage_user=? AND c.status='APPROVED'", backend, uid) != 1)
      throw new Problem(403, "NODE_TEAM_MISMATCH", "成员存储只能分配给其授权团队");
  }

  public long tempReserved() {
    return db.count("SELECT COALESCE(SUM(temp_bytes),0) FROM storage_reservation");
  }

  public void reserve(String id, String backend, long bytes, long temp) {
    if (temp < 0 || bytes < 0) throw Problem.invalid("容量无效");
    try {
      if (temp > Files.getFileStore(data).getUsableSpace() - diskReserve - tempReserved())
        throw new Problem(507, "TEMP_CAPACITY", "A 临时空间预算不足，请等待任务完成");
    } catch (IOException e) {
      throw new Problem(507, "TEMP_CAPACITY", "无法检测 A 临时空间");
    }
    db.update(
        "INSERT INTO storage_reservation(id,backend,bytes,temp_bytes) VALUES(?,?,?,?)",
        id,
        backend,
        bytes,
        temp);
  }

  public void release(String id) {
    db.update("DELETE FROM storage_reservation WHERE id=?", id);
  }

  public void copied(String backend, String key, long bytes) {
    if (db.count("SELECT COUNT(*) FROM storage_copy WHERE backend=? AND object_key=?", backend, key)
        == 0)
      db.update(
          "INSERT INTO storage_copy(backend,object_key,size,created_at) VALUES(?,?,?,?)",
          backend,
          key,
          bytes,
          System.currentTimeMillis());
  }

  public boolean referenced(String backend) {
    return db.count("SELECT COUNT(*) FROM blob_replica WHERE backend=?", backend) > 0
        || db.count(
                "SELECT COUNT(*) FROM storage_job_item WHERE target_backend=? AND status IN"
                    + " ('QUEUED','RUNNING')",
                backend)
            > 0
        || defaultBackend().equals(backend)
        || db.count("SELECT COUNT(*) FROM file_blob WHERE backend=?", backend) > 0
        || db.count("SELECT COUNT(*) FROM upload_session WHERE backend=?", backend) > 0
        || db.count("SELECT COUNT(*) FROM storage_reservation WHERE backend=?", backend) > 0
        || db.count("SELECT COUNT(*) FROM storage_copy WHERE backend=?", backend) > 0
        || db.count(
                "SELECT COUNT(*) FROM maintenance_job WHERE source_backend=? OR target_backend=?",
                backend,
                backend)
            > 0;
  }

  public Map<String, Object> list() {
    var rows = db.list("SELECT backend FROM storage_node ORDER BY backend");
    List<Map<String, Object>> result = new ArrayList<>();
    for (var r : rows) {
      String backend = s(r, "backend");
      var n = new LinkedHashMap<>(node(backend));
      n.put("usedBytes", used(backend));
      n.put("reservedBytes", reserved(backend));
      n.put("stale", System.currentTimeMillis() - n(n, "checked_at") > 120000);
      n.put("referenced", referenced(backend));
      result.add(n);
    }
    return Map.of(
        "nodes",
        result,
        "defaultBackend",
        defaultBackend(),
        "unknownBackends",
        db.list(
            "SELECT DISTINCT backend FROM (SELECT backend FROM file_blob UNION SELECT backend FROM"
                + " upload_session) refs WHERE NOT EXISTS(SELECT 1 FROM storage_node n WHERE"
                + " n.backend=refs.backend)"));
  }

  public synchronized Map<String, Object> check(String backend, boolean full) {
    String key = "objects/" + Util.id();
    long version =
        locked(
            () -> {
              var node = node(backend);
              // A durable scratch reference protects an in-flight full probe from endpoint
              // replacement.
              if (full) copied(backend, key, 64);
              return n(node, "config_version");
            });
    long start = System.nanoTime();
    boolean ok = false;
    try {
      if (full) {
        byte[] bytes = Util.token().getBytes(java.nio.charset.StandardCharsets.UTF_8);
        try {
          storage.put(backend, key, new ByteArrayInputStream(bytes), bytes.length);
          try (var input = storage.open(backend, key, 0, bytes.length)) {
            if (!Arrays.equals(bytes, input.readNBytes(bytes.length + 1)))
              throw new IOException("Probe mismatch");
          }
        } finally {
          storage.delete(backend, key);
          locked(
              () -> {
                db.update(
                    "DELETE FROM storage_copy WHERE backend=? AND object_key=?", backend, key);
                return null;
              });
        }
      } else storage.probe(backend);
      ok = true;
    } catch (Exception ignored) {
      /* Never expose provider URLs, credentials or SDK errors. */
    }
    final boolean success = ok;
    return locked(
        () -> {
          if (db.count(
                  "SELECT COUNT(*) FROM storage_node_health WHERE backend=? AND config_version=?",
                  backend,
                  version)
              != 1) return Map.of("ok", false, "state", "UNKNOWN");
          var old = node(backend);
          int failures = success ? 0 : (int) n(old, "failures") + 1;
          int successes = success ? (int) n(old, "successes") + 1 : 0;
          boolean validated = b(old, "validated") || (full && success);
          String state =
              !success
                  ? (failures >= 3 ? "OFFLINE" : "DEGRADED")
                  : (validated && (full || successes >= 2 || "ONLINE".equals(s(old, "state")))
                      ? "ONLINE"
                      : "UNKNOWN");
          if (success
              && validated
              && Set.of("OFFLINE", "DEGRADED").contains(s(old, "state"))
              && successes < 2) state = "DEGRADED";
          long now = System.currentTimeMillis();
          db.update(
              "UPDATE storage_node_health SET"
                  + " state=?,validated=?,failures=?,successes=?,checked_at=?,latency_ms=? WHERE"
                  + " backend=?",
              state,
              validated,
              failures,
              successes,
              now,
              (System.nanoTime() - start) / 1000000,
              backend);
          if (!state.equals(s(old, "state")))
            db.update(
                "INSERT INTO storage_node_event(id,backend,state,created_at) VALUES(?,?,?,?)",
                Util.id(),
                backend,
                state,
                now);
          return Map.of("ok", success, "state", state);
        });
  }

  @Scheduled(fixedDelay = 30000, initialDelay = 30000)
  public void poll() {
    for (var n : db.list("SELECT backend FROM storage_node WHERE enabled=TRUE")) {
      try {
        String backend = s(n, "backend");
        check(backend, Set.of("OFFLINE", "DEGRADED").contains(s(node(backend), "state")));
      } catch (Problem removed) {
        if (removed.status != 404) throw removed;
      }
    }
    db.update(
        "DELETE FROM storage_placement_log WHERE created_at<?",
        System.currentTimeMillis() - 30L * 86400000);
    db.update(
        "DELETE FROM storage_node_event WHERE created_at<?",
        System.currentTimeMillis() - 30L * 86400000);
  }
}
