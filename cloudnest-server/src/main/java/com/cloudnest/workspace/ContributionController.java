package com.cloudnest.workspace;

import static com.cloudnest.common.Db.*;

import com.cloudnest.common.*;
import com.cloudnest.node.NodeService;
import com.cloudnest.security.SecretCipher;
import com.cloudnest.user.RuntimeSettings;
import jakarta.validation.Valid;
import jakarta.validation.constraints.*;
import java.util.*;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/api")
public class ContributionController {
  private final Db db;
  private final TeamService teams;
  private final NodeService nodes;
  private final SecretCipher cipher;
  private final RuntimeSettings settings;

  public ContributionController(
      Db db, TeamService teams, NodeService nodes, SecretCipher cipher, RuntimeSettings settings) {
    this.db = db;
    this.teams = teams;
    this.nodes = nodes;
    this.cipher = cipher;
    this.settings = settings;
  }

  public record Register(
      @NotBlank @Size(max = 80) String name,
      @NotBlank @Size(max = 500) String endpoint,
      @NotBlank @Pattern(regexp = "[A-Za-z0-9_-]{32,128}") String nodeToken,
      @Min(1048576) long capacityBytes) {}

  public record Approve(
      @NotBlank @Size(max = 80) String failureDomain,
      @Min(0) @Max(100) int reliability,
      @NotBlank String performanceClass) {}

  @PostMapping("/teams/{team}/contributions")
  public Object register(
      Authentication a, @PathVariable String team, @Valid @RequestBody Register in) {
    var uri = RuntimeSettings.httpUrl(in.endpoint());
    if (!uri.getPath().isEmpty() && !uri.getPath().equals("/")) throw Problem.invalid("节点地址不能包含路径");
    if (!uri.getScheme().equals("https") && !privateHost(uri.getHost()))
      throw Problem.invalid("HTTP 节点必须位于受控私网；公网节点需要 HTTPS");
    return teams.with(
        a.getName(),
        team,
        0,
        w -> {
          if (!b(teams.member(a.getName(), team), "contributor"))
            throw new Problem(403, "CONTRIBUTOR_REQUIRED", "需要独立的存储贡献授权");
          if (db.count("SELECT COUNT(*) FROM storage_contribution WHERE team_id=?", team) >= 32)
            throw Problem.conflict("团队贡献节点最多 32 个");
          String id = Util.id(), heartbeat = Util.token();
          db.update(
              "INSERT INTO"
                  + " storage_contribution(id,team_id,contributor_id,name,endpoint,bucket,access_key,secret_key,capacity_bytes,created_at,token_hash)"
                  + " VALUES(?,?,?,?,?,'objects','',?,?,?,?)",
              id,
              team,
              a.getName(),
              in.name(),
              in.endpoint().replaceAll("/$", ""),
              cipher.encrypt(in.nodeToken()),
              in.capacityBytes(),
              System.currentTimeMillis(),
              Util.hash(heartbeat));
          teams.audit(team, a.getName(), "CONTRIBUTION_REGISTERED", id);
          return Map.of("id", id, "heartbeatToken", heartbeat, "status", "PENDING");
        });
  }

  static boolean privateHost(String host) {
    if (host == null) return false;
    if (host.equals("localhost") || host.endsWith(".ts.net")) return true;
    if (!host.matches("\\d+\\.\\d+\\.\\d+\\.\\d+")) return false;
    try {
      var p = Arrays.stream(host.split("\\.")).mapToInt(Integer::parseInt).toArray();
      if (Arrays.stream(p).anyMatch(x -> x < 0 || x > 255)) return false;
      return p[0] == 10
          || p[0] == 127
          || (p[0] == 192 && p[1] == 168)
          || (p[0] == 172 && p[1] >= 16 && p[1] <= 31)
          || (p[0] == 100 && p[1] >= 64 && p[1] <= 127);
    } catch (NumberFormatException e) {
      return false;
    }
  }

  @GetMapping("/teams/{team}/contributions")
  public Object list(Authentication a, @PathVariable String team) {
    return teams.with(
        a.getName(),
        team,
        0,
        w ->
            db.list(
                "SELECT"
                    + " c.id,c.name,c.contributor_id,c.backend,c.capacity_bytes,c.status,c.failure_domain,c.last_heartbeat"
                    + " FROM storage_contribution c WHERE team_id=? ORDER BY created_at DESC",
                team));
  }

  @PostMapping("/teams/{team}/contributions/{id}/review")
  public void review(Authentication a, @PathVariable String team, @PathVariable String id) {
    teams.with(
        a.getName(),
        team,
        3,
        w -> {
          if (db.update(
                  "UPDATE storage_contribution SET status='REVIEWED' WHERE id=? AND team_id=? AND"
                      + " status='PENDING'",
                  id,
                  team)
              != 1) throw Problem.conflict("节点状态不可审核");
          teams.audit(team, a.getName(), "CONTRIBUTION_REVIEWED", id);
          return null;
        });
  }

  @GetMapping("/admin/contributions")
  public Object pending(Authentication a) {
    settings.admin(a.getName());
    return db.list(
        "SELECT"
            + " id,team_id,contributor_id,name,endpoint,capacity_bytes,status,failure_domain,last_heartbeat"
            + " FROM storage_contribution ORDER BY created_at DESC LIMIT 200");
  }

  @PostMapping("/admin/contributions/{id}/approve")
  public Object approve(Authentication a, @PathVariable String id, @Valid @RequestBody Approve in) {
    settings.admin(a.getName());
    if (in.failureDomain().isBlank()
        || in.failureDomain().equals("unassigned")
        || !Set.of("FAST", "STANDARD", "CAPACITY").contains(in.performanceClass()))
      throw Problem.invalid("故障域或性能等级无效");
    var contribution = db.one("SELECT team_id FROM storage_contribution WHERE id=?", id);
    String team = s(contribution, "team_id");
    return db.transaction(
        () -> {
          db.one("SELECT id FROM team_workspace WHERE id=? FOR UPDATE", team);
          var c =
              db.one(
                  "SELECT * FROM storage_contribution WHERE id=? AND status IN"
                      + " ('REVIEWED','APPROVED')",
                  id);
          if (!b(teams.member(s(c, "contributor_id"), team), "contributor"))
            throw Problem.conflict("贡献授权已撤销");
          String backend = "peer:" + id;
          nodes.locked(
              () -> {
                nodes.ensure(backend, s(c, "name"), false);
                db.update(
                    "UPDATE storage_contribution SET backend=?,failure_domain=? WHERE id=?",
                    backend,
                    in.failureDomain().strip(),
                    id);
                db.update(
                    "UPDATE storage_node SET"
                        + " budget_bytes=?,failure_domain=?,reliability=?,performance_class=?,supports_range=TRUE,direct_download=FALSE"
                        + " WHERE backend=?",
                    c.get("capacity_bytes"),
                    in.failureDomain().strip(),
                    in.reliability(),
                    in.performanceClass(),
                    backend);
                return null;
              });
          // Explicit system administrator action is the first outbound connection to a member
          // endpoint.
          var health = nodes.check(backend, true);
          if (!Boolean.TRUE.equals(health.get("ok")))
            throw Problem.conflict("节点认证或读写校验失败，请检查私网连接和节点令牌");
          nodes.locked(
              () -> {
                db.update(
                    "UPDATE storage_contribution SET status='APPROVED',last_heartbeat=? WHERE id=?",
                    System.currentTimeMillis(),
                    id);
                db.update(
                    "UPDATE storage_node SET enabled=TRUE,management='ACTIVE' WHERE backend=?",
                    backend);
                return null;
              });
          teams.audit(team, a.getName(), "CONTRIBUTION_APPROVED", id);
          return Map.of("backend", backend, "status", "APPROVED");
        });
  }

  @PostMapping("/teams/{team}/contributions/{id}/stop")
  public void stop(Authentication a, @PathVariable String team, @PathVariable String id) {
    teams.with(
        a.getName(),
        team,
        0,
        w -> {
          var c = db.one("SELECT * FROM storage_contribution WHERE id=? AND team_id=?", id, team);
          if (!a.getName().equals(s(c, "contributor_id"))
              && TeamService.rank(s(teams.member(a.getName(), team), "file_role")) < 3)
            throw new Problem(403, "TEAM_FORBIDDEN", "仅贡献者或团队管理员可停用");
          nodes.locked(
              () -> {
                db.update("UPDATE storage_contribution SET status='STOPPED' WHERE id=?", id);
                if (c.get("backend") != null)
                  db.update(
                      "UPDATE storage_node SET management='WRITE_PAUSED' WHERE backend=?",
                      c.get("backend"));
                return null;
              });
          teams.audit(team, a.getName(), "CONTRIBUTION_STOPPED", id);
          return null;
        });
  }

  @PostMapping("/public/nodes/{id}/heartbeat")
  public Object heartbeat(@PathVariable String id, @RequestHeader("X-Node-Token") String token) {
    if (token.length() > 200) throw new Problem(401, "NODE_UNAUTHORIZED", "节点认证失败");
    var rows =
        db.list(
            "SELECT c.* FROM storage_contribution c JOIN team_member m ON m.team_id=c.team_id AND"
                + " m.user_id=c.contributor_id JOIN sys_user u ON u.id=m.user_id WHERE c.id=? AND"
                + " c.token_hash=? AND c.status='APPROVED' AND m.contributor=TRUE AND"
                + " u.enabled=TRUE",
            id,
            Util.hash(token));
    if (rows.isEmpty()) throw new Problem(401, "NODE_UNAUTHORIZED", "节点认证失败或贡献已暂停");
    db.update(
        "UPDATE storage_contribution SET last_heartbeat=? WHERE id=? AND status='APPROVED'",
        System.currentTimeMillis(),
        id);
    return Map.of("accepted", true);
  }
}
