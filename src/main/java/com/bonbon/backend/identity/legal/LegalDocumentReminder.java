package com.bonbon.backend.identity.legal;

import java.time.Instant;
import java.util.UUID;

/** A material change takes effect in a few days: a last reminder to the people it affects. */
public record LegalDocumentReminder(UUID documentId, DocumentType type, int version, String title, Instant effectiveAt) {
}
