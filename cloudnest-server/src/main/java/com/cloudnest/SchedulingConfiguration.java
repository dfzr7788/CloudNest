package com.cloudnest;

import org.springframework.boot.autoconfigure.condition.AllNestedConditions;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Conditional;
import org.springframework.context.annotation.Configuration;
import org.springframework.scheduling.annotation.EnableScheduling;

@Configuration
@EnableScheduling
@Conditional(SchedulingConfiguration.WorkerCondition.class)
public class SchedulingConfiguration {
  static class WorkerCondition extends AllNestedConditions {
    WorkerCondition() {
      super(ConfigurationPhase.REGISTER_BEAN);
    }

    @ConditionalOnProperty(
        name = "cloudnest.maintenance",
        havingValue = "false",
        matchIfMissing = true)
    static class NotMaintenance {}

    @ConditionalOnProperty(
        name = "cloudnest.worker.enabled",
        havingValue = "true",
        matchIfMissing = true)
    static class WorkerEnabled {}
  }
}
