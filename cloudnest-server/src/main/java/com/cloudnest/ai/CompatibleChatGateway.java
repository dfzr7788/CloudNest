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
    var message =
        request(
            config,
            List.of(
                Map.of("role", "system", "content", instruction),
                Map.of("role", "user", "content", text)),
            null,
            false,
            onCall);
    var result = message.message().path("content");
    if (!result.isTextual() || result.asText().isBlank() || result.asText().length() > 100000)
      throw new ResponseFailure(
          "AI_INVALID_RESPONSE", "No valid final text; no automatic retry", message.usage());
    var usage = message.usage();
    return new Answer(
        result.asText(), usage.inputTokens(), usage.outputTokens(), usage.totalTokens());
  }

  @Override
  public ToolAnswer tools(
      Configuration config,
      List<Map<String, Object>> messages,
      List<Map<String, Object>> definitions,
      boolean probe,
      Consumer<Call> onCall) {
    return request(config, messages, definitions, probe, onCall);
  }

  private ToolAnswer request(
      Configuration config,
      List<Map<String, Object>> messages,
      List<Map<String, Object>> definitions,
      boolean probe,
      Consumer<Call> onCall) {
    OkHttpClient client = null;
    try {
      client = policy.client(config.baseUrl());
      var body =
          new LinkedHashMap<String, Object>(
              Map.of(
                  "model",
                  config.model(),
                  config.outputParameter(),
                  config.maxOutput(),
                  "messages",
                  messages));
      if (definitions != null) {
        body.put("tools", definitions);
        body.put("parallel_tool_calls", false);
        body.put(
            "tool_choice",
            probe
                ? Map.of("type", "function", "function", Map.of("name", "get_node_status"))
                : "auto");
      }
      // The official DeepSeek service defaults to thinking mode. These bounded reading
      // operations need a final answer, so explicitly select its documented non-thinking mode.
      // Never send provider-specific fields to an unrelated compatible endpoint.
      if ("api.deepseek.com".equals(HttpUrl.get(config.baseUrl()).host())
          && Set.of(
                  "deepseek-flash",
                  "deepseek-v4-pro",
                  "deepseek-v4-flash",
                  "deepseek-v4-flash-vision-exp")
              .contains(config.model())) body.put("thinking", Map.of("type", "disabled"));
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
        JsonNode root =
            json.reader()
                .with(
                    DeserializationFeature.FAIL_ON_READING_DUP_TREE_KEY,
                    DeserializationFeature.FAIL_ON_TRAILING_TOKENS)
                .readTree(data);
        var choice = root.path("choices").path(0);
        var usage = root.path("usage");
        var measured =
            new Answer(
                "",
                token(usage, "prompt_tokens"),
                token(usage, "completion_tokens"),
                token(usage, "total_tokens"));
        if ("length".equals(choice.path("finish_reason").asText()))
          throw new ResponseFailure(
              "AI_OUTPUT_LIMIT", "模型达到输出上限，未生成完整回答；请提高输出上限或关闭模型思考模式；不会自动重试", measured);
        if (!choice.path("message").isObject())
          throw new ResponseFailure("AI_INVALID_RESPONSE", "No valid message", measured);
        return new ToolAnswer(choice.path("message"), measured);
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

  static Long token(JsonNode node, String field) {
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
