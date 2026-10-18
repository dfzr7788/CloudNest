package com.cloudnest.ai;

import java.util.*;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/api/ai/conversations")
public class AiConversationController {
  private final AiConversationService conversations;
  private final AiTaskService tasks;

  public AiConversationController(AiConversationService conversations, AiTaskService tasks) {
    this.conversations = conversations;
    this.tasks = tasks;
  }

  @PostMapping
  public Object create(Authentication a, @RequestBody Map<String, Object> in) {
    return conversations.create(a.getName(), in);
  }

  @GetMapping
  public Object list(
      Authentication a,
      @RequestParam(required = false) String fileId,
      @RequestParam(defaultValue = "1") int page) {
    return conversations.list(a.getName(), fileId, page);
  }

  @GetMapping("/{id}")
  public Object get(Authentication a, @PathVariable String id) {
    return conversations.get(a.getName(), id);
  }

  @DeleteMapping("/{id}")
  public void delete(Authentication a, @PathVariable String id) {
    conversations.delete(a.getName(), id);
  }

  @GetMapping("/{id}/citations/{citationId}")
  public Object citation(
      Authentication a, @PathVariable String id, @PathVariable String citationId) {
    return conversations.citation(a.getName(), id, citationId);
  }

  @PostMapping("/{id}/messages")
  public ResponseEntity<?> question(
      Authentication a,
      @PathVariable String id,
      @RequestHeader(value = "Idempotency-Key", required = false) String key,
      @RequestBody Map<String, Object> in) {
    if (!Set.of("question", "allowPartial").containsAll(in.keySet()))
      throw com.cloudnest.common.Problem.invalid("不支持的提问字段");
    var c = conversations.require(a.getName(), id, false);
    var input = new HashMap<>(in);
    input.put("conversationId", id);
    input.put("modelId", c.get("model_id"));
    input.put("expectedRevision", c.get("revision"));
    return ResponseEntity.accepted()
        .body(
            tasks.create(
                a.getName(),
                "QA",
                c.get("file_id").toString(),
                c.get("model_id").toString(),
                key,
                input));
  }
}
