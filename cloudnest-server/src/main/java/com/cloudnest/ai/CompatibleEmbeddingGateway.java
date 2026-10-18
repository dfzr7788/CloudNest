package com.cloudnest.ai;

import com.cloudnest.common.Problem;
import com.fasterxml.jackson.databind.*;
import java.nio.charset.StandardCharsets;
import java.util.*;
import java.util.function.Consumer;
import okhttp3.*;
import org.springframework.stereotype.Component;

/** Bounded float embeddings only. No model discovery, retries, dimension guessing or fallback. */
@Component
public class CompatibleEmbeddingGateway implements EmbeddingGateway {
  private final AiOutboundPolicy policy;
  private final AiSecrets secrets;
  private final ObjectMapper json;

  public CompatibleEmbeddingGateway(AiOutboundPolicy policy, AiSecrets secrets, ObjectMapper json) {
    this.policy = policy;
    this.secrets = secrets;
    this.json = json;
  }

  public Answer embed(Configuration config, List<String> texts, Consumer<Call> onCall) {
    validateInput(config, texts);
    OkHttpClient client = null;
    try {
      client = policy.client(config.baseUrl());
      var body = new LinkedHashMap<String, Object>();
      body.put("model", config.model());
      body.put("input", texts);
      body.put("encoding_format", "float");
      if (config.requestDimensions()) body.put("dimensions", config.dimensions());
      var request =
          new Request.Builder()
              .url(config.baseUrl() + "/embeddings")
              .header("Authorization", "Bearer " + secrets.decrypt(config.encryptedKey()))
              .post(
                  RequestBody.create(
                      json.writeValueAsBytes(body), MediaType.get("application/json")))
              .build();
      Call call = client.newCall(request);
      onCall.accept(call);
      try (Response response = call.execute()) {
        if (!response.isSuccessful()) {
          int status = response.code();
          String code =
              status == 401 || status == 403
                  ? "AI_AUTH_FAILED"
                  : status == 429
                      ? "AI_UPSTREAM_RATE_LIMITED"
                      : status == 404
                          ? "AI_MODEL_UNSUPPORTED"
                          : status >= 300 && status < 400
                              ? "AI_REDIRECT_BLOCKED"
                              : "AI_UPSTREAM_FAILED";
          throw new Problem(502, code, "向量模型请求失败（HTTP " + status + "），请检查地址、模型、余额和参数；不会自动重试");
        }
        if (response.body() == null) throw invalid("AI_INVALID_EMBEDDING", "向量服务没有响应内容", null);
        byte[] bytes = response.body().byteStream().readNBytes(4 * 1024 * 1024 + 1);
        if (bytes.length > 4 * 1024 * 1024)
          throw new Problem(502, "AI_RESPONSE_TOO_LARGE", "向量响应超过 4 MiB");
        var root = json.readTree(bytes);
        if (root == null || !root.isObject())
          throw invalid("AI_INVALID_EMBEDDING", "向量响应不是有效对象", null);
        var usage = root.path("usage");
        var measured =
            new ModelGateway.Answer(
                "",
                CompatibleChatGateway.token(usage, "prompt_tokens"),
                null,
                CompatibleChatGateway.token(usage, "total_tokens"));
        var data = root.path("data");
        if (!data.isArray() || data.size() != texts.size())
          throw invalid("AI_INVALID_EMBEDDING", "返回向量数量与输入数量不一致", measured);
        var ordered = new float[texts.size()][];
        for (var row : data) {
          var index = row.path("index");
          var vector = row.path("embedding");
          if (!index.isIntegralNumber()
              || !index.canConvertToInt()
              || index.asInt() < 0
              || index.asInt() >= texts.size()
              || ordered[index.asInt()] != null)
            throw invalid("AI_INVALID_EMBEDDING", "向量索引缺失、重复或越界", measured);
          if (!vector.isArray() || vector.size() != config.dimensions())
            throw invalid("AI_EMBEDDING_DIMENSION_MISMATCH", "向量维度与配置不一致，请核对供应商规格", measured);
          float[] values = new float[config.dimensions()];
          double norm = 0;
          for (int i = 0; i < values.length; i++) {
            var value = vector.get(i);
            double n = value.asDouble();
            float f = (float) n;
            if (!value.isNumber() || !Double.isFinite(n) || !Float.isFinite(f))
              throw invalid("AI_INVALID_EMBEDDING", "向量包含非有限或非数值元素", measured);
            values[i] = f;
            norm += (double) f * f;
          }
          if (norm == 0 || !Double.isFinite(norm))
            throw invalid("AI_INVALID_EMBEDDING", "向量为零或范数无效", measured);
          ordered[index.asInt()] = values;
        }
        return new Answer(List.of(ordered), measured.inputTokens(), measured.totalTokens());
      }
    } catch (Problem p) {
      throw p;
    } catch (com.fasterxml.jackson.core.JsonProcessingException e) {
      throw invalid("AI_INVALID_EMBEDDING", "向量响应不是有效 JSON", null);
    } catch (java.io.InterruptedIOException e) {
      throw new Problem(504, "AI_TIMEOUT", "向量请求超时，可能已计费；不会自动重试");
    } catch (IllegalStateException e) {
      throw new Problem(502, "AI_CREDENTIAL_UNREADABLE", "无法解密 AI 凭据，请恢复匹配的 jwt.key 或重新输入密钥");
    } catch (Exception e) {
      throw new Problem(502, "AI_CONNECTION_FAILED", "向量连接、TLS 或响应格式错误；不会自动重试");
    } finally {
      if (client != null) {
        client.connectionPool().evictAll();
        client.dispatcher().executorService().shutdown();
      }
    }
  }

  private void validateInput(Configuration config, List<String> texts) {
    if (config.dimensions() < 1
        || config.dimensions() > 4096
        || config.maxBatchSize() < 1
        || config.maxBatchSize() > 32
        || texts == null
        || texts.isEmpty()
        || texts.size() > config.maxBatchSize()) throw Problem.invalid("向量维度或批次数量超出范围");
    long bytes = 128, characters = 0;
    for (String text : texts) {
      if (text == null || text.isBlank()) throw Problem.invalid("向量输入不能为空");
      bytes += text.getBytes(StandardCharsets.UTF_8).length;
      characters += text.length();
    }
    if (bytes > config.inputLimit() || characters > 24000)
      throw new Problem(400, "AI_CONTEXT_TOO_LARGE", "向量批次输入超过保守预算，请缩小批次");
  }

  private static Problem invalid(String code, String message, ModelGateway.Answer usage) {
    return new ModelGateway.ResponseFailure(
        code,
        message + "；不会自动重试",
        usage == null ? new ModelGateway.Answer("", null, null, null) : usage);
  }
}
