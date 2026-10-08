package com.bonbon.backend.settlement.service;

import java.time.Clock;

import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/**
 * Every day shortly after midnight, Vietnam time: bills last week (on Mondays the week that just closed; on other days
 * it finds nothing new), sends reminders and moves overdue shops along. Off in tests, which call the service with a
 * time they choose.
 */
@Component
@ConditionalOnProperty(name = "bonbon.settlement.debt.enabled", havingValue = "true", matchIfMissing = true)
class CommissionDebtJob {

    private final CommissionDebtService debt;
    private final Clock clock;

    CommissionDebtJob(CommissionDebtService debt, Clock clock) {
        this.debt = debt;
        this.clock = clock;
    }

    @Scheduled(cron = "0 10 0 * * *", zone = "Asia/Ho_Chi_Minh")
    void run() {
        debt.runDaily(clock.instant());
    }
}
