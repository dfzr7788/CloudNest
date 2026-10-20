package com.cloudnest.control;

import com.cloudnest.common.Db;
import com.cloudnest.common.Problem;
import com.cloudnest.node.NodeService;
import com.cloudnest.storage.MigrationService;
import org.springframework.stereotype.Service;

@Service
public class DatabaseStorageTaskCoordinator implements StorageTaskCoordinator {
  private final Db db;
  private final IdentityAuthorization identity;
  private final FileMetadata metadata;
  private final NodeService nodes;
  private final MigrationService jobs;

  public DatabaseStorageTaskCoordinator(
      Db db,
      IdentityAuthorization identity,
      FileMetadata metadata,
      NodeService nodes,
      MigrationService jobs) {
    this.db = db;
    this.identity = identity;
    this.metadata = metadata;
    this.nodes = nodes;
    this.jobs = jobs;
  }

  @Override
  public String submit(String actorId, Command command) {
    identity.requireAdministrator(actorId);
    if (command == null
        || command.fileId() == null
        || command.expectedRevision() < 0
        || command.kind() == null
        || command.targetBackend() == null
        || command.targetBackend().isBlank()
        || command.targetBackend().length() > 80)
      throw Problem.invalid("A file revision, target and storage task kind are required");
    return db.atomic(
        actorId,
        () ->
            nodes.locked(
                () -> {
                  identity.requireAdministrator(actorId);
                  var file = metadata.ownedFile(actorId, command.fileId());
                  if (file.revision() != command.expectedRevision())
                    throw new Problem(
                        409,
                        "FILE_REVISION_CHANGED",
                        "File revision changed; refresh before submitting");
                  var blob =
                      db.one(
                          "SELECT * FROM file_blob WHERE id=? AND user_id=? AND status='READY'",
                          file.blobId(),
                          actorId);
                  return jobs.enqueue(
                      actorId, blob, command.targetBackend(), command.kind().name(), null);
                }));
  }
}
