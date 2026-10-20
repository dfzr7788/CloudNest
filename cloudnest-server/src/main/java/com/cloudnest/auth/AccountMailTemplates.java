package com.cloudnest.auth;

import java.util.List;
import java.util.Locale;

/** Immutable language choice can safely cross the asynchronous delivery boundary. */
public final class AccountMailTemplates {
  private AccountMailTemplates() {}

  public record Message(String subject, String body) {}

  public enum Language {
    ZH_CN,
    EN_US;

    public Message verification(String link) {
      return this == EN_US
          ? new Message(
              "CloudNest: verify your email",
              "Open this link within 15 minutes to verify your email:\n" + link)
          : new Message("CloudNest 验证邮箱", "请在 15 分钟内打开链接验证邮箱：\n" + link);
    }

    public Message reset(String link) {
      return this == EN_US
          ? new Message(
              "CloudNest: reset your password",
              "Open this link within 15 minutes to set a new password. If you did not request this,"
                  + " ignore this message.\n"
                  + link)
          : new Message("CloudNest 重置密码", "请在 15 分钟内打开链接设置新密码。如非本人操作请忽略。\n" + link);
    }

    public String acknowledgement() {
      return this == EN_US
          ? "If the account has a verified email address, it will receive a password reset link."
          : "如果账号已绑定邮箱，将收到密码重置链接";
    }
  }

  public static Language language(String header) {
    if (header == null || header.length() > 512) return Language.ZH_CN;
    try {
      String selected = Locale.lookupTag(Locale.LanguageRange.parse(header), List.of("zh", "en"));
      return "en".equals(selected) ? Language.EN_US : Language.ZH_CN;
    } catch (IllegalArgumentException invalid) {
      return Language.ZH_CN;
    }
  }
}
