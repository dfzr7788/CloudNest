package com.cloudnest.ai;

import java.util.List;
import java.util.Map;
import java.util.function.Consumer;
import okhttp3.Call;

public interface ModelGateway {
  record Configuration(
      String baseUrl, String encryptedKey, String model, String outputParameter, int maxOutput) {}

  record Answer(String text, Long inputTokens, Long outputTokens, Long totalTokens) {}

  record ToolAnswer(com.fasterxml.jackson.databind.JsonNode message, Answer usage) {}

  default ToolAnswer tools(
      Configuration config,
      List<Map<String, Object>> messages,
      List<Map<String, Object>> definitions,
      boolean probe,
      Consumer<Call> onCall) {
    throw new com.cloudnest.common.Problem(
        409, "AGENT_TOOL_CALLING_UNSUPPORTED", "Gateway does not support tool calling");
  }

  /** A completed provider response can fail validation while still incurring usage. */
  final class ResponseFailure extends com.cloudnest.common.Problem {
    public final Answer usage;

    public ResponseFailure(String code, String message, Answer usage) {
      super(502, code, message);
      this.usage = usage;
    }
  }

  Answer chat(Configuration config, String instruction, String text, Consumer<Call> onCall);
}
