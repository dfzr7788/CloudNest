package com.cloudnest.upload;

import com.cloudnest.common.*;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.Valid;
import jakarta.validation.constraints.*;
import java.io.*;
import java.util.*;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.multipart.MultipartFile;

@RestController
@RequestMapping("/api")
public class UploadController {
  private final UploadService upload;

  public UploadController(UploadService upload) {
    this.upload = upload;
  }

  public record Init(
      String folderId, @NotBlank String name, @Min(0) long size, @NotBlank String sha256) {}

  @PostMapping("/uploads/init")
  public Map<String, Object> init(Authentication a, @Valid @RequestBody Init in) {
    return upload.init(a.getName(), in.folderId, in.name, in.size, in.sha256);
  }

  @GetMapping("/uploads")
  public List<Map<String, Object>> pending(Authentication a) {
    return upload.pending(a.getName());
  }

  @GetMapping("/uploads/{id}")
  public Map<String, Object> status(Authentication a, @PathVariable String id) {
    return upload.status(a.getName(), id);
  }

  @PutMapping("/uploads/{id}/parts/{number}")
  public void part(
      Authentication a, @PathVariable String id, @PathVariable int number, HttpServletRequest req)
      throws IOException {
    upload.part(a.getName(), id, number, req.getInputStream());
  }

  @PostMapping("/uploads/{id}/complete")
  public Map<String, Object> complete(Authentication a, @PathVariable String id)
      throws IOException {
    return upload.complete(a.getName(), id);
  }

  @DeleteMapping("/uploads/{id}")
  public void cancel(Authentication a, @PathVariable String id) {
    upload.cancel(a.getName(), id);
  }

  @PostMapping("/files/upload")
  public Map<String, Object> ordinary(
      Authentication a,
      @RequestParam(required = false) String folderId,
      @RequestParam("file") MultipartFile file)
      throws IOException {
    if (file.getSize() > 100 * 1024 * 1024L)
      throw new Problem(413, "TOO_LARGE", "普通上传限制 100 MiB，请使用分片上传");
    String hash;
    try (var in = file.getInputStream()) {
      hash = Util.hash(in);
    }
    var s = upload.init(a.getName(), folderId, file.getOriginalFilename(), file.getSize(), hash);
    String id = Db.s(s, "id");
    if ("COMPLETE".equals(s.get("status"))) return s;
    try (var in = file.getInputStream()) {
      long part = ((Number) s.get("partSize")).longValue();
      int count = ((Number) s.get("partCount")).intValue();
      for (int i = 0; i < count; i++) {
        long length = Math.min(part, file.getSize() - (long) i * part);
        var limited =
            new FilterInputStream(in) {
              long remaining = length;

              @Override
              public int read(byte[] b, int off, int len) throws IOException {
                if (remaining == 0) return -1;
                int n = super.read(b, off, (int) Math.min(len, remaining));
                if (n > 0) remaining -= n;
                return n;
              }

              @Override
              public int read() throws IOException {
                if (remaining == 0) return -1;
                int n = super.read();
                if (n >= 0) remaining--;
                return n;
              }

              @Override
              public void close() {}
            };
        upload.part(a.getName(), id, i, limited);
      }
      return upload.complete(a.getName(), id);
    } catch (IOException | RuntimeException e) {
      upload.cancel(a.getName(), id);
      throw e;
    }
  }
}
