package com.cloudnest.storage;

import static com.cloudnest.common.Db.*;

import com.cloudnest.common.*;
import com.cloudnest.node.NodeService;
import java.io.*;
import java.nio.file.*;
import java.util.*;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

@Service
public class ReplicaService {
  private final Db db;
  private final StorageService storage;
  private final NodeService nodes;
  private final Path staging;

  public ReplicaService(
      Db db, StorageService storage, NodeService nodes, @Value("${cloudnest.data-dir}") String dir)
      throws IOException {
    this.db = db;
    this.storage = storage;
    this.nodes = nodes;
    staging = Path.of(dir).toAbsolutePath().resolve("replica-read");
    Files.createDirectories(staging);
    // Single server: no response from a previous process can still own these files.
    try (var files = Files.list(staging)) {
      for (var f : files.toList()) Files.deleteIfExists(f);
    }
    db.update("DELETE FROM storage_reservation WHERE id LIKE 'read:%'");
  }

  public void verified(Map<String, Object> blob, String backend, String key) {
    String id = s(blob, "id");
    db.update("DELETE FROM blob_replica WHERE blob_id=? AND backend=?", id, backend);
    db.update(
        "INSERT INTO blob_replica(blob_id,backend,object_key,size,state,verified_at)"
            + " VALUES(?,?,?,?,'VERIFIED',?)",
        id,
        backend,
        key,
        n(blob, "size"),
        System.currentTimeMillis());
  }

  public boolean verify(Map<String, Object> blob, String backend, String key) throws IOException {
    var digest = Util.digest();
    long total = 0;
    byte[] buffer = new byte[65536];
    try (var input = storage.openWhole(backend, key, n(blob, "size"))) {
      for (int count; (count = input.read(buffer)) != -1; ) {
        total += count;
        if (total > n(blob, "size")) return false;
        digest.update(buffer, 0, count);
      }
    }
    return total == n(blob, "size")
        && HexFormat.of().formatHex(digest.digest()).equals(s(blob, "sha256"));
  }

  public InputStream open(Map<String, Object> blob, long offset, long length) throws IOException {
    String id = s(blob, "id"), primary = s(blob, "backend"), key = s(blob, "object_key");
    var candidates =
        db.list(
            "SELECT * FROM blob_replica WHERE blob_id=? AND state='VERIFIED' ORDER BY verified_at"
                + " DESC",
            id);
    // Preserve legacy streaming for objects that have never requested protection.
    if (n(blob, "desired_replicas") == 1
        && candidates.stream().noneMatch(r -> !primary.equals(s(r, "backend")))
        && db.count("SELECT COUNT(*) FROM blob_replica WHERE blob_id=? AND backend=? AND state='CORRUPT'",id,primary)==0)
      return storage.open(primary, key, offset, length);
    if (candidates.stream().noneMatch(r -> primary.equals(s(r, "backend")))
        && db.count(
                "SELECT COUNT(*) FROM blob_replica WHERE blob_id=? AND backend=? AND"
                    + " state='CORRUPT'",
                id,
                primary)
            == 0) candidates.add(Map.of("backend", primary, "object_key", key));
    candidates.sort(Comparator.comparing(r -> !primary.equals(s(r, "backend"))));
    String reservation = "read:" + Util.id();
    nodes.locked(
        () -> {
          nodes.reserve(reservation, "local", 0, n(blob, "size"));
          return null;
        });
    Path file = null;
    boolean transferred = false;
    try {
      file = Files.createTempFile(staging, "verified-", ".tmp");
      boolean found = false;
      for (var replica : candidates) {
        String backend = s(replica, "backend");
        try {
          var node = nodes.node(backend);
          if (!"ONLINE".equals(s(node, "state"))
              || !b(node, "validated")
              || System.currentTimeMillis() - n(node, "checked_at") > 120000) continue;
          var digest = Util.digest();
          long total = 0;
          byte[] buffer = new byte[65536];
          try (var input = storage.openWhole(backend, s(replica, "object_key"), n(blob, "size"));
              var output = Files.newOutputStream(file)) {
            for (int count; (count = input.read(buffer)) != -1; ) {
              total += count;
              if (total > n(blob, "size")) break;
              output.write(buffer, 0, count);
              digest.update(buffer, 0, count);
            }
          }
          if (total != n(blob, "size")
              || !HexFormat.of().formatHex(digest.digest()).equals(s(blob, "sha256"))) {
            db.update(
                "UPDATE blob_replica SET state='CORRUPT' WHERE blob_id=? AND backend=?",
                id,
                backend);
            continue;
          }
          nodes.locked(
              () -> {
                if (db.count("SELECT COUNT(*) FROM file_blob WHERE id=? AND status='READY'", id)
                    > 0) verified(blob, backend, s(replica, "object_key"));
                return null;
              });
          found = true;
          break;
        } catch (IOException | Problem unavailable) {
          /* Try another verified physical replica. */
        }
      }
      if (!found) throw new Problem(503, "REPLICAS_UNAVAILABLE", "所有已验证副本均不可用，请等待节点恢复或从独立备份恢复");
      final Path owned = file;
      var input = Files.newInputStream(file);
      try {
        input.skipNBytes(offset);
      } catch (IOException failure) {
        input.close();
        throw failure;
      }
      transferred = true;
      return new FilterInputStream(input) {
        long remaining = length;
        boolean closed;

        @Override
        public int read() throws IOException {
          if (remaining == 0) return -1;
          int value = super.read();
          if (value >= 0) remaining--;
          return value;
        }

        @Override
        public int read(byte[] b, int off, int len) throws IOException {
          Objects.checkFromIndexSize(off, len, b.length);
          if (len == 0) return 0;
          if (remaining == 0) return -1;
          int count = in.read(b, off, (int) Math.min(len, remaining));
          if (count > 0) remaining -= count;
          return count;
        }

        @Override
        public long skip(long count) throws IOException {
          long skipped = in.skip(Math.max(0, Math.min(count, remaining)));
          remaining -= skipped;
          return skipped;
        }

        @Override
        public void close() throws IOException {
          if (closed) return;
          closed = true;
          try {
            super.close();
          } finally {
            try {
              Files.deleteIfExists(owned);
            } finally {
              nodes.locked(
                  () -> {
                    nodes.release(reservation);
                    return null;
                  });
            }
          }
        }
      };
    } finally {
      if (!transferred) {
        try {
          if (file != null) Files.deleteIfExists(file);
        } finally {
          nodes.locked(
              () -> {
                nodes.release(reservation);
                return null;
              });
        }
      }
    }
  }

  public void deleteAll(Map<String, Object> blob) throws IOException {
    // Caller holds user and storage locks; unavailable copies remain retryable and accounted.
    for (var r : db.list("SELECT * FROM blob_replica WHERE blob_id=?", s(blob, "id")))
      storage.delete(s(r, "backend"), s(r, "object_key"));
    storage.delete(s(blob, "backend"), s(blob, "object_key"));
    db.update(
        "DELETE FROM storage_copy WHERE EXISTS(SELECT 1 FROM blob_replica r WHERE r.blob_id=? AND"
            + " r.backend=storage_copy.backend AND r.object_key=storage_copy.object_key)",
        s(blob, "id"));
    db.update("DELETE FROM blob_replica WHERE blob_id=?", s(blob, "id"));
  }
}
