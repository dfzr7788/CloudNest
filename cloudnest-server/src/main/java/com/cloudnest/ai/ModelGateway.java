package com.cloudnest.ai;

import java.util.function.Consumer;
import okhttp3.Call;

public interface ModelGateway {
  record Configuration(
      String baseUrl, String encryptedKey, String model, String outputParameter, int maxOutput) {}

  record Answer(String text, Long inputTokens, Long outputTokens, Long totalTokens) {}

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
