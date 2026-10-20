package com.cloudnest.control;

import java.util.List;
import java.util.Map;

/** Narrow control-plane read boundary. Every call must authorize its actor. */
public interface StorageDiagnostics {
  Map<String, Object> overview(String actorId);

  record Slice<T>(List<T> items, boolean hasMore) {}

  record NodeHealth(
      String backend,
      String name,
      boolean enabled,
      String management,
      String state,
      String failureDomain,
      long checkedAt,
      boolean stale) {}

  record NodeCapacity(
      String backend,
      long usedBytes,
      long reservedBytes,
      long budgetBytes,
      Long remainingBudgetBytes) {}

  record Capacity(long logicalBytes, Slice<NodeCapacity> nodes, String capacitySource) {}

  record NodeEvent(String state, long createdAt) {}

  record StorageJob(
      String id,
      String kind,
      String status,
      String targetBackend,
      long attempts,
      long createdAt,
      long updatedAt) {}

  record Policy(String mode, long minReliability, String defaultBackend, List<String> rules) {}

  Slice<NodeHealth> nodeStatus(String actorId, int limit);

  Capacity capacity(String actorId, int limit);

  Slice<NodeEvent> events(String actorId, String backend, int limit);

  Slice<StorageJob> activeJobs(String actorId, int limit);

  Policy policy(String actorId);
}
