package com.cloudnest.ai;

import java.util.List;
import java.util.function.Consumer;
import okhttp3.Call;

public interface EmbeddingGateway {
  record Configuration(
      String baseUrl,
      String encryptedKey,
      String model,
      int dimensions,
      int inputLimit,
      int maxBatchSize,
      boolean requestDimensions) {}

  record Answer(List<float[]> vectors, Long inputTokens, Long totalTokens) {}

  Answer embed(Configuration config, List<String> texts, Consumer<Call> onCall);
}
