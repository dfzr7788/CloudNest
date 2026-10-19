package com.cloudnest.user;

import com.cloudnest.auth.MailRecovery;
import com.cloudnest.common.*;
import jakarta.validation.Valid;
import jakarta.validation.constraints.*;
import java.net.http.*;
import java.time.Duration;
import java.util.*;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/api/admin/config")
public class ConfigurationController {
  @org.springframework.beans.factory.annotation.Autowired private Db db;

  @org.springframework.beans.factory.annotation.Autowired
  private com.cloudnest.node.NodeService nodes;

  private final RuntimeSettings settings;
  private final MailRecovery mail;
  private static final Map<String, Set<String>> FIELDS =
      Map.of(
          "mail",
          Set.of("host", "port", "username", "password", "from", "tls", "ssl", "baseUrl"),
          "office",
          Set.of("url", "secret", "baseUrl"),
          "storage",
          Set.of("defaultBackend", "warningPercent"));

  public ConfigurationController(RuntimeSettings settings, MailRecovery mail) {
    this.settings = settings;
    this.mail = mail;
  }

  private void section(String section) {
    if (!FIELDS.containsKey(section)) throw Problem.missing();
  }

  @GetMapping("/{section}")
  public Map<String, String> get(Authentication a, @PathVariable String section) {
    settings.admin(a.getName());
    section(section);
    var value = settings.get(section);
    for (String key : List.of("password", "secret"))
      if (value.containsKey(key)) {
        value.put(key + "Configured", Boolean.toString(!value.get(key).isBlank()));
        value.remove(key);
      }
    return value;
  }

  @PutMapping("/{section}")
  public void save(
      Authentication a, @PathVariable String section, @RequestBody Map<String, String> input) {
    settings.admin(a.getName());
    section(section);
    if (!FIELDS.get(section).containsAll(input.keySet())) throw Problem.invalid("未知配置字段");
    var value = settings.get(section);
    for (var entry : input.entrySet()) {
      if (entry.getValue() == null || entry.getValue().length() > 4096)
        throw Problem.invalid("配置长度无效");
      if (Set.of("password", "secret").contains(entry.getKey()) && entry.getValue().isBlank())
        continue;
      value.put(
          entry.getKey(),
          Set.of("password", "secret").contains(entry.getKey())
              ? entry.getValue()
              : entry.getValue().strip());
    }
    for (String key : List.of("url", "baseUrl"))
      if (value.containsKey(key) && !value.get(key).isBlank())
        RuntimeSettings.httpUrl(value.get(key));
    if (section.equals("mail")) {
      try {
        int port = Integer.parseInt(value.getOrDefault("port", "587"));
        if (port < 1 || port > 65535) throw new Exception();
      } catch (Exception e) {
        throw Problem.invalid("SMTP 端口无效");
      }
      if (value.getOrDefault("host", "").contains("/") || value.getOrDefault("host", "").isBlank())
        throw Problem.invalid("SMTP 主机无效");
    }
    if (section.equals("office")
        && !value.getOrDefault("url", "").isBlank()
        && value.getOrDefault("secret", "").length() < 32)
      throw Problem.invalid("Office JWT 密钥至少 32 个字符");
    if (section.equals("storage")) {
      String backend = value.getOrDefault("defaultBackend", "local");
      if (!Set.of("local", "minio").contains(backend) && !backend.matches("s3:[a-f0-9]{12}"))
        throw Problem.invalid("存储策略无效");
      if (backend.startsWith("s3:")
          && db.count(
                  "SELECT COUNT(*) FROM storage_policy WHERE id=? AND enabled=TRUE",
                  backend.substring(3))
              != 1) throw Problem.invalid("存储策略不存在或已停用");
      try {
        int n = Integer.parseInt(value.getOrDefault("warningPercent", "80"));
        if (n < 50 || n > 95) throw new Exception();
      } catch (Exception e) {
        throw Problem.invalid("容量告警阈值为 50～95");
      }
    }
    if (section.equals("storage")) {
      nodes.locked(
          () -> {
            nodes.admit(value.getOrDefault("defaultBackend", "local"), false, 0);
            settings.set(section, value);
            return null;
          });
    } else settings.set(section, value);
  }

  public record Test(@Email String email) {}

  @PostMapping("/{section}/test")
  public Map<String, Object> test(
      Authentication a, @PathVariable String section, @Valid @RequestBody Test in)
      throws Exception {
    settings.admin(a.getName());
    section(section);
    if (section.equals("mail")) {
      if (in.email() == null || in.email().isBlank()) throw Problem.invalid("请输入测试收件邮箱");
      mail.send(in.email(), "CloudNest 邮件连接测试", "邮件服务连接正常。");
      return Map.of("ok", true);
    }
    if (section.equals("office")) {
      var config = settings.get(section);
      var base = RuntimeSettings.httpUrl(config.getOrDefault("url", ""));
      var client =
          HttpClient.newBuilder()
              .connectTimeout(Duration.ofSeconds(5))
              .followRedirects(HttpClient.Redirect.NEVER)
              .build();
      var response =
          client.send(
              HttpRequest.newBuilder(
                      base.resolve(base.getPath().replaceAll("/+$", "") + "/healthcheck"))
                  .timeout(Duration.ofSeconds(5))
                  .GET()
                  .build(),
              HttpResponse.BodyHandlers.discarding());
      if (response.statusCode() != 200)
        throw new Problem(502, "OFFICE_UNAVAILABLE", "Office 服务健康检查失败");
      return Map.of("ok", true);
    }
    throw Problem.invalid("请在存储策略中测试连接");
  }
}
