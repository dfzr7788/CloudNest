package com.cloudnest.control;

/**
 * Narrow administrative submission boundary. Returned IDs mean queued, never verified completion.
 */
public interface StorageTaskCoordinator {
  enum Kind {
    MIGRATE,
    REPLICATE
  }

  record Command(String fileId, long expectedRevision, String targetBackend, Kind kind) {}

  /** Rechecks authority, ownership and revision under the user/storage locks before enqueueing. */
  String submit(String actorId, Command command);
}
