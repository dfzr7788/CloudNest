package com.cloudnest.control;

import static com.cloudnest.common.Db.*;

import com.cloudnest.file.DriveService;
import org.springframework.stereotype.Service;

@Service
public class DatabaseFileMetadata implements FileMetadata {
  private final IdentityAuthorization identity;
  private final DriveService drive;

  public DatabaseFileMetadata(IdentityAuthorization identity, DriveService drive) {
    this.identity = identity;
    this.drive = drive;
  }

  @Override
  public File ownedFile(String actorId, String fileId) {
    identity.requireUser(actorId);
    var row = drive.file(actorId, fileId);
    return new File(
        s(row, "id"), s(row, "parent_id"), s(row, "name"), n(row, "revision"), s(row, "blob_id"));
  }
}
