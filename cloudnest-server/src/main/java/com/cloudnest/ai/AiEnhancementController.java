package com.cloudnest.ai;

import java.util.*;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/api/ai")
public class AiEnhancementController {
  private final AiEnhancementService enhancements;
  private final AiConnectionService configs;
  private final AiTaskService tasks;

  public AiEnhancementController(
      AiEnhancementService enhancements, AiConnectionService configs, AiTaskService tasks) {
    this.enhancements = enhancements;
    this.configs = configs;
    this.tasks = tasks;
  }

  @GetMapping("/files/{id}/change-preview")
  public Object preview(
      Authentication a,
      @PathVariable String id,
      @RequestParam String fromVersion,
      @RequestParam String toVersion) {
    configs.requireEnabled();
    return enhancements.diffPreview(a.getName(), id, fromVersion, toVersion);
  }

  @PostMapping("/files/{id}/change-summaries")
  public ResponseEntity<?> diff(
      Authentication a,
      @PathVariable String id,
      @RequestHeader(value = "Idempotency-Key", required = false) String key,
      @RequestBody Map<String, Object> in) {
    return ResponseEntity.accepted()
        .body(
            tasks.create(
                a.getName(),
                "VERSION_DIFF",
                id,
                AiConnectionService.text(in, "modelId", 36),
                key,
                in));
  }

  @PostMapping("/organization-preview")
  public Object previewOrganization(Authentication a, @RequestBody Map<String, Object> in) {
    configs.requireEnabled();
    return enhancements.organizationPreview(a.getName(), in);
  }

  @PostMapping("/organization-jobs")
  public ResponseEntity<?> organize(
      Authentication a,
      @RequestHeader(value = "Idempotency-Key", required = false) String key,
      @RequestBody Map<String, Object> in) {
    return ResponseEntity.accepted()
        .body(
            tasks.create(
                a.getName(),
                "ORGANIZE",
                null,
                AiConnectionService.text(in, "modelId", 36),
                key,
                in));
  }

  @PostMapping("/tasks/{id}/apply-organization")
  public Object apply(
      Authentication a, @PathVariable String id, @RequestBody Map<String, Object> in) {
    return enhancements.apply(a.getName(), id, in);
  }
}
