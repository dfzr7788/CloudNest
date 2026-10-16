package com.cloudnest.storage;

import java.io.*;

public interface StorageService {
  void put(String backend, String key, InputStream input, long size) throws IOException;

  InputStream open(String backend, String key, long offset, long length) throws IOException;

  void delete(String backend, String key) throws IOException;

  boolean exists(String backend, String key) throws IOException;
}
