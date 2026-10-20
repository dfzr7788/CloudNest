package com.cloudnest.resilience;

import static com.cloudnest.common.Db.*;

import com.cloudnest.common.*;
import com.cloudnest.file.*;
import com.cloudnest.user.RuntimeSettings;
import jakarta.servlet.http.*;
import jakarta.validation.Valid;
import jakarta.validation.constraints.*;
import java.io.IOException;
import java.util.*;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/api/resilience")
public class ResilienceController {
  private final Db db;
  private final SnapshotService snapshots;
  private final ResilienceTasks tasks;
  private final RuntimeSettings settings;
  private final ContentService content;
  private final com.cloudnest.node.NodeService pool;

  public ResilienceController(
      Db db,
      SnapshotService snapshots,
      ResilienceTasks tasks,
      RuntimeSettings settings,
      ContentService content,
      com.cloudnest.node.NodeService pool) {
    this.db = db;
    this.snapshots = snapshots;
    this.tasks = tasks;
    this.settings = settings;
    this.content = content;
    this.pool = pool;
  }

  public record Create(String folderId, @Size(max = 180) String name) {}

  public record Restore(String targetFolder, String selectedEntry, @NotBlank String requestKey) {}

  public record Policy(
      @NotBlank String folderId,
      boolean enabled,
      @Min(1) @Max(8760) int intervalHours,
      @Min(1) @Max(365) int keepCount,
      @NotBlank String protection) {}

  public record Scan(
      String backend, String ownerId, String folderId, @NotBlank String requestKey) {}

  public record ScanPolicy(
      boolean enabled,
      @Min(1) @Max(8760) int intervalHours,
      @Min(65536) @Max(1073741824) long bytesPerSecond) {}

  public record Capabilities(
      boolean publicUpload, boolean supportsRange, @NotBlank String performanceClass) {}

  @PutMapping("/nodes/{backend}/capabilities")
  public void capabilities(
      Authentication a, @PathVariable String backend, @Valid @RequestBody Capabilities in) {
    settings.admin(a.getName());
    if (!Set.of("FAST", "STANDARD", "CAPACITY").contains(in.performanceClass()))
      throw Problem.invalid("性能等级无效");
    db.transaction(
        () -> {
          db.one("SELECT id FROM storage_guard WHERE id=1 FOR UPDATE");
          var node = db.one("SELECT * FROM storage_node WHERE backend=?", backend);
          if (in.publicUpload()
              && (!backend.startsWith("s3:")
                  || !b(node, "direct_download")
                  || node.get("public_endpoint") == null
                  || !RuntimeSettings.httpUrl(s(node, "public_endpoint"))
                      .getScheme()
                      .equals("https"))) throw Problem.invalid("公网直传需要启用 HTTPS S3 直连");
          db.update(
              "UPDATE storage_node SET public_upload=?,supports_range=?,performance_class=? WHERE"
                  + " backend=?",
              in.publicUpload(),
              in.supportsRange(),
              in.performanceClass(),
              backend);
          return null;
        });
  }

  @GetMapping("/snapshots")
  public Object list(Authentication a, @RequestParam(required = false) String folderId) {
    return snapshots.list(a.getName(), folderId);
  }

  @PostMapping("/snapshots")
  public Object create(Authentication a, @Valid @RequestBody Create in) {
    return snapshots.create(a.getName(), in.folderId(), in.name());
  }

  @GetMapping("/snapshots/{id}/entries")
  public Object entries(Authentication a, @PathVariable String id) {
    return snapshots.entries(a.getName(), id);
  }

  @GetMapping("/snapshots/{id}/diff")
  public Object diff(
      Authentication a, @PathVariable String id, @RequestParam(required = false) String against) {
    return snapshots.diff(a.getName(), id, against);
  }

  @DeleteMapping("/snapshots/{id}")
  public void delete(Authentication a, @PathVariable String id) {
    snapshots.delete(a.getName(), id);
  }

  @PostMapping("/snapshots/{id}/preview")
  public Object preview(Authentication a, @PathVariable String id, @RequestBody Restore in) {
    return tasks.preview(a.getName(), id, in.targetFolder(), in.selectedEntry());
  }

  @PostMapping("/snapshots/{id}/restore")
  public Object restore(Authentication a, @PathVariable String id, @Valid @RequestBody Restore in) {
    return tasks.restore(a.getName(), id, in.targetFolder(), in.selectedEntry(), in.requestKey());
  }

  @GetMapping("/snapshots/{id}/entries/{entry}/download")
  public void download(
      Authentication a,
      @PathVariable String id,
      @PathVariable String entry,
      HttpServletRequest req,
      HttpServletResponse res)
      throws IOException {
    snapshots.owned(a.getName(), id);
    var e =
        db.one(
            "SELECT * FROM folder_snapshot_entry WHERE snapshot_id=? AND entry_id=? AND"
                + " kind='FILE'",
            id,
            entry);
    content.sendBlob(
        e,
        db.one(
            "SELECT * FROM file_blob WHERE id=? AND user_id=? AND status='READY'",
            e.get("blob_id"),
            a.getName()),
        false,
        req,
        res);
  }

  @GetMapping("/policies")
  public Object policies(Authentication a) {
    return snapshots.policies(a.getName());
  }

  @PutMapping("/policies")
  public void policy(Authentication a, @Valid @RequestBody Policy in) {
    snapshots.policy(
        a.getName(),
        in.folderId(),
        in.enabled(),
        in.intervalHours(),
        in.keepCount(),
        in.protection());
  }

  @GetMapping("/tasks")
  public Object tasks(Authentication a) {
    return tasks.list(a.getName());
  }

  @PostMapping("/tasks/{id}/{action}")
  public void action(Authentication a, @PathVariable String id, @PathVariable String action) {
    tasks.action(a.getName(), id, action);
  }

  @PostMapping("/integrity")
  public Object scan(Authentication a, @Valid @RequestBody Scan in) {
    settings.admin(a.getName());
    return tasks.scan(a.getName(), in.backend(), in.ownerId(), in.folderId(), in.requestKey());
  }

  @GetMapping("/integrity/{id}")
  public Object scanItems(Authentication a, @PathVariable String id) {
    settings.admin(a.getName());
    tasks.owned(a.getName(), id);
    return db.list("SELECT * FROM integrity_scan_item WHERE task_id=? ORDER BY ordinal", id);
  }

  @GetMapping("/integrity-policy")
  public Object scanPolicy(Authentication a) {
    settings.admin(a.getName());
    return db.one("SELECT * FROM integrity_scan_policy WHERE id=1");
  }

  @PutMapping("/integrity-policy")
  public void scanPolicy(Authentication a, @Valid @RequestBody ScanPolicy in) {
    settings.admin(a.getName());
    db.update(
        "UPDATE integrity_scan_policy SET"
            + " enabled=?,interval_hours=?,bytes_per_second=?,administrator_id=?,next_run=?,last_error=NULL"
            + " WHERE id=1",
        in.enabled(),
        in.intervalHours(),
        in.bytesPerSecond(),
        a.getName(),
        System.currentTimeMillis() + in.intervalHours() * 3600000L);
  }

  @GetMapping("/tiering")
  public Object tiering(Authentication a) {
    settings.admin(a.getName());
    var nodes =
        db.list(
            "SELECT backend,name,failure_domain,reliability,cost,performance_class FROM"
                + " storage_node WHERE enabled=TRUE AND management='ACTIVE' ORDER BY"
                + " cost,reliability DESC LIMIT 100");
    var suggestions = new ArrayList<Map<String, Object>>();
    for (var e :
        db.list(
            "SELECT e.id,e.name,e.last_opened,b.user_id,b.id AS"
                + " blob_id,b.backend,b.desired_replicas,b.size FROM drive_entry e JOIN file_blob b"
                + " ON b.id=e.blob_id WHERE e.deleted=FALSE AND b.status='READY' AND"
                + " COALESCE(e.last_opened,e.created_at)<? ORDER BY e.size DESC LIMIT 100",
            System.currentTimeMillis() - 90L * 86400000)) {
      var candidates = new ArrayList<Map<String, Object>>();
      for (var candidate : nodes) {
        if (Objects.equals(candidate.get("backend"), e.get("backend"))
            || n(candidate, "reliability") < 60) continue;
        try {
          pool.authorizeTarget(s(e, "user_id"), s(candidate, "backend"));
          pool.admit(s(candidate, "backend"), true, n(e, "size"));
          candidates.add(candidate);
        } catch (Problem unavailable) {
          /* Suggestions obey the same admission rules as real work. */
        }
      }
      if (!candidates.isEmpty())
        suggestions.add(
            Map.of(
                "file",
                e,
                "reason",
                "NOT_ACCESSED_90_DAYS",
                "candidates",
                candidates,
                "requiresApproval",
                true));
    }
    return Map.of(
        "suggestions", suggestions, "automaticMigration", false, "independentBackupRequired", true);
  }
}
