package com.cloudnest.storage;

import static com.cloudnest.common.Db.*;

import com.cloudnest.common.*;
import com.cloudnest.file.DriveService;
import com.cloudnest.node.*;
import com.cloudnest.user.RuntimeSettings;
import jakarta.validation.Valid;
import jakarta.validation.constraints.*;
import java.util.*;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/api")
public class HorizonController {
  private final Db db;
  private final NodeService nodes;
  private final RuntimeSettings settings;
  private final DriveService drive;
  private final MigrationService jobs;

  public HorizonController(
      Db db,
      NodeService nodes,
      RuntimeSettings settings,
      DriveService drive,
      MigrationService jobs) {
    this.db = db;
    this.nodes = nodes;
    this.settings = settings;
    this.drive = drive;
    this.jobs = jobs;
  }

  @GetMapping("/admin/horizon")
  public Map<String, Object> overview(Authentication a) {
    settings.admin(a.getName());
    var alerts =
        new ArrayList<>(
            db.list(
                "SELECT n.backend,n.name,h.state,n.budget_bytes FROM storage_node n JOIN"
                    + " storage_node_health h ON h.backend=n.backend WHERE n.enabled=TRUE AND"
                    + " (h.state<>'ONLINE' OR h.checked_at<?)",
                System.currentTimeMillis() - 120000));
    int warning = Integer.parseInt(settings.get("storage").getOrDefault("warningPercent", "80"));
    for (var row :
        db.list(
            "SELECT backend,name,budget_bytes FROM storage_node WHERE enabled=TRUE AND"
                + " budget_bytes>0")) {
      String backend = s(row, "backend");
      if ((nodes.used(backend) + nodes.reserved(backend)) * 100.0 / n(row, "budget_bytes")
          >= warning)
        alerts.add(
            Map.of(
                "backend",
                backend,
                "name",
                s(row, "name"),
                "state",
                "CAPACITY_WARNING",
                "budget_bytes",
                n(row, "budget_bytes")));
    }
    return Map.of(
        "placement",
        db.one("SELECT * FROM storage_placement_policy WHERE id=1"),
        "decisions",
        db.list("SELECT * FROM storage_placement_log ORDER BY created_at DESC LIMIT 100"),
        "jobs",
        db.list(
            "SELECT j.*,(SELECT MIN(e.name) FROM drive_entry e WHERE e.blob_id=j.blob_id) AS"
                + " file_name,b.size,b.backend AS source_backend FROM storage_job_item j LEFT JOIN"
                + " file_blob b ON b.id=j.blob_id ORDER BY j.created_at DESC LIMIT 100"),
        "alerts",
        alerts,
        "protection",
        db.list(
            "SELECT b.id,b.backend,b.size,b.desired_replicas,r.backend AS"
                + " replica_backend,r.state,r.verified_at,n.failure_domain,h.state AS health FROM"
                + " file_blob b LEFT JOIN blob_replica r ON r.blob_id=b.id LEFT JOIN storage_node n"
                + " ON n.backend=r.backend LEFT JOIN storage_node_health h ON h.backend=r.backend"
                + " WHERE b.desired_replicas=2 AND b.status='READY' ORDER BY b.created_at DESC"
                + " LIMIT 200"),
        "logicalBytes",
        db.count("SELECT COALESCE(SUM(storage_used),0) FROM sys_user"),
        "events",
        db.list("SELECT * FROM storage_node_event ORDER BY created_at DESC LIMIT 100"));
  }

  public record Policy(@NotBlank String mode, @Min(0) @Max(100) int minReliability) {}

  @PutMapping("/admin/horizon/placement")
  public void policy(Authentication a, @Valid @RequestBody Policy in) {
    settings.admin(a.getName());
    if (!PlacementService.MODES.contains(in.mode())) throw Problem.invalid("调度模式无效");
    nodes.locked(
        () -> {
          db.update(
              "UPDATE storage_placement_policy SET mode=?,min_reliability=? WHERE id=1",
              in.mode(),
              in.minReliability());
          return null;
        });
  }

  public record Attributes(
      @NotBlank @Size(max = 80) String failureDomain,
      @Min(0) @Max(100) int reliability,
      @Min(0) @Max(100) int priority,
      @Min(0) @Max(100) int cost) {}

  @PutMapping("/admin/horizon/nodes/{backend}")
  public void attributes(
      Authentication a, @PathVariable String backend, @Valid @RequestBody Attributes in) {
    settings.admin(a.getName());
    if (in.failureDomain().isBlank()) throw Problem.invalid("故障域不能为空");
    nodes.locked(
        () -> {
          nodes.node(backend);
          db.update(
              "UPDATE storage_node SET failure_domain=?,reliability=?,priority=?,cost=? WHERE"
                  + " backend=?",
              in.failureDomain().trim(),
              in.reliability(),
              in.priority(),
              in.cost(),
              backend);
          return null;
        });
  }

  @GetMapping("/storage/targets")
  public List<Map<String, Object>> targets(Authentication a) {
    return db.list(
        "SELECT backend,name,failure_domain,reliability FROM storage_node WHERE enabled=TRUE ORDER"
            + " BY name");
  }

  public record Request(
      @NotBlank String kind,
      @NotBlank String target,
      @Size(max = 200) List<String> fileIds,
      String folderId) {}

  @PostMapping("/storage/jobs")
  public Map<String, Object> submit(Authentication a, @Valid @RequestBody Request in) {
    String uid = a.getName();
    return db.atomic(
        uid,
        () ->
            nodes.locked(
                () -> {
                  Set<String> blobs = new LinkedHashSet<>();
                  if (in.fileIds() != null)
                    for (String id : in.fileIds()) blobs.add(s(drive.file(uid, id), "blob_id"));
                  if (in.folderId() != null) {
                    var queue = new ArrayDeque<String>();
                    queue.add(s(drive.folder(uid, in.folderId()), "id"));
                    int visited = 0;
                    while (!queue.isEmpty()) {
                      if (++visited > 10000) throw Problem.invalid("目录过大，请分批处理");
                      for (var e :
                          db.list(
                              "SELECT id,kind,blob_id FROM drive_entry WHERE user_id=? AND"
                                  + " parent_id=? AND deleted=FALSE",
                              uid,
                              queue.remove()))
                        if ("FOLDER".equals(s(e, "kind"))) queue.add(s(e, "id"));
                        else blobs.add(s(e, "blob_id"));
                      if (blobs.size() > 200) throw Problem.invalid("一次最多处理 200 个对象，请分批选择");
                    }
                  }
                  if (blobs.isEmpty()) throw Problem.invalid("请选择文件或非空目录");
                  if (db.count(
                              "SELECT COUNT(*) FROM storage_job_item WHERE user_id=? AND status IN"
                                  + " ('QUEUED','RUNNING')",
                              uid)
                          + blobs.size()
                      > 200) throw Problem.conflict("待处理任务最多 200 项");
                  var ids = new ArrayList<String>();
                  for (String bid : blobs)
                    ids.add(
                        jobs.enqueue(
                            uid,
                            db.one(
                                "SELECT * FROM file_blob WHERE id=? AND user_id=? AND"
                                    + " status='READY'",
                                bid,
                                uid),
                            in.target(),
                            in.kind(),
                            null));
                  return Map.of("ids", ids);
                }));
  }

  @GetMapping("/storage/jobs")
  public List<Map<String, Object>> ownJobs(Authentication a) {
    return db.list(
        "SELECT j.*,(SELECT MIN(e.name) FROM drive_entry e WHERE e.blob_id=j.blob_id) AS"
            + " file_name,b.size FROM storage_job_item j LEFT JOIN file_blob b ON b.id=j.blob_id"
            + " WHERE j.user_id=? ORDER BY j.created_at DESC LIMIT 200",
        a.getName());
  }

  @PostMapping("/storage/jobs/{id}/retry")
  public Map<String, Object> retry(Authentication a, @PathVariable String id) {
    return Map.of("id", jobs.retry(owner(a, id), id));
  }

  @DeleteMapping("/storage/jobs/{id}")
  public void cancel(Authentication a, @PathVariable String id) {
    jobs.cancel(owner(a, id), id);
  }

  private String owner(Authentication a, String id) {
    var job = db.one("SELECT user_id FROM storage_job_item WHERE id=?", id);
    String uid = s(job, "user_id");
    if (!uid.equals(a.getName())) settings.admin(a.getName());
    return uid;
  }

  @GetMapping("/storage/files/{id}/replicas")
  public Map<String, Object> replicas(Authentication a, @PathVariable String id) {
    var e = drive.file(a.getName(), id);
    var b = db.one("SELECT * FROM file_blob WHERE id=?", s(e, "blob_id"));
    var copies =
        db.list(
            "SELECT r.*,n.failure_domain,h.state AS health,h.checked_at,h.validated FROM"
                + " blob_replica r JOIN storage_node n ON n.backend=r.backend JOIN"
                + " storage_node_health h ON h.backend=r.backend WHERE r.blob_id=?",
            s(b, "id"));
    var available =
        copies.stream()
            .filter(
                r ->
                    "VERIFIED".equals(s(r, "state"))
                        && "ONLINE".equals(s(r, "health"))
                        && b(r, "validated")
                        && System.currentTimeMillis() - n(r, "checked_at") <= 120000)
            .toList();
    boolean protectedNow =
        available.stream()
            .anyMatch(x -> available.stream().anyMatch(y -> PlacementService.independent(x, y)));
    return Map.of(
        "backend",
        s(b, "backend"),
        "desiredReplicas",
        n(b, "desired_replicas"),
        "protected",
        protectedNow,
        "replicas",
        copies);
  }

  @DeleteMapping("/storage/files/{id}/protection")
  public void stopProtection(Authentication a, @PathVariable String id) {
    db.atomic(
        a.getName(),
        () ->
            nodes.locked(
                () -> {
                  var file = drive.file(a.getName(), id);
                  String bid = s(file, "blob_id");
                  db.update("UPDATE file_blob SET desired_replicas=1 WHERE id=?", bid);
                  for (var task :
                      db.list(
                          "SELECT id FROM storage_job_item WHERE blob_id=? AND kind='REPLICATE' AND"
                              + " status IN ('QUEUED','RUNNING')",
                          bid)) {
                    db.update(
                        "UPDATE storage_job_item SET status='CANCELLED',updated_at=? WHERE id=?",
                        System.currentTimeMillis(),
                        s(task, "id"));
                    nodes.release("job:" + s(task, "id"));
                  }
                  return null;
                }));
  }
}
