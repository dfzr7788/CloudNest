package com.cloudnest.workspace;

import static com.cloudnest.common.Db.*;

import com.cloudnest.common.*;
import com.cloudnest.file.DriveService;
import com.cloudnest.node.NodeService;
import com.cloudnest.storage.MigrationService;
import jakarta.validation.Valid;
import jakarta.validation.constraints.*;
import java.util.*;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/api/teams/{team}/storage")
public class TeamStorage {
  private final Db db;
  private final TeamService teams;
  private final DriveService drive;
  private final NodeService nodes;
  private final MigrationService jobs;

  public TeamStorage(
      Db db, TeamService teams, DriveService drive, NodeService nodes, MigrationService jobs) {
    this.db = db;
    this.teams = teams;
    this.drive = drive;
    this.nodes = nodes;
    this.jobs = jobs;
  }

  public record Submit(
      @NotBlank String target,
      @NotBlank String kind,
      @NotEmpty @Size(max = 200) List<String> fileIds) {}

  @GetMapping("/targets")
  public Object targets(Authentication a, @PathVariable String team) {
    return teams.with(
        a.getName(),
        team,
        3,
        w ->
            db.list(
                "SELECT"
                    + " n.backend,n.name,n.failure_domain,n.budget_bytes,n.reliability,n.performance_class,h.state,h.validated,h.checked_at"
                    + " FROM storage_node n JOIN storage_node_health h ON h.backend=n.backend WHERE"
                    + " n.enabled=TRUE AND (n.backend NOT LIKE 'peer:%' OR EXISTS(SELECT 1 FROM"
                    + " storage_contribution c WHERE c.backend=n.backend AND c.team_id=? AND"
                    + " c.status='APPROVED')) ORDER BY n.name",
                team));
  }

  @GetMapping("/jobs")
  public Object list(Authentication a, @PathVariable String team) {
    return teams.with(
        a.getName(),
        team,
        3,
        w ->
            db.list(
                "SELECT * FROM storage_job_item WHERE user_id=? ORDER BY created_at DESC LIMIT 200",
                w.get("storage_user")));
  }

  @PostMapping("/jobs")
  public Object submit(Authentication a, @PathVariable String team, @Valid @RequestBody Submit in) {
    return teams.with(
        a.getName(),
        team,
        3,
        w ->
            db.atomic(
                s(w, "storage_user"),
                () ->
                    nodes.locked(
                        () -> {
                          String uid = s(w, "storage_user");
                          if (db.count(
                                      "SELECT COUNT(*) FROM storage_job_item WHERE user_id=? AND"
                                          + " status IN ('QUEUED','RUNNING')",
                                      uid)
                                  + in.fileIds().size()
                              > 200) throw Problem.conflict("待处理任务最多 200 项");
                          var blobs = new LinkedHashSet<String>();
                          for (String id : in.fileIds())
                            blobs.add(s(drive.file(uid, id), "blob_id"));
                          var ids = new ArrayList<String>();
                          for (String blob : blobs)
                            ids.add(
                                jobs.enqueue(
                                    uid,
                                    db.one(
                                        "SELECT * FROM file_blob WHERE id=? AND user_id=? AND"
                                            + " status='READY'",
                                        blob,
                                        uid),
                                    in.target(),
                                    in.kind(),
                                    null));
                          teams.audit(team, a.getName(), "STORAGE_TASK_SUBMITTED", in.target());
                          return Map.of("ids", ids);
                        })));
  }

  @PostMapping("/jobs/{id}/retry")
  public Object retry(Authentication a, @PathVariable String team, @PathVariable String id) {
    return teams.with(
        a.getName(),
        team,
        3,
        w -> {
          var result = Map.of("id", jobs.retry(s(w, "storage_user"), id));
          teams.audit(team, a.getName(), "STORAGE_TASK_RETRIED", id);
          return result;
        });
  }

  @DeleteMapping("/jobs/{id}")
  public void cancel(Authentication a, @PathVariable String team, @PathVariable String id) {
    teams.with(
        a.getName(),
        team,
        3,
        w -> {
          jobs.cancel(s(w, "storage_user"), id);
          teams.audit(team, a.getName(), "STORAGE_TASK_CANCELLED", id);
          return null;
        });
  }
}
