package com.insightgiver.config;

import org.springframework.boot.autoconfigure.condition.ConditionalOnBooleanProperty;
import org.springframework.context.annotation.Configuration;
import org.springframework.scheduling.annotation.EnableScheduling;

/** Runs the hourly insight job and the pending-entry sweeper. Tests switch it off and call them directly. */
@Configuration(proxyBeanMethods = false)
@EnableScheduling
@ConditionalOnBooleanProperty(name = "insight-giver.scheduling.enabled", matchIfMissing = true)
class SchedulingConfig {
}
