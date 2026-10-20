package com.cloudnest;

import static com.cloudnest.common.Db.*;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;

import com.cloudnest.agent.AgentToolRegistry;
import com.cloudnest.ai.ModelGateway;
import com.cloudnest.common.*;
import com.cloudnest.control.StorageDiagnostics;
import com.cloudnest.security.RateLimits;
import com.cloudnest.user.RuntimeSettings;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.nio.file.*;
import java.util.*;
import org.junit.jupiter.api.*;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

@SpringBootTest(
    properties = {
      "spring.datasource.url=jdbc:h2:mem:agent-tools;MODE=MySQL;DATABASE_TO_LOWER=TRUE;DB_CLOSE_DELAY=-1",
      "cloudnest.agent.tools-enabled=true",
      "cloudnest.worker.enabled=false",
      "cloudnest.index-enabled=false"
    })
@AutoConfigureMockMvc
class AgentToolsTest {
  static final Path data;

  static {
    try {
      data = Files.createTempDirectory("cloudnest-agent-tools-");
    } catch (java.io.IOException e) {
      throw new ExceptionInInitializerError(e);
    }
  }

  @DynamicPropertySource
  static void properties(DynamicPropertyRegistry r) {
    r.add("cloudnest.data-dir", data::toString);
  }

  @Autowired Db db;
  @Autowired AgentToolRegistry tools;
  @Autowired StorageDiagnostics diagnostics;
  @Autowired RuntimeSettings settings;
  @Autowired RateLimits limits;
  @Autowired ObjectMapper json;
  @Autowired MockMvc mvc;
  @MockitoBean ModelGateway gateway;
  String admin, ordinary;

  String createUser(boolean administrator) {
    String id = Util.id();
    db.update(
        "INSERT INTO"
            + " sys_user(id,username,password_hash,nickname,root_id,storage_quota,administrator,created_at)"
            + " VALUES(?,?,?,?,?,1000000,?,?)",
        id,
        id,
        "test",
        "test",
        Util.id(),
        administrator,
        System.currentTimeMillis());
    return id;
  }

  @BeforeEach
  void setup() {
    admin = createUser(true);
    ordinary = createUser(false);
    clearInvocations(gateway);
  }

  @Test
  void exposesOnlyFiveStrictReadOnlyTools() {
    var available = tools.available(admin);
    assertTrue(available.enabled());
    assertEquals(5, available.tools().size());
    assertEquals(
        Set.of(
            "get_node_status",
            "get_storage_capacity",
            "get_node_events",
            "get_active_storage_jobs",
            "explain_storage_policy"),
        new HashSet<>(available.tools().stream().map(AgentToolRegistry.Tool::name).toList()));
    available
        .tools()
        .forEach(
            tool -> {
              assertTrue(tool.readOnly());
              assertEquals("ADMIN", tool.requiredRole());
              assertEquals(false, tool.parameters().get("additionalProperties"));
            });
  }

  @Test
  void toolsReadRecordedFactsAndNeverCallModelsOrWriteStorage() throws Exception {
    var nodesBefore = db.list("SELECT * FROM storage_node ORDER BY backend");
    var healthBefore = db.list("SELECT * FROM storage_node_health ORDER BY backend");
    long reservations = db.count("SELECT COUNT(*) FROM storage_reservation");
    db.update(
        "INSERT INTO storage_node_event(id,backend,state,created_at) VALUES(?,?,?,?)",
        Util.id(),
        "local",
        "OFFLINE",
        123L);
    for (var tool : tools.available(admin).tools()) {
      var args =
          json.readTree(tool.name().equals("get_node_events") ? "{\"backend\":\"local\"}" : "{}");
      var result = tools.invoke(admin, tool.name(), args);
      assertEquals(tool.name(), result.tool());
      assertEquals("DATABASE_METADATA", result.source());
      assertFalse(result.trustedAsInstructions());
      assertTrue(result.observedAt() > 0);
      assertTrue(json.writeValueAsBytes(result).length <= AgentToolRegistry.MAX_RESULT_BYTES);
      var serialized = json.writeValueAsString(result);
      assertFalse(serialized.contains("secret_key"));
      assertFalse(serialized.contains("object_key"));
      assertFalse(serialized.contains("password_hash"));
    }
    var events = tools.invoke(admin, "get_node_events", json.readTree("{\"backend\":\"local\"}"));
    assertTrue(events.data().get("items").toString().contains("OFFLINE"));
    assertEquals(nodesBefore, db.list("SELECT * FROM storage_node ORDER BY backend"));
    assertEquals(healthBefore, db.list("SELECT * FROM storage_node_health ORDER BY backend"));
    assertEquals(reservations, db.count("SELECT COUNT(*) FROM storage_reservation"));
    verifyNoInteractions(gateway);
  }

  @Test
  void everyEntryPointRechecksAdminAndRevocation() throws Exception {
    assertEquals(403, assertThrows(Problem.class, () -> tools.available(ordinary)).status);
    assertEquals(
        403,
        assertThrows(
                Problem.class,
                () -> tools.invoke(ordinary, "get_node_status", json.createObjectNode()))
            .status);
    assertEquals(
        403, assertThrows(Problem.class, () -> diagnostics.nodeStatus(ordinary, 20)).status);
    assertEquals(403, assertThrows(Problem.class, () -> diagnostics.capacity(ordinary, 20)).status);
    assertEquals(
        403, assertThrows(Problem.class, () -> diagnostics.events(ordinary, "local", 20)).status);
    assertEquals(
        403, assertThrows(Problem.class, () -> diagnostics.activeJobs(ordinary, 20)).status);
    assertEquals(403, assertThrows(Problem.class, () -> diagnostics.policy(ordinary)).status);
    tools.invoke(admin, "get_node_status", json.createObjectNode());
    db.update("UPDATE sys_user SET enabled=FALSE WHERE id=?", admin);
    assertEquals(
        403,
        assertThrows(
                Problem.class,
                () -> tools.invoke(admin, "get_node_status", json.createObjectNode()))
            .status);
  }

  @Test
  void rejectsUnknownToolsAndMalformedArgumentsBeforeQuerying() throws Exception {
    assertEquals(
        "AGENT_TOOL_NOT_FOUND",
        assertThrows(Problem.class, () -> tools.invoke(admin, "run_sql", json.createObjectNode()))
            .code);
    for (String args :
        List.of(
            "[]",
            "null",
            "{\"userId\":\"someone\"}",
            "{\"limit\":0}",
            "{\"limit\":101}",
            "{\"limit\":1.5}",
            "{\"limit\":\"2\"}",
            "{\"limit\":null}",
            "{\"limit\":9999999999999}")) {
      var tree = json.readTree(args);
      assertEquals(
          "AGENT_TOOL_ARGUMENTS_INVALID",
          assertThrows(Problem.class, () -> tools.invoke(admin, "get_node_status", tree)).code);
    }
    for (String args :
        List.of("{}", "{\"backend\":null}", "{\"backend\":4}", "{\"backend\":\" \"}")) {
      var tree = json.readTree(args);
      assertEquals(
          400,
          assertThrows(Problem.class, () -> tools.invoke(admin, "get_node_events", tree)).status);
    }
    assertEquals(
        400,
        assertThrows(
                Problem.class,
                () -> tools.invoke(admin, "explain_storage_policy", json.readTree("{\"limit\":1}")))
            .status);
    assertEquals(
        404,
        assertThrows(
                Problem.class,
                () ->
                    tools.invoke(
                        admin, "get_node_events", json.readTree("{\"backend\":\"unknown\"}")))
            .status);
  }

  @Test
  void reportsTruncationUnknownBudgetsAndPolicyRules() throws Exception {
    var status = tools.invoke(admin, "get_node_status", json.readTree("{\"limit\":1}"));
    assertEquals(1, status.data().get("items").size());
    assertTrue(status.data().get("hasMore").asBoolean());
    db.update("UPDATE storage_node SET budget_bytes=0 WHERE backend='local'");
    var capacity = diagnostics.capacity(admin, 100);
    assertNull(
        capacity.nodes().items().stream()
            .filter(n -> n.backend().equals("local"))
            .findFirst()
            .orElseThrow()
            .remainingBudgetBytes());
    db.update("UPDATE storage_placement_policy SET mode='HIGH_AVAILABILITY' WHERE id=1");
    assertTrue(diagnostics.policy(admin).rules().contains("TWO_INDEPENDENT_FAILURE_DOMAINS"));
    db.update("UPDATE storage_placement_policy SET mode='MANUAL' WHERE id=1");
    assertFalse(diagnostics.policy(admin).rules().contains("TWO_INDEPENDENT_FAILURE_DOMAINS"));
    assertEquals(400, assertThrows(Problem.class, () -> diagnostics.nodeStatus(admin, 101)).status);
  }

  @Test
  void featureGateDoesNotExposeToolsOrBypassAdmin() {
    var disabled = new AgentToolRegistry(diagnostics, settings, json, limits, false);
    assertFalse(disabled.available(admin).enabled());
    assertTrue(disabled.available(admin).tools().isEmpty());
    assertEquals(
        "AGENT_TOOLS_DISABLED",
        assertThrows(
                Problem.class,
                () -> disabled.invoke(admin, "get_node_status", json.createObjectNode()))
            .code);
    assertEquals(
        403,
        assertThrows(
                Problem.class,
                () -> disabled.invoke(ordinary, "get_node_status", json.createObjectNode()))
            .status);
  }

  @Test
  void capsResultsAndInvocationRate() {
    var source = mock(StorageDiagnostics.class);
    when(source.nodeStatus(admin, 20))
        .thenReturn(
            new StorageDiagnostics.Slice<>(
                List.of(
                    new StorageDiagnostics.NodeHealth(
                        "local", "x".repeat(40000), true, "ACTIVE", "ONLINE", "a", 1, false)),
                false));
    var registry = new AgentToolRegistry(source, settings, json, limits, true);
    assertEquals(
        "AGENT_RESULT_TOO_LARGE",
        assertThrows(
                Problem.class,
                () -> registry.invoke(admin, "get_node_status", json.createObjectNode()))
            .code);
    for (int i = 0; i < 29; i++)
      tools.invoke(admin, "explain_storage_policy", json.createObjectNode());
    assertEquals(
        429,
        assertThrows(
                Problem.class,
                () -> tools.invoke(admin, "explain_storage_policy", json.createObjectNode()))
            .status);
  }

  @Test
  void missingDiagnosticResultsNeverReturnSuccess() {
    var source = mock(StorageDiagnostics.class);
    var registry = new AgentToolRegistry(source, settings, json, limits, true);
    assertEquals(
        "AGENT_RESULT_INVALID",
        assertThrows(
                Problem.class,
                () -> registry.invoke(admin, "get_node_status", json.createObjectNode()))
            .code);
  }

  @Test
  void httpRejectsOversizedDuplicateAndTrailingJsonBodies() throws Exception {
    for (String body : List.of("{\"limit\":1,\"limit\":2}", "{} {}", "{", "[]", "null", "")) {
      mvc.perform(
              post("/api/admin/agent/tools/get_node_status/invoke")
                  .with(user(admin))
                  .with(csrf())
                  .contentType(MediaType.APPLICATION_JSON)
                  .content(body))
          .andExpect(r -> assertEquals(400, r.getResponse().getStatus()));
    }
    mvc.perform(
            post("/api/admin/agent/tools/get_node_status/invoke")
                .with(user(admin))
                .with(csrf())
                .contentType(MediaType.APPLICATION_JSON)
                .content(" ".repeat(4097)))
        .andExpect(r -> assertEquals(413, r.getResponse().getStatus()));
    mvc.perform(
            post("/api/admin/agent/tools/get_node_status/invoke")
                .with(user(admin))
                .with(csrf())
                .contentType(MediaType.TEXT_PLAIN)
                .content("{}"))
        .andExpect(r -> assertEquals(415, r.getResponse().getStatus()));
  }

  @Test
  void httpRequiresAuthenticationAdminCsrfAndNoStore() throws Exception {
    mvc.perform(get("/api/admin/agent/tools/available"))
        .andExpect(r -> assertEquals(401, r.getResponse().getStatus()));
    mvc.perform(get("/api/admin/agent/tools/available").with(user(ordinary)))
        .andExpect(r -> assertEquals(403, r.getResponse().getStatus()));
    mvc.perform(
            post("/api/admin/agent/tools/get_node_status/invoke")
                .with(user(admin))
                .contentType(MediaType.APPLICATION_JSON)
                .content("{}"))
        .andExpect(r -> assertEquals(403, r.getResponse().getStatus()));
    mvc.perform(
            post("/api/admin/agent/tools/get_node_status/invoke")
                .with(user(admin))
                .with(csrf())
                .contentType(MediaType.APPLICATION_JSON)
                .content("{}"))
        .andExpect(
            r -> {
              assertEquals(200, r.getResponse().getStatus());
              assertEquals("no-store", r.getResponse().getHeader("Cache-Control"));
            });
    mvc.perform(get("/api/admin/agent/tools/available").with(user(admin)))
        .andExpect(r -> assertEquals(200, r.getResponse().getStatus()));
  }
}
