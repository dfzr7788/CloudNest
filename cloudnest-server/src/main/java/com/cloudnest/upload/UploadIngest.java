package com.cloudnest.upload;

import static com.cloudnest.common.Db.*;

import com.cloudnest.common.*;
import java.io.*;
import java.nio.file.*;
import java.util.*;
import java.util.concurrent.Semaphore;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

@Service
public class UploadIngest {
  @org.springframework.beans.factory.annotation.Autowired private Db db;

  @org.springframework.beans.factory.annotation.Autowired
  private com.cloudnest.node.NodeService nodes;

  private final UploadService uploads;
  private final Path root;
  private final Semaphore slots = new Semaphore(2);

  public UploadIngest(UploadService uploads, @Value("${cloudnest.data-dir}") String dir)
      throws IOException {
    this.uploads = uploads;
    root = Path.of(dir).toAbsolutePath().resolve("ingest");
    Files.createDirectories(root);
  }

  public Map<String, Object> receive(
      String uid,
      String parent,
      String name,
      InputStream input,
      long max,
      String target,
      Long revision)
      throws IOException {
    return receive(uid, parent, name, input, max, target, revision, () -> {});
  }

  public Map<String, Object> receive(
      String uid,
      String parent,
      String name,
      InputStream input,
      long max,
      String target,
      Long revision,
      Runnable authorize)
      throws IOException {
    if (!slots.tryAcquire()) throw new Problem(503, "UPLOAD_BUSY", "上传繁忙，请稍后重试");
    String reservation = "ingest:" + Util.id();
    Path file = null;
    String session = null;
    try {
      nodes.locked(
          () -> {
            nodes.reserve(reservation, "local", 0, max);
            return null;
          });
      file = Files.createTempFile(root, "import-", ".part");
      long size = 0;
      byte[] block = new byte[1024 * 1024];
      try (input;
          var out = Files.newOutputStream(file)) {
        int count;
        while ((count = input.read(block)) != -1) {
          size += count;
          if (size > max) throw new Problem(413, "TOO_LARGE", "文件超过该入口的上传限制");
          if (Files.getFileStore(root).getUsableSpace() < 64 * 1024 * 1024L + count * 2L)
            throw new Problem(507, "DISK_FULL", "磁盘空间不足");
          out.write(block, 0, count);
        }
      }
      String hash;
      try (var in = Files.newInputStream(file)) {
        hash = Util.hash(in);
      }
      long totalLength = size;
      var job =
          db.atomic(
              uid,
              () -> {
                authorize.run();
                return uploads.init(uid, parent, name, totalLength, hash, target, revision);
              });
      session = s(job, "id");
      if (!"COMPLETE".equals(s(job, "status"))) {
        try (var stream = Files.newInputStream(file)) {
          long part = n(job, "partSize");
          int count = ((Number) job.get("partCount")).intValue();
          for (int i = 0; i < count; i++) {
            final long length = Math.min(part, size - (long) i * part);
            var slice =
                new FilterInputStream(stream) {
                  long remaining = length;

                  @Override
                  public int read() throws IOException {
                    if (remaining == 0) return -1;
                    int value = super.read();
                    if (value >= 0) remaining--;
                    return value;
                  }

                  @Override
                  public int read(byte[] b, int off, int len) throws IOException {
                    if (remaining == 0) return -1;
                    int read = super.read(b, off, (int) Math.min(len, remaining));
                    if (read > 0) remaining -= read;
                    return read;
                  }
                };
            uploads.part(uid, session, i, slice);
          }
        }
        String finalSession = session;
        job =
            db.atomic(
                uid,
                () -> {
                  authorize.run();
                  try {
                    return uploads.complete(uid, finalSession);
                  } catch (IOException e) {
                    throw new UncheckedIOException(e);
                  }
                });
      }
      session = null;
      return job;
    } finally {
      if (session != null)
        try {
          uploads.cancel(uid, session);
        } catch (Exception ignored) {
        }
      try {
        if (file != null) Files.deleteIfExists(file);
        nodes.locked(
            () -> {
              nodes.release(reservation);
              return null;
            });
      } finally {
        slots.release();
      }
    }
  }
}
