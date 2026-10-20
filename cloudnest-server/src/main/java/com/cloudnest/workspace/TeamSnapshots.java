package com.cloudnest.workspace;

import static com.cloudnest.common.Db.*;

import com.cloudnest.resilience.*;
import jakarta.validation.Valid;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/api/teams/{team}/resilience")
public class TeamSnapshots {
  private final TeamService teams;
  private final SnapshotService snapshots;
  private final ResilienceTasks tasks;

  public TeamSnapshots(TeamService teams, SnapshotService snapshots, ResilienceTasks tasks) {
    this.teams = teams;
    this.snapshots = snapshots;
    this.tasks = tasks;
  }

  @GetMapping("/snapshots")
  public Object list(
      Authentication a,
      @PathVariable String team,
      @RequestParam(required = false) String folderId) {
    return teams.with(a.getName(), team, 3, w -> snapshots.list(s(w, "storage_user"), folderId));
  }

  @PostMapping("/snapshots")
  public Object create(
      Authentication a,
      @PathVariable String team,
      @Valid @RequestBody ResilienceController.Create in) {
    return teams.with(
        a.getName(),
        team,
        3,
        w -> {
          var r = snapshots.create(s(w, "storage_user"), in.folderId(), in.name());
          teams.audit(team, a.getName(), "SNAPSHOT_CREATED", s(r, "id"));
          return r;
        });
  }

  @GetMapping("/snapshots/{id}/entries")
  public Object entries(Authentication a, @PathVariable String team, @PathVariable String id) {
    return teams.with(a.getName(), team, 3, w -> snapshots.entries(s(w, "storage_user"), id));
  }

  @GetMapping("/snapshots/{id}/diff")
  public Object diff(
      Authentication a,
      @PathVariable String team,
      @PathVariable String id,
      @RequestParam(required = false) String against) {
    return teams.with(a.getName(), team, 3, w -> snapshots.diff(s(w, "storage_user"), id, against));
  }

  @DeleteMapping("/snapshots/{id}")
  public void delete(Authentication a, @PathVariable String team, @PathVariable String id) {
    teams.with(
        a.getName(),
        team,
        3,
        w -> {
          snapshots.delete(s(w, "storage_user"), id);
          teams.audit(team, a.getName(), "SNAPSHOT_DELETED", id);
          return null;
        });
  }

  @PostMapping("/snapshots/{id}/preview")
  public Object preview(
      Authentication a,
      @PathVariable String team,
      @PathVariable String id,
      @RequestBody ResilienceController.Restore in) {
    return teams.with(
        a.getName(),
        team,
        3,
        w -> tasks.preview(s(w, "storage_user"), id, in.targetFolder(), in.selectedEntry()));
  }

  @PostMapping("/snapshots/{id}/restore")
  public Object restore(
      Authentication a,
      @PathVariable String team,
      @PathVariable String id,
      @Valid @RequestBody ResilienceController.Restore in) {
    return teams.with(
        a.getName(),
        team,
        3,
        w -> {
          var r =
              tasks.restore(
                  s(w, "storage_user"), id, in.targetFolder(), in.selectedEntry(), in.requestKey());
          teams.audit(team, a.getName(), "RESTORE_REQUESTED", s(r, "id"));
          return r;
        });
  }

  @GetMapping("/policies")
  public Object policies(Authentication a, @PathVariable String team) {
    return teams.with(a.getName(), team, 3, w -> snapshots.policies(s(w, "storage_user")));
  }

  @PutMapping("/policies")
  public void policy(
      Authentication a,
      @PathVariable String team,
      @Valid @RequestBody ResilienceController.Policy in) {
    teams.with(
        a.getName(),
        team,
        3,
        w -> {
          snapshots.policy(
              s(w, "storage_user"),
              in.folderId(),
              in.enabled(),
              in.intervalHours(),
              in.keepCount(),
              in.protection());
          teams.audit(team, a.getName(), "PROTECTION_UPDATED", in.folderId());
          return null;
        });
  }

  @GetMapping("/tasks")
  public Object tasks(Authentication a, @PathVariable String team) {
    return teams.with(a.getName(), team, 3, w -> tasks.list(s(w, "storage_user")));
  }

  @PostMapping("/tasks/{id}/{action}")
  public void action(
      Authentication a,
      @PathVariable String team,
      @PathVariable String id,
      @PathVariable String action) {
    teams.with(
        a.getName(),
        team,
        3,
        w -> {
          tasks.action(s(w, "storage_user"), id, action);
          teams.audit(team, a.getName(), "TASK_UPDATED", id);
          return null;
        });
  }
}
