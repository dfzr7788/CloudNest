package com.cloudnest.security;

import java.nio.charset.StandardCharsets;
import java.security.*;
import java.util.*;
import javax.crypto.*;
import javax.crypto.spec.*;
import org.springframework.stereotype.Component;

@Component
public class SecretCipher {
  private final SecretKeySpec key;

  public SecretCipher(SecretKeySpec signingKey) throws GeneralSecurityException {
    var digest = MessageDigest.getInstance("SHA-256");
    digest.update("cloudnest-settings-v1".getBytes(StandardCharsets.UTF_8));
    key = new SecretKeySpec(digest.digest(signingKey.getEncoded()), "AES");
  }

  public String encrypt(String value) {
    try {
      byte[] iv = new byte[12];
      new SecureRandom().nextBytes(iv);
      var cipher = Cipher.getInstance("AES/GCM/NoPadding");
      cipher.init(Cipher.ENCRYPT_MODE, key, new GCMParameterSpec(128, iv));
      byte[] encrypted = cipher.doFinal(value.getBytes(StandardCharsets.UTF_8));
      byte[] result = Arrays.copyOf(iv, iv.length + encrypted.length);
      System.arraycopy(encrypted, 0, result, iv.length, encrypted.length);
      return Base64.getEncoder().encodeToString(result);
    } catch (GeneralSecurityException e) {
      throw new IllegalStateException("Cannot encrypt settings", e);
    }
  }

  public String decrypt(String value) {
    try {
      byte[] data = Base64.getDecoder().decode(value);
      if (data.length < 28) throw new IllegalArgumentException("Invalid secret");
      var cipher = Cipher.getInstance("AES/GCM/NoPadding");
      cipher.init(Cipher.DECRYPT_MODE, key, new GCMParameterSpec(128, Arrays.copyOf(data, 12)));
      return new String(cipher.doFinal(data, 12, data.length - 12), StandardCharsets.UTF_8);
    } catch (GeneralSecurityException | IllegalArgumentException e) {
      throw new IllegalStateException("Cannot decrypt settings; restore matching jwt.key", e);
    }
  }
}
