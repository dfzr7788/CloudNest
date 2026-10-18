package com.cloudnest.ai;

import com.cloudnest.common.Problem;
import java.util.*;
import org.springframework.http.*;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/api/ai")
public class AiController {
  private final AiConnectionService configs;
  private final AiTaskService tasks;

  public AiController(AiConnectionService configs, AiTaskService tasks) {
    this.configs = configs;
    this.tasks = tasks;
  }

  @GetMapping("/capabilities")
  public Object capabilities() {
    return Map.of(
        "enabled",
        configs.enabled(),
        "features",
        List.of(
            "SUMMARY",
            "TAGS",
            "DOCUMENT_QA",
            "EMBEDDING_TEST",
            "KNOWLEDGE_BASE",
            "SEMANTIC_SEARCH",
            "VERSION_DIFF",
            "ORGANIZE"),
        "protocol",
        "OPENAI_COMPATIBLE_CHAT",
        "localEndpoints",
        false,
        "dailyCalls",
        100,
        "maxTaskCalls",
        8,
        "noticeVersion",
        1);
  }

  @GetMapping("/connections")
  public Object connections(Authentication a) {
    return configs.connections(a.getName());
  }

  @PostMapping("/connections")
  public Object createConnection(Authentication a, @RequestBody Map<String, Object> in) {
    return configs.saveConnection(a.getName(), null, in);
  }

  @PatchMapping("/connections/{id}")
  public Object updateConnection(
      Authentication a, @PathVariable String id, @RequestBody Map<String, Object> in) {
    return configs.saveConnection(a.getName(), id, in);
  }

  @DeleteMapping("/connections/{id}")
  public void deleteConnection(Authentication a, @PathVariable String id) {
    configs.revoke(a.getName(), id, true);
  }

  @DeleteMapping("/connections/{id}/credential")
  public void revoke(Authentication a, @PathVariable String id) {
    configs.revoke(a.getName(), id, false);
  }

  @GetMapping("/models")
  public Object models(Authentication a) {
    return configs.models(a.getName());
  }

  @PostMapping("/models")
  public Object createModel(Authentication a, @RequestBody Map<String, Object> in) {
    return configs.saveModel(a.getName(), null, in);
  }

  @PatchMapping("/models/{id}")
  public Object updateModel(
      Authentication a, @PathVariable String id, @RequestBody Map<String, Object> in) {
    return configs.saveModel(a.getName(), id, in);
  }

  @DeleteMapping("/models/{id}")
  public void deleteModel(Authentication a, @PathVariable String id) {
    configs.deleteModel(a.getName(), id);
  }

  @GetMapping("/preferences")
  public Object preferences(Authentication a) {
    return configs.preference(a.getName());
  }

  @PutMapping("/preferences")
  public void preferences(Authentication a, @RequestBody Map<String, Object> in) {
    Object id = in.get("defaultChatModelId");
    if (id != null && !(id instanceof String)) throw Problem.invalid("默认模型 ID 无效");
    configs.preference(a.getName(), (String) id);
  }

  @PostMapping("/consents")
  public void consent(Authentication a, @RequestBody Map<String, Object> in) {
    configs.consent(
        a.getName(),
        AiConnectionService.text(in, "connectionId", 36),
        AiConnectionService.text(in, "destinationFingerprint", 64));
  }

  @PostMapping("/models/{id}/test")
  public ResponseEntity<?> test(
      Authentication a,
      @PathVariable String id,
      @RequestHeader(value = "Idempotency-Key", required = false) String key) {
    return ResponseEntity.accepted()
        .body(tasks.create(a.getName(), "TEST", null, id, key, Map.of()));
  }

  @PostMapping("/models/{id}/embedding-test")
  public ResponseEntity<?> embeddingTest(
      Authentication a,
      @PathVariable String id,
      @RequestHeader(value = "Idempotency-Key", required = false) String key) {
    return ResponseEntity.accepted()
        .body(tasks.create(a.getName(), "EMBEDDING_TEST", null, id, key, Map.of()));
  }

  @GetMapping("/files/{id}/analysis")
  public Object analysis(
      Authentication a,
      @PathVariable String id,
      @RequestParam String modelId,
      @RequestParam(defaultValue = "120000") int rangeChars) {
    if (rangeChars < 100 || rangeChars > 262144) throw Problem.invalid("分析范围应为 100–262144 字符");
    return tasks.analysis(a.getName(), id, modelId, rangeChars);
  }

  @PostMapping("/files/{id}/summaries")
  public ResponseEntity<?> summary(
      Authentication a,
      @PathVariable String id,
      @RequestHeader(value = "Idempotency-Key", required = false) String key,
      @RequestBody Map<String, Object> in) {
    return create(a, id, key, in, "SUMMARY");
  }

  @PostMapping("/files/{id}/tag-suggestions")
  public ResponseEntity<?> tags(
      Authentication a,
      @PathVariable String id,
      @RequestHeader(value = "Idempotency-Key", required = false) String key,
      @RequestBody Map<String, Object> in) {
    return create(a, id, key, in, "TAGS");
  }

  private ResponseEntity<?> create(
      Authentication a, String id, String key, Map<String, Object> in, String kind) {
    return ResponseEntity.accepted()
        .body(
            tasks.create(
                a.getName(), kind, id, AiConnectionService.text(in, "modelId", 36), key, in));
  }

  @GetMapping("/tasks/{id}")
  public Object task(Authentication a, @PathVariable String id) {
    return tasks.get(a.getName(), id);
  }

  @GetMapping("/tasks")
  public Object tasks(
      Authentication a,
      @RequestParam(required = false) String fileId,
      @RequestParam(defaultValue = "1") int page,
      @RequestParam(defaultValue = "false") boolean analysisOnly) {
    return tasks.list(a.getName(), fileId, page, analysisOnly);
  }

  @PostMapping("/tasks/{id}/cancel")
  public ResponseEntity<?> cancel(Authentication a, @PathVariable String id) {
    return ResponseEntity.accepted().body(tasks.cancel(a.getName(), id));
  }

  @DeleteMapping("/artifacts/{id}")
  public void delete(Authentication a, @PathVariable String id) {
    tasks.deleteArtifact(a.getName(), id);
  }

  @GetMapping("/usage")
  public Object usage(
      Authentication a,
      @RequestParam(defaultValue = "30") int days,
      @RequestParam(defaultValue = "1") int page) {
    return tasks.usage(a.getName(), days, page);
  }
}
