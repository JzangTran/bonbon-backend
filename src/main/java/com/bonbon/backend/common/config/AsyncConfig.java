package com.bonbon.backend.common.config;

import org.springframework.context.annotation.Configuration;
import org.springframework.scheduling.annotation.EnableAsync;

/** Enables after-commit side effects (emails, notifications) to run off the request thread. */
@Configuration
@EnableAsync
class AsyncConfig {
}
