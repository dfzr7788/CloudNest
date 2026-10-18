package com.cloudnest.ai;

import com.cloudnest.common.Problem;
import com.fasterxml.jackson.databind.*;
import java.util.*;
import java.util.function.Consumer;
import okhttp3.*;
import org.springframework.stereotype.Component;

@Component
public class CompatibleChatGateway implements ModelGateway {
  private final AiOutboundPolicy policy;
  private final AiSecrets secrets;
  private final ObjectMapper json;

  public CompatibleChatGateway(AiOutboundPolicy policy, AiSecrets secrets, ObjectMapper json) {
    this.policy = policy;
    this.secrets = secrets;
    this.json = json;
  }

  public Answer chat(Configuration config, String instruction, String text, Consumer<Call> onCall) {
    OkHttpClient client = null;
    try {
      client = policy.client(config.baseUrl());
      var body =
          Map.of(
              "model",
              config.model(),
              config.outputParameter(),
              config.maxOutput(),
              "messages",
              List.of(
                  Map.of("role", "system", "content", instruction),
                  Map.of("role", "user", "content", text)));
      var request =
          new Request.Builder()
              .url(config.baseUrl() + "/chat/completions")
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
          throw new Problem(502, code, "模型服务请求失败（HTTP " + status + "），请检查地址、模型、余额和参数；不会自动重试");
        }
        if (response.body() == null) throw invalid();
        byte[] data = response.body().byteStream().readNBytes(2 * 1024 * 1024 + 1);
        if (data.length > 2 * 1024 * 1024)
          throw new Problem(502, "AI_RESPONSE_TOO_LARGE", "模型响应超过 2 MiB");
        JsonNode root = json.readTree(data);
        var result = root.path("choices").path(0).path("message").path("content");
        if (!result.isTextual() || result.asText().isBlank() || result.asText().length() > 100000)
          throw invalid();
        var usage = root.path("usage");
        return new Answer(
            result.asText(),
            token(usage, "prompt_tokens"),
            token(usage, "completion_tokens"),
            token(usage, "total_tokens"));
      }
    } catch (Problem p) {
      throw p;
    } catch (com.fasterxml.jackson.core.JsonProcessingException e) {
      throw invalid();
    } catch (java.io.InterruptedIOException e) {
      throw new Problem(504, "AI_TIMEOUT", "模型请求超时，可能已计费；不会自动重试");
    } catch (IllegalStateException e) {
      throw new Problem(502, "AI_CREDENTIAL_UNREADABLE", "无法解密 AI 凭据，请恢复匹配的 jwt.key 或重新输入密钥");
    } catch (Exception e) {
      throw new Problem(502, "AI_CONNECTION_FAILED", "模型连接、TLS 或响应格式错误；请检查配置，不会自动重试");
    } finally {
      if (client != null) {
        client.connectionPool().evictAll();
        client.dispatcher().executorService().shutdown();
      }
    }
  }

  private static Long token(JsonNode node, String field) {
    var n = node.path(field);
    return n.isIntegralNumber()
            && n.canConvertToLong()
            && n.asLong() >= 0
            && n.asLong() <= 1000000000L
        ? n.asLong()
        : null;
  }

  private static Problem invalid() {
    return new Problem(502, "AI_INVALID_RESPONSE", "模型未返回有效文本；不会自动重试");
  }
}
