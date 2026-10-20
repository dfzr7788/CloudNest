package com.cloudnest.sync;

import com.cloudnest.common.Problem;
import com.cloudnest.file.ContentService;
import com.cloudnest.upload.UploadService;
import com.fasterxml.jackson.databind.*;
import jakarta.servlet.http.*;
import java.io.IOException;
import java.util.*;
import org.springframework.http.*;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/api/sync")
public class SyncController {
  private final SyncDevices devices;
  private final SyncService sync;
  private final UploadService uploads;
  private final ContentService content;
  private final ObjectMapper json;

  public SyncController(
      SyncDevices devices,
      SyncService sync,
      UploadService uploads,
      ContentService content,
      ObjectMapper json) {
    this.devices = devices;
    this.sync = sync;
    this.uploads = uploads;
    this.content = content;
    this.json = json;
  }

  @GetMapping("/available")
  public ResponseEntity<?> available() {
    return response(Map.of("enabled", devices.enabled(), "protocol", 1, "development", true));
  }

  @GetMapping("/devices")
  public ResponseEntity<?> devices(Authentication a) {
    return response(devices.list(a.getName()));
  }

  @PostMapping("/devices")
  public ResponseEntity<?> issue(Authentication a, HttpServletRequest request) throws IOException {
    devices.require();
    var value = body(request);
    if (value.size() != 1 || !value.path("name").isTextual())
      throw Problem.invalid("Expected device name only");
    return response(devices.issue(a.getName(), value.path("name").asText()));
  }

  @DeleteMapping("/devices/{id}")
  public void revoke(Authentication a, @PathVariable String id) {
    devices.revoke(a.getName(), id);
  }

  @GetMapping("/client/bootstrap")
  public ResponseEntity<?> bootstrap(Authentication a) {
    return response(sync.bootstrap(devices.identity(a)));
  }

  @PostMapping("/client/logout")
  public void logout(Authentication a) {
    var identity = devices.identity(a);
    devices.revoke(identity.userId(), identity.deviceId());
  }

  @GetMapping("/client/changes")
  public ResponseEntity<?> changes(Authentication a, @RequestParam String cursor) {
    return response(sync.changes(devices.identity(a), cursor));
  }

  @PostMapping("/client/operations")
  public ResponseEntity<?> operate(Authentication a, HttpServletRequest request)
      throws IOException {
    var identity = devices.identity(a);
    return response(sync.operate(identity, body(request)));
  }

  @GetMapping("/client/uploads/{id}")
  public ResponseEntity<?> upload(Authentication a, @PathVariable String id) {
    var identity = devices.identity(a);
    sync.requireUpload(identity, id);
    return response(uploads.status(identity.userId(), id));
  }

  @PutMapping("/client/uploads/{id}/parts/{number}")
  public void part(
      Authentication a,
      @PathVariable String id,
      @PathVariable int number,
      HttpServletRequest request)
      throws IOException {
    var identity = devices.identity(a);
    sync.requireUpload(identity, id);
    uploads.part(identity.userId(), id, number, request.getInputStream());
  }

  @PostMapping("/client/uploads/{id}/complete")
  public ResponseEntity<?> complete(Authentication a, @PathVariable String id) throws IOException {
    var identity = devices.identity(a);
    sync.requireUpload(identity, id);
    return response(uploads.complete(identity.userId(), id, () -> devices.check(identity)));
  }

  @DeleteMapping("/client/uploads/{id}")
  public void cancelUpload(Authentication a, @PathVariable String id) {
    var identity = devices.identity(a);
    sync.requireUpload(identity, id);
    uploads.cancel(identity.userId(), id);
  }

  @GetMapping("/client/files/{id}/download")
  public void download(
      Authentication a,
      @PathVariable String id,
      HttpServletRequest request,
      HttpServletResponse response)
      throws IOException {
    var identity = devices.identity(a);
    content.send(identity.userId(), id, false, request, response);
  }

  private JsonNode body(HttpServletRequest request) throws IOException {
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
      if (value == null || !value.isObject()) throw Problem.invalid("Expected a JSON object");
      return value;
    } catch (com.fasterxml.jackson.core.JsonProcessingException e) {
      throw Problem.invalid("Expected a single JSON object");
    }
  }

  private ResponseEntity<?> response(Object value) {
    return ResponseEntity.ok().cacheControl(CacheControl.noStore()).body(value);
  }
}
