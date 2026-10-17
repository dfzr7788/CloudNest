package com.cloudnest.file;

import static com.cloudnest.common.Db.*;

import com.cloudnest.common.*;
import com.cloudnest.upload.UploadService;
import jakarta.servlet.http.*;
import java.io.*;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.time.*;
import java.time.format.DateTimeFormatter;
import java.util.*;
import java.util.concurrent.Semaphore;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.security.core.Authentication;
import org.springframework.stereotype.Controller;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.util.*;

@Controller
public class DavController {
  private final Db db;
  private final DriveService drive;
  private final ContentService content;
  private final UploadService upload;
  private final CopyController copy;
  private final Path temp;
  private final long max;
  private final Semaphore writes = new Semaphore(2);

  public DavController(
      Db db,
      DriveService drive,
      ContentService content,
      UploadService upload,
      CopyController copy,
      @Value("${cloudnest.data-dir}") String dir,
      @Value("${cloudnest.max-file-size}") long max)
      throws IOException {
    this.db = db;
    this.drive = drive;
    this.content = content;
    this.upload = upload;
    this.copy = copy;
    this.max = max;
    temp = Path.of(dir).toAbsolutePath().resolve("dav-temp");
    Files.createDirectories(temp);
  }

  private List<String> segments(String path) {
    if (!path.equals("/dav") && !path.startsWith("/dav/")) throw Problem.missing();
    path = UriUtils.decode(path.substring(4), StandardCharsets.UTF_8);
    var parts = new ArrayList<String>();
    for (String p : path.split("/")) {
      if (p.isBlank()) continue;
      if (p.equals(".") || p.equals("..") || p.contains("\\") || p.indexOf('\0') >= 0)
        throw Problem.invalid("无效路径");
      parts.add(Util.name(p));
    }
    if (parts.size() > 64) throw Problem.invalid("路径层级过深");
    return parts;
  }

  private Map<String, Object> resolve(String uid, List<String> parts) {
    var e = drive.folder(uid, null);
    for (String name : parts) {
      if (!s(e, "kind").equals("FOLDER")) throw Problem.missing();
      var rows =
          db.list(
              "SELECT id FROM drive_entry WHERE user_id=? AND parent_id=? AND name=? AND"
                  + " deleted=FALSE",
              uid,
              e.get("id"),
              name);
      if (rows.size() != 1) throw Problem.missing();
      e = drive.live(uid, s(rows.getFirst(), "id"));
    }
    return e;
  }

  private String etag(Map<String, Object> e) {
    return "\"" + e.get("id") + "-" + e.get("revision") + "\"";
  }

  private void writable(Authentication a) {
    if (a.getAuthorities().stream().noneMatch(g -> g.getAuthority().equals("DAV_WRITE")))
      throw new Problem(403, "DAV_READ_ONLY", "应用密码仅允许读取");
  }

  private String xml(String text) {
    return HtmlUtils.htmlEscape(text == null ? "" : text);
  }

  @RequestMapping({"/dav", "/dav/**"})
  public void dav(Authentication a, HttpServletRequest req, HttpServletResponse res)
      throws IOException {
    String uid = a.getName(), method = req.getMethod();
    var parts = segments(req.getRequestURI());
    res.setHeader("DAV", "1");
    res.setHeader("MS-Author-Via", "DAV");
    res.setHeader("Cache-Control", "no-store");
    if (method.equals("OPTIONS")) {
      res.setHeader("Allow", "OPTIONS, PROPFIND, GET, HEAD, PUT, MKCOL, DELETE, MOVE, COPY");
      res.setStatus(204);
      return;
    }
    if (method.equals("PUT")) {
      writable(a);
      put(uid, parts, req, res);
      return;
    }
    if (method.equals("MKCOL")) {
      writable(a);
      if (parts.isEmpty()) throw Problem.conflict("根目录已存在");
      if (req.getContentLengthLong() > 0) {
        res.setStatus(415);
        return;
      }
      var parent = resolve(uid, parts.subList(0, parts.size() - 1));
      drive.createFolder(uid, s(parent, "id"), parts.getLast());
      res.setStatus(201);
      return;
    }
    var e = resolve(uid, parts);
    if (req.getHeader("If-Match") != null
        && !req.getHeader("If-Match").equals("*")
        && !req.getHeader("If-Match").equals(etag(e))) {
      res.setStatus(412);
      return;
    }
    if (method.equals("PROPFIND")) {
      String depth = Optional.ofNullable(req.getHeader("Depth")).orElse("1");
      if (!Set.of("0", "1").contains(depth)) {
        res.setStatus(403);
        return;
      }
      var entries = new ArrayList<Map<String, Object>>();
      entries.add(e);
      if (depth.equals("1") && s(e, "kind").equals("FOLDER")) {
        var children =
            db.list(
                "SELECT * FROM drive_entry WHERE user_id=? AND parent_id=? AND deleted=FALSE ORDER"
                    + " BY id LIMIT 10001",
                uid,
                e.get("id"));
        if (children.size() > 10000) {
          res.setStatus(507);
          return;
        }
        entries.addAll(children);
      }
      String path =
          "/dav/"
              + String.join(
                  "/",
                  parts.stream()
                      .map(p -> UriUtils.encodePathSegment(p, StandardCharsets.UTF_8))
                      .toList());
      if (!path.endsWith("/")) path += "/";
      var user =
          db.one(
              "SELECT storage_quota,storage_used,storage_reserved FROM sys_user WHERE id=?", uid);
      StringBuilder out =
          new StringBuilder(
              "<?xml version=\"1.0\" encoding=\"utf-8\"?><d:multistatus xmlns:d=\"DAV:\">");
      for (var entry : entries) {
        boolean folder = s(entry, "kind").equals("FOLDER");
        String href =
            entry == e
                ? path
                : path
                    + UriUtils.encodePathSegment(s(entry, "name"), StandardCharsets.UTF_8)
                    + (folder ? "/" : "");
        if (entry == e && !folder) href = path.substring(0, path.length() - 1);
        out.append("<d:response><d:href>")
            .append(xml(href))
            .append("</d:href><d:propstat><d:prop><d:displayname>")
            .append(xml(s(entry, "name")))
            .append("</d:displayname><d:resourcetype>")
            .append(folder ? "<d:collection/>" : "")
            .append("</d:resourcetype><d:getcontentlength>")
            .append(n(entry, "size"))
            .append("</d:getcontentlength><d:getcontenttype>")
            .append(folder ? "httpd/unix-directory" : xml(s(entry, "mime")))
            .append("</d:getcontenttype><d:getetag>")
            .append(xml(etag(entry)))
            .append("</d:getetag><d:getlastmodified>")
            .append(
                DateTimeFormatter.RFC_1123_DATE_TIME.format(
                    Instant.ofEpochMilli(n(entry, "updated_at")).atZone(ZoneOffset.UTC)))
            .append("</d:getlastmodified><d:quota-used-bytes>")
            .append(n(user, "storage_used"))
            .append("</d:quota-used-bytes><d:quota-available-bytes>")
            .append(
                n(user, "storage_quota") - n(user, "storage_used") - n(user, "storage_reserved"))
            .append(
                "</d:quota-available-bytes></d:prop><d:status>HTTP/1.1 200"
                    + " OK</d:status></d:propstat></d:response>");
      }
      out.append("</d:multistatus>");
      res.setStatus(207);
      res.setContentType("application/xml;charset=UTF-8");
      res.getWriter().write(out.toString());
      return;
    }
    if (method.equals("GET")) {
      if (s(e, "kind").equals("FOLDER")) {
        res.setStatus(405);
        return;
      }
      res.setHeader("ETag", etag(e));
      content.send(uid, s(e, "id"), false, req, res);
      return;
    }
    if (method.equals("HEAD")) {
      res.setHeader("ETag", etag(e));
      res.setContentLengthLong(n(e, "size"));
      res.setStatus(200);
      return;
    }
    writable(a);
    if (method.equals("DELETE")) {
      drive.trash(uid, s(e, "id"), s(e, "kind"));
      res.setStatus(204);
      return;
    }
    if (method.equals("MOVE") || method.equals("COPY")) {
      String destination = req.getHeader("Destination");
      if (destination == null) throw Problem.invalid("缺少目标路径");
      URI dest;
      try {
        dest = URI.create(destination);
      } catch (Exception ex) {
        throw Problem.invalid("目标路径无效");
      }
      if (dest.getRawQuery() != null || dest.getFragment() != null || dest.getUserInfo() != null)
        throw Problem.invalid("目标路径无效");
      if (dest.isAbsolute()) {
        var origin = URI.create(req.getRequestURL().toString());
        if (!Objects.equals(origin.getAuthority(), dest.getAuthority())
            || !Objects.equals(origin.getScheme(), dest.getScheme()))
          throw Problem.invalid("不能跨服务器移动或复制");
      }
      var target = segments(dest.getRawPath());
      if (target.isEmpty()) throw Problem.invalid("不能替换根目录");
      var parent = resolve(uid, target.subList(0, target.size() - 1));
      var exists =
          db.list(
              "SELECT id FROM drive_entry WHERE user_id=? AND parent_id=? AND name=? AND"
                  + " deleted=FALSE",
              uid,
              parent.get("id"),
              target.getLast());
      if (!exists.isEmpty()) {
        res.setStatus("F".equals(req.getHeader("Overwrite")) ? 412 : 409);
        return;
      }
      db.atomic(
          uid,
          () -> {
            String id = s(e, "id");
            if (method.equals("COPY"))
              id =
                  ((List<String>)
                          copy.copy(a, new CopyController.Input(List.of(id), s(parent, "id")))
                              .get("ids"))
                      .getFirst();
            drive.edit(uid, id, target.getLast(), s(parent, "id"), s(e, "kind"));
            return null;
          });
      res.setStatus(201);
      return;
    }
    res.setStatus(405);
  }

  private void put(String uid, List<String> parts, HttpServletRequest req, HttpServletResponse res)
      throws IOException {
    if (parts.isEmpty()) throw Problem.invalid("不能覆盖根目录");
    if (!writes.tryAcquire()) throw new Problem(503, "DAV_BUSY", "上传繁忙，请稍后重试");
    Path staged = null;
    String session = null;
    try {
      if (req.getContentLengthLong() > max) throw new Problem(413, "TOO_LARGE", "文件超过允许大小");
      var parent = resolve(uid, parts.subList(0, parts.size() - 1));
      String name = parts.getLast();
      var existing =
          db.list(
              "SELECT * FROM drive_entry WHERE user_id=? AND parent_id=? AND name=? AND"
                  + " deleted=FALSE",
              uid,
              parent.get("id"),
              name);
      if (existing.size() > 1
          || (!existing.isEmpty() && !s(existing.getFirst(), "kind").equals("FILE")))
        throw Problem.conflict("目标不是文件");
      var old = existing.isEmpty() ? null : existing.getFirst();
      if (("*".equals(req.getHeader("If-None-Match")) && old != null)
          || (req.getHeader("If-Match") != null
              && (old == null
                  || (!req.getHeader("If-Match").equals("*")
                      && !req.getHeader("If-Match").equals(etag(old)))))) {
        res.setStatus(412);
        return;
      }
      staged = Files.createTempFile(temp, "upload-", ".part");
      long size = 0;
      byte[] buf = new byte[1024 * 1024];
      try (var input = req.getInputStream();
          var out = Files.newOutputStream(staged)) {
        int count;
        while ((count = input.read(buf)) != -1) {
          size += count;
          if (size > max) throw new Problem(413, "TOO_LARGE", "文件超过允许大小");
          if (Files.getFileStore(temp).getUsableSpace() < 2L * count + 64 * 1024 * 1024)
            throw new Problem(507, "DISK_FULL", "磁盘不足");
          out.write(buf, 0, count);
        }
      }
      if (req.getContentLengthLong() >= 0 && req.getContentLengthLong() != size)
        throw Problem.invalid("上传长度不一致");
      String hash;
      try (var in = Files.newInputStream(staged)) {
        hash = Util.hash(in);
      }
      var job =
          upload.init(
              uid,
              s(parent, "id"),
              name,
              size,
              hash,
              old == null ? null : s(old, "id"),
              old == null ? null : n(old, "revision"));
      session = s(job, "id");
      if (!"COMPLETE".equals(s(job, "status"))) {
        try (var input = Files.newInputStream(staged)) {
          long part = n(job, "partSize");
          int count = ((Number) job.get("partCount")).intValue();
          for (int i = 0; i < count; i++) {
            final long length = Math.min(part, size - (long) i * part);
            var bounded =
                new FilterInputStream(input) {
                  long remaining = length;

                  @Override
                  public int read() throws IOException {
                    if (remaining <= 0) return -1;
                    int v = super.read();
                    if (v >= 0) remaining--;
                    return v;
                  }

                  @Override
                  public int read(byte[] b, int off, int len) throws IOException {
                    if (remaining <= 0) return -1;
                    int v = super.read(b, off, (int) Math.min(len, remaining));
                    if (v > 0) remaining -= v;
                    return v;
                  }
                };
            upload.part(uid, session, i, bounded);
          }
        }
        upload.complete(uid, session);
      }
      session = null;
      res.setStatus(old == null ? 201 : 204);
    } finally {
      if (session != null)
        try {
          upload.cancel(uid, session);
        } catch (Exception ignored) {
        }
      if (staged != null) Files.deleteIfExists(staged);
      writes.release();
    }
  }
}
