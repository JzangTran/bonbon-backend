package com.bonbon.backend.common.config;

import org.springframework.context.annotation.Configuration;
import org.springframework.scheduling.annotation.EnableScheduling;

/** Turns on {@code @Scheduled} jobs (order timers, later the weekly evaluations). */
@Configuration
@EnableScheduling
class SchedulingConfig {
}
