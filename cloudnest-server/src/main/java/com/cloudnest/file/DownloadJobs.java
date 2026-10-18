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
  @Value("${cloudnest.zip.max-bytes:536870912}")
  private long maxBytes;

  @Value("${cloudnest.zip.max-files:1000}")
  private int maxFiles;

  @Value("${cloudnest.zip.max-entries:2000}")
  private int maxEntries;

  @Value("${cloudnest.zip.hours:1}")
  private int hours;

  @Value("${cloudnest.zip.reserve-bytes:268435456}")
  private long reserveBytes;

  @Value("${cloudnest.zip.workers:1}")
  private int workers;

  @Value("${cloudnest.zip.compression-level:1}")
  private int compressionLevel;

  private final Db db;
  private final DriveService drive;
  private final ContentService content;
  private final ObjectMapper json;
  private final Path root;
  private ExecutorService executor;

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
    if (maxBytes < 1
        || maxBytes > 1099511627776L
        || maxFiles < 1
        || maxFiles > 100000
        || maxEntries < maxFiles
        || maxEntries > 200000
        || hours < 1
        || hours > 168
        || reserveBytes < 0
        || workers<1 || workers>4
        || compressionLevel<0 || compressionLevel>9) throw new IllegalArgumentException("Invalid ZIP settings");
    executor=new ThreadPoolExecutor(workers,workers,0,TimeUnit.SECONDS,new ArrayBlockingQueue<>(20),Thread.ofPlatform().daemon().name("zip-",0).factory(),new ThreadPoolExecutor.AbortPolicy());
    db.update(
        "UPDATE download_job SET status='FAILED',error='服务重启，请重新创建任务' WHERE status IN"
            + " ('QUEUED','BUILDING')");
  }

  @PreDestroy
  void stop() {
    if(executor!=null)executor.shutdownNow();
  }

  public record Input(@NotEmpty @Size(max = 100) List<@NotBlank String> fileIds) {}

  public record ArchiveEntry(
      String id, String parentId, String name, String path, boolean directory, long revision) {}

  private String safeName(String name) {
    String safe = Util.name(name).replaceAll("[<>:\"|?*]", "_").replaceAll("[. ]+$", "_");
    if (safe.matches("(?i)^(CON|PRN|AUX|NUL|COM[1-9]|LPT[1-9])(\\..*)?$")) safe = "_" + safe;
    return safe;
  }

  private List<ArchiveEntry> snapshot(String uid, List<String> ids) {
    Set<String> selected = new HashSet<>(ids), names = new HashSet<>(), seen = new HashSet<>();
    List<ArchiveEntry> result = new ArrayList<>();
    long total = 0;
    int fileCount = 0;
    record Pending(String id, String prefix, int depth) {}
    ArrayDeque<Pending> pending = new ArrayDeque<>();
    for (String id : ids) {
      var entry = drive.live(uid, id);
      String parent = s(entry, "parent_id");
      boolean covered = false;
      int depth = 0;
      while (parent != null) {
        if (++depth > 64) throw Problem.invalid("目录层级超过 64 层");
        if (selected.contains(parent)) {
          covered = true;
          break;
        }
        parent = s(drive.live(uid, parent), "parent_id");
      }
      if (!covered) pending.add(new Pending(id, "", 0));
    }
    while (!pending.isEmpty()) {
      Pending item = pending.removeFirst();
      if (item.depth() > 64) throw Problem.invalid("目录层级超过 64 层");
      if (!seen.add(item.id())) throw Problem.conflict("目录结构异常");
      if (seen.size() > maxEntries)
        throw new Problem(413, "ZIP_TOO_MANY_ENTRIES", "单次打包最多 " + maxEntries + " 个文件和目录");
      var entry = drive.live(uid, item.id());
      boolean directory = s(entry, "kind").equals("FOLDER");
      String base = safeName(s(entry, "name")), candidate = item.prefix() + base;
      int duplicate = 0;
      while (!names.add(candidate.toLowerCase(Locale.ROOT)))
        candidate = item.prefix() + "(" + (++duplicate) + ") " + base;
      if (candidate.getBytes(java.nio.charset.StandardCharsets.UTF_8).length > 4096)
        throw Problem.invalid("压缩包中的路径过长");
      result.add(
          new ArchiveEntry(
              item.id(),
              s(entry, "parent_id"),
              s(entry, "name"),
              candidate + (directory ? "/" : ""),
              directory,
              n(entry, "revision")));
      if (directory) {
        for (var child :
            db.list(
                "SELECT id FROM drive_entry WHERE user_id=? AND parent_id=? AND deleted=FALSE ORDER"
                    + " BY kind,name,id LIMIT ?",
                uid,
                item.id(),
                maxEntries + 1)) {
          if (pending.size() + result.size() >= maxEntries)
            throw new Problem(413, "ZIP_TOO_MANY_ENTRIES", "单次打包最多 " + maxEntries + " 个文件和目录");
          pending.addLast(new Pending(s(child, "id"), candidate + "/", item.depth() + 1));
        }
      } else {
        total += n(entry, "size");
        if (++fileCount > maxFiles)
          throw new Problem(413, "ZIP_TOO_MANY_FILES", "单次打包最多 " + maxFiles + " 个文件");
        if (total > maxBytes) throw new Problem(413, "ZIP_TOO_LARGE", "打包总大小超过管理员设置的限制");
      }
    }
    return result;
  }

  private List<ArchiveEntry> manifest(Map<String, Object> job) throws IOException {
    if (job.get("archive_manifest") == null) return List.of();
    return json.readValue(s(job, "archive_manifest"), new TypeReference<List<ArchiveEntry>>() {});
  }

  private void validateSnapshot(String uid, List<ArchiveEntry> entries) {
    for (ArchiveEntry item : entries) {
      var entry = drive.live(uid, item.id());
      if (!Objects.equals(item.parentId(), s(entry, "parent_id"))
          || !item.name().equals(s(entry, "name"))
          || item.directory() != s(entry, "kind").equals("FOLDER")
          || (!item.directory() && item.revision() != n(entry, "revision")))
        throw Problem.conflict("文件或目录已变更，请重新打包");
    }
  }

  /** Public shares must authorize every archived descendant, not only selected roots. */
  public List<String> sourceIds(String uid, String id) throws IOException {
    var job = job(uid, id);
    var entries = manifest(job);
    return entries.isEmpty()
        ? json.readValue(s(job, "entry_ids"), new TypeReference<List<String>>() {})
        : entries.stream().map(ArchiveEntry::id).toList();
  }

  @GetMapping
  public List<Map<String, Object>> list(Authentication a) {
    return db
        .list(
            "SELECT"
                + " id,status,error,created_at,expires_at,entry_ids,archive_manifest,completed_bytes,total_bytes"
                + " FROM download_job WHERE user_id=? ORDER BY created_at DESC LIMIT 20",
            a.getName())
        .stream()
        .map(
            row -> {
              Map<String, Object> result = new LinkedHashMap<>();
              result.put("id", row.get("id"));
              result.put("status", row.get("status"));
              result.put("error", row.get("error"));
              result.put("completedBytes", row.get("completed_bytes"));
              result.put("totalBytes", row.get("total_bytes"));
              result.put("createdAt", row.get("created_at"));
              result.put("expiresAt", row.get("expires_at"));
              try {
                result.put(
                    "fileIds",
                    json.readValue(s(row, "entry_ids"), new TypeReference<List<String>>() {}));
                if (row.get("archive_manifest") != null) {
                  var entries = manifest(row);
                  result.put("fileCount", entries.stream().filter(e -> !e.directory()).count());
                  result.put(
                      "folderCount", entries.stream().filter(ArchiveEntry::directory).count());
                }
              } catch (IOException e) {
                result.put("fileIds", List.of());
              }
              return result;
            })
        .toList();
  }

  @PostMapping("/estimate")
  public Map<String, Object> estimate(Authentication a, @Valid @RequestBody Input input)
      throws IOException {
    var entries =
        db.atomic(
            a.getName(), () -> snapshot(a.getName(), input.fileIds().stream().distinct().toList()));
    long total = 0;
    for (var entry : entries)
      if (!entry.directory())
        total = Math.addExact(total, n(drive.file(a.getName(), entry.id()), "size"));
    long pending =
        db.count(
            "SELECT COALESCE(SUM(total_bytes-completed_bytes),0) FROM download_job WHERE status IN"
                + " ('QUEUED','BUILDING')");
    long free = Math.max(0, Files.getFileStore(root).getUsableSpace() - pending);
    return Map.of(
        "bytes",
        total,
        "files",
        entries.stream().filter(e -> !e.directory()).count(),
        "entries",
        entries.size(),
        "freeBytes",
        free,
        "canPack",
        free >= total + entries.size() * 512L + reserveBytes,
        "maxBytes",
        maxBytes,
        "maxFiles",
        maxFiles,
        "maxEntries",
        maxEntries);
  }

  @DeleteMapping("/{id}")
  public void cancel(Authentication a, @PathVariable String id) throws IOException {
    job(a.getName(), id);
    db.update(
        "UPDATE download_job SET status='CANCELLED',error=NULL WHERE id=? AND user_id=?",
        id,
        a.getName());
    try {
      Files.deleteIfExists(root.resolve(id + ".zip"));
    } catch (IOException ignored) {
    }
  }

  @PostMapping
  public synchronized Map<String, Object> create(Authentication a, @Valid @RequestBody Input input)
      throws IOException {
    var estimate = estimate(a, input);
    if (!Boolean.TRUE.equals(estimate.get("canPack")))
      throw new Problem(507, "ZIP_DISK_FULL", "磁盘不足，无法创建压缩包");
    List<String> ids = input.fileIds.stream().distinct().toList();
    String uid = a.getName(), id = Util.id();
    db.atomic(
        uid,
        () -> {
          var entries = snapshot(uid, ids);
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
            for (ArchiveEntry entry : entries)
              if (!entry.directory()) revisions.put(entry.id(), entry.revision());
            db.update(
                "INSERT INTO"
                    + " download_job(id,user_id,status,entry_ids,created_at,expires_at,entry_revisions,archive_manifest)"
                    + " VALUES(?,?,'QUEUED',?,?,?,?,?)",
                id,
                uid,
                json.writeValueAsString(ids),
                System.currentTimeMillis(),
                System.currentTimeMillis() + hours * 3600000L,
                json.writeValueAsString(revisions),
                json.writeValueAsString(entries));
          } catch (IOException e) {
            throw new UncheckedIOException(e);
          }
          db.update("UPDATE download_job SET total_bytes=? WHERE id=?", estimate.get("bytes"), id);
          return null;
        });
    try {
      executor.submit(() -> build(uid, id));
    } catch (RejectedExecutionException e) {
      db.update("UPDATE download_job SET status='FAILED',error='任务繁忙，请重试' WHERE id=?", id);
    }
    return Map.of("id", id);
  }

  private void validate(String uid, List<String> ids) {
    long total = 0;
    for (String id : ids) total += n(drive.file(uid, id), "size");
    if (total > maxBytes) throw new Problem(413, "ZIP_TOO_LARGE", "打包总大小超过管理员设置的限制");
  }

  private void build(String uid, String id) {
    Path path = root.resolve(id + ".zip");
    try {
      if (db.update("UPDATE download_job SET status='BUILDING' WHERE id=? AND status='QUEUED'", id)
          == 0) return;
      var entries = manifest(job(uid, id));
      validateSnapshot(uid, entries);
      try (var zip = new ZipOutputStream(Files.newOutputStream(path))) {
        zip.setLevel(compressionLevel);
        long completed = 0;
        for (ArchiveEntry entry : entries) {
          if (!"BUILDING".equals(s(job(uid, id), "status"))) throw new IOException("Cancelled");
          zip.putNextEntry(new ZipEntry(entry.path()));
          if (!entry.directory()) {
            var source =
                db.atomic(
                    uid,
                    () -> {
                      var current = drive.file(uid, entry.id());
                      if (n(current, "revision") != entry.revision())
                        throw Problem.conflict("文件版本已变化");
                      return content.blob(uid, entry.id());
                    });
            try (var input = content.open(source)) {
              byte[] block = new byte[1024 * 1024];
              int count;
              while ((count = input.read(block)) != -1) {
                if (!"BUILDING".equals(s(job(uid, id), "status")))
                  throw new IOException("Cancelled");
                if (completed + count > maxBytes
                    || Files.getFileStore(root).getUsableSpace() < reserveBytes + count)
                  throw new IOException("ZIP disk/size limit reached");
                zip.write(block, 0, count);
                completed += count;
                db.update("UPDATE download_job SET completed_bytes=? WHERE id=?", completed, id);
              }
            }
          }
          zip.closeEntry();
        }
      }
      validateSnapshot(uid, entries);
      if (db.update("UPDATE download_job SET status='READY' WHERE id=? AND status='BUILDING'", id)
          == 0) Files.deleteIfExists(path);
    } catch (Exception e) {
      db.update(
          "UPDATE download_job SET status='FAILED',error='打包失败，文件可能已变更，请重新选择' WHERE id=? AND"
              + " status<>'CANCELLED'",
          id);
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
    m.put("completedBytes", j.get("completed_bytes"));
    m.put("totalBytes", j.get("total_bytes"));
    return m;
  }

  @GetMapping("/{id}/file")
  public void download(Authentication a, @PathVariable String id, HttpServletResponse response)
      throws IOException {
    var j = job(a.getName(), id);
    if (!s(j, "status").equals("READY")) throw Problem.conflict("打包尚未完成");
    var entries = manifest(j);
    if (entries.isEmpty())
      validate(
          a.getName(), json.readValue(s(j, "entry_ids"), new TypeReference<List<String>>() {}));
    else validateSnapshot(a.getName(), entries);
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
