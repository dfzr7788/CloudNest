package com.cloudnest;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.scheduling.annotation.ScheduledAnnotationBeanPostProcessor;

class SchedulingConfigurationTest {
  private final ApplicationContextRunner runner =
      new ApplicationContextRunner().withUserConfiguration(SchedulingConfiguration.class);

  @Test
  void defaultSingleProcessRunsWorkers() {
    runner.run(ctx -> assertThat(ctx).hasSingleBean(ScheduledAnnotationBeanPostProcessor.class));
  }

  @Test
  void apiOnlyRoleAndMaintenanceNeverScheduleWorkers() {
    for (String[] properties :
        new String[][] {
          {"cloudnest.worker.enabled=false"},
          {"cloudnest.maintenance=true"},
          {"cloudnest.maintenance=true", "cloudnest.worker.enabled=true"}
        }) {
      runner
          .withPropertyValues(properties)
          .run(ctx -> assertThat(ctx).doesNotHaveBean(ScheduledAnnotationBeanPostProcessor.class));
    }
  }
}
