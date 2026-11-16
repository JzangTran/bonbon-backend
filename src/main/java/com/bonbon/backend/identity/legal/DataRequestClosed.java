package com.bonbon.backend.identity.legal;

import java.util.UUID;

/** A data-subject request was completed or rejected; notification tells the person, with the reason when rejected. */
public record DataRequestClosed(UUID requestId, UUID userId, String audience, String type, boolean completed, String note) {
}
