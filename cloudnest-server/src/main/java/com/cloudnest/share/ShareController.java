package com.cloudnest.share;

import static com.cloudnest.common.Db.*;

import com.cloudnest.common.*;
import com.cloudnest.file.*;
import com.cloudnest.security.RateLimits;
import jakarta.servlet.http.*;
import jakarta.validation.Valid;
import jakarta.validation.constraints.*;
import java.io.IOException;
import java.time.Duration;
import java.util.*;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.*;
import org.springframework.security.core.Authentication;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/api")
public class ShareController {
  @org.springframework.beans.factory.annotation.Autowired private DirectDownloadService direct;

  @org.springframework.beans.factory.annotation.Autowired
  private com.cloudnest.upload.UploadIngest ingest;

  private final Db db;
  private final DriveService drive;
  private final ContentService content;
  private final PasswordEncoder passwords;
  private final RateLimits limits;
  private final boolean secure;
  private final DownloadJobs jobs;

  public ShareController(
      Db db,
      DriveService drive,
      ContentService content,
      PasswordEncoder passwords,
      RateLimits limits,
      DownloadJobs jobs,
      @Value("${cloudnest.secure-cookies}") boolean secure) {
    this.db = db;
    this.drive = drive;
    this.content = content;
    this.passwords = passwords;
    this.limits = limits;
    this.jobs = jobs;
    this.secure = secure;
  }

  public record Create(
      @NotBlank String fileId,
      @Min(1) @Max(720) int hours,
      @Size(max = 32) String code,
      boolean allowDownload) {}

  public record Verify(@Size(max = 32) String code) {}

  @PostMapping("/shares")
  public Map<String, Object> create(Authentication a, @Valid @RequestBody Create in) {
    if (in.code != null && !in.code.isEmpty() && in.code.length() < 4)
      throw Problem.invalid("提取码至少 4 个字符");
    if (in.code != null && in.code.getBytes(java.nio.charset.StandardCharsets.UTF_8).length > 72)
      throw Problem.invalid("提取码的 UTF-8 编码不能超过 72 字节");
    return db.atomic(
        a.getName(),
        () -> {
          var e = drive.live(a.getName(), in.fileId);
          String id = Util.id(), token = Util.token();
          long now = System.currentTimeMillis(),
              expires = now + Duration.ofHours(in.hours).toMillis();
          db.update(
              "INSERT INTO"
                  + " file_share(id,user_id,file_id,token_hash,code_hash,allow_download,expires_at,created_at)"
                  + " VALUES(?,?,?,?,?,?,?,?)",
              id,
              a.getName(),
              in.fileId,
              Util.hash(token),
              in.code == null || in.code.isEmpty() ? null : passwords.encode(in.code),
              in.allowDownload,
              expires,
              now);
          if (s(e, "kind").equals("FILE"))
            db.update("UPDATE file_share SET pinned_blob=? WHERE id=?", e.get("blob_id"), id);
          db.update(
              "INSERT INTO activity_log(id,user_id,action,target_id,created_at) VALUES(?,?,?,?,?)",
              Util.id(),
              a.getName(),
              "SHARE_CREATED",
              in.fileId,
              System.currentTimeMillis());
          return Map.of(
              "id",
              id,
              "token",
              token,
              "path",
              "/s/" + token,
              "expiresAt",
              expires,
              "name",
              s(e, "name"));
        });
  }

  public record Collection(
      @jakarta.validation.constraints.NotEmpty @Size(max = 100) List<@NotBlank String> fileIds,
      @NotBlank @Size(max = 180) String title,
      @Min(1) @Max(720) int hours,
      @Size(max = 32) String code,
      boolean allowDownload,
      boolean collect) {}

  @PostMapping("/shares/collection")
  public Map<String, Object> collection(Authentication a, @Valid @RequestBody Collection in) {
    return db.atomic(
        a.getName(),
        () -> {
          var ids = in.fileIds().stream().distinct().toList();
          for (String id : ids) drive.live(a.getName(), id);
          if (in.collect()
              && (ids.size() != 1
                  || !s(drive.live(a.getName(), ids.getFirst()), "kind").equals("FOLDER")))
            throw Problem.invalid("文件收集需要选择一个文件夹");
          var result =
              create(
                  a,
                  new Create(
                      in.collect() ? ids.getFirst() : drive.root(a.getName()),
                      in.hours(),
                      in.code(),
                      !in.collect() && in.allowDownload()));
          String share = s(result, "id");
          db.update(
              "UPDATE file_share SET share_mode=?,title=? WHERE id=?",
              in.collect() ? "COLLECT" : "COLLECTION",
              in.title(),
              share);
          if (!in.collect())
            for (String id : ids)
              db.update("INSERT INTO share_member(share_id,entry_id) VALUES(?,?)", share, id);
          return result;
        });
  }

  @GetMapping("/shares/{id}/stats")
  public Map<String, Object> stats(Authentication a, @PathVariable String id) {
    var share =
        db.one(
            "SELECT views,downloads,download_authorizations,collected FROM file_share WHERE id=?"
                + " AND user_id=?",
            id,
            a.getName());
    return Map.of(
        "totals",
        share,
        "events",
        db.list(
            "SELECT action,created_at FROM share_event WHERE share_id=? ORDER BY created_at DESC"
                + " LIMIT 100",
            id));
  }

  private void hit(Map<String, Object> share, String action) {
    String column = action.equals("VIEW") ? "views" : "downloads";
    db.update("UPDATE file_share SET " + column + "=" + column + "+1 WHERE id=?", share.get("id"));
    db.update(
        "INSERT INTO share_event(id,share_id,action,created_at) VALUES(?,?,?,?)",
        Util.id(),
        share.get("id"),
        action,
        System.currentTimeMillis());
  }

  @org.springframework.scheduling.annotation.Scheduled(fixedDelay = 3600000)
  public void cleanEvents() {
    db.update(
        "DELETE FROM share_event WHERE created_at<?", System.currentTimeMillis() - 90L * 86400000);
  }

  @PostMapping("/public/shares/{token}/collect")
  public Map<String, Object> collect(
      @PathVariable String token,
      @RequestParam("file") org.springframework.web.multipart.MultipartFile file,
      HttpServletRequest req)
      throws IOException {
    limits.check("collect:" + req.getRemoteAddr(), 10);
    var share = authorized(token, req);
    if (!"COLLECT".equals(s(share, "share_mode"))) throw Problem.missing();
    if (file.getSize() > 100 * 1024 * 1024L)
      throw new Problem(413, "TOO_LARGE", "收集入口单文件最多 100 MiB");
    String uid = s(share, "user_id"), name = Util.name(file.getOriginalFilename());
    db.atomic(
        uid,
        () -> {
          var fresh = db.one("SELECT * FROM file_share WHERE id=?", share.get("id"));
          if (n(fresh, "collected") >= 100
              || b(fresh, "revoked")
              || n(fresh, "expires_at") < System.currentTimeMillis())
            throw Problem.conflict("收集已结束或已达到 100 个文件上限");
          db.update("UPDATE file_share SET collected=collected+1 WHERE id=?", share.get("id"));
          return null;
        });
    boolean success = false;
    try {
      String suffix = " (收集 " + Util.id().substring(0, 8) + ")";
      int dot = name.lastIndexOf('.');
      String stem = dot > 0 ? name.substring(0, dot) : name,
          extension = dot > 0 ? name.substring(dot) : "";
      if (extension.length() > 40) extension = extension.substring(0, 40);
      if (stem.length() > 110) stem = stem.substring(0, 110);
      ingest.receive(
          uid,
          s(share, "file_id"),
          stem + suffix + extension,
          file.getInputStream(),
          100 * 1024 * 1024L,
          null,
          null,
          () -> {
            var fresh =
                db.one("SELECT revoked,expires_at FROM file_share WHERE id=?", share.get("id"));
            if (b(fresh, "revoked") || n(fresh, "expires_at") < System.currentTimeMillis())
              throw Problem.missing();
          });
      success = true;
      return Map.of("received", true);
    } finally {
      if (!success)
        db.update("UPDATE file_share SET collected=collected-1 WHERE id=?", share.get("id"));
    }
  }

  @GetMapping("/shares")
  public List<Map<String, Object>> list(Authentication a) {
    return db
        .list(
            "SELECT s.*,e.name FROM file_share s JOIN drive_entry e ON e.id=s.file_id WHERE"
                + " s.user_id=? ORDER BY s.created_at DESC",
            a.getName())
        .stream()
        .map(
            s ->
                Map.<String, Object>of(
                    "id",
                    s.get("id"),
                    "fileId",
                    s.get("file_id"),
                    "name",
                    s.get("title") == null ? s.get("name") : s.get("title"),
                    "expiresAt",
                    s.get("expires_at"),
                    "allowDownload",
                    b(s, "allow_download"),
                    "revoked",
                    b(s, "revoked"),
                    "hasCode",
                    s.get("code_hash") != null))
        .toList();
  }

  @DeleteMapping("/shares/{id}")
  public void revoke(Authentication a, @PathVariable String id) {
    db.atomic(
        a.getName(),
        () -> {
          db.one("SELECT id FROM file_share WHERE id=? AND user_id=?", id, a.getName());
          db.update("UPDATE file_share SET revoked=TRUE,pinned_blob=NULL WHERE id=?", id);
          db.update(
              "INSERT INTO activity_log(id,user_id,action,target_id,created_at) VALUES(?,?,?,?,?)",
              Util.id(),
              a.getName(),
              "SHARE_REVOKED",
              id,
              System.currentTimeMillis());
          return null;
        });
  }

  private Map<String, Object> valid(String token, HttpServletRequest req) {
    if (!token.matches("[A-Za-z0-9_-]{43}")) throw Problem.missing();
    limits.check("share:" + req.getRemoteAddr(), 120);
    var s = db.one("SELECT * FROM file_share WHERE token_hash=?", Util.hash(token));
    if (b(s, "revoked") || n(s, "expires_at") <= System.currentTimeMillis())
      throw new Problem(410, "SHARE_EXPIRED", "分享已失效或被撤销");
    drive.live(s(s, "user_id"), s(s, "file_id"));
    return s;
  }

  private boolean granted(Map<String, Object> s, HttpServletRequest req) {
    if (s.get("code_hash") == null) return true;
    String cookieName = "CN_SHARE_" + s(s, "id");
    String value =
        Arrays.stream(Optional.ofNullable(req.getCookies()).orElse(new Cookie[0]))
            .filter(c -> c.getName().equals(cookieName))
            .map(Cookie::getValue)
            .findFirst()
            .orElse("");
    return db.count(
            "SELECT COUNT(*) FROM share_grant WHERE id=? AND share_id=? AND expires_at>?",
            Util.hash(value),
            s.get("id"),
            System.currentTimeMillis())
        == 1;
  }

  @GetMapping("/public/shares/{token}")
  public Map<String, Object> info(@PathVariable String token, HttpServletRequest req) {
    var s = valid(token, req);
    if (!granted(s, req)) return Map.of("locked", true, "expiresAt", s.get("expires_at"));
    var e = drive.live(s(s, "user_id"), s(s, "file_id"));
    if (s.get("pinned_blob") != null) {
      var pinned = db.one("SELECT * FROM file_blob WHERE id=?", s.get("pinned_blob"));
      e.put("size", pinned.get("size"));
      e.put("mime", pinned.get("mime"));
    }
    if (s.get("title") != null) e.put("name", s.get("title"));
    hit(s, "VIEW");
    var roots =
        "COLLECTION".equals(s(s, "share_mode"))
            ? db.list("SELECT entry_id FROM share_member WHERE share_id=?", s.get("id")).stream()
                .map(m -> s(m, "entry_id"))
                .toList()
            : List.of(s(s, "file_id"));
    return Map.of(
        "mode",
        s(s, "share_mode"),
        "fileIds",
        roots,
        "locked",
        false,
        "file",
        drive.view(e),
        "allowDownload",
        b(s, "allow_download"),
        "expiresAt",
        s.get("expires_at"));
  }

  @PostMapping("/public/shares/{token}/verify")
  public Map<String, Object> verify(
      @PathVariable String token,
      @Valid @RequestBody Verify in,
      HttpServletRequest req,
      HttpServletResponse res) {
    limits.check("code:" + req.getRemoteAddr(), 20);
    String owner = s(valid(token, req), "user_id");
    return db.atomic(
        owner,
        () -> {
          var s = valid(token, req);
          if (s.get("code_hash") != null
              && (in.code == null || !passwords.matches(in.code, s(s, "code_hash"))))
            throw new Problem(403, "WRONG_CODE", "提取码错误");
          String grant = Util.token();
          long duration =
              Math.min(
                  Duration.ofHours(1).toMillis(), n(s, "expires_at") - System.currentTimeMillis());
          db.update(
              "INSERT INTO share_grant(id,share_id,expires_at) VALUES(?,?,?)",
              Util.hash(grant),
              s.get("id"),
              System.currentTimeMillis() + duration);
          res.addHeader(
              HttpHeaders.SET_COOKIE,
              ResponseCookie.from("CN_SHARE_" + s(s, "id"), grant)
                  .path("/api/public/shares")
                  .httpOnly(true)
                  .sameSite("Lax")
                  .secure(secure)
                  .maxAge(Duration.ofMillis(duration))
                  .build()
                  .toString());
          return Map.of("verified", true);
        });
  }

  @GetMapping("/public/shares/{token}/{action:preview|download}")
  public void read(
      @PathVariable String token,
      @PathVariable String action,
      HttpServletRequest req,
      HttpServletResponse res)
      throws IOException {
    var s = valid(token, req);
    if (!granted(s, req)) throw new Problem(403, "CODE_REQUIRED", "请输入提取码");
    if (action.equals("download") && !b(s, "allow_download"))
      throw new Problem(403, "DOWNLOAD_DISABLED", "此分享未开放下载");
    var e = drive.file(s(s, "user_id"), s(s, "file_id"));
    var blob =
        db.one(
            "SELECT * FROM file_blob WHERE id=? AND user_id=? AND status='READY'",
            s.get("pinned_blob"),
            s.get("user_id"));
    content.sendBlob(e, blob, action.equals("preview"), req, res);
    if (action.equals("download") && req.getMethod().equals("GET")) hit(s, "DOWNLOAD");
  }

  private Map<String, Object> authorized(String token, HttpServletRequest req) {
    var s = valid(token, req);
    if (!granted(s, req)) throw new Problem(403, "CODE_REQUIRED", "请输入提取码");
    return s;
  }

  @GetMapping("/public/shares/{token}/download-authorization")
  public Map<String, Object> authorization(
      @PathVariable String token, HttpServletRequest req, HttpServletResponse res) {
    var share = authorized(token, req);
    if (!b(share, "allow_download")) throw new Problem(403, "DOWNLOAD_DISABLED", "此分享未开放下载");
    if (!"READ".equals(s(share, "share_mode"))) throw Problem.missing();
    var entry = drive.file(s(share, "user_id"), s(share, "file_id"));
    var blob =
        db.one(
            "SELECT * FROM file_blob WHERE id=? AND user_id=? AND status='READY'",
            share.get("pinned_blob"),
            share.get("user_id"));
    var result =
        direct.authorize(
            entry, blob, "/api/public/shares/" + token + "/download", n(share, "expires_at"), res);
    authorizedHit(share);
    return result;
  }

  @GetMapping("/public/shares/{token}/entries/{id}/download-authorization")
  public Map<String, Object> memberAuthorization(
      @PathVariable String token,
      @PathVariable String id,
      HttpServletRequest req,
      HttpServletResponse res) {
    var share = authorized(token, req);
    var entry = member(share, id);
    if (!b(share, "allow_download")) throw new Problem(403, "DOWNLOAD_DISABLED", "此分享未开放下载");
    drive.file(s(share, "user_id"), id);
    var result =
        direct.authorize(
            entry,
            content.blob(s(share, "user_id"), id),
            "/api/public/shares/" + token + "/entries/" + id + "/download",
            n(share, "expires_at"),
            res);
    authorizedHit(share);
    return result;
  }

  private void authorizedHit(Map<String, Object> share) {
    // Separate from legacy streamed request counters; an issued URL is not a completed transfer.
    db.transaction(
        () -> {
          db.update(
              "UPDATE file_share SET download_authorizations=download_authorizations+1 WHERE id=?",
              share.get("id"));
          db.update(
              "INSERT INTO share_event(id,share_id,action,created_at) VALUES(?,?,?,?)",
              Util.id(),
              share.get("id"),
              "DOWNLOAD_AUTHORIZED",
              System.currentTimeMillis());
          return null;
        });
  }

  private Map<String, Object> member(Map<String, Object> share, String id) {
    String uid = s(share, "user_id"), root = s(share, "file_id");
    if ("COLLECT".equals(s(share, "share_mode")))
      throw new Problem(403, "COLLECT_ONLY", "该链接仅用于上传文件");
    drive.folder(uid, root);
    var e = drive.live(uid, id);
    if ("COLLECTION".equals(s(share, "share_mode"))) {
      Set<String> roots =
          new HashSet<>(
              db
                  .list("SELECT entry_id FROM share_member WHERE share_id=?", share.get("id"))
                  .stream()
                  .map(m -> s(m, "entry_id"))
                  .toList());
      String cursor = id;
      Set<String> seen = new HashSet<>();
      while (cursor != null && seen.add(cursor)) {
        if (roots.contains(cursor)) return e;
        cursor = s(drive.live(uid, cursor), "parent_id");
      }
      throw Problem.missing();
    }
    String cursor = id;
    Set<String> seen = new HashSet<>();
    while (cursor != null && seen.add(cursor)) {
      if (cursor.equals(root)) return e;
      cursor = s(drive.live(uid, cursor), "parent_id");
    }
    throw Problem.missing();
  }

  @GetMapping("/public/shares/{token}/entries/{id}/attachment")
  public void attachment(
      @PathVariable String token,
      @PathVariable String id,
      @RequestParam String path,
      HttpServletRequest req,
      HttpServletResponse res)
      throws IOException {
    var share = authorized(token, req);
    member(share, id);
    String target = content.attachment(s(share, "user_id"), id, path, s(share, "file_id"));
    member(share, target);
    content.send(s(share, "user_id"), target, true, req, res);
  }

  @GetMapping("/public/shares/{token}/entries")
  public Map<String, Object> entries(
      @PathVariable String token,
      @RequestParam(required = false) String parentId,
      @RequestParam(defaultValue = "1") int page,
      HttpServletRequest req) {
    var share = authorized(token, req);
    if ("COLLECT".equals(s(share, "share_mode")))
      throw new Problem(403, "COLLECT_ONLY", "该链接仅用于上传文件");
    if (parentId == null && "COLLECTION".equals(s(share, "share_mode"))) {
      var members =
          db.list(
              "SELECT e.* FROM drive_entry e JOIN share_member m ON m.entry_id=e.id WHERE"
                  + " m.share_id=? AND e.deleted=FALSE ORDER BY e.kind,e.name,e.id LIMIT 100",
              share.get("id"));
      var views = new ArrayList<Map<String, Object>>();
      for (var e : members) {
        try {
          member(share, s(e, "id"));
          views.add(drive.view(e));
        } catch (Problem ignored) {
        }
      }
      int start = (Math.max(1, page) - 1) * 50;
      return Map.of(
          "items",
          views.subList(Math.min(start, views.size()), Math.min(start + 50, views.size())),
          "total",
          views.size(),
          "page",
          page,
          "pageSize",
          50);
    }
    String id = parentId == null ? s(share, "file_id") : parentId;
    member(share, id);
    drive.folder(s(share, "user_id"), id);
    var result = drive.list(s(share, "user_id"), id, "", "current", "name", "asc", page, 50);
    // Avoid exposing paths outside the shared subtree.
    return result;
  }

  @GetMapping("/public/shares/{token}/entries/{id}/{action:preview|download}")
  public void readMember(
      @PathVariable String token,
      @PathVariable String id,
      @PathVariable String action,
      HttpServletRequest req,
      HttpServletResponse res)
      throws IOException {
    var share = authorized(token, req);
    member(share, id);
    if (action.equals("download") && !b(share, "allow_download"))
      throw new Problem(403, "DOWNLOAD_DISABLED", "此分享未开放下载");
    content.send(s(share, "user_id"), id, action.equals("preview"), req, res);
    if (action.equals("download") && req.getMethod().equals("GET")) hit(share, "DOWNLOAD");
  }

  public record Update(
      @Min(1) @Max(720) int hours, boolean allowDownload, @Size(max = 32) String code) {}

  @PatchMapping("/shares/{id}")
  public void edit(Authentication a, @PathVariable String id, @Valid @RequestBody Update in) {
    if (in.code() != null
        && ((!in.code().isEmpty() && in.code().length() < 4)
            || in.code().getBytes(java.nio.charset.StandardCharsets.UTF_8).length > 72))
      throw Problem.invalid("新提取码需要 4～32 字符且 UTF-8 最多72字节；留空表示移除");
    db.atomic(
        a.getName(),
        () -> {
          var share = db.one("SELECT * FROM file_share WHERE id=? AND user_id=?", id, a.getName());
          if (b(share, "revoked") || n(share, "expires_at") <= System.currentTimeMillis())
            throw Problem.conflict("请重新创建已失效的分享");
          db.update(
              "UPDATE file_share SET expires_at=?,allow_download=? WHERE id=?",
              System.currentTimeMillis() + Duration.ofHours(in.hours()).toMillis(),
              in.allowDownload(),
              id);
          db.update("DELETE FROM share_grant WHERE share_id=?", id);
          if (in.code() != null)
            db.update(
                "UPDATE file_share SET code_hash=? WHERE id=?",
                in.code().isEmpty() ? null : passwords.encode(in.code()),
                id);
          db.update(
              "INSERT INTO activity_log(id,user_id,action,target_id,created_at) VALUES(?,?,?,?,?)",
              Util.id(),
              a.getName(),
              "SHARE_UPDATED",
              id,
              System.currentTimeMillis());
          return null;
        });
  }

  @PostMapping("/public/shares/{token}/downloads")
  public Map<String, Object> createZip(
      @PathVariable String token, @Valid @RequestBody DownloadJobs.Input in, HttpServletRequest req)
      throws IOException {
    var share = authorized(token, req);
    if (!b(share, "allow_download")) throw new Problem(403, "DOWNLOAD_DISABLED", "此分享未开放下载");
    for (String id : in.fileIds()) member(share, id);
    var result =
        jobs.create(
            new org.springframework.security.authentication.UsernamePasswordAuthenticationToken(
                s(share, "user_id"), null),
            in);
    db.update("UPDATE download_job SET share_id=? WHERE id=?", share.get("id"), result.get("id"));
    return result;
  }

  private Authentication zipAuth(Map<String, Object> share, String job) throws IOException {
    var j = db.one("SELECT * FROM download_job WHERE id=? AND share_id=?", job, share.get("id"));
    for (String id : jobs.sourceIds(s(share, "user_id"), job)) member(share, id);
    if (!b(share, "allow_download")) throw new Problem(403, "DOWNLOAD_DISABLED", "此分享未开放下载");
    return new org.springframework.security.authentication.UsernamePasswordAuthenticationToken(
        s(share, "user_id"), null);
  }

  @GetMapping("/public/shares/{token}/downloads/{id}")
  public Map<String, Object> zipStatus(
      @PathVariable String token, @PathVariable String id, HttpServletRequest req)
      throws IOException {
    var share = authorized(token, req);
    return jobs.status(zipAuth(share, id), id);
  }

  @DeleteMapping("/public/shares/{token}/downloads/{id}")
  public void cancelZip(@PathVariable String token, @PathVariable String id, HttpServletRequest req)
      throws IOException {
    var share = authorized(token, req);
    jobs.cancel(zipAuth(share, id), id);
  }

  @GetMapping("/public/shares/{token}/downloads/{id}/file")
  public void zipFile(
      @PathVariable String token,
      @PathVariable String id,
      HttpServletRequest req,
      HttpServletResponse res)
      throws IOException {
    var share = authorized(token, req);
    jobs.download(zipAuth(share, id), id, res);
    if (req.getMethod().equals("GET")) hit(share, "DOWNLOAD");
  }
}
