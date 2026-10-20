package com.cloudnest.control;

import com.cloudnest.common.Db;
import com.cloudnest.common.Problem;
import org.springframework.stereotype.Service;

@Service
public class DatabaseIdentityAuthorization implements IdentityAuthorization {
  private final Db db;

  public DatabaseIdentityAuthorization(Db db) {
    this.db = db;
  }

  @Override
  public void requireUser(String actorId) {
    if (db.count("SELECT COUNT(*) FROM sys_user WHERE id=? AND enabled=TRUE", actorId) != 1)
      throw new Problem(403, "ACCOUNT_DISABLED", "An enabled account is required");
  }

  @Override
  public void requireAdministrator(String actorId) {
    if (db.count(
            "SELECT COUNT(*) FROM sys_user WHERE id=? AND administrator=TRUE AND enabled=TRUE",
            actorId)
        != 1) throw new Problem(403, "ADMIN_REQUIRED", "需要管理员权限");
  }
}
