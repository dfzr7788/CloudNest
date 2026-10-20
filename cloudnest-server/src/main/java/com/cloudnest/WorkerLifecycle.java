package com.cloudnest;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

/** Single-process recovery authority; this is not a distributed worker lease. */
@Component
public class WorkerLifecycle {
  private final boolean recover;

  public WorkerLifecycle(
      @Value("${cloudnest.worker.enabled:true}") boolean enabled,
      @Value("${cloudnest.maintenance:false}") boolean maintenance) {
    recover = enabled && !maintenance;
  }

  public boolean mayRecover() {
    return recover;
  }
}
