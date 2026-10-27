package com.bonbon.backend.support;

import java.util.UUID;

/**
 * An administrator answered a contact request from the public form. The answer goes to the email the person typed, so the event
 * carries everything the email needs. Published inside the answer's transaction.
 */
public record ContactRequestAnswered(UUID requestId, String email, String fullName, String question, String answer) {
}
