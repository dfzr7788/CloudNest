package com.cloudnest.node;

import static com.cloudnest.common.Db.*;

import com.cloudnest.common.*;
import com.cloudnest.user.RuntimeSettings;
import jakarta.validation.Valid;
import jakarta.validation.constraints.*;
import java.util.*;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/api/admin/nodes")
public class NodeController {
  private final NodeService nodes;
  private final Db db;
  private final RuntimeSettings settings;

  @org.springframework.beans.factory.annotation.Autowired
  private com.cloudnest.storage.StorageService storage;

  public NodeController(NodeService nodes, Db db, RuntimeSettings settings) {
    this.nodes = nodes;
    this.db = db;
    this.settings = settings;
  }

  @GetMapping
  public Map<String, Object> list(Authentication a) {
    settings.admin(a.getName());
    return nodes.list();
  }

  public record Update(
      @NotBlank @Size(max = 80) String name,
      boolean enabled,
      @NotBlank String management,
      boolean allowUpload,
      boolean allowMigration,
      @Min(0) @Max(9007199254740991L) long budgetBytes,
      @Size(max = 500) String publicEndpoint,
      boolean directDownload) {}

  @PutMapping("/{backend}")
  public void update(
      Authentication a, @PathVariable String backend, @Valid @RequestBody Update in) {
    settings.admin(a.getName());
    if (!Set.of("ACTIVE", "WRITE_PAUSED", "MAINTENANCE").contains(in.management()))
      throw Problem.invalid("管理状态无效");
    String endpoint = in.publicEndpoint() == null ? "" : in.publicEndpoint().replaceAll("/+$", "");
    if (!endpoint.isBlank()) {
      var uri = RuntimeSettings.httpUrl(endpoint);
      if (!uri.getPath().isEmpty()) throw Problem.invalid("公网 Endpoint 不能含路径");
    }
    if (in.directDownload() && ("local".equals(backend) || endpoint.isBlank()))
      throw Problem.invalid("直连需要远端公网 Endpoint");
    nodes.locked(
        () -> {
          nodes.node(backend);
          if (backend.startsWith("peer:")) {
            var contribution=db.one("SELECT capacity_bytes FROM storage_contribution WHERE backend=?",backend);
            if(in.budgetBytes()==0 || in.budgetBytes()>n(contribution,"capacity_bytes")) throw Problem.invalid("贡献节点预算不能超过成员授权上限或设为无限");
          }
          if (in.budgetBytes() > 0
              && in.budgetBytes() < nodes.used(backend) + nodes.reserved(backend))
            throw Problem.conflict("预算不能低于已用与预留");
          db.update(
              "UPDATE storage_node SET"
                  + " name=?,enabled=?,management=?,allow_upload=?,allow_migration=?,budget_bytes=?,public_endpoint=?,direct_download=?"
                  + " WHERE backend=?",
              in.name(),
              in.enabled(),
              in.management(),
              in.allowUpload(),
              in.allowMigration(),
              in.budgetBytes(),
              endpoint,
              in.directDownload(),
              backend);
          return null;
        });
  }

  @PostMapping("/{backend}/test")
  public Map<String, Object> test(Authentication a, @PathVariable String backend) {
    settings.admin(a.getName());
    return nodes.check(backend, true);
  }

  @GetMapping("/{backend}/events")
  public List<Map<String, Object>> events(Authentication a, @PathVariable String backend) {
    settings.admin(a.getName());
    return db.list(
        "SELECT state,created_at FROM storage_node_event WHERE backend=? ORDER BY created_at DESC"
            + " LIMIT 100",
        backend);
  }

  @DeleteMapping("/{backend}")
  public void remove(Authentication a, @PathVariable String backend) {
    settings.admin(a.getName());
    nodes.locked(
        () -> {
          if (Set.of("local", "minio").contains(backend) || nodes.referenced(backend))
            throw Problem.conflict("节点仍在使用或为内置节点，不能删除");
          db.update("DELETE FROM storage_node_health WHERE backend=?", backend);
          db.update("DELETE FROM storage_node_event WHERE backend=?", backend);
          db.update("DELETE FROM storage_node WHERE backend=?", backend);
          if (backend.startsWith("s3:"))
            db.update("DELETE FROM storage_policy WHERE id=?", backend.substring(3));
          return null;
        });
  }

  @GetMapping("/{backend}/copies")
  public List<Map<String, Object>> copies(Authentication a, @PathVariable String backend) {
    settings.admin(a.getName());
    return db.list(
        "SELECT c.* FROM storage_copy c WHERE backend=? AND NOT EXISTS(SELECT 1 FROM blob_replica r"
            + " WHERE r.backend=c.backend AND r.object_key=c.object_key) AND NOT EXISTS(SELECT 1"
            + " FROM file_blob b WHERE b.backend=c.backend AND b.object_key=c.object_key) ORDER BY"
            + " created_at",
        backend);
  }

  @DeleteMapping("/{backend}/copies")
  public void cleanCopy(Authentication a, @PathVariable String backend, @RequestParam String key) {
    settings.admin(a.getName());
    nodes.locked(
        () -> {
          var copy =
              db.one("SELECT * FROM storage_copy WHERE backend=? AND object_key=?", backend, key);
          if (db.count(
                      "SELECT COUNT(*) FROM blob_replica WHERE backend=? AND object_key=?",
                      backend,
                      key)
                  > 0
              || db.count(
                      "SELECT COUNT(*) FROM storage_job_item WHERE target_backend=? AND status IN"
                          + " ('QUEUED','RUNNING')",
                      backend)
                  > 0
              || System.currentTimeMillis() - n(copy, "created_at") < 120000
              || db.count(
                      "SELECT COUNT(*) FROM file_blob WHERE backend=? AND object_key=?",
                      backend,
                      key)
                  > 0
              || db.count(
                      "SELECT COUNT(*) FROM upload_session WHERE backend=? AND object_key=?",
                      backend,
                      key)
                  > 0
              || db.count(
                      "SELECT COUNT(*) FROM maintenance_job WHERE status IN ('QUEUED','RUNNING')"
                          + " AND (source_backend=? OR target_backend=?)",
                      backend,
                      backend)
                  > 0) throw Problem.conflict("副本仍有引用、迁移进行中或未超过链接安全等待期");
          try {
            storage.delete(backend, key);
          } catch (java.io.IOException e) {
            throw new Problem(502, "COPY_DELETE_FAILED", "副本删除失败，保留占用记录，请修复连接后重试");
          }
          db.update("DELETE FROM storage_copy WHERE backend=? AND object_key=?", backend, key);
          return null;
        });
  }
}
