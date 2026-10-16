package com.cloudnest.storage;

import io.minio.*;
import java.io.*;
import java.nio.file.*;
import java.util.regex.Pattern;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

@Service
public class ObjectStorage implements StorageService {
  private final Path root;
  private final String endpoint, access, secret, bucket;
  private volatile MinioClient client;
  private static final Pattern KEY = Pattern.compile("objects/[a-f0-9-]{36}");

  public ObjectStorage(
      @Value("${cloudnest.data-dir}") String dir,
      @Value("${cloudnest.minio.endpoint}") String endpoint,
      @Value("${cloudnest.minio.access-key}") String access,
      @Value("${cloudnest.minio.secret-key}") String secret,
      @Value("${cloudnest.minio.bucket}") String bucket)
      throws IOException {
    this.root = Path.of(dir).toAbsolutePath().normalize().resolve("storage");
    Files.createDirectories(root);
    this.endpoint = endpoint;
    this.access = access;
    this.secret = secret;
    this.bucket = bucket;
  }

  private Path path(String key) {
    if (!KEY.matcher(key).matches()) throw new IllegalArgumentException("Invalid object key");
    return root.resolve(key);
  }

  private synchronized MinioClient minio() throws Exception {
    if (client == null) {
      if (access.isBlank() || secret.isBlank())
        throw new IOException("MinIO credentials are required");
      var c = MinioClient.builder().endpoint(endpoint).credentials(access, secret).build();
      if (!c.bucketExists(BucketExistsArgs.builder().bucket(bucket).build()))
        c.makeBucket(MakeBucketArgs.builder().bucket(bucket).build());
      // No public policy is installed: every object is accessed through authorization.
      client = c;
    }
    return client;
  }

  @Override
  public void put(String backend, String key, InputStream in, long size) throws IOException {
    path(key);
    if (backend.equals("local")) {
      Path target = path(key);
      Files.createDirectories(target.getParent());
      Path tmp = target.resolveSibling(target.getFileName() + ".writing");
      try {
        long written;
        try (var out = Files.newOutputStream(tmp)) {
          written = in.transferTo(out);
        }
        if (written != size) throw new IOException("Object length mismatch");
        try {
          Files.move(
              tmp, target, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING);
        } catch (AtomicMoveNotSupportedException e) {
          Files.move(tmp, target, StandardCopyOption.REPLACE_EXISTING);
        }
      } finally {
        Files.deleteIfExists(tmp);
      }
    } else if (backend.equals("minio")) {
      try {
        minio()
            .putObject(
                PutObjectArgs.builder().bucket(bucket).object(key).stream(
                        in, size, 10 * 1024 * 1024)
                    .contentType("application/octet-stream")
                    .build());
      } catch (Exception e) {
        throw new IOException("MinIO write failed", e);
      }
    } else throw new IOException("Unknown storage backend");
  }

  @Override
  public InputStream open(String backend, String key, long offset, long length) throws IOException {
    path(key);
    if (backend.equals("local")) {
      var in = Files.newInputStream(path(key));
      try {
        in.skipNBytes(offset);
        return in;
      } catch (Exception e) {
        in.close();
        throw e;
      }
    }
    if (!backend.equals("minio")) throw new IOException("Unknown storage backend");
    try {
      return minio()
          .getObject(
              GetObjectArgs.builder()
                  .bucket(bucket)
                  .object(key)
                  .offset(offset)
                  .length(Math.max(1, length))
                  .build());
    } catch (Exception e) {
      throw new IOException("MinIO read failed", e);
    }
  }

  @Override
  public void delete(String backend, String key) throws IOException {
    path(key);
    if (backend.equals("local")) {
      Path p = path(key);
      Files.deleteIfExists(p);
      Files.deleteIfExists(p.resolveSibling(p.getFileName() + ".writing"));
    } else if (backend.equals("minio"))
      try {
        minio().removeObject(RemoveObjectArgs.builder().bucket(bucket).object(key).build());
      } catch (Exception e) {
        throw new IOException("MinIO delete failed", e);
      }
    else throw new IOException("Unknown storage backend");
  }

  @Override
  public boolean exists(String backend, String key) throws IOException {
    path(key);
    if (backend.equals("local")) return Files.isRegularFile(path(key));
    if (!backend.equals("minio")) throw new IOException("Unknown storage backend");
    try {
      minio().statObject(StatObjectArgs.builder().bucket(bucket).object(key).build());
      return true;
    } catch (io.minio.errors.ErrorResponseException e) {
      if (e.errorResponse().code().equals("NoSuchKey")) return false;
      throw new IOException(e);
    } catch (Exception e) {
      throw new IOException(e);
    }
  }
}
