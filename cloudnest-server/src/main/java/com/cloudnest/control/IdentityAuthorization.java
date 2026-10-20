package com.cloudnest.control;

/** Revalidates database-backed account authority on every invocation. */
public interface IdentityAuthorization {
  void requireUser(String actorId);

  void requireAdministrator(String actorId);
}
