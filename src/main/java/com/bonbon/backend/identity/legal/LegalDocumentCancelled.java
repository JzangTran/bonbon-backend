package com.bonbon.backend.identity.legal;

import java.util.UUID;

/** A published version that had not taken effect yet was withdrawn: the change will not apply. */
public record LegalDocumentCancelled(UUID documentId, DocumentType type, int version, String title, String reason) {
}
