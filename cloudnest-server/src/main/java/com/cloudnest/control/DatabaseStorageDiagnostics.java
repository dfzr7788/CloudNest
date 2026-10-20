package com.cloudnest.control;

import static com.cloudnest.common.Db.*;

import com.cloudnest.common.Db;
import com.cloudnest.node.NodeService;
import com.cloudnest.user.RuntimeSettings;
import java.util.*;
import org.springframework.stereotype.Service;

/** Read-only metadata view. Does not probe nodes or invoke storage writes. */
@Service
public class DatabaseStorageDiagnostics implements StorageDiagnostics {
  private final Db db;
  private final NodeService nodes;
  private final RuntimeSettings settings;

  public DatabaseStorageDiagnostics(Db db, NodeService nodes, RuntimeSettings settings) {
    this.db = db;
    this.nodes = nodes;
    this.settings = settings;
  }

  @Override
  public Map<String, Object> overview(String actorId) {
    settings.admin(actorId);
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

  private void authorize(String actorId, int limit) {
    settings.admin(actorId);
    if (limit < 1 || limit > 100) throw com.cloudnest.common.Problem.invalid("limit 必须在 1–100 之间");
  }

  private <T> Slice<T> slice(List<T> rows, int limit) {
    return new Slice<>(
        List.copyOf(rows.subList(0, Math.min(limit, rows.size()))), rows.size() > limit);
  }

  @Override
  public Slice<NodeHealth> nodeStatus(String actorId, int limit) {
    authorize(actorId, limit);
    long now = System.currentTimeMillis();
    var rows =
        db.list(
            "SELECT n.backend,n.name,n.enabled,n.management,n.failure_domain,"
                + " COALESCE(h.state,'UNKNOWN') AS state,COALESCE(h.checked_at,0) AS checked_at"
                + " FROM storage_node n LEFT JOIN storage_node_health h ON n.backend=h.backend"
                + " ORDER BY n.backend LIMIT ?",
            limit + 1);
    return slice(
        rows.stream()
            .map(
                row ->
                    new NodeHealth(
                        s(row, "backend"),
                        s(row, "name"),
                        b(row, "enabled"),
                        s(row, "management"),
                        s(row, "state"),
                        s(row, "failure_domain"),
                        n(row, "checked_at"),
                        now - n(row, "checked_at") > 120000))
            .toList(),
        limit);
  }

  @Override
  public Capacity capacity(String actorId, int limit) {
    authorize(actorId, limit);
    var rows =
        db.list(
            "SELECT backend,budget_bytes FROM storage_node ORDER BY backend LIMIT ?", limit + 1);
    // Only compute usage for the bounded visible page. No provider probes or credentials are
    // needed.
    var capacities =
        rows.subList(0, Math.min(limit, rows.size())).stream()
            .map(
                row -> {
                  String backend = s(row, "backend");
                  long used = nodes.used(backend),
                      reserved = nodes.reserved(backend),
                      budget = n(row, "budget_bytes");
                  Long remaining =
                      budget == 0
                          ? null
                          : Math.max(
                              0, budget - Math.min(used, budget) - Math.min(reserved, budget));
                  return new NodeCapacity(backend, used, reserved, budget, remaining);
                })
            .toList();
    return new Capacity(
        db.count("SELECT COALESCE(SUM(storage_used),0) FROM sys_user"),
        new Slice<>(capacities, rows.size() > limit),
        "ADMIN_BUDGET_NOT_PROVIDER_FREE_SPACE");
  }

  @Override
  public Slice<NodeEvent> events(String actorId, String backend, int limit) {
    authorize(actorId, limit);
    db.one("SELECT backend FROM storage_node WHERE backend=?", backend);
    return slice(
        db
            .list(
                "SELECT state,created_at FROM storage_node_event WHERE backend=?"
                    + " ORDER BY created_at DESC,state LIMIT ?",
                backend,
                limit + 1)
            .stream()
            .map(row -> new NodeEvent(s(row, "state"), n(row, "created_at")))
            .toList(),
        limit);
  }

  @Override
  public Slice<StorageJob> activeJobs(String actorId, int limit) {
    authorize(actorId, limit);
    return slice(
        db
            .list(
                "SELECT id,kind,status,target_backend,attempts,created_at,updated_at"
                    + " FROM storage_job_item WHERE status IN ('QUEUED','RUNNING')"
                    + " ORDER BY created_at,id LIMIT ?",
                limit + 1)
            .stream()
            .map(
                row ->
                    new StorageJob(
                        s(row, "id"),
                        s(row, "kind"),
                        s(row, "status"),
                        s(row, "target_backend"),
                        n(row, "attempts"),
                        n(row, "created_at"),
                        n(row, "updated_at")))
            .toList(),
        limit);
  }

  @Override
  public Policy policy(String actorId) {
    settings.admin(actorId);
    var row = db.one("SELECT mode,min_reliability FROM storage_placement_policy WHERE id=1");
    var rules =
        new ArrayList<>(
            List.of(
                "REQUIRES_VERIFIED_ONLINE_NODES",
                "ADMIN_BUDGET_NOT_PROVIDER_FREE_SPACE",
                "WRITE_PERMISSIONS_AND_RESERVATIONS_APPLY",
                "REPLICAS_NOT_BACKUPS"));
    if ("HIGH_AVAILABILITY".equals(s(row, "mode"))) {
      rules.add("TWO_INDEPENDENT_FAILURE_DOMAINS");
      rules.add("PROTECTION_AFTER_VERIFICATION");
    }
    return new Policy(
        s(row, "mode"), n(row, "min_reliability"), nodes.defaultBackend(), List.copyOf(rules));
  }
}
