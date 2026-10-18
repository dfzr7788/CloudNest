package com.cloudnest.ai;

import java.util.*;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/api/ai")
public class AiKnowledgeController {
  private final AiKnowledgeService knowledge;
  private final AiTaskService tasks;

  public AiKnowledgeController(AiKnowledgeService knowledge, AiTaskService tasks) {
    this.knowledge = knowledge;
    this.tasks = tasks;
  }

  @GetMapping("/knowledge-bases")
  public Object list(Authentication a) {
    return knowledge.list(a.getName());
  }

  @PostMapping("/knowledge-bases")
  public Object create(Authentication a, @RequestBody Map<String, Object> in) {
    return knowledge.save(a.getName(), null, in);
  }

  @PatchMapping("/knowledge-bases/{id}")
  public Object edit(
      Authentication a, @PathVariable String id, @RequestBody Map<String, Object> in) {
    return knowledge.save(a.getName(), id, in);
  }

  @DeleteMapping("/knowledge-bases/{id}")
  public void delete(Authentication a, @PathVariable String id) {
    knowledge.delete(a.getName(), id);
  }

  @GetMapping("/knowledge-bases/{id}/index-preview")
  public Object preview(Authentication a, @PathVariable String id) {
    return knowledge.preview(a.getName(), id);
  }

  @PostMapping("/knowledge-bases/{id}/index-jobs")
  public ResponseEntity<?> index(
      Authentication a,
      @PathVariable String id,
      @RequestHeader(value = "Idempotency-Key", required = false) String key,
      @RequestBody Map<String, Object> in) {
    return job(a, id, key, in, "KB_INDEX");
  }

  @PostMapping("/knowledge-bases/{id}/semantic-search")
  public ResponseEntity<?> search(
      Authentication a,
      @PathVariable String id,
      @RequestHeader(value = "Idempotency-Key", required = false) String key,
      @RequestBody Map<String, Object> in) {
    return job(a, id, key, in, "KB_SEARCH");
  }

  @PostMapping("/knowledge-bases/{id}/answers")
  public ResponseEntity<?> answer(
      Authentication a,
      @PathVariable String id,
      @RequestHeader(value = "Idempotency-Key", required = false) String key,
      @RequestBody Map<String, Object> in) {
    return job(a, id, key, in, "KB_ANSWER");
  }

  private ResponseEntity<?> job(
      Authentication a, String id, String key, Map<String, Object> in, String kind) {
    var body = new LinkedHashMap<>(in);
    body.put("knowledgeBaseId", id);
    return ResponseEntity.accepted()
        .body(
            tasks.create(
                a.getName(), kind, null, AiConnectionService.text(body, "modelId", 36), key, body));
  }

  @GetMapping("/knowledge-bases/{id}/sessions")
  public Object sessions(Authentication a, @PathVariable String id) {
    return knowledge.sessions(a.getName(), id);
  }

  @PostMapping("/knowledge-bases/{id}/sessions")
  public Object session(
      Authentication a, @PathVariable String id, @RequestBody Map<String, Object> in) {
    return knowledge.createSession(a.getName(), id, AiConnectionService.text(in, "modelId", 36));
  }

  @GetMapping("/knowledge-sessions/{id}")
  public Object history(Authentication a, @PathVariable String id) {
    var out = new ArrayList<Object>();
    for (var row : knowledge.sessionTasks(a.getName(), id)) {
      try {
        out.add(tasks.get(a.getName(), row.get("id").toString()));
      } catch (com.cloudnest.common.Problem ignored) {
      }
    }
    return out;
  }

  @DeleteMapping("/knowledge-sessions/{id}")
  public void deleteSession(Authentication a, @PathVariable String id) {
    knowledge.deleteSession(a.getName(), id);
  }
}
