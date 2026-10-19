package com.cloudnest.storage;

import static com.cloudnest.common.Db.*;

import com.cloudnest.common.*;
import com.cloudnest.security.SecretCipher;
import com.cloudnest.user.RuntimeSettings;
import jakarta.annotation.*;
import jakarta.validation.Valid;
import jakarta.validation.constraints.*;
import java.io.*;
import java.nio.file.*;
import java.util.*;
import java.util.concurrent.*;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/api/admin/storage")
public class StorageController {
  @Value("${cloudnest.storage:local}")
  private String environmentBackend;

  @org.springframework.beans.factory.annotation.Autowired
  private com.cloudnest.node.NodeService nodes;

  private final Db db;
  private final RuntimeSettings settings;
  private final SecretCipher cipher;
  private final StorageService storage;
  private final Path data;
  private final ExecutorService worker =
      Executors.newSingleThreadExecutor(
          Thread.ofPlatform().daemon().name("storage-migrate-", 0).factory());

  public StorageController(
      Db db,
      RuntimeSettings settings,
      SecretCipher cipher,
      StorageService storage,
      @Value("${cloudnest.data-dir}") String dir) {
    this.db = db;
    this.settings = settings;
    this.cipher = cipher;
    this.storage = storage;
    data = Path.of(dir).toAbsolutePath();
  }

  @PostConstruct
  void recover() {
    db.update(
        "UPDATE maintenance_job SET status='INTERRUPTED',error='服务重启，请重新发起；已迁移对象会自动跳过' WHERE status"
            + " IN ('QUEUED','RUNNING')");
    db.update("DELETE FROM storage_reservation WHERE id LIKE 'migration:%'");
  }

  @PreDestroy
  void stop() {
    worker.shutdownNow();
  }

  @GetMapping
  public Map<String, Object> list(Authentication a) throws IOException {
    settings.admin(a.getName());
    var fs = Files.getFileStore(data);
    long free = fs.getUsableSpace(), total = fs.getTotalSpace();
    int warning = Integer.parseInt(settings.get("storage").getOrDefault("warningPercent", "80"));
    return Map.of(
        "policies",
        db.list(
            "SELECT id,name,endpoint,bucket,region,enabled FROM storage_policy ORDER BY name,id"),
        "defaultBackend",
        settings.get("storage").getOrDefault("defaultBackend", environmentBackend),
        "freeBytes",
        free,
        "totalBytes",
        total,
        "warning",
        total > 0 && (total - free) * 100.0 / total >= warning,
        "usage",
        db.list(
            "SELECT backend,COUNT(*) AS objects,COALESCE(SUM(size),0) AS bytes FROM file_blob WHERE"
                + " status='READY' GROUP BY backend"),
        "jobs",
        db.list(
            "SELECT * FROM maintenance_job WHERE kind='MIGRATE' ORDER BY created_at DESC LIMIT"
                + " 20"));
  }

  public record Policy(
      String id,
      @NotBlank @Size(max = 80) String name,
      @NotBlank String endpoint,
      @NotBlank @Size(max = 100) String bucket,
      String accessKey,
      String secretKey,
      @Size(max = 80) String region) {}

  @PostMapping("/policies")
  public Map<String, Object> policy(Authentication a, @Valid @RequestBody Policy in) {
    settings.admin(a.getName());
    RuntimeSettings.httpUrl(in.endpoint());
    if (in.endpoint().length() > 500 || !in.bucket().matches("[a-z0-9][a-z0-9.-]{1,98}[a-z0-9]"))
      throw Problem.invalid("Endpoint 或桶名称无效");
    String id = in.id() == null ? Util.id().replace("-", "").substring(0, 12) : in.id();
    if (!id.matches("[a-f0-9]{12}")) throw Problem.invalid("策略编号无效");
    return nodes.locked(
        () -> {
          var old = db.list("SELECT * FROM storage_policy WHERE id=?", id);
          if (old.isEmpty() && db.count("SELECT COUNT(*) FROM storage_policy") >= 16)
            throw Problem.conflict("最多配置 16 个存储策略");
          String access = in.accessKey(), secret = in.secretKey();
          if (!old.isEmpty()) {
            var existing = old.getFirst();
            if ((!in.endpoint().equals(s(existing, "endpoint"))
                    || !in.bucket().equals(s(existing, "bucket")))
                && nodes.referenced("s3:" + id))
              throw Problem.conflict("策略已有对象，请新建策略并迁移，不能直接更改桶位置");
            if (access == null || access.isBlank())
              access = cipher.decrypt(s(existing, "access_key"));
            if (secret == null || secret.isBlank())
              secret = cipher.decrypt(s(existing, "secret_key"));
          }
          if (access == null
              || access.isBlank()
              || secret == null
              || secret.isBlank()
              || access.length() > 500
              || secret.length() > 1000) throw Problem.invalid("需要有效的存储凭据");
          db.update("DELETE FROM storage_policy WHERE id=?", id);
          db.update(
              "INSERT INTO"
                  + " storage_policy(id,name,endpoint,bucket,access_key,secret_key,region,enabled)"
                  + " VALUES(?,?,?,?,?,?,?,TRUE)",
              id,
              in.name(),
              in.endpoint().replaceAll("/+$", ""),
              in.bucket(),
              cipher.encrypt(access),
              cipher.encrypt(secret),
              in.region());
          nodes.ensure("s3:" + id, in.name(), true);
          db.update(
              "UPDATE storage_node_health SET"
                  + " state='UNKNOWN',validated=FALSE,successes=0,failures=0,config_version=config_version+1"
                  + " WHERE backend=?",
              "s3:" + id);
          return Map.of("id", id);
        });
  }

  @DeleteMapping("/policies/{id}")
  public void remove(Authentication a, @PathVariable String id) {
    settings.admin(a.getName());
    String backend = "s3:" + id;
    nodes.locked(
        () -> {
          if (nodes.referenced(backend)) throw Problem.conflict("策略仍在使用，不能删除");
          db.update("DELETE FROM storage_node_event WHERE backend=?", backend);
          db.update("DELETE FROM storage_node_health WHERE backend=?", backend);
          db.update("DELETE FROM storage_node WHERE backend=?", backend);
          db.update("DELETE FROM storage_policy WHERE id=?", id);
          return null;
        });
  }

  public record Backend(@NotBlank String backend) {}

  private void validate(String backend) {
    if (!Set.of("local", "minio").contains(backend)
        && (!backend.matches("s3:[a-f0-9]{12}")
            || db.count("SELECT COUNT(*) FROM storage_policy WHERE id=?", backend.substring(3))
                != 1)) throw Problem.invalid("存储策略不存在");
  }

  @PostMapping("/test")
  public Map<String, Object> test(Authentication a, @Valid @RequestBody Backend in)
      throws IOException {
    settings.admin(a.getName());
    validate(in.backend());
    return nodes.check(in.backend(), true);
  }

  public record Migration(@NotBlank String source, @NotBlank String target) {}

  @PostMapping("/migrations")
  public synchronized Map<String, Object> migrate(
      Authentication a, @Valid @RequestBody Migration in) {
    settings.admin(a.getName());
    validate(in.source());
    validate(in.target());
    if (in.source().equals(in.target())) throw Problem.invalid("来源与目标不能相同");
    String id = Util.id();
    var candidates =
        nodes.locked(
            () -> {
              if (db.count(
                      "SELECT COUNT(*) FROM storage_job_item WHERE status IN ('QUEUED','RUNNING')")
                  > 0) throw Problem.conflict("已有文件级任务正在进行");
              if (db.count(
                      "SELECT COUNT(*) FROM maintenance_job WHERE status IN ('QUEUED','RUNNING')")
                  > 0) throw Problem.conflict("已有维护任务正在进行");
              long bytes =
                  db.count(
                      "SELECT COALESCE(SUM(size),0) FROM file_blob WHERE backend=? AND"
                          + " status='READY'",
                      in.source());
              nodes.admit(in.target(), true, bytes);
              nodes.reserve("migration:" + id, in.target(), bytes, 0);
              long now = System.currentTimeMillis();
              db.update(
                  "INSERT INTO"
                      + " maintenance_job(id,kind,status,source_backend,target_backend,total,created_at,updated_at)"
                      + " VALUES(?,'MIGRATE','QUEUED',?,?,?,?,?)",
                  id,
                  in.source(),
                  in.target(),
                  db.count(
                      "SELECT COUNT(*) FROM file_blob WHERE backend=? AND status='READY'",
                      in.source()),
                  now,
                  now);
              return db.list(
                  "SELECT id FROM file_blob WHERE backend=? AND status='READY' ORDER BY id",
                  in.source());
            });
    worker.submit(() -> run(id, in.source(), in.target(), candidates));
    return Map.of("id", id);
  }

  @DeleteMapping("/migrations/{id}")
  public void cancel(Authentication a, @PathVariable String id) {
    settings.admin(a.getName());
    db.update(
        "UPDATE maintenance_job SET status='CANCELLED',updated_at=? WHERE id=? AND kind='MIGRATE'",
        System.currentTimeMillis(),
        id);
  }

  private void run(String id, String source, String target, List<Map<String, Object>> candidates) {
    try {
      if (db.update(
              "UPDATE maintenance_job SET status='RUNNING' WHERE id=? AND status='QUEUED'", id)
          == 0) return;
      for (var candidate : candidates) {
        if (Thread.currentThread().isInterrupted()
            || !s(db.one("SELECT status FROM maintenance_job WHERE id=?", id), "status")
                .equals("RUNNING")) return;
        var batch =
            db.list(
                "SELECT * FROM file_blob WHERE backend=? AND status='READY' AND id=?",
                source,
                candidate.get("id"));
        if (batch.isEmpty()) continue;
        var blob = batch.getFirst();
        String key = s(blob, "object_key");
        nodes.locked(
            () -> {
              nodes.copied(target, key, n(blob, "size"));
              db.update(
                  "UPDATE storage_reservation SET bytes=GREATEST(0,bytes-?) WHERE id=?",
                  n(blob, "size"),
                  "migration:" + id);
              return null;
            });
        try (var input = storage.open(source, key, 0, n(blob, "size"))) {
          storage.put(target, key, input, n(blob, "size"));
        }
        try (var input = storage.open(target, key, 0, n(blob, "size"))) {
          if (!Util.hash(input).equals(s(blob, "sha256"))) throw new IOException("迁移内容校验失败");
        }
        boolean moved =
            db.atomic(
                s(blob, "user_id"),
                () ->
                    nodes.locked(
                        () -> {
                          if (!"RUNNING"
                              .equals(
                                  s(
                                      db.one("SELECT status FROM maintenance_job WHERE id=?", id),
                                      "status"))) return false;
                          nodes.copied(source, key, n(blob, "size"));
                          return db.update(
                                  "UPDATE file_blob SET backend=? WHERE id=? AND backend=? AND"
                                      + " status='READY'",
                                  target,
                                  blob.get("id"),
                                  source)
                              == 1;
                        }));
        if (moved)
          db.update(
              "UPDATE maintenance_job SET completed=completed+1,updated_at=? WHERE id=?",
              System.currentTimeMillis(),
              id);
      }
      db.update(
          "UPDATE maintenance_job SET status='COMPLETE',updated_at=? WHERE id=? AND"
              + " status='RUNNING'",
          System.currentTimeMillis(),
          id);
    } catch (Exception e) {
      db.update(
          "UPDATE maintenance_job SET"
              + " status='FAILED',error='迁移失败，请检查存储连接；来源副本保留，可修复后重试',updated_at=? WHERE id=? AND"
              + " status<>'CANCELLED'",
          System.currentTimeMillis(),
          id);
    } finally {
      nodes.locked(
          () -> {
            nodes.release("migration:" + id);
            return null;
          });
    }
  }
}
