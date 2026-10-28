package com.bonbon.backend.identity.legal.service;

import java.time.Clock;

import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/** Hourly: a material change that takes effect within 3 days gets its reminder. Off in tests, which call the service with a time they choose. */
@Component
@ConditionalOnProperty(name = "bonbon.legal.reminders.enabled", havingValue = "true", matchIfMissing = true)
class LegalReminderJob {

    private final LegalAdminService legal;
    private final Clock clock;

    LegalReminderJob(LegalAdminService legal, Clock clock) {
        this.legal = legal;
        this.clock = clock;
    }

    @Scheduled(fixedDelayString = "${bonbon.legal.reminders.interval:PT1H}", initialDelayString = "PT4M")
    void run() {
        legal.remindDue(clock.instant());
    }
}
