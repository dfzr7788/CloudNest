package com.cloudnest.auth;

import static com.cloudnest.common.Db.*;

import com.cloudnest.common.*;
import com.cloudnest.security.RateLimits;
import com.cloudnest.user.RuntimeSettings;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.Valid;
import jakarta.validation.constraints.*;
import java.nio.charset.StandardCharsets;
import java.util.*;
import org.springframework.mail.SimpleMailMessage;
import org.springframework.mail.javamail.JavaMailSenderImpl;
import org.springframework.security.core.Authentication;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/api")
public class MailRecovery {
  private final java.util.concurrent.ThreadPoolExecutor deliveries =
      new java.util.concurrent.ThreadPoolExecutor(
          1,
          2,
          30,
          java.util.concurrent.TimeUnit.SECONDS,
          new java.util.concurrent.ArrayBlockingQueue<>(50),
          r -> {
            Thread t = new Thread(r, "account-mail");
            t.setDaemon(true);
            return t;
          });

  @jakarta.annotation.PreDestroy
  public void close() {
    deliveries.shutdownNow();
  }

  @org.springframework.scheduling.annotation.Scheduled(fixedDelay = 3600000)
  public void expire() {
    db.update("DELETE FROM account_token WHERE expires_at<?", System.currentTimeMillis());
  }

  private final Db db;
  private final PasswordEncoder passwords;
  private final RuntimeSettings settings;
  private final RateLimits limits;

  public MailRecovery(
      Db db, PasswordEncoder passwords, RuntimeSettings settings, RateLimits limits) {
    this.db = db;
    this.passwords = passwords;
    this.settings = settings;
    this.limits = limits;
  }

  public void send(String to, String subject, String text) {
    var config = settings.get("mail");
    if (config.getOrDefault("host", "").isBlank())
      throw new Problem(503, "MAIL_DISABLED", "管理员尚未配置邮件服务");
    var sender = new JavaMailSenderImpl();
    sender.setHost(config.get("host"));
    sender.setPort(Integer.parseInt(config.getOrDefault("port", "587")));
    sender.setUsername(config.getOrDefault("username", ""));
    sender.setPassword(config.getOrDefault("password", ""));
    var properties = sender.getJavaMailProperties();
    properties.setProperty("mail.smtp.auth", Boolean.toString(!sender.getUsername().isBlank()));
    properties.setProperty("mail.smtp.starttls.enable", config.getOrDefault("tls", "true"));
    properties.setProperty("mail.smtp.starttls.required", config.getOrDefault("tls", "true"));
    properties.setProperty("mail.smtp.ssl.enable", config.getOrDefault("ssl", "false"));
    properties.setProperty("mail.smtp.connectiontimeout", "5000");
    properties.setProperty("mail.smtp.timeout", "5000");
    properties.setProperty("mail.smtp.writetimeout", "5000");
    properties.setProperty("mail.smtp.ssl.checkserveridentity", "true");
    var message = new SimpleMailMessage();
    message.setFrom(config.getOrDefault("from", config.getOrDefault("username", "")));
    message.setTo(to);
    message.setSubject(subject);
    message.setText(text);
    try {
      sender.send(message);
    } catch (org.springframework.mail.MailException e) {
      throw new Problem(503, "MAIL_FAILED", "邮件发送失败，请联系管理员检查配置");
    }
  }

  private String base() {
    String base = settings.get("mail").getOrDefault("baseUrl", "");
    RuntimeSettings.httpUrl(base);
    return base.replaceAll("/+$", "");
  }

  public record Bind(@Email @NotBlank @Size(max = 254) String email, @NotBlank String password) {}

  @PostMapping("/user/access/email")
  public void bind(
      Authentication a,
      @Valid @RequestBody Bind in,
      @RequestHeader(value = "Accept-Language", defaultValue = "zh-CN") String language) {
    limits.check("email-bind:" + a.getName(), 3);
    if (!passwords.matches(
        in.password(),
        s(db.one("SELECT password_hash FROM sys_user WHERE id=?", a.getName()), "password_hash")))
      throw new Problem(401, "INVALID_PASSWORD", "密码错误");
    String email = in.email().toLowerCase(Locale.ROOT);
    if (db.count(
            "SELECT COUNT(*) FROM sys_user WHERE email_address=? AND id<>?", email, a.getName())
        > 0) throw Problem.conflict("该邮箱不可使用");
    String token = Util.token();
    db.atomic(
        a.getName(),
        () -> {
          db.update("DELETE FROM account_token WHERE user_id=? AND purpose='EMAIL'", a.getName());
          db.update(
              "INSERT INTO account_token(token_hash,user_id,purpose,email_address,expires_at)"
                  + " VALUES(?,?,'EMAIL',?,?)",
              Util.hash(token),
              a.getName(),
              email,
              System.currentTimeMillis() + 900000);
          return null;
        });
    var message =
        AccountMailTemplates.language(language)
            .verification(base() + "/login?verifyEmail=" + token);
    send(email, message.subject(), message.body());
  }

  public record Token(@NotBlank @Size(max = 100) String token) {}

  @PostMapping("/public/auth/verify-email")
  public void verify(@Valid @RequestBody Token in, HttpServletRequest req) {
    limits.check("email-verify:" + req.getRemoteAddr(), 10);
    var claim =
        db.one(
            "SELECT * FROM account_token WHERE token_hash=? AND purpose='EMAIL' AND expires_at>?",
            Util.hash(in.token()),
            System.currentTimeMillis());
    db.atomic(
        s(claim, "user_id"),
        () -> {
          if (db.update("DELETE FROM account_token WHERE token_hash=?", Util.hash(in.token())) != 1)
            throw Problem.conflict("链接已使用");
          if (db.count(
                  "SELECT COUNT(*) FROM sys_user WHERE email_address=? AND id<>?",
                  claim.get("email_address"),
                  claim.get("user_id"))
              > 0) throw Problem.conflict("该邮箱不可使用");
          db.update(
              "UPDATE sys_user SET email_address=? WHERE id=?",
              claim.get("email_address"),
              claim.get("user_id"));
          return null;
        });
  }

  public record Forgot(@NotBlank @Size(max = 254) String account) {}

  @PostMapping("/public/auth/forgot")
  public Map<String, Object> forgot(
      @Valid @RequestBody Forgot in,
      HttpServletRequest req,
      @RequestHeader(value = "Accept-Language", defaultValue = "zh-CN") String language) {
    limits.check("forgot-ip:" + req.getRemoteAddr(), 3);
    String account = in.account().toLowerCase(Locale.ROOT);
    limits.check("forgot-user:" + Util.hash(account), 2);
    String origin = base();
    var mailLanguage = AccountMailTemplates.language(language);
    if (settings.get("mail").getOrDefault("host", "").isBlank())
      throw new Problem(503, "MAIL_DISABLED", "管理员尚未配置邮件找回");
    try {
      deliveries.execute(
          () -> {
            try {
              var matches =
                  db.list(
                      "SELECT id,email_address FROM sys_user WHERE enabled=TRUE AND email_address"
                          + " IS NOT NULL AND (username=? OR email_address=?)",
                      account,
                      account);
              if (matches.isEmpty()) return;
              var user = matches.getFirst();
              String token = Util.token();
              db.atomic(
                  s(user, "id"),
                  () -> {
                    db.update(
                        "DELETE FROM account_token WHERE user_id=? AND purpose='RESET'",
                        user.get("id"));
                    db.update(
                        "INSERT INTO account_token(token_hash,user_id,purpose,expires_at)"
                            + " VALUES(?,?,'RESET',?)",
                        Util.hash(token),
                        user.get("id"),
                        System.currentTimeMillis() + 900000);
                    return null;
                  });
              var message = mailLanguage.reset(origin + "/login?reset=" + token);
              send(s(user, "email_address"), message.subject(), message.body());
            } catch (Exception error) {
              org.slf4j.LoggerFactory.getLogger(getClass())
                  .warn("Account recovery delivery failed; check administrator SMTP configuration");
            }
          });
    } catch (java.util.concurrent.RejectedExecutionException busy) {
      throw new Problem(503, "MAIL_BUSY", "邮件队列繁忙，请稍后重试");
    }
    return Map.of("message", mailLanguage.acknowledgement());
  }

  public record Reset(
      @NotBlank @Size(max = 100) String token, @NotNull @Size(min = 8, max = 64) String password) {}

  @PostMapping("/public/auth/reset")
  public void reset(@Valid @RequestBody Reset in, HttpServletRequest req) {
    limits.check("reset:" + req.getRemoteAddr(), 10);
    if (in.password().getBytes(StandardCharsets.UTF_8).length > 72)
      throw Problem.invalid("密码 UTF-8 长度不能超过 72 字节");
    var token =
        db.one(
            "SELECT * FROM account_token WHERE token_hash=? AND purpose='RESET' AND expires_at>?",
            Util.hash(in.token()),
            System.currentTimeMillis());
    db.atomic(
        s(token, "user_id"),
        () -> {
          if (db.update("DELETE FROM account_token WHERE token_hash=?", Util.hash(in.token())) != 1)
            throw Problem.conflict("链接已使用");
          db.update(
              "UPDATE sys_user SET password_hash=? WHERE id=? AND enabled=TRUE",
              passwords.encode(in.password()),
              token.get("user_id"));
          db.update("DELETE FROM auth_session WHERE user_id=?", token.get("user_id"));
          db.update("DELETE FROM dav_credential WHERE user_id=?", token.get("user_id"));
          return null;
        });
  }
}
