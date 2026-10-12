package com.bonbon.backend.account;

import java.util.UUID;

/** The grace period ended and the identity was anonymized; modules delete the personal data they keep for the person. {@code formerEmail} is for the last message only. */
public record AccountErased(UUID userId, String formerEmail) {
}
