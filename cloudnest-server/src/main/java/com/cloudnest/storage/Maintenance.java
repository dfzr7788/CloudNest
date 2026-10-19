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
  @org.springframework.beans.factory.annotation.Autowired
  private com.cloudnest.node.NodeService nodes;

  @Value("${cloudnest.restore-endpoint:}")
  private String restoreEndpoint;

  @Value("${cloudnest.minio.endpoint}")
  private String legacyEndpoint;

  private final Db db;
  private final StorageService storage;
  private final ConfigurableApplicationContext context;
  private final String action;

  @Value("${cloudnest.operator-username:}")
  private String operatorUsername;

  @Value("${cloudnest.data-dir}")
  private String dataDirectory;

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
    if (action.equals("object-manifest")) return objectManifest();
    if (action.equals("verify-manifest")) return verifyManifest();
    if (action.equals("restore-split-target")) return restoreSplitTarget();
    if (Set.of("export-objects", "restore-local-objects").contains(action))
      return portableObjects();
    if (Set.of("create-admin", "create-user", "reset-password", "reset-mfa").contains(action))
      return operator();
    if (!Set.of("audit", "recount", "migrate-minio").contains(action))
      throw new IllegalArgumentException("Action must be audit, recount or migrate-minio");
    if (action.equals("migrate-minio") && nodes != null) nodes.check("minio", true);
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
                    if (nodes != null)
                      nodes.locked(
                          () -> {
                            nodes.admit("minio", true, n(blob, "size"));
                            nodes.copied("minio", s(blob, "object_key"), n(blob, "size"));
                            return null;
                          });
                    try (var input =
                        storage.open("local", s(blob, "object_key"), 0, n(blob, "size"))) {
                      storage.put("minio", s(blob, "object_key"), input, n(blob, "size"));
                    }
                    try (var input =
                        storage.open("minio", s(blob, "object_key"), 0, n(blob, "size"))) {
                      if (!Util.hash(input).equals(hash))
                        throw new IOException("Migration checksum mismatch");
                    }
                    if (nodes != null)
                      nodes.locked(
                          () -> {
                            nodes.copied("local", s(blob, "object_key"), n(blob, "size"));
                            db.update(
                                "UPDATE file_blob SET backend='minio' WHERE id=? AND"
                                    + " backend='local'",
                                blob.get("id"));
                            return null;
                          });
                    else
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
    for (var replica :
        db.list(
            "SELECT r.*,b.sha256 FROM blob_replica r JOIN file_blob b ON b.id=r.blob_id WHERE"
                + " b.status='READY' AND b.desired_replicas=2")) {
      boolean valid = false;
      try (var input =
          storage.openWhole(s(replica, "backend"), s(replica, "object_key"), n(replica, "size"))) {
        valid = Util.hash(input).equals(s(replica, "sha256"));
      } catch (IOException unavailable) {
      }
      report.add(
          Map.of(
              "blobId",
              s(replica, "blob_id"),
              "backend",
              s(replica, "backend"),
              "replicaChecksumValid",
              valid));
      if (!valid) problems++;
    }
    return Map.of("action", action, "problems", problems, "records", report);
  }

  private Map<String, Object> objectManifest() {
    var objects =
        db.list(
            "SELECT id,backend,object_key,size,sha256 FROM file_blob WHERE status='READY' ORDER BY"
                + " id");
    var replicaRecords =
        db.list(
            "SELECT r.*,b.sha256 FROM blob_replica r JOIN file_blob b ON b.id=r.blob_id ORDER BY"
                + " r.blob_id,r.backend");
    var policies = db.list("SELECT id,endpoint,bucket,region FROM storage_policy ORDER BY id");
    var backends =
        db
            .list(
                "SELECT backend FROM file_blob UNION SELECT backend FROM upload_session UNION"
                    + " SELECT backend FROM storage_copy UNION SELECT backend FROM blob_replica")
            .stream()
            .map(row -> s(row, "backend"))
            .toList();
    try {
      var file = java.nio.file.Path.of(dataDirectory).resolve("backup-manifest.json");
      new com.fasterxml.jackson.databind.ObjectMapper()
          .writeValue(
              file.toFile(),
              Map.of(
                  "format",
                  1,
                  "objects",
                  objects,
                  "replicas",
                  replicaRecords,
                  "policies",
                  policies,
                  "legacyEndpoint",
                  legacyEndpoint,
                  "backends",
                  backends));
    } catch (IOException e) {
      throw new IllegalStateException("Manifest export failed", e);
    }
    return Map.of("action", action, "problems", 0, "objects", objects.size());
  }

  private Map<String, Object> verifyManifest() {
    try {
      var json = new com.fasterxml.jackson.databind.ObjectMapper();
      var manifest =
          json.readTree(
              java.nio.file.Path.of(dataDirectory).resolve("backup-manifest.json").toFile());
      var actual =
          db.list(
              "SELECT id,backend,object_key,size,sha256 FROM file_blob WHERE status='READY' ORDER"
                  + " BY id");
      var expected = manifest.path("objects");
      if (!expected.isArray() || expected.size() != actual.size())
        throw new IOException("Manifest count mismatch");
      for (int i = 0; i < actual.size(); i++) {
        var row = actual.get(i);
        var saved = expected.get(i);
        for (String field : List.of("id", "backend", "object_key", "sha256"))
          if (!s(row, field).equals(saved.path(field).asText()))
            throw new IOException("Manifest reference mismatch");
        if (n(row, "size") != saved.path("size").asLong(-1))
          throw new IOException("Manifest size mismatch");
      }
      if (manifest.has("replicas")) {
        var replicas =
            db.list(
                "SELECT r.*,b.sha256 FROM blob_replica r JOIN file_blob b ON b.id=r.blob_id ORDER"
                    + " BY r.blob_id,r.backend");
        if (!json.readTree(json.writeValueAsBytes(replicas)).equals(manifest.path("replicas")))
          throw new IOException("Replica manifest mismatch");
      }
      return Map.of("action", action, "problems", 0, "objects", actual.size());
    } catch (IOException e) {
      throw new IllegalStateException("Restored database does not match checkpoint manifest", e);
    }
  }

  private Map<String, Object> restoreSplitTarget() {
    var uri = com.cloudnest.user.RuntimeSettings.httpUrl(restoreEndpoint);
    if (!uri.getPath().isEmpty())
      throw new IllegalArgumentException("Endpoint must not contain a path");
    return nodes.locked(
        () -> {
          db.update("UPDATE storage_policy SET endpoint=?", restoreEndpoint);
          db.update(
              "UPDATE storage_node SET"
                  + " direct_download=FALSE,public_endpoint=NULL,management='WRITE_PAUSED'");
          db.update(
              "UPDATE storage_node_health SET"
                  + " state='UNKNOWN',validated=FALSE,checked_at=0,failures=0,successes=0");
          db.update(
              "UPDATE storage_job_item SET status='INTERRUPTED',error='隔离恢复后需要重新检测并重试' WHERE status"
                  + " IN ('QUEUED','RUNNING')");
          db.update("DELETE FROM storage_reservation WHERE id LIKE 'job:%'");
          // Never resume old tasks against restored storage without a fresh operator decision.
          db.update(
              "UPDATE maintenance_job SET status='INTERRUPTED' WHERE status IN"
                  + " ('QUEUED','RUNNING')");
          return Map.of("action", action, "problems", 0);
        });
  }

  private Map<String, Object> portableObjects() {
    try {
      var directory =
          java.nio.file.Path.of(dataDirectory).toAbsolutePath().resolve("backup-export");
      java.nio.file.Files.createDirectories(directory.resolve("objects"));
      var records = db.list("SELECT * FROM file_blob WHERE status='READY' ORDER BY id");
      boolean restore = action.equals("restore-local-objects");
      long required =
          restore
              ? 0
              : records.stream()
                  .filter(b -> !"local".equals(s(b, "backend")))
                  .mapToLong(b -> n(b, "size"))
                  .sum();
      if (java.nio.file.Files.getFileStore(directory).getUsableSpace() < required + 268435456L)
        throw new IOException("Insufficient space for portable backup");
      int migrated = 0;
      for (var blob : records) {
        String key = s(blob, "object_key"), backend = s(blob, "backend");
        if (!key.matches("objects/[a-f0-9-]{36}")) throw new IOException("Invalid object key");
        if (backend.equals("local")) continue;
        var file = directory.resolve(key);
        if (restore) {
          try (var in = java.nio.file.Files.newInputStream(file)) {
            if (!Util.hash(in).equals(s(blob, "sha256")))
              throw new IOException("Backup checksum mismatch");
          }
          try (var in = java.nio.file.Files.newInputStream(file)) {
            storage.put("local", key, in, n(blob, "size"));
          }
          try (var in = storage.open("local", key, 0, n(blob, "size"))) {
            if (!Util.hash(in).equals(s(blob, "sha256")))
              throw new IOException("Restored object checksum mismatch");
          }
          db.update("UPDATE file_blob SET backend='local' WHERE id=?", blob.get("id"));
          migrated++;
        } else {
          var staged = file.resolveSibling(file.getFileName() + ".part");
          try (var in = storage.open(backend, key, 0, n(blob, "size"));
              var out = java.nio.file.Files.newOutputStream(staged)) {
            long copied = in.transferTo(out);
            if (copied != n(blob, "size")) throw new IOException("Backup object truncated");
          }
          try (var in = java.nio.file.Files.newInputStream(staged)) {
            if (!Util.hash(in).equals(s(blob, "sha256")))
              throw new IOException("Backup checksum mismatch");
          }
          java.nio.file.Files.move(staged, file, java.nio.file.StandardCopyOption.REPLACE_EXISTING);
          migrated++;
        }
      }
      if (restore) {
        db.update("UPDATE file_blob SET backend='local' WHERE status<>'READY'");
        db.update(
            "UPDATE upload_session SET backend='local' WHERE status IN ('UPLOADING','ASSEMBLING')");
        db.update("UPDATE upload_session SET backend='local'");
        db.update(
            "UPDATE storage_reservation SET backend='local' WHERE id IN (SELECT id FROM"
                + " upload_session)");
        db.update("DELETE FROM blob_replica");
        db.update(
            "INSERT INTO blob_replica(blob_id,backend,object_key,size) SELECT"
                + " id,backend,object_key,size FROM file_blob WHERE status='READY'");
        db.update(
            "UPDATE storage_job_item SET status='INTERRUPTED',error='便携恢复只保留本地数据，请重新配置保护目标' WHERE"
                + " status IN ('QUEUED','RUNNING')");
        db.update("DELETE FROM storage_reservation WHERE id LIKE 'job:%'");
        db.update("DELETE FROM storage_copy WHERE backend<>'local'");
        db.update(
            "UPDATE storage_node SET enabled=FALSE,direct_download=FALSE,public_endpoint=NULL WHERE"
                + " backend<>'local'");
        db.update("UPDATE storage_policy SET enabled=FALSE");
        db.update("DELETE FROM system_setting WHERE name='storage'");
      }
      return Map.of("action", action, "problems", 0, "objects", migrated);
    } catch (IOException error) {
      throw new IllegalStateException("Portable object backup/restore failed", error);
    }
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
          if (action.equals("reset-mfa")) {
            if (rows.isEmpty()) throw Problem.missing();
            var user = rows.getFirst();
            uid = s(user, "id");
            if (!new org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder()
                .matches(password, s(user, "password_hash")))
              throw new Problem(401, "INVALID_PASSWORD", "需要当前登录密码；忘记密码请先使用维护命令重置");
            db.update(
                "UPDATE sys_user SET totp_secret=NULL,totp_pending=NULL,totp_step=-1 WHERE id=?",
                uid);
            db.update("DELETE FROM mfa_recovery WHERE user_id=?", uid);
            db.update("DELETE FROM auth_session WHERE user_id=?", uid);
            db.update("DELETE FROM dav_credential WHERE user_id=?", uid);
            db.update("DELETE FROM account_token WHERE user_id=?", uid);
          } else if (action.equals("reset-password")) {
            if (rows.isEmpty()) throw Problem.missing();
            uid = s(rows.getFirst(), "id");
            db.update("UPDATE sys_user SET password_hash=? WHERE id=?", hash, uid);
            db.update("DELETE FROM auth_session WHERE user_id=?", uid);
            db.update("DELETE FROM dav_credential WHERE user_id=?", uid);
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
