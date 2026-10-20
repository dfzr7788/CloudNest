package com.cloudnest.storage;

import static com.cloudnest.common.Db.*;

import com.cloudnest.common.Db;
import com.cloudnest.security.SecretCipher;
import java.io.*;
import java.util.concurrent.TimeUnit;
import okhttp3.*;
import org.springframework.stereotype.Service;

/** Scoped HTTP object protocol for contributed directories, over HTTPS or a controlled tailnet. */
@Service
public class PeerStorage {
  private final Db db;
  private final SecretCipher cipher;
  private final OkHttpClient client =
      new OkHttpClient.Builder()
          .connectTimeout(5, TimeUnit.SECONDS)
          .readTimeout(30, TimeUnit.SECONDS)
          .writeTimeout(30, TimeUnit.SECONDS)
          .followRedirects(false)
          .followSslRedirects(false)
          .build();

  public PeerStorage(Db db, SecretCipher cipher) {
    this.db = db;
    this.cipher = cipher;
  }

  private Request.Builder request(String backend, String key) throws IOException {
    if (!backend.matches("peer:[a-f0-9-]{36}")
        || !(key.equals("health") || key.matches("objects/[a-f0-9-]{36}")))
      throw new IOException("Invalid peer object");
    var c =
        db.one(
            "SELECT endpoint,secret_key FROM storage_contribution WHERE id=?",
            backend.substring(5));
    return new Request.Builder()
        .url(s(c, "endpoint") + "/" + key)
        .header("Authorization", "Bearer " + cipher.decrypt(s(c, "secret_key")));
  }

  private Response send(Request request) throws IOException {
    return client.newCall(request).execute();
  }

  public InputStream open(String backend, String key, long offset, long length, boolean whole)
      throws IOException {
    var request = request(backend, key);
    if (!whole && length > 0)
      request.header("Range", "bytes=" + offset + "-" + (offset + length - 1));
    var response = send(request.get().build());
    if (response.code() != (whole || length == 0 ? 200 : 206) || response.body() == null) {
      response.close();
      throw new IOException("Peer object unavailable");
    }
    return response.body().byteStream();
  }

  public void put(String backend, String key, InputStream input, long size) throws IOException {
    var body =
        new RequestBody() {
          @Override
          public MediaType contentType() {
            return MediaType.get("application/octet-stream");
          }

          @Override
          public long contentLength() {
            return size;
          }

          @Override
          public void writeTo(okio.BufferedSink sink) throws IOException {
            if (input.transferTo(sink.outputStream()) != size)
              throw new IOException("Peer object length mismatch");
          }

          @Override
          public boolean isOneShot() {
            return true;
          }
        };
    try (var response = send(request(backend, key).put(body).build())) {
      if (response.code() != 201) throw new IOException("Peer write rejected");
    }
  }

  public void delete(String backend, String key) throws IOException {
    try (var response = send(request(backend, key).delete().build())) {
      if (response.code() != 204) throw new IOException("Peer delete rejected");
    }
  }

  public boolean exists(String backend, String key) throws IOException {
    try (var response = send(request(backend, key).head().build())) {
      if (response.code() == 404) return false;
      if (response.code() != 200) throw new IOException("Peer unavailable");
      return true;
    }
  }

  public void probe(String backend) throws IOException {
    try (var response = send(request(backend, "health").get().build())) {
      if (response.code() != 200) throw new IOException("Peer unavailable");
    }
  }
}
