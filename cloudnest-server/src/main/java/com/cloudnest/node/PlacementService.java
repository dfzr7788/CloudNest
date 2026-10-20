package com.cloudnest.node;

import static com.cloudnest.common.Db.*;

import com.cloudnest.common.*;
import java.util.*;
import org.springframework.stereotype.Service;

/** Selection and reservation are called under the same storage_guard transaction. */
@Service
public class PlacementService {
  public static final Set<String> MODES =
      Set.of("MANUAL", "CAPACITY", "STABILITY", "HIGH_AVAILABILITY");
  private final Db db;
  private final NodeService nodes;

  public PlacementService(Db db, NodeService nodes) {
    this.db = db;
    this.nodes = nodes;
  }

  public record Decision(String backend, String replicaBackend, String mode, String reason) {}

  public Decision select(String uid, long bytes) {
    var policy = db.one("SELECT * FROM storage_placement_policy WHERE id=1");
    String mode = s(policy, "mode");
    int minimum = (int) n(policy, "min_reliability");
    var eligible = new ArrayList<Map<String, Object>>();
    var rejected = new ArrayList<String>();
    for (var row : db.list("SELECT backend FROM storage_node ORDER BY backend")) {
      String backend = s(row, "backend");
      if (mode.equals("MANUAL") && !backend.equals(nodes.defaultBackend())) continue;
      var node = nodes.node(backend);
      try {
        nodes.authorizeTarget(uid, backend);
        nodes.admit(backend, false, bytes);
        if (mode.equals("HIGH_AVAILABILITY")) nodes.admit(backend, true, bytes);
        if (n(node, "reliability") < minimum) throw Problem.conflict("可靠性低于策略要求");
        eligible.add(node);
      } catch (Problem p) {
        rejected.add(backend + ":" + p.getMessage());
      }
    }
    eligible.sort(
        Comparator.<Map<String, Object>>comparingDouble(x -> score(x, mode))
            .reversed()
            .thenComparing(x -> s(x, "backend")));
    Map<String, Object> primary = null, secondary = null;
    if (mode.equals("HIGH_AVAILABILITY")) {
      outer:
      for (var a : eligible)
        for (var b : eligible) {
          if (!s(a, "backend").equals(s(b, "backend")) && independent(a, b)) {
            primary = a;
            secondary = b;
            break outer;
          }
        }
    } else if (!eligible.isEmpty()) primary = eligible.getFirst();
    if (primary == null)
      throw new Problem(
          409,
          "NO_PLACEMENT",
          mode.equals("HIGH_AVAILABILITY") ? "没有两个已验证、容量足够且故障域不同的节点" : "没有符合健康、写入权限、容量和可靠性要求的节点");
    String backend = s(primary, "backend"),
        replica = secondary == null ? null : s(secondary, "backend");
    String reason =
        "策略="
            + mode
            + "；选择="
            + backend
            + "；评分="
            + Math.round(score(primary, mode))
            + "；可靠性="
            + n(primary, "reliability")
            + "；预算余量="
            + remaining(primary)
            + (replica == null ? "" : "；异域副本目标=" + replica)
            + "；排除="
            + String.join("；", rejected);
    if (reason.length() > 2000) reason = reason.substring(0, 2000);
    db.update(
        "INSERT INTO storage_placement_log(id,user_id,backend,mode,reason,created_at)"
            + " VALUES(?,?,?,?,?,?)",
        Util.id(),
        uid,
        backend,
        mode,
        reason,
        System.currentTimeMillis());
    return new Decision(backend, replica, mode, reason);
  }

  public static boolean independent(Map<String, Object> a, Map<String, Object> b) {
    String domain = s(a, "failure_domain");
    return domain != null
        && !domain.equals("unassigned")
        && !domain.isBlank()
        && !domain.equals(s(b, "failure_domain"))
        && !"unassigned".equals(s(b, "failure_domain"));
  }

  private long remaining(Map<String, Object> node) {
    long budget = n(node, "budget_bytes");
    return budget == 0
        ? 0
        : Math.max(0, budget - nodes.used(s(node, "backend")) - nodes.reserved(s(node, "backend")));
  }

  private double score(Map<String, Object> node, String mode) {
    // Unbudgeted remote capacity is unknown, never treated as infinite free space.
    double free = Math.min(100, remaining(node) / (double) (1024L * 1024 * 1024));
    double reliability = n(node, "reliability"),
        priority = n(node, "priority"),
        cost = n(node, "cost");
    double latency = Math.min(100, n(node, "latency_ms") / 10.0);
    return (mode.equals("CAPACITY") ? free * 5 + reliability : reliability * 5 + free)
        + priority * .2
        - cost * .2
        - latency * .1
        + Math.min(20, n(node, "successes"));
  }
}
