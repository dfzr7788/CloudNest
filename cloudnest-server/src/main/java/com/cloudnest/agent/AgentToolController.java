package com.cloudnest.agent;

import com.cloudnest.common.Problem;
import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.servlet.http.HttpServletRequest;
import org.springframework.http.CacheControl;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/api/admin/agent/tools")
public class AgentToolController {
  private final AgentToolRegistry tools;
  private final ObjectMapper json;

  public AgentToolController(AgentToolRegistry tools, ObjectMapper json) {
    this.tools = tools;
    this.json = json;
  }

  @GetMapping("/available")
  public ResponseEntity<AgentToolRegistry.Available> available(Authentication a) {
    return ResponseEntity.ok()
        .cacheControl(CacheControl.noStore())
        .body(tools.available(a.getName()));
  }

  @PostMapping("/{name}/invoke")
  public ResponseEntity<AgentToolRegistry.Result> invoke(
      Authentication a, @PathVariable String name, HttpServletRequest request)
      throws java.io.IOException {
    // Authorize before reading even a bounded body. The registry authorizes again before execution.
    if (!tools.available(a.getName()).enabled())
      throw new Problem(409, "AGENT_TOOLS_DISABLED", "只读 Agent 工具尚未启用");
    try {
      if (request.getContentType() == null
          || !MediaType.APPLICATION_JSON.isCompatibleWith(
              MediaType.parseMediaType(request.getContentType())))
        throw new Problem(415, "AGENT_TOOL_ARGUMENTS_INVALID", "工具参数必须使用 application/json");
    } catch (org.springframework.http.InvalidMediaTypeException e) {
      throw new Problem(415, "AGENT_TOOL_ARGUMENTS_INVALID", "工具参数必须使用 application/json");
    }
    byte[] bytes = request.getInputStream().readNBytes(4097);
    if (bytes.length > 4096)
      throw new Problem(413, "AGENT_TOOL_ARGUMENTS_INVALID", "工具请求体超过 4 KiB");
    JsonNode arguments;
    try {
      arguments =
          json.reader()
              .with(
                  DeserializationFeature.FAIL_ON_READING_DUP_TREE_KEY,
                  DeserializationFeature.FAIL_ON_TRAILING_TOKENS)
              .readTree(bytes);
    } catch (com.fasterxml.jackson.core.JsonProcessingException e) {
      throw new Problem(400, "AGENT_TOOL_ARGUMENTS_INVALID", "工具参数不是有效的单个 JSON 对象");
    }
    return ResponseEntity.ok()
        .cacheControl(CacheControl.noStore())
        .body(tools.invoke(a.getName(), name, arguments));
  }
}
