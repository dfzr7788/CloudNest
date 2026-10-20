package com.cloudnest.control;

import com.cloudnest.common.*;
import com.cloudnest.user.RuntimeSettings;
import java.util.*;
import org.springframework.http.*;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/api/admin/topology")
public class TopologyController {
  private final StorageDiagnostics diagnostics;
  private final RuntimeSettings settings;
  private final Db db;

  public TopologyController(StorageDiagnostics diagnostics, RuntimeSettings settings, Db db) {
    this.diagnostics = diagnostics;
    this.settings = settings;
    this.db = db;
  }

  @GetMapping
  public ResponseEntity<?> overview(Authentication a) {
    String uid = a.getName();
    settings.admin(uid);
    var rows =
        db.list(
            "SELECT b.backend AS `source`,r.backend AS `target`,r.state AS `state`,COUNT(*) AS"
                + " `objects`,COALESCE(SUM(r.size),0) AS `bytes` FROM file_blob b JOIN blob_replica"
                + " r ON r.blob_id=b.id WHERE b.status='READY' AND r.backend<>b.backend GROUP BY"
                + " b.backend,r.backend,r.state ORDER BY b.backend,r.backend,r.state LIMIT 201");
    boolean more = rows.size() > 200;
    return response(
        Map.of(
            "observedAt",
            System.currentTimeMillis(),
            "source",
            "DATABASE_METADATA",
            "consistency",
            "NON_ATOMIC_METADATA_READ",
            "nodes",
            diagnostics.nodeStatus(uid, 100),
            "capacity",
            diagnostics.capacity(uid, 100),
            "jobs",
            diagnostics.activeJobs(uid, 20),
            "relations",
            rows.subList(0, Math.min(200, rows.size())),
            "hasMoreRelations",
            more));
  }

  @GetMapping("/events")
  public ResponseEntity<?> events(Authentication a, @RequestParam String backend) {
    settings.admin(a.getName());
    if (backend.isBlank() || backend.length() > 80) throw Problem.invalid("Invalid backend");
    return response(diagnostics.events(a.getName(), backend, 20));
  }

  private ResponseEntity<?> response(Object value) {
    return ResponseEntity.ok().cacheControl(CacheControl.noStore()).body(value);
  }
}
