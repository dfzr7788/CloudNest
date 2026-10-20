package com.cloudnest.control;

/** Authorized metadata only. Never grants a storage credential or object URL. */
public interface FileMetadata {
  record File(String id, String parentId, String name, long revision, String blobId) {}

  /** Requires an enabled owner and a live file with live ancestors; shares are a separate scope. */
  File ownedFile(String actorId, String fileId);
}
