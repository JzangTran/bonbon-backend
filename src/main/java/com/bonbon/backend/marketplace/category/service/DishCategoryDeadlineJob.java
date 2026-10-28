package com.bonbon.backend.marketplace.category.service;

import java.time.Clock;

import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/**
 * Every few minutes: a dish past its fix deadline gets the suggested category, and a correction past its window expires. The deadline is
 * short (a day), so the job must not run once a night. Off in tests, which call the service with a time they choose.
 */
@Component
@ConditionalOnProperty(name = "bonbon.category.moderation.enabled", havingValue = "true", matchIfMissing = true)
class DishCategoryDeadlineJob {

    private final DishCategoryModerationService moderation;
    private final Clock clock;

    DishCategoryDeadlineJob(DishCategoryModerationService moderation, Clock clock) {
        this.moderation = moderation;
        this.clock = clock;
    }

    @Scheduled(fixedDelayString = "${bonbon.category.moderation.interval:PT5M}", initialDelayString = "PT90S")
    void run() {
        moderation.runDue(clock.instant());
    }
}
