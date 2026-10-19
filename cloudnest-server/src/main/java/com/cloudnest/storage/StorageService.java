package com.cloudnest.storage;

import java.io.*;

public interface StorageService {
  void put(String backend, String key, InputStream input, long size) throws IOException;

  InputStream open(String backend, String key, long offset, long length) throws IOException;

  /** Full object, without a Range that could hide appended corruption or reject empty objects. */
  default InputStream openWhole(String backend, String key, long expectedSize) throws IOException {
    return open(backend, key, 0, expectedSize);
  }

  void delete(String backend, String key) throws IOException;

  boolean exists(String backend, String key) throws IOException;

  default void probe(String backend) throws IOException {
    throw new IOException("Probe unsupported");
  }

  default java.util.Optional<String> presign(
      String backend, String key, String endpoint, String name, int seconds) throws IOException {
    return java.util.Optional.empty();
  }
}
