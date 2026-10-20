package com.cloudnest.agent;

import com.cloudnest.common.Problem;
import com.fasterxml.jackson.databind.*;
import jakarta.servlet.http.HttpServletRequest;
import org.springframework.http.*;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/api/admin/agent/approvals")
public class AgentApprovalController {
  private final AgentApprovalService approvals;
  private final ObjectMapper json;

  public AgentApprovalController(AgentApprovalService approvals, ObjectMapper json) {
    this.approvals = approvals;
    this.json = json;
  }

  @GetMapping("/available")
  public ResponseEntity<?> available(Authentication a) {
    return response(java.util.Map.of("enabled", approvals.available(a.getName())));
  }

  @GetMapping
  public ResponseEntity<?> list(Authentication a) {
    return response(approvals.list(a.getName()));
  }

  @PostMapping("/simulate")
  public ResponseEntity<?> simulate(Authentication a, HttpServletRequest request)
      throws java.io.IOException {
    authorize(a);
    return response(approvals.simulate(a.getName(), body(request)));
  }

  @PostMapping
  public ResponseEntity<?> propose(
      Authentication a, @RequestHeader("Idempotency-Key") String key, HttpServletRequest request)
      throws java.io.IOException {
    authorize(a);
    return response(approvals.propose(a.getName(), key, body(request)));
  }

  @PostMapping("/{id}/approve")
  public ResponseEntity<?> approve(
      Authentication a, @PathVariable String id, HttpServletRequest request)
      throws java.io.IOException {
    authorize(a);
    return response(approvals.decide(a.getName(), id, hash(body(request)), true));
  }

  @PostMapping("/{id}/reject")
  public ResponseEntity<?> reject(
      Authentication a, @PathVariable String id, HttpServletRequest request)
      throws java.io.IOException {
    authorize(a);
    return response(approvals.decide(a.getName(), id, hash(body(request)), false));
  }

  private String hash(JsonNode in) {
    if (in.size() != 1
        || !in.path("proposalHash").isTextual()
        || !in.path("proposalHash").asText().matches("[a-f0-9]{64}"))
      throw Problem.invalid("Expected proposalHash");
    return in.path("proposalHash").asText();
  }

  private void authorize(Authentication a) {
    if (!approvals.available(a.getName()))
      throw new Problem(409, "AGENT_APPROVALS_DISABLED", "Storage action proposals disabled");
  }

  private JsonNode body(HttpServletRequest request) throws java.io.IOException {
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
    try {
      var value =
          json.reader()
              .with(
                  DeserializationFeature.FAIL_ON_READING_DUP_TREE_KEY,
                  DeserializationFeature.FAIL_ON_TRAILING_TOKENS)
              .readTree(bytes);
      if (value == null || !value.isObject()) throw Problem.invalid("Expected object");
      return value;
    } catch (com.fasterxml.jackson.core.JsonProcessingException e) {
      throw Problem.invalid("Invalid single JSON object");
    }
  }

  private ResponseEntity<?> response(Object value) {
    return ResponseEntity.ok().cacheControl(CacheControl.noStore()).body(value);
  }
}
