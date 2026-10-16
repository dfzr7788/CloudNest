package com.cloudnest.storage;

import static com.cloudnest.common.Db.*;

import com.cloudnest.common.*;
import java.io.*;
import java.util.*;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.*;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.ConfigurableApplicationContext;
import org.springframework.stereotype.Component;

/** Offline operator command; never exposed as an HTTP endpoint. Stop the normal server first. */
@Component
@ConditionalOnProperty(name = "cloudnest.maintenance", havingValue = "true")
public class Maintenance implements ApplicationRunner {
  private final Db db;
  private final StorageService storage;
  private final ConfigurableApplicationContext context;
  private final String action;

  @Value("${cloudnest.operator-username:}")
  private String operatorUsername;

  @Value("${cloudnest.quota}")
  private long defaultQuota;

  public Maintenance(
      Db db,
      StorageService storage,
      ConfigurableApplicationContext context,
      @Value("${cloudnest.action:audit}") String action) {
    this.db = db;
    this.storage = storage;
    this.context = context;
    this.action = action;
  }

  @Override
  public void run(ApplicationArguments args) throws Exception {
    Map<String, Object> output = perform();
    System.out.println(
        "CLOUDNEST_MAINTENANCE_RESULT="
            + new com.fasterxml.jackson.databind.ObjectMapper().writeValueAsString(output));
    final int code = ((Number) output.get("problems")).intValue() == 0 ? 0 : 2;
    System.exit(SpringApplication.exit(context, () -> code));
  }

  public Map<String, Object> perform() {
    if (Set.of("create-admin", "create-user", "reset-password").contains(action)) return operator();
    if (!Set.of("audit", "recount", "migrate-minio").contains(action))
      throw new IllegalArgumentException("Action must be audit, recount or migrate-minio");
    List<Map<String, Object>> report = new ArrayList<>();
    int problems = 0;
    for (var user : db.list("SELECT * FROM sys_user ORDER BY id")) {
      String uid = s(user, "id");
      Map<String, Object> result =
          db.atomic(
              uid,
              () -> {
                long
                    used =
                        db.count(
                            "SELECT (SELECT COALESCE(SUM(size),0) FROM drive_entry WHERE user_id=?"
                                + " AND kind='FILE') + (SELECT COALESCE(SUM(v.size),0) FROM"
                                + " file_version v JOIN drive_entry e ON e.id=v.entry_id WHERE"
                                + " e.user_id=?)",
                            uid,
                            uid),
                    reserved =
                        db.count(
                            "SELECT COALESCE(SUM(size),0) FROM upload_session WHERE user_id=? AND"
                                + " status IN ('UPLOADING','ASSEMBLING')",
                            uid);
                Map<String, Object> m = new LinkedHashMap<>();
                m.put("userId", uid);
                m.put("expectedUsed", used);
                m.put("actualUsed", n(user, "storage_used"));
                m.put("expectedReserved", reserved);
                m.put("actualReserved", n(user, "storage_reserved"));
                if (action.equals("recount")) {
                  db.update(
                      "UPDATE sys_user SET storage_used=?,storage_reserved=? WHERE id=?",
                      used,
                      reserved,
                      uid);
                  m.put("recounted", true);
                }
                return m;
              });
      report.add(result);
      if (!result.get("expectedUsed").equals(result.get("actualUsed"))
          || !result.get("expectedReserved").equals(result.get("actualReserved"))) problems++;
    }
    for (var blob : db.list("SELECT * FROM file_blob WHERE status='READY' ORDER BY id")) {
      String uid = s(blob, "user_id");
      Map<String, Object> result =
          db.atomic(
              uid,
              () -> {
                Map<String, Object> m = new LinkedHashMap<>();
                m.put("blobId", blob.get("id"));
                m.put("backend", blob.get("backend"));
                try {
                  String hash;
                  try (var in =
                      storage.open(s(blob, "backend"), s(blob, "object_key"), 0, n(blob, "size"))) {
                    hash = Util.hash(in);
                  }
                  boolean valid = hash.equals(s(blob, "sha256"));
                  m.put("checksumValid", valid);
                  if (valid
                      && action.equals("migrate-minio")
                      && s(blob, "backend").equals("local")) {
                    try (var input =
                        storage.open("local", s(blob, "object_key"), 0, n(blob, "size"))) {
                      storage.put("minio", s(blob, "object_key"), input, n(blob, "size"));
                    }
                    try (var input =
                        storage.open("minio", s(blob, "object_key"), 0, n(blob, "size"))) {
                      if (!Util.hash(input).equals(hash))
                        throw new IOException("Migration checksum mismatch");
                    }
                    db.update(
                        "UPDATE file_blob SET backend='minio' WHERE id=? AND backend='local'",
                        blob.get("id"));
                    m.put("migrated", true);
                  }
                } catch (IOException e) {
                  m.put("checksumValid", false);
                  m.put("error", e.getMessage());
                }
                return m;
              });
      report.add(result);
      if (Boolean.FALSE.equals(result.get("checksumValid"))) problems++;
    }
    return Map.of("action", action, "problems", problems, "records", report);
  }

  private Map<String, Object> operator() {
    String username = operatorUsername == null ? "" : operatorUsername.toLowerCase(Locale.ROOT),
        password = System.getenv("CN_OPERATOR_PASSWORD");
    if (!username.matches("[a-z0-9_]{3,40}")
        || password == null
        || password.length() < 8
        || password.length() > 64
        || password.getBytes(java.nio.charset.StandardCharsets.UTF_8).length > 72)
      throw new IllegalArgumentException(
          "需要有效的 cloudnest.operator-username 与 CN_OPERATOR_PASSWORD 环境变量（8～64 字符、UTF-8 最多72字节）");
    String hash =
        new org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder(12).encode(password);
    return db.transaction(
        () -> {
          var rows = db.list("SELECT * FROM sys_user WHERE username=?", username);
          String uid;
          if (action.equals("reset-password")) {
            if (rows.isEmpty()) throw Problem.missing();
            uid = s(rows.getFirst(), "id");
            db.update("UPDATE sys_user SET password_hash=? WHERE id=?", hash, uid);
            db.update("DELETE FROM auth_session WHERE user_id=?", uid);
          } else {
            if (!rows.isEmpty()) throw Problem.conflict("账号已存在");
            uid = Util.id();
            String root = Util.id();
            long now = System.currentTimeMillis();
            db.update(
                "INSERT INTO"
                    + " sys_user(id,username,password_hash,nickname,root_id,storage_quota,created_at,administrator)"
                    + " VALUES(?,?,?,?,?,?,?,?)",
                uid,
                username,
                hash,
                username,
                root,
                defaultQuota,
                now,
                action.equals("create-admin"));
            db.update(
                "INSERT INTO drive_entry(id,user_id,parent_id,kind,name,created_at,updated_at)"
                    + " VALUES(?,?,NULL,'FOLDER','我的云盘',?,?)",
                root,
                uid,
                now,
                now);
          }
          db.update(
              "INSERT INTO activity_log(id,user_id,action,created_at) VALUES(?,?,?,?)",
              Util.id(),
              uid,
              action.toUpperCase(Locale.ROOT).replace('-', '_'),
              System.currentTimeMillis());
          return Map.of("action", action, "userId", uid, "problems", 0);
        });
  }
}
