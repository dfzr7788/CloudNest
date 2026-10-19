package com.cloudnest.file;

import jakarta.servlet.http.*;
import java.io.IOException;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/api/files")
public class ContentController {
  private final ContentService content;
  @org.springframework.beans.factory.annotation.Autowired private DirectDownloadService direct;
  @org.springframework.beans.factory.annotation.Autowired private DriveService drive;

  public ContentController(ContentService content) {
    this.content = content;
  }

  @GetMapping("/{id}/download-authorization")
  public java.util.Map<String, Object> authorization(
      Authentication a, @PathVariable String id, HttpServletResponse res) {
    var entry = drive.file(a.getName(), id);
    return direct.authorize(
        entry,
        content.blob(a.getName(), id),
        "/api/files/" + id + "/download",
        Long.MAX_VALUE,
        res);
  }

  @GetMapping("/{id}/download")
  public void download(
      Authentication a, @PathVariable String id, HttpServletRequest req, HttpServletResponse res)
      throws IOException {
    content.send(a.getName(), id, false, req, res);
  }

  @GetMapping("/{id}/preview")
  public void preview(
      Authentication a, @PathVariable String id, HttpServletRequest req, HttpServletResponse res)
      throws IOException {
    content.send(a.getName(), id, true, req, res);
  }

  @GetMapping("/{id}/attachment")
  public void attachment(
      Authentication a,
      @PathVariable String id,
      @RequestParam String path,
      HttpServletRequest req,
      HttpServletResponse res)
      throws IOException {
    content.send(a.getName(), content.attachment(a.getName(), id, path, null), true, req, res);
  }
}
