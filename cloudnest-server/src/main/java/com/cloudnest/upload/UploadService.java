package com.cloudnest.upload;

import static com.cloudnest.common.Db.*;

import com.cloudnest.common.*;
import com.cloudnest.file.DriveService;
import com.cloudnest.storage.StorageService;
import jakarta.annotation.PostConstruct;
import java.io.*;
import java.nio.file.*;
import java.time.Duration;
import java.util.*;
import org.apache.tika.Tika;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;

@Service
public class UploadService {
  @org.springframework.beans.factory.annotation.Autowired
  private com.cloudnest.node.NodeService nodes;

  @Value("${cloudnest.maintenance:false}")
  private boolean maintenance;

  private final Db db;
  private final DriveService drive;
  private final StorageService storage;
  private final Path parts;
  private final long max, partSize, lifetime;
  private final String backend;

  @org.springframework.beans.factory.annotation.Autowired
  private com.cloudnest.user.RuntimeSettings settings;

  private final Object[] locks = new Object[256];

  public UploadService(
      Db db,
      DriveService drive,
      StorageService storage,
      @Value("${cloudnest.data-dir}") String dir,
      @Value("${cloudnest.max-file-size}") long max,
      @Value("${cloudnest.part-size}") long partSize,
      @Value("${cloudnest.upload-hours}") int hours,
      @Value("${cloudnest.storage}") String backend)
      throws IOException {
    this.db = db;
    this.drive = drive;
    this.storage = storage;
    this.parts = Path.of(dir).toAbsolutePath().resolve("parts");
    Files.createDirectories(parts);
    this.max = max;
    this.partSize = partSize;
    this.lifetime = Duration.ofHours(hours).toMillis();
    this.backend = backend;
    if (partSize < 1024
        || partSize > 100 * 1024 * 1024L
        || max < 0
        || !Set.of("local", "minio").contains(backend))
      throw new IllegalArgumentException("Invalid upload settings");
    Arrays.setAll(locks, i -> new Object());
  }

  @PostConstruct
  void recover() {
    db.update("UPDATE upload_session SET status='UPLOADING' WHERE status='ASSEMBLING'");
  }

  private Object lock(String id) {
    return locks[(id.hashCode() & 0x7fffffff) % locks.length];
  }

  private Path dir(String id) {
    if (!id.matches("[a-f0-9-]{36}")) throw Problem.missing();
    return parts.resolve(id);
  }

  private Map<String, Object> session(String uid, String id) {
    return db.one("SELECT * FROM upload_session WHERE id=? AND user_id=?", id, uid);
  }

  private void uploading(Map<String, Object> s) {
    if (!s(s, "status").equals("UPLOADING") || n(s, "expires_at") < System.currentTimeMillis())
      throw new Problem(409, "UPLOAD_INACTIVE", "上传已取消、过期或正在处理");
  }

  public Map<String, Object> init(String uid, String folder, String input, long size, String hash) {
    return init(uid, folder, input, size, hash, null, null);
  }

  public Map<String, Object> init(
      String uid,
      String folder,
      String input,
      long size,
      String hash,
      String target,
      Long revision) {
    if (size < 0 || size > max) throw new Problem(413, "TOO_LARGE", "文件超过允许大小");
    if (hash == null || !hash.matches("[a-f0-9]{64}")) throw Problem.invalid("需要有效的 SHA-256");
    String name = Util.name(input);
    return db.atomic(
        uid,
        () ->
            nodes.locked(
                () -> {
                  String parent = s(drive.folder(uid, folder), "id");
                  var pending =
                      db.list(
                          "SELECT * FROM upload_session WHERE user_id=? AND folder_id=? AND name=?"
                              + " AND size=? AND sha256=? AND status='UPLOADING' AND expires_at>?"
                              + " ORDER BY created_at DESC",
                          uid,
                          parent,
                          name,
                          size,
                          hash,
                          System.currentTimeMillis());
                  if (!pending.isEmpty()) {
                    var prior = pending.getFirst();
                    if (!Objects.equals(target, s(prior, "target_id"))
                        || (target != null
                            && !Objects.equals(revision, n(prior, "target_revision"))))
                      throw Problem.conflict("已有同名上传，请先继续或取消该任务");
                    return describe(prior);
                  }
                  if (db.count(
                          "SELECT COUNT(*) FROM upload_session WHERE user_id=? AND status IN"
                              + " ('UPLOADING','ASSEMBLING')",
                          uid)
                      >= 20) throw Problem.conflict("最多同时保留 20 个上传会话，请先取消或完成已有上传");
                  if (target != null) {
                    var e = drive.file(uid, target);
                    if (!parent.equals(s(e, "parent_id"))
                        || !name.equals(s(e, "name"))
                        || revision == null
                        || revision != n(e, "revision")) throw Problem.conflict("覆盖目标已变化，请重新选择文件");
                    if (db.count("SELECT COUNT(*) FROM file_version WHERE entry_id=?", target)
                        >= 20) throw Problem.conflict("历史版本已达上限，请先清理旧版本");
                  }
                  drive.available(uid, parent, "FILE", name, target);
                  var user = db.one("SELECT * FROM sys_user WHERE id=?", uid);
                  if (n(user, "storage_used") + n(user, "storage_reserved")
                      > n(user, "storage_quota") - size)
                    throw new Problem(409, "QUOTA_EXCEEDED", "存储空间不足，请清理回收站或取消上传");
                  String selectedBackend =
                      settings.get("storage").getOrDefault("defaultBackend", backend);
                  if (selectedBackend.startsWith("s3:")
                      && db.count(
                              "SELECT COUNT(*) FROM storage_policy WHERE id=? AND enabled=TRUE",
                              selectedBackend.substring(3))
                          != 1) throw Problem.conflict("默认存储策略不可用，请联系管理员");
                  String id = Util.id(), key = "objects/" + Util.id();
                  nodes.admit(selectedBackend, false, size);
                  nodes.reserve(id, selectedBackend, size, Math.multiplyExact(size, 3));
                  long now = System.currentTimeMillis();
                  db.update(
                      "INSERT INTO"
                          + " upload_session(id,user_id,folder_id,name,size,sha256,part_size,part_count,status,object_key,backend,created_at,expires_at)"
                          + " VALUES(?,?,?,?,?,?,?,?,'UPLOADING',?,?,?,?)",
                      id,
                      uid,
                      parent,
                      name,
                      size,
                      hash,
                      partSize,
                      Math.max(1, (size + partSize - 1) / partSize),
                      key,
                      selectedBackend,
                      now,
                      now + lifetime);
                  db.update(
                      "UPDATE sys_user SET storage_reserved=storage_reserved+? WHERE id=?",
                      size,
                      uid);
                  if (target != null)
                    db.update(
                        "UPDATE upload_session SET target_id=?,target_revision=? WHERE id=?",
                        target,
                        revision,
                        id);
                  var reusable =
                      db.list(
                          "SELECT b.* FROM file_blob b WHERE b.user_id=? AND b.sha256=? AND"
                              + " b.size=? AND b.status='READY' AND EXISTS(SELECT 1 FROM"
                              + " drive_entry e WHERE e.blob_id=b.id AND e.user_id=?)",
                          uid,
                          hash,
                          size,
                          uid);
                  if (!reusable.isEmpty()) {
                    publish(session(uid, id), reusable.getFirst());
                    nodes.release(id);
                  }
                  return describe(session(uid, id));
                }));
  }

  public Map<String, Object> status(String uid, String id) {
    synchronized (lock(id)) {
      return describe(session(uid, id));
    }
  }

  public List<Map<String, Object>> pending(String uid) {
    return db
        .list(
            "SELECT * FROM upload_session WHERE user_id=? AND status='UPLOADING' AND expires_at>?"
                + " ORDER BY created_at DESC",
            uid,
            System.currentTimeMillis())
        .stream()
        .map(this::describe)
        .toList();
  }

  private Map<String, Object> describe(Map<String, Object> s) {
    Map<String, Object> m = new LinkedHashMap<>();
    for (String k : List.of("id", "name", "size", "sha256", "status")) m.put(k, s.get(k));
    m.put("folderId", s.get("folder_id"));
    m.put("partSize", s.get("part_size"));
    m.put("partCount", s.get("part_count"));
    m.put("expiresAt", s.get("expires_at"));
    m.put("fileId", s.get("result_id"));
    m.put(
        "uploadedParts",
        db
            .list(
                "SELECT part_number FROM upload_part WHERE session_id=? ORDER BY part_number",
                s.get("id"))
            .stream()
            .map(p -> n(p, "part_number"))
            .toList());
    return m;
  }

  public void part(String uid, String id, int number, InputStream in) throws IOException {
    synchronized (lock(id)) {
      var s = session(uid, id);
      uploading(s);
      long expected =
          number == n(s, "part_count") - 1
              ? n(s, "size") - (long) number * n(s, "part_size")
              : n(s, "part_size");
      if (number < 0 || number >= n(s, "part_count")) throw Problem.invalid("分片编号无效");
      Path directory = dir(id);
      Files.createDirectories(directory);
      Path tmp = Files.createTempFile(directory, "incoming-", ".tmp");
      try {
        if (Files.getFileStore(directory).getUsableSpace() < expected + 64 * 1024 * 1024L)
          throw new Problem(507, "INSUFFICIENT_STORAGE", "服务器临时空间不足，请稍后重试");
        var digest = Util.digest();
        long written = 0;
        byte[] buf = new byte[65536];
        try (var out = Files.newOutputStream(tmp)) {
          for (int n; (n = in.read(buf)) != -1; ) {
            written += n;
            if (written > expected) throw Problem.invalid("分片长度超过约定大小");
            out.write(buf, 0, n);
            digest.update(buf, 0, n);
          }
        }
        if (written != expected) throw Problem.invalid("分片长度不完整");
        String hash = HexFormat.of().formatHex(digest.digest());
        var old =
            db.list("SELECT * FROM upload_part WHERE session_id=? AND part_number=?", id, number);
        if (!old.isEmpty() && !hash.equals(s(old.getFirst(), "sha256")))
          throw Problem.conflict("重复分片内容不一致");
        Files.move(tmp, directory.resolve(number + ".part"), StandardCopyOption.REPLACE_EXISTING);
        if (old.isEmpty())
          db.update(
              "INSERT INTO upload_part(session_id,part_number,size,sha256) VALUES(?,?,?,?)",
              id,
              number,
              written,
              hash);
      } finally {
        Files.deleteIfExists(tmp);
      }
    }
  }

  public Map<String, Object> complete(String uid, String id) throws IOException {
    synchronized (lock(id)) {
      var s = session(uid, id);
      if (s(s, "status").equals("COMPLETE")) return describe(s);
      uploading(s);
      var rows = db.list("SELECT * FROM upload_part WHERE session_id=? ORDER BY part_number", id);
      if (rows.size() != n(s, "part_count")) throw Problem.conflict("分片尚未全部上传");
      db.update("UPDATE upload_session SET status='ASSEMBLING' WHERE id=?", id);
      Path merged = dir(id).resolve("merged.tmp");
      try {
        var digest = Util.digest();
        long total = 0;
        try (var out = Files.newOutputStream(merged)) {
          for (var p : rows) {
            Path source = dir(id).resolve(n(p, "part_number") + ".part");
            try (var in = Files.newInputStream(source)) {
              var pd = Util.digest();
              byte[] buf = new byte[65536];
              long size = 0;
              for (int n; (n = in.read(buf)) != -1; ) {
                out.write(buf, 0, n);
                digest.update(buf, 0, n);
                pd.update(buf, 0, n);
                total += n;
                size += n;
              }
              if (size != n(p, "size")
                  || !HexFormat.of().formatHex(pd.digest()).equals(s(p, "sha256")))
                throw new Problem(422, "CHECKSUM_MISMATCH", "分片校验失败，请取消后重新上传");
            }
          }
        }
        if (total != n(s, "size")
            || !HexFormat.of().formatHex(digest.digest()).equals(s(s, "sha256")))
          throw new Problem(422, "CHECKSUM_MISMATCH", "文件校验失败，请取消后重新上传");
        String mime;
        try (var input = Files.newInputStream(merged)) {
          mime = new Tika().detect(input);
        }
        try (var input = Files.newInputStream(merged)) {
          storage.put(s(s, "backend"), s(s, "object_key"), input, total);
        }
        final String detected = mime;
        db.atomic(
            uid,
            () ->
                nodes.locked(
                    () -> {
                      var current = session(uid, id);
                      if (!s(current, "status").equals("ASSEMBLING"))
                        throw Problem.conflict("上传状态已改变");
                      drive.folder(uid, s(current, "folder_id"));
                      drive.available(
                          uid,
                          s(current, "folder_id"),
                          "FILE",
                          s(current, "name"),
                          s(current, "target_id"));
                      // Recheck under the user row lock: two simultaneous uploads may have
                      // identical
                      // content.
                      var blobs =
                          db.list(
                              "SELECT * FROM file_blob WHERE user_id=? AND sha256=? AND size=? AND"
                                  + " status='READY'",
                              uid,
                              s(s, "sha256"),
                              n(s, "size"));
                      Map<String, Object> blob;
                      if (blobs.isEmpty()) {
                        String bid = Util.id();
                        db.update(
                            "INSERT INTO"
                                + " file_blob(id,user_id,object_key,backend,size,sha256,mime,status,created_at)"
                                + " VALUES(?,?,?,?,?,?,?,'READY',?)",
                            bid,
                            uid,
                            s(s, "object_key"),
                            s(s, "backend"),
                            n(s, "size"),
                            s(s, "sha256"),
                            detected,
                            System.currentTimeMillis());
                        blob = db.one("SELECT * FROM file_blob WHERE id=?", bid);
                      } else blob = blobs.getFirst();
                      publish(current, blob);
                      return null;
                    }));
        // Only delete this session's candidate if another already verified object was reused.
        if (db.count("SELECT COUNT(*) FROM file_blob WHERE object_key=?", s(s, "object_key")) == 0)
          try {
            storage.delete(s(s, "backend"), s(s, "object_key"));
            nodes.locked(
                () -> {
                  nodes.release(id);
                  return null;
                });
          } catch (IOException e) {
            LoggerFactory.getLogger(getClass()).warn("Deferred duplicate object cleanup {}", id, e);
          }
        cleanParts(id);
        if (db.count("SELECT COUNT(*) FROM file_blob WHERE object_key=?", s(s, "object_key")) > 0)
          nodes.locked(
              () -> {
                nodes.release(id);
                return null;
              });
        return describe(session(uid, id));
      } catch (IOException | RuntimeException e) {
        db.update(
            "UPDATE upload_session SET status='UPLOADING' WHERE id=? AND status='ASSEMBLING'", id);
        throw e;
      } finally {
        Files.deleteIfExists(merged);
      }
    }
  }

  private void publish(Map<String, Object> s, Map<String, Object> blob) {
    String uid = s(s, "user_id"), fid = s(s, "target_id") == null ? Util.id() : s(s, "target_id");
    long now = System.currentTimeMillis();
    if (s(s, "target_id") != null) {
      var old = drive.file(uid, fid);
      if (n(old, "revision") != n(s, "target_revision")
          || !Objects.equals(old.get("parent_id"), s.get("folder_id"))
          || !Objects.equals(old.get("name"), s.get("name")))
        throw Problem.conflict("覆盖目标已变化，请取消任务后重新上传");
      if (db.count("SELECT COUNT(*) FROM file_version WHERE entry_id=?", fid) >= 20)
        throw Problem.conflict("历史版本已达上限");
      db.update(
          "INSERT INTO file_version(id,entry_id,blob_id,size,mime,created_at) VALUES(?,?,?,?,?,?)",
          Util.id(),
          fid,
          old.get("blob_id"),
          old.get("size"),
          old.get("mime"),
          old.get("updated_at"));
      db.update(
          "UPDATE drive_entry SET blob_id=?,size=?,mime=?,updated_at=?,revision=revision+1 WHERE"
              + " id=?",
          blob.get("id"),
          s.get("size"),
          blob.get("mime"),
          now,
          fid);
    } else
      db.update(
          "INSERT INTO"
              + " drive_entry(id,user_id,parent_id,kind,name,blob_id,size,mime,created_at,updated_at)"
              + " VALUES(?,?,?,'FILE',?,?,?,?,?,?)",
          fid,
          uid,
          s(s, "folder_id"),
          s(s, "name"),
          s(blob, "id"),
          n(s, "size"),
          s(blob, "mime"),
          now,
          now);
    db.update(
        "UPDATE sys_user SET storage_reserved=storage_reserved-?,storage_used=storage_used+? WHERE"
            + " id=?",
        n(s, "size"),
        n(s, "size"),
        uid);
    nodes.locked(
        () -> {
          db.update(
              "UPDATE upload_session SET status='COMPLETE',result_id=? WHERE id=?",
              fid,
              s(s, "id"));
          if (Objects.equals(s(s, "object_key"), s(blob, "object_key")))
            db.update("UPDATE storage_reservation SET bytes=0 WHERE id=?", s(s, "id"));
          return null;
        });
    db.update(
        "INSERT INTO activity_log(id,user_id,action,target_id,created_at) VALUES(?,?,?,?,?)",
        Util.id(),
        uid,
        "UPLOAD_COMPLETED",
        fid,
        now);
  }

  public void cancel(String uid, String id) {
    synchronized (lock(id)) {
      db.atomic(
          uid,
          () -> {
            var s = session(uid, id);
            if (Set.of("UPLOADING", "ASSEMBLING").contains(s(s, "status"))) {
              db.update("UPDATE upload_session SET status='CANCELLED' WHERE id=?", id);
              db.update(
                  "UPDATE sys_user SET storage_reserved=storage_reserved-? WHERE id=?",
                  n(s, "size"),
                  uid);
            }
            return null;
          });
      try {
        var cancelled = session(uid, id);
        if ("CANCELLED".equals(s(cancelled, "status"))) {
          storage.delete(s(cancelled, "backend"), s(cancelled, "object_key"));
          cleanParts(id);
          nodes.locked(
              () -> {
                nodes.release(id);
                return null;
              });
        }
      } catch (IOException e) {
        LoggerFactory.getLogger(getClass()).warn("Deferred part cleanup {}", id, e);
      }
    }
  }

  private void cleanParts(String id) throws IOException {
    Path p = dir(id);
    if (Files.isDirectory(p))
      try (var files = Files.list(p)) {
        for (Path f : files.toList()) Files.deleteIfExists(f);
      }
    Files.deleteIfExists(p);
  }

  @Scheduled(fixedDelay = 60000, initialDelay = 60000)
  public void cleanup() {
    if (maintenance) return;
    long now = System.currentTimeMillis();
    db.update("UPDATE file_share SET pinned_blob=NULL WHERE revoked=TRUE OR expires_at<=?", now);
    for (var candidate :
        db.list(
            "SELECT b.* FROM file_blob b WHERE b.status='READY' AND NOT EXISTS(SELECT 1 FROM"
                + " drive_entry e WHERE e.blob_id=b.id) AND NOT EXISTS(SELECT 1 FROM file_version v"
                + " WHERE v.blob_id=b.id) AND NOT EXISTS(SELECT 1 FROM file_share s WHERE"
                + " s.pinned_blob=b.id)"))
      db.atomic(
          s(candidate, "user_id"),
          () -> {
            drive.releaseBlob(s(candidate, "id"));
            return null;
          });
    for (var s :
        db.list(
            "SELECT * FROM upload_session WHERE (status='UPLOADING' AND expires_at<?) OR"
                + " status='CANCELLED' OR (status='COMPLETE' AND created_at<?)",
            now,
            now - 3600000)) {
      String id = s(s, "id"), uid = s(s, "user_id");
      synchronized (lock(id)) {
        try {
          if (s(s, "status").equals("UPLOADING")) cancel(uid, id);
          cleanParts(id);
          if (db.count("SELECT COUNT(*) FROM file_blob WHERE object_key=?", s(s, "object_key"))
              == 0) storage.delete(s(s, "backend"), s(s, "object_key"));
          nodes.locked(
              () -> {
                nodes.release(id);
                return null;
              });
          if (n(s, "expires_at") < now) {
            db.update("DELETE FROM upload_part WHERE session_id=?", id);
            db.update(
                "DELETE FROM upload_session WHERE id=? AND status IN ('CANCELLED','COMPLETE')", id);
          }
        } catch (Exception e) {
          LoggerFactory.getLogger(getClass()).warn("Upload cleanup will retry {}", id, e);
        }
      }
    }
    for (var b : db.list("SELECT * FROM file_blob WHERE status='DELETING'"))
      try {
        db.atomic(
            s(b, "user_id"),
            () -> {
              if (!drive.referenced(s(b, "id"))) {
                try {
                  storage.delete(s(b, "backend"), s(b, "object_key"));
                } catch (IOException e) {
                  throw new java.io.UncheckedIOException(e);
                }
                db.update("DELETE FROM file_blob WHERE id=?", s(b, "id"));
              }
              return null;
            });
      } catch (Exception e) {
        LoggerFactory.getLogger(getClass()).warn("Object cleanup will retry {}", s(b, "id"), e);
      }
    db.update("DELETE FROM auth_session WHERE expires_at<?", now);
    db.update("DELETE FROM share_grant WHERE expires_at<?", now);
    db.update("DELETE FROM activity_log WHERE created_at<?", now - 90L * 86400000);
  }
}
