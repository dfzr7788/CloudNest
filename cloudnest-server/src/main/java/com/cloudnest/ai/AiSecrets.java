package com.cloudnest.ai;

import com.cloudnest.security.SecretCipher;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import javax.crypto.spec.SecretKeySpec;
import org.springframework.stereotype.Component;

/** Versioned, purpose-separated encryption; backups must retain the matching jwt.key. */
@Component
public class AiSecrets {
  private final SecretCipher cipher;

  public AiSecrets(SecretKeySpec root) throws Exception {
    var digest = MessageDigest.getInstance("SHA-256");
    digest.update("cloudnest-ai-credentials-v1".getBytes(StandardCharsets.UTF_8));
    cipher = new SecretCipher(new SecretKeySpec(digest.digest(root.getEncoded()), "AES"));
  }

  public String encrypt(String value) {
    return "ai:v1:" + cipher.encrypt(value);
  }

  public String decrypt(String value) {
    if (value == null || !value.startsWith("ai:v1:"))
      throw new IllegalStateException(
          "AI credential version unsupported; restore matching jwt.key");
    return cipher.decrypt(value.substring(6));
  }
}
