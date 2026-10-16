package com.cloudnest.file;

import static com.cloudnest.common.Db.*;

import com.cloudnest.common.*;
import com.cloudnest.storage.StorageService;
import jakarta.servlet.http.*;
import java.io.*;
import java.nio.charset.StandardCharsets;
import java.util.*;
import org.springframework.http.ContentDisposition;
import org.springframework.stereotype.Service;

@Service
public class ContentService {
  private final Db db;
  private final DriveService drive;
  private final StorageService storage;

  public ContentService(Db db, DriveService drive, StorageService storage) {
    this.db = db;
    this.drive = drive;
    this.storage = storage;
  }

  public Map<String, Object> blob(String uid, String id) {
    var e = drive.file(uid, id);
    return db.one(
        "SELECT * FROM file_blob WHERE id=? AND user_id=? AND status='READY'",
        e.get("blob_id"),
        uid);
  }

  public InputStream open(Map<String, Object> b) throws IOException {
    return storage.open(s(b, "backend"), s(b, "object_key"), 0, n(b, "size"));
  }

  public void send(
      String uid, String id, boolean preview, HttpServletRequest req, HttpServletResponse res)
      throws IOException {
    var e = drive.file(uid, id);
    var b = blob(uid, id);
    sendBlob(e, b, preview, req, res);
  }

  public void sendBlob(
      Map<String, Object> e,
      Map<String, Object> b,
      boolean preview,
      HttpServletRequest req,
      HttpServletResponse res)
      throws IOException {
    String mime = s(b, "mime");
    long size = n(b, "size");
    if (preview) {
      if (!Set.of(
              "image/png",
              "image/jpeg",
              "image/gif",
              "image/webp",
              "image/bmp",
              "application/pdf",
              "audio/mpeg",
              "audio/wav",
              "audio/x-wav",
              "audio/ogg",
              "video/mp4",
              "video/webm",
              "text/plain")
          .contains(mime)) throw new Problem(415, "PREVIEW_UNSUPPORTED", "该类型暂不支持预览，请下载后查看");
      if (mime.equals("text/plain") && size > 2 * 1024 * 1024)
        throw new Problem(413, "PREVIEW_TOO_LARGE", "文本超过 2 MiB，请下载后查看");
    }
    long offset = 0, length = size;
    String range = req.getHeader("Range");
    if (range != null) {
      try {
        if (!range.matches("bytes=\\d*-\\d*") || range.equals("bytes=-") || size == 0)
          throw new IllegalArgumentException();
        String[] p = range.substring(6).split("-", -1);
        long end;
        if (p[0].isBlank()) {
          long suffix = Long.parseLong(p[1]);
          if (suffix <= 0) throw new IllegalArgumentException();
          offset = Math.max(0, size - suffix);
          end = size - 1;
        } else {
          offset = Long.parseLong(p[0]);
          end = p[1].isBlank() ? size - 1 : Math.min(size - 1, Long.parseLong(p[1]));
        }
        if (offset >= size || end < offset) throw new IllegalArgumentException();
        length = end - offset + 1;
        res.setStatus(206);
        res.setHeader("Content-Range", "bytes " + offset + "-" + end + "/" + size);
      } catch (IllegalArgumentException ex) {
        res.setStatus(416);
        res.setHeader("Content-Range", "bytes */" + size);
        return;
      }
    }
    // Open storage before committing response headers, so failures still produce a structured
    // error.
    InputStream in =
        length == 0
            ? InputStream.nullInputStream()
            : storage.open(s(b, "backend"), s(b, "object_key"), offset, length);
    try (in) {
      res.setHeader("Accept-Ranges", "bytes");
      res.setHeader("Cache-Control", "no-store");
      res.setHeader("X-Content-Type-Options", "nosniff");
      res.setContentType(
          preview
              ? (mime.equals("text/plain") ? "text/plain;charset=UTF-8" : mime)
              : "application/octet-stream");
      res.setHeader(
          "Content-Disposition",
          (preview ? ContentDisposition.inline() : ContentDisposition.attachment())
              .filename(s(e, "name"), StandardCharsets.UTF_8)
              .build()
              .toString());
      res.setContentLengthLong(length);
      byte[] buf = new byte[65536];
      long remaining = length;
      while (remaining > 0) {
        int read = in.read(buf, 0, (int) Math.min(buf.length, remaining));
        if (read == -1) throw new EOFException("Stored object is truncated");
        res.getOutputStream().write(buf, 0, read);
        remaining -= read;
      }
    }
  }
}
