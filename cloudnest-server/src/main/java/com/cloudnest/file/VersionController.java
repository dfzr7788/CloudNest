package com.cloudnest.file;

import static com.cloudnest.common.Db.*;

import com.cloudnest.common.*;
import jakarta.servlet.http.*;
import java.io.IOException;
import java.util.*;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/api/files/{id}/versions")
public class VersionController {
  private final Db db;
  private final DriveService drive;
  private final ContentService content;

  public VersionController(Db db, DriveService drive, ContentService content) {
    this.db = db;
    this.drive = drive;
    this.content = content;
  }

  @GetMapping
  public Map<String, Object> list(Authentication a, @PathVariable String id) {
    return db.atomic(
        a.getName(),
        () -> {
          var e = drive.file(a.getName(), id);
          return Map.of(
              "revision",
              n(e, "revision"),
              "items",
              db.list(
                  "SELECT id,size,mime,created_at FROM file_version WHERE entry_id=? ORDER BY"
                      + " created_at DESC,id",
                  id));
        });
  }

  public record Append(String sourceId, long expectedRevision) {}

  @PostMapping
  public void append(Authentication a, @PathVariable String id, @RequestBody Append in) {
    db.atomic(
        a.getName(),
        () -> {
          var target = drive.file(a.getName(), id);
          var source = drive.file(a.getName(), in.sourceId());
          if (id.equals(in.sourceId())) throw Problem.invalid("请选择另一个文件作为新版本");
          if (n(target, "revision") != in.expectedRevision())
            throw Problem.conflict("文件版本已变化，请刷新后重试");
          if (db.count("SELECT COUNT(*) FROM file_version WHERE entry_id=?", id) >= 20)
            throw Problem.conflict("最多保留 20 个历史版本，请先清理旧版本");
          if (db.count("SELECT COUNT(*) FROM file_version WHERE entry_id=?", in.sourceId()) > 0
              || db.count("SELECT COUNT(*) FROM file_share WHERE file_id=?", in.sourceId()) > 0
              || db.count("SELECT COUNT(*) FROM share_member WHERE entry_id=?", in.sourceId()) > 0
              || db.count(
                      "SELECT COUNT(*) FROM office_session WHERE file_id=? AND expires_at>?",
                      in.sourceId(),
                      System.currentTimeMillis())
                  > 0) throw Problem.conflict("来源文件已有历史或分享，请上传独立副本");
          saveOld(target);
          db.update(
              "UPDATE drive_entry SET blob_id=?,size=?,mime=?,revision=revision+1,updated_at=?"
                  + " WHERE id=?",
              source.get("blob_id"),
              source.get("size"),
              source.get("mime"),
              System.currentTimeMillis(),
              id);
          db.update("DELETE FROM drive_entry WHERE id=?", in.sourceId());
          log(a.getName(), "VERSION_APPENDED", id);
          return null;
        });
  }

  public record Restore(long expectedRevision) {}

  @PostMapping("/{version}/restore")
  public void restore(
      Authentication a,
      @PathVariable String id,
      @PathVariable String version,
      @RequestBody Restore in) {
    db.atomic(
        a.getName(),
        () -> {
          var e = drive.file(a.getName(), id);
          if (n(e, "revision") != in.expectedRevision()) throw Problem.conflict("文件版本已变化，请刷新后重试");
          var v = db.one("SELECT * FROM file_version WHERE id=? AND entry_id=?", version, id);
          db.update("DELETE FROM file_version WHERE id=?", version);
          saveOld(e);
          db.update(
              "UPDATE drive_entry SET blob_id=?,size=?,mime=?,revision=revision+1,updated_at=?"
                  + " WHERE id=?",
              v.get("blob_id"),
              v.get("size"),
              v.get("mime"),
              System.currentTimeMillis(),
              id);
          log(a.getName(), "VERSION_RESTORED", id);
          return null;
        });
  }

  @DeleteMapping("/{version}")
  public void remove(Authentication a, @PathVariable String id, @PathVariable String version) {
    db.atomic(
        a.getName(),
        () -> {
          drive.file(a.getName(), id);
          var v = db.one("SELECT * FROM file_version WHERE id=? AND entry_id=?", version, id);
          db.update("DELETE FROM file_version WHERE id=?", version);
          db.update(
              "UPDATE sys_user SET storage_used=storage_used-? WHERE id=?",
              v.get("size"),
              a.getName());
          drive.releaseBlob(s(v, "blob_id"));
          log(a.getName(), "VERSION_DELETED", id);
          return null;
        });
  }

  @GetMapping("/{version}/download")
  public void download(
      Authentication a,
      @PathVariable String id,
      @PathVariable String version,
      HttpServletRequest req,
      HttpServletResponse res)
      throws IOException {
    var e = drive.file(a.getName(), id);
    var v = db.one("SELECT * FROM file_version WHERE id=? AND entry_id=?", version, id);
    content.sendBlob(
        e,
        db.one(
            "SELECT * FROM file_blob WHERE id=? AND user_id=? AND status='READY'",
            v.get("blob_id"),
            a.getName()),
        false,
        req,
        res);
  }

  private void saveOld(Map<String, Object> e) {
    db.update(
        "INSERT INTO file_version(id,entry_id,blob_id,size,mime,created_at) VALUES(?,?,?,?,?,?)",
        Util.id(),
        e.get("id"),
        e.get("blob_id"),
        e.get("size"),
        e.get("mime"),
        e.get("updated_at"));
  }

  private void log(String uid, String action, String id) {
    db.update(
        "INSERT INTO activity_log(id,user_id,action,target_id,created_at) VALUES(?,?,?,?,?)",
        Util.id(),
        uid,
        action,
        id,
        System.currentTimeMillis());
  }
}
