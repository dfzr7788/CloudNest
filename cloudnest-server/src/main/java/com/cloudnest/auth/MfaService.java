package com.cloudnest.auth;

import static com.cloudnest.common.Db.*;

import com.cloudnest.common.*;
import com.cloudnest.security.SecretCipher;
import java.nio.ByteBuffer;
import java.security.SecureRandom;
import java.util.*;
import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import org.springframework.stereotype.Service;

@Service
public class MfaService {
  @org.springframework.beans.factory.annotation.Autowired
  private com.cloudnest.security.RateLimits limits;

  private final Db db;
  private final SecretCipher cipher;

  public MfaService(Db db, SecretCipher cipher) {
    this.db = db;
    this.cipher = cipher;
  }

  public String newSecret() {
    byte[] bytes = new byte[20];
    new SecureRandom().nextBytes(bytes);
    return Base64.getEncoder().encodeToString(bytes);
  }

  public String base32(String secret) {
    byte[] bytes = Base64.getDecoder().decode(secret);
    StringBuilder out = new StringBuilder();
    int buffer = 0, bits = 0;
    for (byte b : bytes) {
      buffer = (buffer << 8) | (b & 255);
      bits += 8;
      while (bits >= 5) {
        bits -= 5;
        out.append("ABCDEFGHIJKLMNOPQRSTUVWXYZ234567".charAt((buffer >> bits) & 31));
      }
    }
    if (bits > 0)
      out.append("ABCDEFGHIJKLMNOPQRSTUVWXYZ234567".charAt((buffer << (5 - bits)) & 31));
    return out.toString();
  }

  public static String code(String secret, long step) {
    try {
      var mac = Mac.getInstance("HmacSHA1");
      mac.init(new SecretKeySpec(Base64.getDecoder().decode(secret), "HmacSHA1"));
      byte[] digest = mac.doFinal(ByteBuffer.allocate(8).putLong(step).array());
      int n = digest[digest.length - 1] & 15;
      int value =
          ((digest[n] & 127) << 24)
              | ((digest[n + 1] & 255) << 16)
              | ((digest[n + 2] & 255) << 8)
              | (digest[n + 3] & 255);
      return String.format(Locale.ROOT, "%06d", value % 1000000);
    } catch (Exception e) {
      throw new IllegalStateException(e);
    }
  }

  public long step(String secret, String candidate, long last) {
    if (candidate == null || !candidate.matches("[0-9]{6}")) return -1;
    long now = System.currentTimeMillis() / 30000;
    for (long t = now - 1; t <= now + 1; t++)
      if (t > last
          && java.security.MessageDigest.isEqual(
              code(secret, t).getBytes(java.nio.charset.StandardCharsets.US_ASCII),
              candidate.getBytes(java.nio.charset.StandardCharsets.US_ASCII))) return t;
    return -1;
  }

  public void verify(String uid, String candidate) {
    db.atomic(
        uid,
        () -> {
          var user = db.one("SELECT totp_secret,totp_step FROM sys_user WHERE id=?", uid);
          if (user.get("totp_secret") == null) return null;
          limits.check("mfa:" + uid, 5);
          if (candidate == null || candidate.isBlank())
            throw new Problem(401, "MFA_REQUIRED", "请输入双重验证代码或一次性恢复码");
          if (db.update(
                  "DELETE FROM mfa_recovery WHERE user_id=? AND token_hash=?",
                  uid,
                  Util.hash(candidate.replace(" ", "")))
              == 1) return null;
          long step = step(cipher.decrypt(s(user, "totp_secret")), candidate, n(user, "totp_step"));
          if (step < 0) throw new Problem(401, "MFA_INVALID", "验证代码无效、过期或已经使用");
          db.update("UPDATE sys_user SET totp_step=? WHERE id=?", step, uid);
          return null;
        });
  }
}
