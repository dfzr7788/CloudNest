package com.cloudnest.workspace;

import static com.cloudnest.common.Db.*;

import com.cloudnest.common.*;
import com.cloudnest.file.*;
import com.cloudnest.upload.*;
import jakarta.servlet.http.*;
import jakarta.validation.Valid;
import jakarta.validation.constraints.*;
import java.io.*;
import java.util.*;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/api/teams")
public class TeamController {
  private final Db db;
  private final TeamService teams;
  private final DriveService drive;
  private final UploadService uploads;
  private final ContentService content;

  public TeamController(
      Db db, TeamService teams, DriveService drive, UploadService uploads, ContentService content) {
    this.db = db;
    this.teams = teams;
    this.drive = drive;
    this.uploads = uploads;
    this.content = content;
  }

  public record Create(@NotBlank String name, @Min(1024) long quota) {}

  public record Invitation(
      @NotBlank String role, boolean contributor, @Min(1) @Max(168) int hours) {}

  public record Token(@NotBlank @Size(max = 200) String token) {}

  public record Role(@NotBlank String role, boolean contributor) {}

  public record Folder(String parentId, @NotBlank String name) {}

  public record Edit(String name, String parentId) {}

  public record Acl(@NotBlank String userId, @NotBlank String permission) {}

  public record Quota(@Min(1024) long bytes) {}

  @GetMapping
  public Object list(Authentication a) {
    return teams.list(a.getName());
  }

  @PostMapping
  public Object create(Authentication a, @Valid @RequestBody Create in) {
    return teams.create(a.getName(), in.name(), in.quota());
  }

  @PostMapping("/accept")
  public Object accept(Authentication a, @Valid @RequestBody Token in) {
    return teams.accept(a.getName(), in.token());
  }

  @GetMapping("/{team}/members")
  public Object members(Authentication a, @PathVariable String team) {
    return teams.members(a.getName(), team);
  }

  @PostMapping("/{team}/invitations")
  public Object invite(
      Authentication a, @PathVariable String team, @Valid @RequestBody Invitation in) {
    return teams.invite(a.getName(), team, in.role(), in.contributor(), in.hours());
  }

  @GetMapping("/{team}/invitations")
  public Object invitations(Authentication a, @PathVariable String team) {
    return teams.with(
        a.getName(),
        team,
        3,
        w ->
            db.list(
                "SELECT id,file_role,contributor,expires_at,consumed_by,revoked FROM"
                    + " team_invitation WHERE team_id=? ORDER BY expires_at DESC LIMIT 100",
                team));
  }

  @DeleteMapping("/{team}/invitations/{id}")
  public void revoke(Authentication a, @PathVariable String team, @PathVariable String id) {
    teams.with(
        a.getName(),
        team,
        3,
        w -> {
          db.update("UPDATE team_invitation SET revoked=TRUE WHERE id=? AND team_id=?", id, team);
          teams.audit(team, a.getName(), "INVITATION_REVOKED", id);
          return null;
        });
  }

  @PutMapping("/{team}/members/{user}")
  public void role(
      Authentication a,
      @PathVariable String team,
      @PathVariable String user,
      @Valid @RequestBody Role in) {
    teams.role(a.getName(), team, user, in.role(), in.contributor(), false);
  }

  @DeleteMapping("/{team}/members/{user}")
  public void remove(Authentication a, @PathVariable String team, @PathVariable String user) {
    teams.role(a.getName(), team, user, "NONE", false, true);
  }

  @PutMapping("/{team}/quota")
  public void quota(Authentication a, @PathVariable String team, @Valid @RequestBody Quota in) {
    teams.quota(a.getName(), team, in.bytes());
  }

  @GetMapping("/{team}/audit")
  public Object audit(Authentication a, @PathVariable String team) {
    return teams.with(
        a.getName(),
        team,
        3,
        w ->
            db.list(
                "SELECT * FROM team_audit WHERE team_id=? ORDER BY created_at DESC LIMIT 200",
                team));
  }

  @PutMapping("/{team}/entries/{id}/acl")
  public void acl(
      Authentication a,
      @PathVariable String team,
      @PathVariable String id,
      @Valid @RequestBody Acl in) {
    teams.acl(a.getName(), team, id, in.userId(), in.permission());
  }

  @GetMapping("/{team}/entries/{id}/acl")
  public Object acl(Authentication a, @PathVariable String team, @PathVariable String id) {
    return teams.with(
        a.getName(),
        team,
        3,
        w -> {
          drive.live(s(w, "storage_user"), id);
          return db.list("SELECT * FROM team_acl WHERE team_id=? AND entry_id=?", team, id);
        });
  }

  @GetMapping("/{team}/files")
  public Object files(
      Authentication a,
      @PathVariable String team,
      @RequestParam(required = false) String parentId,
      @RequestParam(defaultValue = "1") int page) {
    return teams.with(
        a.getName(),
        team,
        1,
        w -> {
          String uid = s(w, "storage_user"), parent = s(drive.folder(uid, parentId), "id");
          teams.entry(a.getName(), w, parent, false);
          // Filter before pagination, preventing restricted entries from leaking in counts/names.
          var visible = new ArrayList<Map<String, Object>>();
          for (var e :
              db.list(
                  "SELECT * FROM drive_entry WHERE user_id=? AND parent_id=? AND deleted=FALSE"
                      + " ORDER BY kind DESC,name,id",
                  uid,
                  parent)) {
            try {
              teams.entry(a.getName(), w, s(e, "id"), false);
              visible.add(drive.view(e));
            } catch (Problem denied) {
              if (denied.status != 403) throw denied;
            }
          }
          int start = (int) Math.min(visible.size(), Math.max(0L, ((long) page - 1) * 100));
          return Map.of(
              "items",
              visible.subList(start, Math.min(start + 100, visible.size())),
              "total",
              visible.size(),
              "parentId",
              parent,
              "breadcrumbs",
              drive.breadcrumbs(uid, parent));
        });
  }

  @GetMapping("/{team}/folders")
  public Object folders(
      Authentication a,
      @PathVariable String team,
      @RequestParam(required = false) String parentId) {
    return teams.with(
        a.getName(),
        team,
        1,
        w -> {
          String uid = s(w, "storage_user"), parent = s(drive.folder(uid, parentId), "id");
          teams.entry(a.getName(), w, parent, false);
          var visible = new ArrayList<Map<String, Object>>();
          for (var e :
              db.list(
                  "SELECT * FROM drive_entry WHERE user_id=? AND parent_id=? AND kind='FOLDER' AND"
                      + " deleted=FALSE ORDER BY name,id LIMIT 20001",
                  uid,
                  parent)) {
            try {
              teams.entry(a.getName(), w, s(e, "id"), false);
              visible.add(drive.view(e));
            } catch (Problem denied) {
              if (denied.status != 403) throw denied;
            }
          }
          if (visible.size() > 20000) throw Problem.invalid("目录过大，请拆分子目录");
          return Map.of("items", visible, "breadcrumbs", drive.breadcrumbs(uid, parent));
        });
  }

  @PostMapping("/{team}/folders")
  public Object folder(Authentication a, @PathVariable String team, @Valid @RequestBody Folder in) {
    return teams.with(
        a.getName(),
        team,
        2,
        w -> {
          String uid = s(w, "storage_user"), parent = s(drive.folder(uid, in.parentId()), "id");
          teams.entry(a.getName(), w, parent, true);
          var result = drive.createFolder(uid, parent, in.name());
          teams.audit(team, a.getName(), "FOLDER_CREATED", s(result, "id"));
          return result;
        });
  }

  @PatchMapping("/{team}/entries/{id}")
  public Object edit(
      Authentication a, @PathVariable String team, @PathVariable String id, @RequestBody Edit in) {
    return teams.with(
        a.getName(),
        team,
        2,
        w -> {
          String uid = s(w, "storage_user");
          teams.subtree(a.getName(), w, id);
          var e = drive.live(uid, id);
          teams.entry(
              a.getName(), w, in.parentId() == null ? s(e, "parent_id") : in.parentId(), true);
          var result = drive.edit(uid, id, in.name(), in.parentId(), s(e, "kind"));
          teams.audit(team, a.getName(), "ENTRY_UPDATED", id);
          return result;
        });
  }

  @DeleteMapping("/{team}/entries/{id}")
  public void trash(Authentication a, @PathVariable String team, @PathVariable String id) {
    teams.with(
        a.getName(),
        team,
        2,
        w -> {
          teams.subtree(a.getName(), w, id);
          var e = drive.live(s(w, "storage_user"), id);
          drive.trash(s(w, "storage_user"), id, s(e, "kind"));
          teams.audit(team, a.getName(), "ENTRY_TRASHED", id);
          return null;
        });
  }

  @GetMapping("/{team}/trash")
  public Object trash(Authentication a, @PathVariable String team) {
    return teams.with(a.getName(), team, 3, w -> drive.trashList(s(w, "storage_user")));
  }

  @PostMapping("/{team}/trash/{id}/restore")
  public Object restore(Authentication a, @PathVariable String team, @PathVariable String id) {
    return teams.with(
        a.getName(),
        team,
        3,
        w -> {
          String uid = s(w, "storage_user");
          var e = drive.entry(uid, id, true);
          var r = drive.restore(uid, id, s(e, "kind"));
          teams.audit(team, a.getName(), "TRASH_RESTORED", id);
          return r;
        });
  }

  @DeleteMapping("/{team}/trash/{id}")
  public void purge(Authentication a, @PathVariable String team, @PathVariable String id) {
    teams.with(
        a.getName(),
        team,
        3,
        w -> {
          String uid = s(w, "storage_user");
          var e = drive.entry(uid, id, true);
          drive.purge(uid, id, s(e, "kind"));
          teams.audit(team, a.getName(), "TRASH_PURGED", id);
          return null;
        });
  }

  @GetMapping("/{team}/files/{id}/download")
  public void download(
      Authentication a,
      @PathVariable String team,
      @PathVariable String id,
      HttpServletRequest req,
      HttpServletResponse res)
      throws IOException {
    String uid =
        teams.with(
            a.getName(),
            team,
            1,
            w -> {
              teams.entry(a.getName(), w, id, false);
              teams.audit(team, a.getName(), "FILE_READ", id);
              return s(w, "storage_user");
            });
    content.send(uid, id, false, req, res);
  }

  @PostMapping("/{team}/uploads/init")
  public Object init(
      Authentication a, @PathVariable String team, @Valid @RequestBody UploadController.Init in) {
    return teams.with(
        a.getName(),
        team,
        2,
        w -> {
          String uid = s(w, "storage_user"), parent = s(drive.folder(uid, in.folderId()), "id");
          teams.entry(a.getName(), w, parent, true);
          if (in.targetId() != null) teams.entry(a.getName(), w, in.targetId(), true);
          var result =
              uploads.init(
                  uid,
                  parent,
                  in.name(),
                  in.size(),
                  in.sha256(),
                  in.targetId(),
                  in.expectedRevision());
          String id = s(result, "id");
          var actors = db.list("SELECT actor_id FROM team_upload_actor WHERE session_id=?", id);
          if (!actors.isEmpty() && !a.getName().equals(s(actors.getFirst(), "actor_id")))
            throw Problem.conflict("已有其他成员的同名上传");
          if (actors.isEmpty())
            db.update(
                "INSERT INTO team_upload_actor(session_id,team_id,actor_id) VALUES(?,?,?)",
                id,
                team,
                a.getName());
          teams.audit(team, a.getName(), "UPLOAD_INITIALIZED", id);
          return result;
        });
  }

  private String authorizeUpload(String actor, String team, Map<String, Object> w, String id) {
    db.one(
        "SELECT session_id FROM team_upload_actor WHERE session_id=? AND team_id=? AND actor_id=?",
        id,
        team,
        actor);
    String uid = s(w, "storage_user");
    var session = db.one("SELECT * FROM upload_session WHERE id=? AND user_id=?", id, uid);
    teams.entry(actor, w, s(session, "folder_id"), true);
    if (session.get("target_id") != null) teams.entry(actor, w, s(session, "target_id"), true);
    return uid;
  }

  @GetMapping("/{team}/uploads/{id}")
  public Object status(Authentication a, @PathVariable String team, @PathVariable String id) {
    return teams.with(
        a.getName(), team, 2, w -> uploads.status(authorizeUpload(a.getName(), team, w, id), id));
  }

  @PutMapping("/{team}/uploads/{id}/parts/{number}")
  public void part(
      Authentication a,
      @PathVariable String team,
      @PathVariable String id,
      @PathVariable int number,
      HttpServletRequest req)
      throws IOException {
    try {
      teams.with(
          a.getName(),
          team,
          2,
          w -> {
            try {
              uploads.part(
                  authorizeUpload(a.getName(), team, w, id), id, number, req.getInputStream());
              return null;
            } catch (IOException e) {
              throw new UncheckedIOException(e);
            }
          });
    } catch (UncheckedIOException e) {
      throw e.getCause();
    }
  }

  @PostMapping("/{team}/uploads/{id}/complete")
  public Object complete(Authentication a, @PathVariable String team, @PathVariable String id)
      throws IOException {
    try {
      return teams.with(
          a.getName(),
          team,
          2,
          w -> {
            try {
              var result = uploads.complete(authorizeUpload(a.getName(), team, w, id), id);
              teams.audit(team, a.getName(), "UPLOAD_COMPLETED", id);
              return result;
            } catch (IOException e) {
              throw new UncheckedIOException(e);
            }
          });
    } catch (UncheckedIOException e) {
      throw e.getCause();
    }
  }

  @DeleteMapping("/{team}/uploads/{id}")
  public void cancel(Authentication a, @PathVariable String team, @PathVariable String id) {
    teams.with(
        a.getName(),
        team,
        2,
        w -> {
          uploads.cancel(authorizeUpload(a.getName(), team, w, id), id);
          teams.audit(team, a.getName(), "UPLOAD_CANCELLED", id);
          return null;
        });
  }
}
