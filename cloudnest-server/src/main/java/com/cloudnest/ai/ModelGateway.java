package com.cloudnest.ai;

import java.util.function.Consumer;
import okhttp3.Call;

public interface ModelGateway {
  record Configuration(
      String baseUrl, String encryptedKey, String model, String outputParameter, int maxOutput) {}

  record Answer(String text, Long inputTokens, Long outputTokens, Long totalTokens) {}

  Answer chat(Configuration config, String instruction, String text, Consumer<Call> onCall);
}
