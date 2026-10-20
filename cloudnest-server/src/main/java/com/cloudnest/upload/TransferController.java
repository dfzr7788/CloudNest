package com.cloudnest.upload;

import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/api/uploads/{id}")
public class TransferController {
  private final TransferPlanner planner;

  public TransferController(TransferPlanner planner) {
    this.planner = planner;
  }

  @PostMapping("/plan")
  public Object plan(Authentication a, @PathVariable String id, HttpServletResponse response) {
    response.setHeader("Cache-Control", "no-store, private");
    return planner.plan(a.getName(), id);
  }

  @PostMapping("/direct-commit")
  public Object commit(Authentication a, @PathVariable String id) throws IOException {
    return planner.commit(a.getName(), id);
  }
}
