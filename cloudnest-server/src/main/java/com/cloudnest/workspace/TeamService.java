package com.cloudnest.workspace;

import static com.cloudnest.common.Db.*;

import com.cloudnest.common.*;
import com.cloudnest.file.DriveService;
import java.util.*;
import java.util.function.Function;
import org.springframework.stereotype.Service;

/** Team resources have their own non-login quota account, never a member's personal root. */
@Service
public class TeamService {
  private final Db db;
  private final DriveService drive;

  public TeamService(Db db, DriveService drive) {
    this.db = db;
    this.drive = drive;
  }

  public static final Set<String> ROLES = Set.of("OWNER", "ADMIN", "EDITOR", "VIEWER", "NONE");

  public static int rank(String role) {
    return switch (role) {
      case "OWNER" -> 4;
      case "ADMIN" -> 3;
      case "EDITOR" -> 2;
      case "VIEWER" -> 1;
      default -> 0;
    };
  }

  public List<Map<String, Object>> list(String actor) {
    return db.list(
        "SELECT"
            + " w.id,w.name,w.owner_id,w.created_at,m.file_role,m.contributor,u.root_id,u.storage_used,u.storage_quota,u.storage_reserved"
            + " FROM team_workspace w JOIN team_member m ON m.team_id=w.id JOIN sys_user u ON"
            + " u.id=w.storage_user WHERE m.user_id=? ORDER BY w.created_at",
        actor);
  }

  public Map<String, Object> create(String actor, String name, long quota) {
    if (quota < 1024) throw Problem.invalid("团队配额至少 1024 字节");
    String title = Util.name(name);
    return db.atomic(
        actor,
        () -> {
          if (db.count("SELECT COUNT(*) FROM team_workspace WHERE owner_id=?", actor) >= 20)
            throw Problem.conflict("最多创建 20 个团队");
          var user =
              db.one(
                  "SELECT * FROM sys_user WHERE id=? AND enabled=TRUE AND workspace_account=FALSE",
                  actor);
          if (quota
              > n(user, "storage_quota") - n(user, "storage_used") - n(user, "storage_reserved"))
            throw new Problem(409, "QUOTA_EXCEEDED", "团队配额从创建者的可用配额划拨");
          String id = Util.id(), storage = Util.id(), root = Util.id();
          long now = System.currentTimeMillis();
          db.update("UPDATE sys_user SET storage_quota=storage_quota-? WHERE id=?", quota, actor);
          // An invalid password hash plus an explicit login exclusion prevents interactive login.
          db.update(
              "INSERT INTO"
                  + " sys_user(id,username,password_hash,nickname,root_id,storage_quota,created_at,workspace_account)"
                  + " VALUES(?,?,?,?,?,?,?,TRUE)",
              storage,
              "workspace_" + id,
              "!workspace-no-login",
              title,
              root,
              quota,
              now);
          db.update(
              "INSERT INTO drive_entry(id,user_id,parent_id,kind,name,created_at,updated_at)"
                  + " VALUES(?,?,NULL,'FOLDER',?,?,?)",
              root,
              storage,
              title,
              now,
              now);
          db.update(
              "INSERT INTO team_workspace(id,name,owner_id,storage_user,created_at)"
                  + " VALUES(?,?,?,?,?)",
              id,
              title,
              actor,
              storage,
              now);
          db.update(
              "INSERT INTO team_member(team_id,user_id,file_role) VALUES(?,?,'OWNER')", id, actor);
          audit(id, actor, "TEAM_CREATED", root);
          return Map.of("id", id, "rootId", root, "name", title);
        });
  }

  public <T> T with(String actor, String team, int minimum, Function<Map<String, Object>, T> work) {
    return db.transaction(
        () -> {
          var w = db.one("SELECT * FROM team_workspace WHERE id=? FOR UPDATE", team);
          var m = member(actor, team);
          if (rank(s(m, "file_role")) < minimum)
            throw new Problem(403, "TEAM_FORBIDDEN", "团队角色不允许此操作");
          return work.apply(w);
        });
  }

  public Map<String, Object> member(String actor, String team) {
    return db.one(
        "SELECT m.* FROM team_member m JOIN sys_user u ON u.id=m.user_id WHERE m.team_id=? AND"
            + " m.user_id=? AND u.enabled=TRUE AND u.workspace_account=FALSE",
        team,
        actor);
  }

  public void entry(String actor, Map<String, Object> team, String entry, boolean write) {
    var m = member(actor, s(team, "id"));
    int role = rank(s(m, "file_role")), needed = write ? 2 : 1;
    if (role < needed) throw new Problem(403, "TEAM_FORBIDDEN", "没有团队文件权限");
    String uid = s(team, "storage_user");
    var e = drive.live(uid, entry);
    if (role >= 3) return;
    // Explicit ACLs restrict inherited role permissions, including descendants.
    Set<String> seen = new HashSet<>();
    while (e != null) {
      String id = s(e, "id");
      if (!seen.add(id)) throw Problem.conflict("目录结构异常");
      var acl =
          db.list(
              "SELECT permission FROM team_acl WHERE team_id=? AND entry_id=? AND user_id=?",
              team.get("id"),
              id,
              actor);
      if (!acl.isEmpty()) {
        int permission =
            switch (s(acl.getFirst(), "permission")) {
              case "WRITE" -> 2;
              case "READ" -> 1;
              default -> 0;
            };
        if (permission < needed) throw new Problem(403, "TEAM_ACL_DENIED", "目录授权不允许此操作");
      }
      String parent = s(e, "parent_id");
      e = parent == null ? null : drive.live(uid, parent);
    }
  }

  public void subtree(String actor, Map<String, Object> w, String id) {
    for (var e : drive.tree(s(w, "storage_user"), id))
      if (!b(e, "deleted")) entry(actor, w, s(e, "id"), true);
  }

  public List<Map<String, Object>> members(String actor, String team) {
    return with(
        actor,
        team,
        0,
        w ->
            db.list(
                "SELECT m.user_id,m.file_role,m.contributor,u.nickname,u.username FROM team_member"
                    + " m JOIN sys_user u ON u.id=m.user_id WHERE m.team_id=?",
                team));
  }

  private void assignable(Map<String, Object> w, String actor, String role) {
    if (!ROLES.contains(role) || role.equals("OWNER")) throw Problem.invalid("成员角色无效");
    if (role.equals("ADMIN") && !actor.equals(s(w, "owner_id")))
      throw new Problem(403, "OWNER_REQUIRED", "仅所有者可授予管理员角色");
  }

  public Map<String, Object> invite(
      String actor, String team, String role, boolean contributor, int hours) {
    if (hours < 1 || hours > 168) throw Problem.invalid("邀请有效期为 1～168 小时");
    return with(
        actor,
        team,
        3,
        w -> {
          assignable(w, actor, role);
          if (db.count(
                  "SELECT COUNT(*) FROM team_invitation WHERE team_id=? AND expires_at>? AND"
                      + " consumed_by IS NULL AND revoked=FALSE",
                  team,
                  System.currentTimeMillis())
              >= 100) throw Problem.conflict("待处理邀请过多");
          String token = Util.token(), id = Util.id();
          long expires = System.currentTimeMillis() + hours * 3600000L;
          db.update(
              "INSERT INTO team_invitation(id,team_id,token_hash,file_role,contributor,expires_at)"
                  + " VALUES(?,?,?,?,?,?)",
              id,
              team,
              Util.hash(token),
              role,
              contributor,
              expires);
          audit(team, actor, "INVITATION_CREATED", id);
          return Map.of("id", id, "token", token, "expiresAt", expires);
        });
  }

  public Map<String, Object> accept(String actor, String token) {
    if (token == null || token.length() > 200) throw Problem.invalid("邀请无效");
    var invitation =
        db.one("SELECT team_id FROM team_invitation WHERE token_hash=?", Util.hash(token));
    String team = s(invitation, "team_id");
    return db.transaction(
        () -> {
          db.one("SELECT id FROM team_workspace WHERE id=? FOR UPDATE", team);
          var i =
              db.one(
                  "SELECT * FROM team_invitation WHERE token_hash=? AND expires_at>? AND"
                      + " consumed_by IS NULL AND revoked=FALSE FOR UPDATE",
                  Util.hash(token),
                  System.currentTimeMillis());
          db.one(
              "SELECT id FROM sys_user WHERE id=? AND enabled=TRUE AND workspace_account=FALSE",
              actor);
          if (db.count(
                  "SELECT COUNT(*) FROM team_member WHERE team_id=? AND user_id=?", team, actor)
              > 0) throw Problem.conflict("已经是团队成员");
          db.update(
              "INSERT INTO team_member(team_id,user_id,file_role,contributor) VALUES(?,?,?,?)",
              team,
              actor,
              i.get("file_role"),
              i.get("contributor"));
          db.update("UPDATE team_invitation SET consumed_by=? WHERE id=?", actor, i.get("id"));
          audit(team, actor, "INVITATION_ACCEPTED", s(i, "id"));
          return Map.of("id", team);
        });
  }

  public void role(
      String actor, String team, String user, String role, boolean contributor, boolean remove) {
    with(
        actor,
        team,
        3,
        w -> {
          if (user.equals(s(w, "owner_id"))) {
            if (!actor.equals(user) || remove || !role.equals("OWNER"))
              throw Problem.conflict("不能修改或移除所有者");
            db.update(
                "UPDATE team_member SET contributor=? WHERE team_id=? AND user_id=?",
                contributor,
                team,
                user);
            audit(team, actor, "CONTRIBUTOR_UPDATED", user);
            return null;
          }
          var old = member(user, team);
          assignable(w, actor, role);
          if (s(old, "file_role").equals("ADMIN") && !actor.equals(s(w, "owner_id")))
            throw new Problem(403, "OWNER_REQUIRED", "仅所有者可管理管理员");
          if (remove) {
            if (db.count(
                    "SELECT COUNT(*) FROM storage_contribution WHERE team_id=? AND contributor_id=?"
                        + " AND status='APPROVED'",
                    team,
                    user)
                > 0) throw Problem.conflict("请先停用成员的存储贡献");
            db.update("DELETE FROM team_acl WHERE team_id=? AND user_id=?", team, user);
            db.update("DELETE FROM team_member WHERE team_id=? AND user_id=?", team, user);
          } else
            db.update(
                "UPDATE team_member SET file_role=?,contributor=? WHERE team_id=? AND user_id=?",
                role,
                contributor,
                team,
                user);
          audit(team, actor, remove ? "MEMBER_REMOVED" : "MEMBER_ROLE_UPDATED", user);
          return null;
        });
  }

  public void acl(String actor, String team, String entry, String user, String permission) {
    if (!Set.of("INHERIT", "READ", "WRITE", "DENY").contains(permission))
      throw Problem.invalid("ACL 无效");
    with(
        actor,
        team,
        3,
        w -> {
          drive.live(s(w, "storage_user"), entry);
          member(user, team);
          db.update(
              "DELETE FROM team_acl WHERE team_id=? AND entry_id=? AND user_id=?",
              team,
              entry,
              user);
          if (!permission.equals("INHERIT"))
            db.update(
                "INSERT INTO team_acl(team_id,entry_id,user_id,permission) VALUES(?,?,?,?)",
                team,
                entry,
                user,
                permission);
          audit(team, actor, "ACL_UPDATED", entry);
          return null;
        });
  }

  public void quota(String actor, String team, long quota) {
    with(
        actor,
        team,
        4,
        w ->
            db.atomic(
                actor,
                () ->
                    db.atomic(
                        s(w, "storage_user"),
                        () -> {
                          var storage =
                              db.one("SELECT * FROM sys_user WHERE id=?", w.get("storage_user"));
                          var owner = db.one("SELECT * FROM sys_user WHERE id=?", actor);
                          long delta = quota - n(storage, "storage_quota");
                          if (quota < 1024
                              || quota < n(storage, "storage_used") + n(storage, "storage_reserved")
                              || delta
                                  > n(owner, "storage_quota")
                                      - n(owner, "storage_used")
                                      - n(owner, "storage_reserved"))
                            throw new Problem(409, "QUOTA_EXCEEDED", "团队已用空间或所有者剩余配额不满足要求");
                          db.update(
                              "UPDATE sys_user SET storage_quota=storage_quota-? WHERE id=?",
                              delta,
                              actor);
                          db.update(
                              "UPDATE sys_user SET storage_quota=? WHERE id=?",
                              quota,
                              w.get("storage_user"));
                          audit(team, actor, "QUOTA_UPDATED", team);
                          return null;
                        })));
  }

  public void audit(String team, String actor, String action, String target) {
    db.update(
        "INSERT INTO team_audit(id,team_id,actor_id,action,target_id,created_at)"
            + " VALUES(?,?,?,?,?,?)",
        Util.id(),
        team,
        actor,
        action,
        target,
        System.currentTimeMillis());
  }
}
