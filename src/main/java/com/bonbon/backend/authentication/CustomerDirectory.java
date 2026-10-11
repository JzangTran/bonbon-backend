package com.bonbon.backend.authentication;

import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;

/** Finding accounts by an exact identifier, for the administrators' lookup. Never exposes secrets or tokens. */
public interface CustomerDirectory {

    /** Email compared case-insensitively. */
    Optional<Account> byEmail(String email);

    /** Phones are not unique (they are not verified), so this can return several; at most {@code limit}. */
    List<Account> byPhone(String phone, int limit);

    Optional<Account> get(UUID userId);

    record Account(UUID id, String email, String phone, String name, Set<String> roles, boolean emailVerified, Instant createdAt) {
    }
}
