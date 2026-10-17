package com.cloudnest;

import static org.junit.jupiter.api.Assertions.*;

import com.cloudnest.auth.MfaService;
import java.nio.charset.StandardCharsets;
import java.util.Base64;
import org.junit.jupiter.api.Test;

class MfaServiceTest {
  @Test
  void rfc6238Sha1VectorsUsingSixDigitTruncation() {
    String secret =
        Base64.getEncoder()
            .encodeToString("12345678901234567890".getBytes(StandardCharsets.US_ASCII));
    long[] times = {59, 1111111109, 1111111111, 1234567890, 2000000000L, 20000000000L};
    String[] expected = {"287082", "081804", "050471", "005924", "279037", "353130"};
    for (int i = 0; i < times.length; i++)
      assertEquals(expected[i], MfaService.code(secret, times[i] / 30));
  }
}
