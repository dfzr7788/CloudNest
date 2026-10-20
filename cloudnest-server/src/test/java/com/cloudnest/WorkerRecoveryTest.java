package com.cloudnest;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

import com.cloudnest.ai.AiKnowledgeService;
import com.cloudnest.ai.AiTaskService;
import com.cloudnest.common.Db;
import com.cloudnest.file.DownloadJobs;
import com.cloudnest.node.NodeService;
import com.cloudnest.storage.MigrationService;
import com.cloudnest.storage.StorageController;
import com.cloudnest.upload.UploadService;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.test.util.ReflectionTestUtils;

class WorkerRecoveryTest {
  @Test
  void recoveryAuthorityMatchesTheConfiguredSingleProcessRole() {
    var runner = new ApplicationContextRunner().withUserConfiguration(WorkerLifecycle.class);
    runner.run(ctx -> assertThat(ctx.getBean(WorkerLifecycle.class).mayRecover()).isTrue());
    for (String[] properties :
        new String[][] {
          {"cloudnest.worker.enabled=false"},
          {"cloudnest.maintenance=true"},
          {"cloudnest.maintenance=true", "cloudnest.worker.enabled=true"}
        }) {
      runner
          .withPropertyValues(properties)
          .run(ctx -> assertThat(ctx.getBean(WorkerLifecycle.class).mayRecover()).isFalse());
    }
  }

  private Object service(Class<?> type, Db db, WorkerLifecycle lifecycle) {
    Object service = mock(type, CALLS_REAL_METHODS);
    ReflectionTestUtils.setField(service, "db", db);
    ReflectionTestUtils.setField(service, "lifecycle", lifecycle);
    if (service instanceof AiTaskService) {
      ReflectionTestUtils.setField(service, "knowledge", mock(AiKnowledgeService.class));
    }
    if (service instanceof DownloadJobs) {
      ReflectionTestUtils.setField(service, "maxBytes", 1024L);
      ReflectionTestUtils.setField(service, "maxFiles", 1);
      ReflectionTestUtils.setField(service, "maxEntries", 2);
      ReflectionTestUtils.setField(service, "hours", 1);
      ReflectionTestUtils.setField(service, "workers", 1);
    }
    return service;
  }

  @Test
  void disabledAndMaintenanceStartupDoNotResetJobsOrReleaseReservations() {
    for (var role : List.of(new WorkerLifecycle(false, false), new WorkerLifecycle(true, true))) {
      for (Class<?> type :
          List.of(
              UploadService.class,
              DownloadJobs.class,
              MigrationService.class,
              StorageController.class,
              AiTaskService.class,
              NodeService.class)) {
        Db db = mock(Db.class);
        Object service = service(type, db, role);
        try {
          ReflectionTestUtils.invokeMethod(service, "recover");
          verifyNoInteractions(db);
          if (service instanceof AiTaskService) {
            verifyNoInteractions(ReflectionTestUtils.getField(service, "knowledge"));
          }
        } finally {
          if (service instanceof DownloadJobs) ReflectionTestUtils.invokeMethod(service, "stop");
        }
      }
    }
  }

  @Test
  void defaultWorkerStillPerformsInterruptedJobRecovery() {
    Db db = mock(Db.class);
    var role = new WorkerLifecycle(true, false);
    Object upload = service(UploadService.class, db, role);
    ReflectionTestUtils.invokeMethod(upload, "recover");
    verify(db).update("UPDATE upload_session SET status='UPLOADING' WHERE status='ASSEMBLING'");
    Object migration = service(MigrationService.class, db, role);
    ReflectionTestUtils.invokeMethod(migration, "recover");
    verify(db).update(contains("UPDATE storage_job_item SET status='INTERRUPTED'"));
    Object storage = service(StorageController.class, db, role);
    ReflectionTestUtils.invokeMethod(storage, "recover");
    verify(db).update(contains("UPDATE maintenance_job SET status='INTERRUPTED'"));
    Object node = service(NodeService.class, db, role);
    ReflectionTestUtils.invokeMethod(node, "recover");
    verify(db).update("DELETE FROM storage_reservation WHERE id LIKE 'ingest:%'");
    Object ai = service(AiTaskService.class, db, role);
    ReflectionTestUtils.invokeMethod(ai, "recover");
    verify(db).update(contains("UPDATE ai_call SET status='UNKNOWN'"));
    verify((AiKnowledgeService) ReflectionTestUtils.getField(ai, "knowledge")).recover();
    Object zip = service(DownloadJobs.class, db, role);
    try {
      ReflectionTestUtils.invokeMethod(zip, "recover");
      verify(db).update(contains("UPDATE download_job SET status='FAILED'"));
    } finally {
      ReflectionTestUtils.invokeMethod(zip, "stop");
    }
  }
}
