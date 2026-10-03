package com.bonbon.backend.legal;

import java.time.Instant;
import java.util.UUID;

public record LegalDocumentView(UUID id, DocumentType type, int version, String title, String summary,
        String content, Instant effectiveAt) {
}
