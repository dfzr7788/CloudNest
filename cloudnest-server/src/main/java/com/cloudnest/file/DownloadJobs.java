package com.cloudnest.file;

import static com.cloudnest.common.Db.*;

import com.cloudnest.common.*;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.annotation.*;
import jakarta.servlet.http.HttpServletResponse;
import jakarta.validation.Valid;
import jakarta.validation.constraints.*;
import java.io.*;
import java.nio.file.*;
import java.util.*;
import java.util.concurrent.*;
import java.util.zip.*;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/api/downloads")
public class DownloadJobs {
  private final Db db;
  private final DriveService drive;
  private final ContentService content;
  private final ObjectMapper json;
  private final Path root;
  private final ExecutorService executor =
      new ThreadPoolExecutor(
          2,
          2,
          0,
          TimeUnit.SECONDS,
          new ArrayBlockingQueue<>(20),
          Thread.ofPlatform().daemon().name("zip-", 0).factory(),
          new ThreadPoolExecutor.AbortPolicy());

  public DownloadJobs(
      Db db,
      DriveService drive,
      ContentService content,
      ObjectMapper json,
      @Value("${cloudnest.data-dir}") String dir)
      throws IOException {
    this.db = db;
    this.drive = drive;
    this.content = content;
    this.json = json;
    root = Path.of(dir).toAbsolutePath().resolve("downloads");
    Files.createDirectories(root);
  }

  @PostConstruct
  void recover() {
    db.update(
        "UPDATE download_job SET status='FAILED',error='服务重启，请重新创建任务' WHERE status IN"
            + " ('QUEUED','BUILDING')");
  }

  @PreDestroy
  void stop() {
    executor.shutdownNow();
  }

  public record Input(@NotEmpty @Size(max = 100) List<@NotBlank String> fileIds) {}

  @GetMapping
  public List<Map<String, Object>> list(Authentication a) {
    return db
        .list(
            "SELECT id,status,error,created_at,expires_at,entry_ids FROM download_job WHERE"
                + " user_id=? ORDER BY created_at DESC LIMIT 20",
            a.getName())
        .stream()
        .map(
            row -> {
              Map<String, Object> result = new LinkedHashMap<>();
              result.put("id", row.get("id"));
              result.put("status", row.get("status"));
              result.put("error", row.get("error"));
              result.put("createdAt", row.get("created_at"));
              result.put("expiresAt", row.get("expires_at"));
              try {
                result.put(
                    "fileIds",
                    json.readValue(s(row, "entry_ids"), new TypeReference<List<String>>() {}));
              } catch (IOException e) {
                result.put("fileIds", List.of());
              }
              return result;
            })
        .toList();
  }

  @PostMapping
  public Map<String, Object> create(Authentication a, @Valid @RequestBody Input input)
      throws IOException {
    List<String> ids = input.fileIds.stream().distinct().toList();
    String uid = a.getName(), id = Util.id();
    db.atomic(
        uid,
        () -> {
          validate(uid, ids);
          if (db.count(
                  "SELECT COUNT(*) FROM download_job WHERE user_id=? AND expires_at>?",
                  uid,
                  System.currentTimeMillis())
              >= 10) throw Problem.conflict("每小时最多保留 10 个打包任务");
          if (db.count(
                  "SELECT COUNT(*) FROM download_job WHERE user_id=? AND status IN"
                      + " ('QUEUED','BUILDING')",
                  uid)
              >= 2) throw Problem.conflict("已有打包任务，请等待完成");
          try {
            Map<String, Long> revisions = new LinkedHashMap<>();
            for (String file : ids) revisions.put(file, n(drive.file(uid, file), "revision"));
            db.update(
                "INSERT INTO"
                    + " download_job(id,user_id,status,entry_ids,created_at,expires_at,entry_revisions)"
                    + " VALUES(?,?,'QUEUED',?,?,?,?)",
                id,
                uid,
                json.writeValueAsString(ids),
                System.currentTimeMillis(),
                System.currentTimeMillis() + 3600000,
                json.writeValueAsString(revisions));
          } catch (IOException e) {
            throw new UncheckedIOException(e);
          }
          return null;
        });
    try {
      executor.submit(() -> build(uid, id, ids));
    } catch (RejectedExecutionException e) {
      db.update("UPDATE download_job SET status='FAILED',error='任务繁忙，请重试' WHERE id=?", id);
    }
    return Map.of("id", id);
  }

  private void validate(String uid, List<String> ids) {
    long total = 0;
    for (String id : ids) total += n(drive.file(uid, id), "size");
    if (total > 512 * 1024 * 1024L) throw new Problem(413, "ZIP_TOO_LARGE", "批量下载总大小限制 512 MiB");
  }

  private void build(String uid, String id, List<String> ids) {
    Path path = root.resolve(id + ".zip");
    try {
      db.update("UPDATE download_job SET status='BUILDING' WHERE id=?", id);
      validate(uid, ids);
      Set<String> names = new HashSet<>();
      try (var zip = new ZipOutputStream(Files.newOutputStream(path))) {
        for (String fileId : ids) {
          var e = drive.file(uid, fileId);
          String name = Util.name(s(e, "name")), candidate = name;
          int n = 0;
          while (!names.add(candidate)) candidate = "(" + (++n) + ") " + name;
          zip.putNextEntry(new ZipEntry(candidate));
          try (var input = content.open(content.blob(uid, fileId))) {
            input.transferTo(zip);
          }
          zip.closeEntry();
        }
      }
      db.update("UPDATE download_job SET status='READY' WHERE id=?", id);
    } catch (Exception e) {
      db.update(
          "UPDATE download_job SET status='FAILED',error='打包失败，文件可能已变更，请重新选择' WHERE id=?", id);
      try {
        Files.deleteIfExists(path);
      } catch (IOException ignored) {
      }
      LoggerFactory.getLogger(getClass()).warn("ZIP failed {}", id, e);
    }
  }

  private Map<String, Object> job(String uid, String id) {
    var j =
        db.one(
            "SELECT * FROM download_job WHERE id=? AND user_id=? AND expires_at>?",
            id,
            uid,
            System.currentTimeMillis());
    return j;
  }

  @GetMapping("/{id}")
  public Map<String, Object> status(Authentication a, @PathVariable String id) {
    var j = job(a.getName(), id);
    Map<String, Object> m = new LinkedHashMap<>();
    m.put("id", id);
    m.put("status", j.get("status"));
    m.put("error", j.get("error"));
    return m;
  }

  @GetMapping("/{id}/file")
  public void download(Authentication a, @PathVariable String id, HttpServletResponse response)
      throws IOException {
    var j = job(a.getName(), id);
    if (!s(j, "status").equals("READY")) throw Problem.conflict("打包尚未完成");
    validate(a.getName(), json.readValue(s(j, "entry_ids"), new TypeReference<List<String>>() {}));
    if (j.get("entry_revisions") == null) throw Problem.conflict("请重新创建打包任务");
    var revisions =
        json.readValue(s(j, "entry_revisions"), new TypeReference<Map<String, Long>>() {});
    for (var revision : revisions.entrySet())
      if (n(drive.file(a.getName(), revision.getKey()), "revision") != revision.getValue())
        throw Problem.conflict("文件版本已变化，请重新打包");
    Path file = root.resolve(id + ".zip");
    response.setContentType("application/zip");
    response.setHeader("Content-Disposition", "attachment; filename=CloudNest.zip");
    response.setHeader("Cache-Control", "no-store");
    response.setContentLengthLong(Files.size(file));
    try (var in = Files.newInputStream(file)) {
      in.transferTo(response.getOutputStream());
    }
  }

  @Scheduled(fixedDelay = 60000, initialDelay = 60000)
  void cleanup() {
    for (var j :
        db.list(
            "SELECT id FROM download_job WHERE expires_at<? AND status NOT IN"
                + " ('QUEUED','BUILDING')",
            System.currentTimeMillis()))
      try {
        Files.deleteIfExists(root.resolve(s(j, "id") + ".zip"));
        db.update("DELETE FROM download_job WHERE id=?", j.get("id"));
      } catch (IOException e) {
        LoggerFactory.getLogger(getClass()).warn("ZIP cleanup retry", e);
      }
  }
}
