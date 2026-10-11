package com.bonbon.backend.account.service;

import java.time.Clock;

import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/** Hourly: an account whose grace period has ended is anonymized. Off in tests, which call the service with a time they choose. */
@Component
@ConditionalOnProperty(name = "bonbon.account.deletions.enabled", havingValue = "true", matchIfMissing = true)
class AccountDeletionJob {

    private final AccountDeletionService deletions;
    private final Clock clock;

    AccountDeletionJob(AccountDeletionService deletions, Clock clock) {
        this.deletions = deletions;
        this.clock = clock;
    }

    @Scheduled(fixedDelayString = "${bonbon.account.deletions.interval:PT1H}", initialDelayString = "PT8M")
    void run() {
        deletions.executeDue(clock.instant());
    }
}
