package com.cloudnest;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

import com.cloudnest.ai.*;
import com.cloudnest.common.Problem;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.util.*;
import java.util.concurrent.atomic.*;
import javax.crypto.spec.SecretKeySpec;
import okhttp3.*;
import org.junit.jupiter.api.Test;

class AiEmbeddingGatewayTest {
  @Test
  void validatesFloatVectorsOrderDimensionsUsageAndOptionalDimensionParameter() throws Exception {
    var json = new ObjectMapper();
    var received = new AtomicReference<com.fasterxml.jackson.databind.JsonNode>();
    var response =
        new AtomicReference<>(
            "{\"data\":[{\"index\":1,\"embedding\":[0,1,0]},{\"index\":0,\"embedding\":[1,0,0]}],\"usage\":{\"prompt_tokens\":7,\"total_tokens\":7}}");
    var status = new AtomicInteger(200);
    var count = new AtomicInteger();
    var policy = mock(AiOutboundPolicy.class);
    when(policy.client(anyString()))
        .thenAnswer(
            i ->
                new OkHttpClient.Builder()
                    .followRedirects(false)
                    .retryOnConnectionFailure(false)
                    .addInterceptor(
                        chain -> {
                          count.incrementAndGet();
                          var req = chain.request();
                          assertEquals("/v1/embeddings", req.url().encodedPath());
                          assertEquals("Bearer test-key", req.header("Authorization"));
                          var buffer = new okio.Buffer();
                          req.body().writeTo(buffer);
                          received.set(json.readTree(buffer.readUtf8()));
                          return new Response.Builder()
                              .request(req)
                              .protocol(Protocol.HTTP_1_1)
                              .code(status.get())
                              .message("fixture")
                              .body(
                                  ResponseBody.create(
                                      response.get(), MediaType.get("application/json")))
                              .build();
                        })
                    .build());
    var secrets = new AiSecrets(new SecretKeySpec(new byte[32], "HmacSHA256"));
    var gateway = new CompatibleEmbeddingGateway(policy, secrets, json);
    var config =
        new EmbeddingGateway.Configuration(
            "https://model.example/v1",
            secrets.encrypt("test-key"),
            "vector-fixture",
            3,
            8192,
            16,
            false);
    var answer = gateway.embed(config, List.of("one", "two"), c -> {});
    assertArrayEquals(new float[] {1, 0, 0}, answer.vectors().get(0));
    assertArrayEquals(new float[] {0, 1, 0}, answer.vectors().get(1));
    assertEquals(7, answer.totalTokens());
    assertFalse(received.get().has("dimensions"));
    assertEquals(
        Set.of("model", "input", "encoding_format"),
        json.convertValue(received.get(), Map.class).keySet());
    assertEquals("float", received.get().path("encoding_format").asText());
    gateway.embed(
        new EmbeddingGateway.Configuration(
            config.baseUrl(), config.encryptedKey(), config.model(), 3, 8192, 16, true),
        List.of("one", "two"),
        c -> {});
    assertEquals(3, received.get().path("dimensions").asInt());
    for (String data :
        List.of(
            "[]",
            "[{\"index\":0,\"embedding\":[1,0,0]},{\"index\":0,\"embedding\":[1,0,0]}]",
            "[{\"index\":-1,\"embedding\":[1,0,0]},{\"index\":1,\"embedding\":[1,0,0]}]",
            "[{\"index\":0,\"embedding\":[1,0]},{\"index\":1,\"embedding\":[1,0]}]",
            "[{\"index\":0,\"embedding\":[0,0,0]},{\"index\":1,\"embedding\":[1,0,0]}]",
            "[{\"index\":0,\"embedding\":[\"NaN\",0,1]},{\"index\":1,\"embedding\":[1,0,0]}]",
            "[{\"index\":0,\"embedding\":[1e100,0,1]},{\"index\":1,\"embedding\":[1,0,0]}]")) {
      response.set("{\"data\":" + data + ",\"usage\":{\"total_tokens\":12}}");
      var failure =
          assertThrows(
              ModelGateway.ResponseFailure.class,
              () -> gateway.embed(config, List.of("one", "two"), c -> {}));
      assertEquals(12, failure.usage.totalTokens());
      assertNull(failure.usage.outputTokens());
    }
    response.set("{\"data\":[{\"index\":0,\"embedding\":[1,0,0]}]}");
    assertNull(gateway.embed(config, List.of("one"), c -> {}).totalTokens());
    int before = count.get();
    status.set(302);
    assertEquals(
        "AI_REDIRECT_BLOCKED",
        assertThrows(Problem.class, () -> gateway.embed(config, List.of("one"), c -> {})).code);
    assertEquals(before + 1, count.get());
    status.set(401);
    assertEquals(
        "AI_AUTH_FAILED",
        assertThrows(Problem.class, () -> gateway.embed(config, List.of("one"), c -> {})).code);
    status.set(200);
    response.set("x".repeat(4 * 1024 * 1024 + 1));
    assertEquals(
        "AI_RESPONSE_TOO_LARGE",
        assertThrows(Problem.class, () -> gateway.embed(config, List.of("one"), c -> {})).code);
  }

  @Test
  void invalidInputsBlockedTargetsAndCancellationFailBeforeAnHttpRequest() throws Exception {
    var policy = mock(AiOutboundPolicy.class);
    var secrets = new AiSecrets(new SecretKeySpec(new byte[32], "HmacSHA256"));
    var gateway = new CompatibleEmbeddingGateway(policy, secrets, new ObjectMapper());
    var config =
        new EmbeddingGateway.Configuration(
            "https://model.example/v1", secrets.encrypt("test"), "fixture", 3, 512, 1, false);
    assertThrows(Problem.class, () -> gateway.embed(config, List.of(), c -> {}));
    assertThrows(Problem.class, () -> gateway.embed(config, List.of(""), c -> {}));
    assertThrows(Problem.class, () -> gateway.embed(config, List.of("a", "b"), c -> {}));
    assertEquals(
        "AI_CONTEXT_TOO_LARGE",
        assertThrows(Problem.class, () -> gateway.embed(config, List.of("x".repeat(512)), c -> {}))
            .code);
    verifyNoInteractions(policy);
    when(policy.client(anyString())).thenThrow(AiOutboundPolicy.blocked());
    assertEquals(
        "AI_ENDPOINT_BLOCKED",
        assertThrows(Problem.class, () -> gateway.embed(config, List.of("a"), c -> {})).code);
    var count = new AtomicInteger();
    doAnswer(
            i ->
                new OkHttpClient.Builder()
                    .addInterceptor(
                        chain -> {
                          count.incrementAndGet();
                          throw new java.io.IOException("must not run");
                        })
                    .build())
        .when(policy)
        .client(anyString());
    assertEquals(
        "AI_CANCELLED",
        assertThrows(
                Problem.class,
                () ->
                    gateway.embed(
                        config,
                        List.of("a"),
                        call -> {
                          call.cancel();
                          throw new Problem(
                              409, "AI_CANCELLED", "cancelled during final authorization check");
                        }))
            .code);
    assertEquals(0, count.get());
    assertThrows(
        Problem.class,
        () -> new AiOutboundPolicy().normalize("https://model.example/v1/embeddings"));
  }
}
