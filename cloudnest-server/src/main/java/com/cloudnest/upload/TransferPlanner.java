package com.cloudnest.upload;

import static com.cloudnest.common.Db.*;

import com.cloudnest.common.*;
import com.cloudnest.node.NodeService;
import com.cloudnest.storage.StorageService;
import java.io.*;
import java.nio.file.*;
import java.util.*;
import java.util.concurrent.Semaphore;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;

@Service
public class TransferPlanner {
  private final Db db;
  private final NodeService nodes;
  private final StorageService storage;
  private final UploadService uploads;
  private final com.cloudnest.WorkerLifecycle lifecycle;
  private final Path staging;
  private final Semaphore slots = new Semaphore(2);
  private final Object[] locks = new Object[128];

  public TransferPlanner(
      Db db,
      NodeService nodes,
      StorageService storage,
      UploadService uploads,
      com.cloudnest.WorkerLifecycle lifecycle,
      @Value("${cloudnest.data-dir}") String directory)
      throws IOException {
    this.db = db;
    this.nodes = nodes;
    this.storage = storage;
    this.uploads = uploads;
    this.lifecycle = lifecycle;
    Arrays.setAll(locks, i -> new Object());
    staging = Path.of(directory).toAbsolutePath().resolve("direct-upload");
    Files.createDirectories(staging);
    if (lifecycle.mayRecover())
      try (var files = Files.list(staging)) {
        for (var file : files.toList())
          if (file.getFileName().toString().matches("direct-.*\\.tmp")) Files.deleteIfExists(file);
      }
  }

  public Map<String, Object> plan(String uid, String id) {
    return db.atomic(
        uid,
        () ->
            nodes.locked(
                () -> {
                  var session =
                      db.one("SELECT * FROM upload_session WHERE id=? AND user_id=?", id, uid);
                  if (!s(session, "status").equals("UPLOADING")
                      || n(session, "expires_at") <= System.currentTimeMillis())
                    throw Problem.conflict("上传不可授权");
                  String backend = s(session, "backend");
                  var node = nodes.node(backend);
                  var fallback =
                      Map.<String, Object>of(
                          "mode",
                          "proxy",
                          "sessionId",
                          id,
                          "supportsResume",
                          true,
                          "partSize",
                          n(session, "part_size"),
                          "reason",
                          "DIRECT_UNAVAILABLE");
                  if (n(session, "size") > 104857600
                      || !backend.startsWith("s3:")
                      || !b(node, "public_upload")
                      || !b(node, "direct_download")
                      || !"ONLINE".equals(s(node, "state"))
                      || !b(node, "validated")
                      || System.currentTimeMillis() - n(node, "checked_at") > 120000)
                    return fallback;
                  try {
                    var old = db.list("SELECT * FROM direct_upload_ticket WHERE session_id=?", id);
                    String key =
                        old.isEmpty() ? "objects/" + Util.id() : s(old.getFirst(), "object_key");
                    var signed =
                        storage.presignUpload(
                            backend, key, s(node, "public_endpoint"), n(session, "size"), 60);
                    if (signed.isEmpty()) return fallback;
                    if (old.isEmpty()) {
                      nodes.admit(backend, false, n(session, "size"));
                      nodes.reserve("direct:" + id, backend, n(session, "size"), 0);
                      db.update(
                          "INSERT INTO"
                              + " direct_upload_ticket(session_id,user_id,backend,object_key,size,expires_at)"
                              + " VALUES(?,?,?,?,?,?)",
                          id,
                          uid,
                          backend,
                          key,
                          n(session, "size"),
                          System.currentTimeMillis() + 180000);
                    } else
                      db.update(
                          "UPDATE direct_upload_ticket SET expires_at=? WHERE session_id=?",
                          System.currentTimeMillis() + 180000,
                          id);
                    return Map.of(
                        "mode",
                        "direct-post",
                        "url",
                        signed.get().url(),
                        "fields",
                        signed.get().fields(),
                        "expiresAt",
                        System.currentTimeMillis() + 60000,
                        "sessionId",
                        id,
                        "fallback",
                        fallback,
                        "requiresServerCommit",
                        true);
                  } catch (IOException unsupported) {
                    return fallback;
                  }
                }));
  }

  /**
   * Consume the signed staging object through the existing bounded part/hash/publication pipeline.
   * The signed key is NEVER published as immutable file content: an unexpired signature can
   * overwrite it.
   */
  public Map<String, Object> commit(String uid, String id) throws IOException {
    synchronized (locks[(id.hashCode() & 0x7fffffff) % locks.length]) {
      return commitLocked(uid, id);
    }
  }

  private Map<String, Object> commitLocked(String uid, String id) throws IOException {
    var status = uploads.status(uid, id);
    if (s(status, "status").equals("COMPLETE")) return status;
    var ticket =
        db.one(
            "SELECT * FROM direct_upload_ticket WHERE session_id=? AND user_id=? AND expires_at>?",
            id,
            uid,
            System.currentTimeMillis());
    if (!slots.tryAcquire()) throw new Problem(503, "UPLOAD_BUSY", "直传验证繁忙，请稍后重试");
    Path file = null;
    try {
      file = Files.createTempFile(staging, "direct-", ".tmp");
      // Reject a corrupt staged object BEFORE recording any resumable part metadata.
      var digest = Util.digest();
      long total = 0;
      byte[] buffer = new byte[65536];
      try (var input =
              storage.openWhole(s(ticket, "backend"), s(ticket, "object_key"), n(ticket, "size"));
          var output = Files.newOutputStream(file)) {
        for (int count; (count = input.read(buffer)) != -1; ) {
          total += count;
          if (total > n(ticket, "size")) throw new Problem(422, "CHECKSUM_MISMATCH", "直传对象大小异常");
          digest.update(buffer, 0, count);
          output.write(buffer, 0, count);
        }
      }
      if (total != n(ticket, "size")
          || !HexFormat.of().formatHex(digest.digest()).equals(s(status, "sha256")))
        throw new Problem(422, "CHECKSUM_MISMATCH", "直传对象校验失败，可继续使用分片代理上传");
      try (var input = Files.newInputStream(file)) {
        long size = n(ticket, "size"), part = n(status, "partSize");
        for (int number = 0; number < n(status, "partCount"); number++) {
          final long length = Math.min(part, size - number * part);
          var slice =
              new FilterInputStream(input) {
                long left = length;

                @Override
                public int read() throws IOException {
                  if (left == 0) return -1;
                  int n = in.read();
                  if (n >= 0) left--;
                  return n;
                }

                @Override
                public int read(byte[] b, int off, int len) throws IOException {
                  if (len == 0) return 0;
                  if (left == 0) return -1;
                  int n = in.read(b, off, (int) Math.min(len, left));
                  if (n > 0) left -= n;
                  return n;
                }

                @Override
                public void close() {}
              };
          uploads.part(uid, id, number, slice);
        }
        if (input.read() != -1) throw new Problem(422, "CHECKSUM_MISMATCH", "直传对象大小异常");
      }
      return uploads.complete(uid, id);
    } finally {
      try {
        if (file != null) Files.deleteIfExists(file);
      } finally {
        slots.release();
      }
    }
  }

  @Scheduled(fixedDelay = 60000, initialDelay = 60000)
  public void cleanup() {
    if (!lifecycle.mayRecover()) return;
    for (var t :
        db.list(
            "SELECT * FROM direct_upload_ticket WHERE expires_at<?", System.currentTimeMillis())) {
      String uid = s(t, "user_id"), id = s(t, "session_id");
      db.atomic(
          uid,
          () ->
              nodes.locked(
                  () -> {
                    var current =
                        db.list(
                            "SELECT * FROM direct_upload_ticket WHERE session_id=? AND"
                                + " expires_at<?",
                            id,
                            System.currentTimeMillis());
                    if (current.isEmpty()) return null;
                    try {
                      storage.delete(s(t, "backend"), s(t, "object_key"));
                      db.update("DELETE FROM direct_upload_ticket WHERE session_id=?", id);
                      nodes.release("direct:" + id);
                    } catch (IOException offline) {
                      /* Reservation and reference remain retryable. */
                    }
                    return null;
                  }));
    }
  }
}
