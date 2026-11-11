package com.bonbon.backend.identity.account;

import java.time.Instant;
import java.util.UUID;

/** The person asked to delete the account; nothing is erased until {@code scheduledAt}. Their sessions and push devices end now. */
public record AccountDeletionRequested(UUID userId, String email, Instant scheduledAt) {
}
