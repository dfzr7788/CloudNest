package com.cloudnest.user;

import static com.cloudnest.common.Db.*;

import com.cloudnest.common.*;
import com.cloudnest.security.SecretCipher;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.net.URI;
import java.util.*;
import org.springframework.stereotype.Service;

@Service
public class RuntimeSettings {
  private final Db db;
  private final SecretCipher cipher;
  private final ObjectMapper json;
  private final com.cloudnest.control.IdentityAuthorization identity;

  public RuntimeSettings(
      Db db,
      SecretCipher cipher,
      ObjectMapper json,
      com.cloudnest.control.IdentityAuthorization identity) {
    this.db = db;
    this.cipher = cipher;
    this.json = json;
    this.identity = identity;
  }

  public void admin(String uid) {
    identity.requireAdministrator(uid);
  }

  public Map<String, String> get(String section) {
    var rows = db.list("SELECT setting_value FROM system_setting WHERE name=?", section);
    if (rows.isEmpty()) return new LinkedHashMap<>();
    try {
      return json.readValue(
          cipher.decrypt(s(rows.getFirst(), "setting_value")),
          new TypeReference<LinkedHashMap<String, String>>() {});
    } catch (java.io.IOException e) {
      throw new IllegalStateException(e);
    }
  }

  public void set(String section, Map<String, String> value) {
    try {
      String encoded = cipher.encrypt(json.writeValueAsString(value));
      db.transaction(
          () -> {
            db.update("DELETE FROM system_setting WHERE name=?", section);
            db.update(
                "INSERT INTO system_setting(name,setting_value) VALUES(?,?)", section, encoded);
            return null;
          });
    } catch (java.io.IOException e) {
      throw new IllegalStateException(e);
    }
  }

  public static URI httpUrl(String value) {
    try {
      var uri = URI.create(value);
      if (!Set.of("http", "https").contains(uri.getScheme())
          || uri.getHost() == null
          || uri.getUserInfo() != null
          || uri.getFragment() != null
          || uri.getQuery() != null) throw new IllegalArgumentException();
      return uri;
    } catch (Exception e) {
      throw Problem.invalid("请输入有效的 HTTP/HTTPS 服务地址");
    }
  }
}
