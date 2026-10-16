package com.cloudnest.common;

public class Problem extends RuntimeException {
  public final int status;
  public final String code;

  public Problem(int status, String code, String message) {
    super(message);
    this.status = status;
    this.code = code;
  }

  public static Problem missing() {
    return new Problem(404, "NOT_FOUND", "资源不存在或不可访问");
  }

  public static Problem conflict(String message) {
    return new Problem(409, "CONFLICT", message);
  }

  public static Problem invalid(String message) {
    return new Problem(400, "INVALID_INPUT", message);
  }
}
