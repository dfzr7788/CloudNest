package com.cloudnest.common;

import java.io.*;
import java.nio.charset.StandardCharsets;
import java.security.*;
import java.text.Normalizer;
import java.util.*;

public final class Util {
  private Util() {}

  public static String id() {
    return UUID.randomUUID().toString();
  }

  public static String token() {
    byte[] b = new byte[32];
    new SecureRandom().nextBytes(b);
    return Base64.getUrlEncoder().withoutPadding().encodeToString(b);
  }

  public static String hash(String text) {
    return HexFormat.of().formatHex(digest().digest(text.getBytes(StandardCharsets.UTF_8)));
  }

  public static MessageDigest digest() {
    try {
      return MessageDigest.getInstance("SHA-256");
    } catch (NoSuchAlgorithmException e) {
      throw new IllegalStateException(e);
    }
  }

  public static String hash(InputStream in) throws IOException {
    MessageDigest d = digest();
    byte[] b = new byte[65536];
    for (int n; (n = in.read(b)) != -1; ) d.update(b, 0, n);
    return HexFormat.of().formatHex(d.digest());
  }

  public static String name(String input) {
    if (input == null) throw Problem.invalid("名称不能为空");
    String n = Normalizer.normalize(input.strip(), Normalizer.Form.NFC);
    if (n.isEmpty()
        || n.length() > 180
        || n.equals(".")
        || n.equals("..")
        || n.codePoints().anyMatch(c -> c < 32 || c == 127 || "/\\".indexOf(c) >= 0))
      throw Problem.invalid("名称不能包含路径分隔符或控制字符，长度为 1～180");
    return n;
  }

  public static boolean equal(String a, String b) {
    return a != null
        && b != null
        && MessageDigest.isEqual(
            a.getBytes(StandardCharsets.UTF_8), b.getBytes(StandardCharsets.UTF_8));
  }
}
