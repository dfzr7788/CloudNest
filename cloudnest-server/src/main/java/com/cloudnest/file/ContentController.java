package com.cloudnest.file;

import jakarta.servlet.http.*;
import java.io.IOException;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/api/files")
public class ContentController {
  private final ContentService content;

  public ContentController(ContentService content) {
    this.content = content;
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
