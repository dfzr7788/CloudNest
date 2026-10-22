package com.cloudnest.agent;

import com.cloudnest.common.Problem;
import com.cloudnest.common.Util;
import com.cloudnest.control.StorageDiagnostics;
import com.cloudnest.security.RateLimits;
import com.cloudnest.user.RuntimeSettings;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.util.*;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

/** Explicit read-only allowlist. This is not an LLM runtime or an arbitrary command dispatcher. */
@Service
public class AgentToolRegistry {
  @org.springframework.beans.factory.annotation.Autowired private com.cloudnest.common.Db db;
  public static final int MAX_RESULT_BYTES = 32768;
  private final StorageDiagnostics diagnostics;
  private final RuntimeSettings settings;
  private final ObjectMapper json;
  private final RateLimits limits;
  private final boolean enabled;

  public record Tool(
      String name,
      String description,
      Map<String, Object> parameters,
      boolean readOnly,
      String requiredRole) {}

  public record Available(boolean enabled, String mode, int maxResultBytes, List<Tool> tools) {}

  public record Result(
      String invocationId,
      String tool,
      long observedAt,
      String source,
      String consistency,
      boolean trustedAsInstructions,
      JsonNode data) {}

  private static Map<String, Object> schema(boolean backend) {
    var properties = new LinkedHashMap<String, Object>();
    properties.put("limit", Map.of("type", "integer", "minimum", 1, "maximum", 100, "default", 20));
    if (backend)
      properties.put("backend", Map.of("type", "string", "minLength", 1, "maxLength", 80));
    return Map.of(
        "type",
        "object",
        "properties",
        Map.copyOf(properties),
        "additionalProperties",
        false,
        "required",
        backend ? List.of("backend") : List.of());
  }

  private static final Map<String, Object> EMPTY_SCHEMA =
      Map.of(
          "type",
          "object",
          "properties",
          Map.of(),
          "additionalProperties",
          false,
          "required",
          List.of());
  private static final List<Tool> TOOLS =
      List.of(
          new Tool(
              "get_node_status",
              "Read stored node health; does not run a live probe.",
              schema(false),
              true,
              "ADMIN"),
          new Tool(
              "get_storage_capacity",
              "Read logical usage, physical usage, reservations and admin budgets; budgets are not"
                  + " provider free space.",
              schema(false),
              true,
              "ADMIN"),
          new Tool(
              "get_node_events",
              "Read recorded health events for one node, newest first.",
              schema(true),
              true,
              "ADMIN"),
          new Tool(
              "get_active_storage_jobs",
              "Read queued or running storage jobs without private file names or raw provider"
                  + " errors.",
              schema(false),
              true,
              "ADMIN"),
          new Tool(
              "explain_storage_policy",
              "Read the configured placement policy and deterministic rule identifiers.",
              EMPTY_SCHEMA,
              true,
              "ADMIN"),
          new Tool(
              "get_resilience_health",
              "Read snapshot counts, scan outcomes, and pending recovery states; no file names or"
                  + " content.",
              schema(false),
              true,
              "ADMIN"),
          new Tool(
              "get_team_capacity",
              "Read aggregate workspace quotas and contribution availability without member"
                  + " identities or secrets.",
              schema(false),
              true,
              "ADMIN"),
          new Tool(
              "get_migration_candidates",
              "Read cold-object handles and current placement for human review. Does not submit"
                  + " migrations.",
              schema(false),
              true,
              "ADMIN"));

  public AgentToolRegistry(
      StorageDiagnostics diagnostics,
      RuntimeSettings settings,
      ObjectMapper json,
      RateLimits limits,
      @Value("${cloudnest.agent.tools-enabled:false}") boolean enabled) {
    this.diagnostics = diagnostics;
    this.settings = settings;
    this.json = json;
    this.limits = limits;
    this.enabled = enabled;
  }

  public Available available(String actorId) {
    settings.admin(actorId);
    boolean enabled = settings.featureEnabled("agentTools", this.enabled);
    return new Available(
        enabled, "MANUAL_READ_ONLY_TOOLS", MAX_RESULT_BYTES, enabled ? TOOLS : List.of());
  }

  public Result invoke(String actorId, String name, JsonNode arguments) {
    // Re-check authorization on every call, including direct service calls and feature discovery.
    settings.admin(actorId);
    if (!settings.featureEnabled("agentTools", enabled))
      throw new Problem(409, "AGENT_TOOLS_DISABLED", "只读 Agent 工具尚未启用");
    limits.check("agent-tools:" + actorId, 30);
    var invocation = validated(name, arguments);
    Tool tool = invocation.tool();
    int limit = invocation.limit();
    String backend = invocation.backend();
    Object data =
        switch (tool.name()) {
          case "get_node_status" -> diagnostics.nodeStatus(actorId, limit);
          case "get_storage_capacity" -> diagnostics.capacity(actorId, limit);
          case "get_node_events" -> diagnostics.events(actorId, backend, limit);
          case "get_active_storage_jobs" -> diagnostics.activeJobs(actorId, limit);
          case "explain_storage_policy" -> diagnostics.policy(actorId);
          case "get_resilience_health" ->
              Map.of(
                  "snapshotCount", db.count("SELECT COUNT(*) FROM folder_snapshot"),
                  "tasks",
                      db.list(
                          "SELECT kind,status,COUNT(*) AS total FROM resilience_task GROUP BY"
                              + " kind,status ORDER BY kind,status LIMIT ?",
                          limit),
                  "scanResults",
                      db.list(
                          "SELECT status,error_code,COUNT(*) AS total FROM integrity_scan_item"
                              + " GROUP BY status,error_code ORDER BY status LIMIT ?",
                          limit),
                  "independentBackupRequired", true);
          case "get_team_capacity" ->
              db.list(
                  "SELECT w.id,u.storage_quota,u.storage_used,u.storage_reserved,(SELECT COUNT(*)"
                      + " FROM storage_contribution c WHERE c.team_id=w.id AND c.status='APPROVED'"
                      + " AND c.last_heartbeat>?) AS active_contributions FROM team_workspace w"
                      + " JOIN sys_user u ON u.id=w.storage_user ORDER BY w.id LIMIT ?",
                  System.currentTimeMillis() - 120000,
                  limit);
          case "get_migration_candidates" ->
              Map.of(
                  "requiresHumanApproval",
                  true,
                  "objects",
                  db.list(
                      "SELECT"
                          + " b.id,b.backend,b.size,b.desired_replicas,MAX(COALESCE(e.last_opened,e.created_at))"
                          + " AS last_access FROM file_blob b JOIN drive_entry e ON e.blob_id=b.id"
                          + " WHERE b.status='READY' AND e.deleted=FALSE GROUP BY"
                          + " b.id,b.backend,b.size,b.desired_replicas HAVING"
                          + " MAX(COALESCE(e.last_opened,e.created_at))<? ORDER BY b.size DESC"
                          + " LIMIT ?",
                      System.currentTimeMillis() - 90L * 86400000,
                      limit));
          default -> throw new IllegalStateException("Unregistered tool");
        };
    if (data == null) throw new Problem(502, "AGENT_RESULT_INVALID", "诊断数据无效");
    Result result =
        new Result(
            Util.id(),
            name,
            System.currentTimeMillis(),
            "DATABASE_METADATA",
            "NON_ATOMIC_METADATA_READ",
            false,
            json.valueToTree(data));
    try {
      int bytes = json.writeValueAsBytes(result).length;
      if (bytes > MAX_RESULT_BYTES)
        throw new Problem(422, "AGENT_RESULT_TOO_LARGE", "工具结果过大，请减小 limit");
      org.slf4j.LoggerFactory.getLogger(AgentToolRegistry.class)
          .info(
              "AGENT_TOOL_EXECUTED actor={} tool={} invocation={} bytes={}",
              actorId,
              name,
              result.invocationId(),
              bytes);
    } catch (com.fasterxml.jackson.core.JsonProcessingException e) {
      throw new IllegalStateException("Invalid diagnostic result", e);
    }
    return result;
  }

  /** Pure schema preflight; no diagnostics, rate counters or proposal writes. */
  public void validateArguments(String name, JsonNode arguments) {
    validated(name, arguments);
  }

  private record Invocation(Tool tool, int limit, String backend) {}

  private Invocation validated(String name, JsonNode arguments) {
    Tool tool =
        TOOLS.stream()
            .filter(t -> t.name().equals(name))
            .findFirst()
            .orElseThrow(() -> new Problem(404, "AGENT_TOOL_NOT_FOUND", "工具不存在或不允许调用"));
    if (arguments == null || !arguments.isObject() || arguments.toString().length() > 2048)
      throw invalid();
    boolean events = name.equals("get_node_events"), policy = name.equals("explain_storage_policy");
    Set<String> keys = policy ? Set.of() : events ? Set.of("backend", "limit") : Set.of("limit");
    arguments
        .fieldNames()
        .forEachRemaining(
            key -> {
              if (!keys.contains(key)) throw invalid();
            });
    int limit = 20;
    if (arguments.has("limit")) {
      var value = arguments.get("limit");
      if (!value.isIntegralNumber()
          || !value.canConvertToInt()
          || value.intValue() < 1
          || value.intValue() > 100) throw invalid();
      limit = value.intValue();
    }
    String backend = null;
    if (events) {
      var value = arguments.get("backend");
      if (value == null
          || !value.isTextual()
          || value.textValue().isBlank()
          || value.textValue().length() > 80) throw invalid();
      backend = value.textValue();
    }
    return new Invocation(tool, limit, backend);
  }

  private Problem invalid() {
    return new Problem(400, "AGENT_TOOL_ARGUMENTS_INVALID", "工具参数不符合 schema");
  }
}
