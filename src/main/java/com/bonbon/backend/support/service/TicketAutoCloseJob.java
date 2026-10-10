package com.bonbon.backend.support.service;

import java.time.Clock;

import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/** Hourly: an answered ticket nobody wrote to for the configured days is closed. Off in tests, which call the service with a time they choose. */
@Component
@ConditionalOnProperty(name = "bonbon.support.tickets.enabled", havingValue = "true", matchIfMissing = true)
class TicketAutoCloseJob {

    private final SupportTicketService tickets;
    private final Clock clock;

    TicketAutoCloseJob(SupportTicketService tickets, Clock clock) {
        this.tickets = tickets;
        this.clock = clock;
    }

    @Scheduled(fixedDelayString = "${bonbon.support.tickets.interval:PT1H}", initialDelayString = "PT3M")
    void run() {
        tickets.closeStale(clock.instant());
    }
}
