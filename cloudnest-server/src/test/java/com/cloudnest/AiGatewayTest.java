package com.cloudnest;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

import com.cloudnest.ai.*;
import com.cloudnest.common.Problem;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.sun.net.httpserver.HttpServer;
import java.net.*;
import java.nio.charset.StandardCharsets;
import java.util.*;
import java.util.concurrent.atomic.AtomicReference;
import javax.crypto.spec.SecretKeySpec;
import okhttp3.*;
import org.junit.jupiter.api.Test;

class AiGatewayTest {
  @Test
  void toolProtocolUsesForcedProbeThenActualToolMessagesWithoutPaidRetries() throws Exception {
    var json = new ObjectMapper();
    var received = new ArrayList<com.fasterxml.jackson.databind.JsonNode>();
    var server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
    server.createContext(
        "/v1/chat/completions",
        exchange -> {
          received.add(json.readTree(exchange.getRequestBody()));
          var message =
              received.size() == 1
                  ? Map.of(
                      "tool_calls",
                      List.of(
                          Map.of(
                              "id",
                              "probe1",
                              "type",
                              "function",
                              "function",
                              Map.of("name", "get_node_status", "arguments", "{}"))))
                  : Map.of("content", "Metadata only");
          byte[] bytes =
              json.writeValueAsBytes(
                  Map.of(
                      "choices",
                      List.of(Map.of("message", message)),
                      "usage",
                      Map.of("total_tokens", 7)));
          exchange.sendResponseHeaders(200, bytes.length);
          exchange.getResponseBody().write(bytes);
          exchange.close();
        });
    server.start();
    try {
      var policy = mock(AiOutboundPolicy.class);
      when(policy.client(anyString()))
          .thenAnswer(
              i ->
                  new OkHttpClient.Builder()
                      .followRedirects(false)
                      .retryOnConnectionFailure(false)
                      .build());
      var secrets = new AiSecrets(new SecretKeySpec(new byte[32], "HmacSHA256"));
      var gateway = new CompatibleChatGateway(policy, secrets, json);
      var config =
          new ModelGateway.Configuration(
              "http://127.0.0.1:" + server.getAddress().getPort() + "/v1",
              secrets.encrypt("fixture"),
              "model",
              "max_tokens",
              128);
      List<Map<String, Object>> definitions =
          List.of(
              Map.of(
                  "type",
                  "function",
                  "function",
                  Map.of("name", "get_node_status", "parameters", Map.of("type", "object"))));
      var first =
          gateway.tools(
              config,
              List.of(Map.of("role", "user", "content", "diagnose")),
              definitions,
              true,
              c -> {});
      assertEquals("probe1", first.message().path("tool_calls").path(0).path("id").asText());
      assertEquals(7, first.usage().totalTokens());
      var second =
          gateway.tools(
              config,
              List.of(
                  Map.of(
                      "role",
                      "tool",
                      "tool_call_id",
                      "probe1",
                      "content",
                      "{\"trustedAsInstructions\":false}")),
              definitions,
              false,
              c -> {});
      assertEquals("Metadata only", second.message().path("content").asText());
      assertEquals(
          "get_node_status",
          received.getFirst().path("tool_choice").path("function").path("name").asText());
      assertFalse(received.getFirst().path("parallel_tool_calls").asBoolean());
      assertEquals("auto", received.getLast().path("tool_choice").asText());
      assertEquals(
          "probe1", received.getLast().path("messages").path(0).path("tool_call_id").asText());
      assertEquals(2, received.size());
    } finally {
      server.stop(0);
    }
  }

  @Test
  void networkPolicyRejectsReservedMixedAndMappedAddresses() throws Exception {
    var policy = new AiOutboundPolicy();
    for (String raw :
        List.of(
            "http://example.com/v1",
            "https://user:password@example.com/v1",
            "https://example.com/v1?key=secret",
            "https://example.com:8443/v1",
            "https://example.com/v1/chat/completions",
            "https://example.com/v1#x")) assertThrows(Problem.class, () -> policy.normalize(raw));
    for (String ip :
        List.of(
            "127.0.0.1",
            "10.0.0.1",
            "169.254.169.254",
            "100.64.0.1",
            "192.0.2.1",
            "198.18.0.1",
            "0.0.0.0",
            "224.0.0.1",
            "::1",
            "::",
            "fc00::1",
            "fe80::1",
            "::ffff:127.0.0.1",
            "2001:db8::1",
            "2002:7f00:1::"))
      assertFalse(AiOutboundPolicy.publicAddress(InetAddress.getByName(ip)), ip);
    var publicIp = InetAddress.getByName("8.8.8.8");
    assertTrue(AiOutboundPolicy.publicAddress(publicIp));
    assertThrows(
        Problem.class,
        () -> policy.validate(List.of(publicIp, InetAddress.getByName("127.0.0.1"))));
    assertEquals(
        "https://example.com/prefix/v1", policy.normalize("https://EXAMPLE.com:443/prefix/v1/"));
    assertThrows(Problem.class, () -> policy.client("https://127.0.0.1/v1"));
  }

  @Test
  void encryptedCredentialsAreVersionedAndRootBound() throws Exception {
    var a = new AiSecrets(new SecretKeySpec(new byte[32], "HmacSHA256"));
    var b = new AiSecrets(new SecretKeySpec(new byte[33], "HmacSHA256"));
    String cipher = a.encrypt("secret");
    assertEquals("secret", a.decrypt(cipher));
    assertThrows(IllegalStateException.class, () -> b.decrypt(cipher));
    assertEquals("secret", a.decrypt(cipher));
    assertThrows(IllegalStateException.class, () -> a.decrypt("ai:v2:invalid"));
  }

  @Test
  void dnsSnapshotAndPoolsPreventRebindingAndReuse() throws Exception {
    var answers = new AtomicReference<>(List.of(InetAddress.getByName("8.8.8.8")));
    var policy =
        new AiOutboundPolicy() {
          @Override
          protected List<InetAddress> resolve(String host) {
            return answers.get();
          }
        };
    var client = policy.client("https://model.example/v1");
    answers.set(List.of(InetAddress.getByName("127.0.0.1")));
    assertEquals("8.8.8.8", client.dns().lookup("model.example").getFirst().getHostAddress());
    assertThrows(UnknownHostException.class, () -> client.dns().lookup("other.example"));
    assertThrows(Problem.class, () -> policy.client("https://model.example/v1"));
    answers.set(List.of(InetAddress.getByName("8.8.8.8")));
    var next = policy.client("https://model.example/v1");
    assertNotSame(client.connectionPool(), next.connectionPool());
    assertFalse(client.followRedirects());
    assertFalse(client.followSslRedirects());
    assertFalse(client.retryOnConnectionFailure());
    assertEquals(Proxy.NO_PROXY, client.proxy());
    client.dispatcher().executorService().shutdown();
    next.dispatcher().executorService().shutdown();
  }

  @Test
  void gatewayValidatesResponsesUsageRedirectsAndDoesNotRetry() throws Exception {
    var server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
    var body =
        new AtomicReference<>(
            "{\"choices\":[{\"message\":{\"content\":\"OK\"}}],\"usage\":{\"prompt_tokens\":5,\"completion_tokens\":2,\"total_tokens\":7}}");
    var status = new java.util.concurrent.atomic.AtomicInteger(200);
    var count = new java.util.concurrent.atomic.AtomicInteger();
    server.createContext(
        "/v1/chat/completions",
        exchange -> {
          count.incrementAndGet();
          assertEquals("Bearer test-key", exchange.getRequestHeaders().getFirst("Authorization"));
          String request =
              new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8);
          assertTrue(request.contains("max_completion_tokens"));
          assertFalse(request.contains("temperature"));
          exchange
              .getResponseHeaders()
              .set("Location", "http://127.0.0.1:" + server.getAddress().getPort() + "/stolen");
          byte[] bytes = body.get().getBytes(StandardCharsets.UTF_8);
          exchange.sendResponseHeaders(status.get(), bytes.length);
          exchange.getResponseBody().write(bytes);
          exchange.close();
        });
    server.start();
    try {
      var policy = mock(AiOutboundPolicy.class);
      when(policy.client(anyString()))
          .thenAnswer(
              i ->
                  new OkHttpClient.Builder()
                      .followRedirects(false)
                      .retryOnConnectionFailure(false)
                      .build());
      var secrets = new AiSecrets(new SecretKeySpec(new byte[32], "HmacSHA256"));
      var gateway = new CompatibleChatGateway(policy, secrets, new ObjectMapper());
      var config =
          new ModelGateway.Configuration(
              "http://127.0.0.1:" + server.getAddress().getPort() + "/v1",
              secrets.encrypt("test-key"),
              "mock",
              "max_completion_tokens",
              16);
      var answer = gateway.chat(config, "test", "test", c -> {});
      assertEquals(7, answer.totalTokens());
      body.set("{\"choices\":[{\"message\":{\"content\":\"OK\"}}]}");
      assertNull(gateway.chat(config, "test", "test", c -> {}).totalTokens());
      status.set(302);
      assertEquals(
          "AI_REDIRECT_BLOCKED",
          assertThrows(Problem.class, () -> gateway.chat(config, "test", "test", c -> {})).code);
      status.set(401);
      assertEquals(
          "AI_AUTH_FAILED",
          assertThrows(Problem.class, () -> gateway.chat(config, "test", "test", c -> {})).code);
      status.set(200);
      body.set("{\"choices\":[]}");
      assertEquals(
          "AI_INVALID_RESPONSE",
          assertThrows(Problem.class, () -> gateway.chat(config, "test", "test", c -> {})).code);
      body.set(
          "{\"choices\":[{\"finish_reason\":\"length\",\"message\":{\"content\":\"partial"
              + " answer\",\"reasoning_content\":\"private"
              + " reasoning\"}}],\"usage\":{\"prompt_tokens\":5,\"completion_tokens\":16,\"total_tokens\":21}}");
      var limited =
          assertThrows(
              ModelGateway.ResponseFailure.class,
              () -> gateway.chat(config, "test", "test", c -> {}));
      assertEquals("AI_OUTPUT_LIMIT", limited.code);
      assertEquals(21L, limited.usage.totalTokens());
      assertFalse(limited.getMessage().contains("private reasoning"));
      body.set(
          "{\"choices\":[{\"finish_reason\":\"stop\",\"message\":{\"content\":null,\"reasoning_content\":\"private"
              + " reasoning\"}}],\"usage\":{\"total_tokens\":18}}");
      var missing =
          assertThrows(
              ModelGateway.ResponseFailure.class,
              () -> gateway.chat(config, "test", "test", c -> {}));
      assertEquals("AI_INVALID_RESPONSE", missing.code);
      assertEquals(18L, missing.usage.totalTokens());
      body.set("x".repeat(2 * 1024 * 1024 + 1));
      assertEquals(
          "AI_RESPONSE_TOO_LARGE",
          assertThrows(Problem.class, () -> gateway.chat(config, "test", "test", c -> {})).code);
      assertEquals(8, count.get());
    } finally {
      server.stop(0);
    }
  }

  @Test
  void officialDeepSeekRequestsDisableThinkingWithoutChangingOtherEndpoints() throws Exception {
    var received = new AtomicReference<com.fasterxml.jackson.databind.JsonNode>();
    var json = new ObjectMapper();
    var server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
    server.createContext(
        "/fixture",
        exchange -> {
          received.set(json.readTree(exchange.getRequestBody()));
          byte[] bytes =
              "{\"choices\":[{\"finish_reason\":\"stop\",\"message\":{\"content\":\"OK\"}}]}"
                  .getBytes(StandardCharsets.UTF_8);
          exchange.sendResponseHeaders(200, bytes.length);
          exchange.getResponseBody().write(bytes);
          exchange.close();
        });
    server.start();
    try {
      var policy = mock(AiOutboundPolicy.class);
      when(policy.client(anyString()))
          .thenAnswer(
              i ->
                  new OkHttpClient.Builder()
                      .retryOnConnectionFailure(false)
                      .addInterceptor(
                          chain ->
                              chain.proceed(
                                  chain
                                      .request()
                                      .newBuilder()
                                      .url(
                                          "http://127.0.0.1:"
                                              + server.getAddress().getPort()
                                              + "/fixture")
                                      .build()))
                      .build());
      var secrets = new AiSecrets(new SecretKeySpec(new byte[32], "HmacSHA256"));
      var gateway = new CompatibleChatGateway(policy, secrets, json);
      for (String base : List.of("https://api.deepseek.com", "https://api.deepseek.com/v1")) {
        for (String model : List.of("deepseek-flash", "deepseek-v4-pro")) {
          gateway.chat(
              new ModelGateway.Configuration(
                  base, secrets.encrypt("fake"), model, "max_tokens", 256),
              "Reply OK",
              "test",
              c -> {});
          assertEquals("disabled", received.get().path("thinking").path("type").asText());
          assertEquals(256, received.get().path("max_tokens").asInt());
        }
      }
      for (String base :
          List.of("https://other.example/v1", "https://api.deepseek.com.other.example/v1")) {
        gateway.chat(
            new ModelGateway.Configuration(
                base, secrets.encrypt("fake"), "deepseek-flash", "max_tokens", 256),
            "Reply OK",
            "test",
            c -> {});
        assertFalse(received.get().has("thinking"));
      }
      gateway.chat(
          new ModelGateway.Configuration(
              "https://api.deepseek.com",
              secrets.encrypt("fake"),
              "unknown-model",
              "max_tokens",
              256),
          "Reply OK",
          "test",
          c -> {});
      assertFalse(received.get().has("thinking"));
    } finally {
      server.stop(0);
    }
  }
}
