package com.cloudnest.agent;

import com.cloudnest.ai.AiTaskService;
import com.cloudnest.common.Problem;
import com.fasterxml.jackson.databind.*;
import jakarta.servlet.http.HttpServletRequest;
import java.util.*;
import org.springframework.http.*;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/api/admin/agent/runs")
public class AgentRunController {
  private final AgentExecutor agent;
  private final AiTaskService tasks;
  private final ObjectMapper json;

  public AgentRunController(AgentExecutor agent, AiTaskService tasks, ObjectMapper json) {
    this.agent = agent;
    this.tasks = tasks;
    this.json = json;
  }

  @GetMapping("/available")
  public ResponseEntity<?> available(Authentication a) {
    return response(agent.available(a.getName()));
  }

  @GetMapping
  public ResponseEntity<?> list(Authentication a) {
    return response(tasks.agentRuns(a.getName()));
  }

  @PostMapping
  public ResponseEntity<?> create(
      Authentication a, @RequestHeader("Idempotency-Key") String key, HttpServletRequest request)
      throws java.io.IOException {
    agent.require(a.getName());
    try {
      if (request.getContentType() == null
          || !MediaType.APPLICATION_JSON.isCompatibleWith(
              MediaType.parseMediaType(request.getContentType())))
        throw new IllegalArgumentException();
    } catch (IllegalArgumentException e) {
      throw new Problem(415, "INVALID_INPUT", "Expected application/json");
    }
    byte[] bytes = request.getInputStream().readNBytes(8193);
    if (bytes.length > 8192) throw new Problem(413, "INVALID_INPUT", "Request exceeds 8 KiB");
    JsonNode input;
    try {
      input =
          json.reader()
              .with(
                  DeserializationFeature.FAIL_ON_READING_DUP_TREE_KEY,
                  DeserializationFeature.FAIL_ON_TRAILING_TOKENS)
              .readTree(bytes);
    } catch (java.io.IOException e) {
      throw Problem.invalid("Expected a single JSON object");
    }
    if (input == null
        || !input.isObject()
        || !input.path("modelId").isTextual()
        || input.path("modelId").asText().length() > 36) throw Problem.invalid("Invalid modelId");
    Map<String, Object> values =
        json.convertValue(input, new com.fasterxml.jackson.core.type.TypeReference<>() {});
    return response(
        tasks.create(a.getName(), "AGENT", null, input.path("modelId").asText(), key, values));
  }

  private Map<String, Object> run(String uid, String id) {
    agent.require(uid);
    var task = tasks.get(uid, id);
    if (!"AGENT".equals(task.get("kind"))) throw Problem.missing();
    return task;
  }

  @GetMapping("/{id}")
  public ResponseEntity<?> get(Authentication a, @PathVariable String id) {
    return response(run(a.getName(), id));
  }

  @PostMapping("/{id}/cancel")
  public ResponseEntity<?> cancel(Authentication a, @PathVariable String id) {
    run(a.getName(), id);
    return response(tasks.cancel(a.getName(), id));
  }

  private ResponseEntity<?> response(Object value) {
    return ResponseEntity.ok().cacheControl(CacheControl.noStore()).body(value);
  }
}
