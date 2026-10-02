package com.bonbon.backend.common.security;

import java.util.List;
import java.util.Set;
import java.util.UUID;

import com.bonbon.backend.common.persistence.ActorType;
import org.springframework.security.oauth2.jwt.Jwt;

/**
 * The caller of a request, taken only from the verified access token (never from the request body).
 *
 * @param id          {@code sub}: id of the principal in its own table
 * @param actorType   {@code actor}: kind of principal, also written to {@code acted_by_type}
 * @param activeRole  {@code role}: the one role active in this session (CUSTOMER, SELLER, ADMIN)
 * @param permissions {@code perms}: {@code <resource>:<action>} permissions granted to that role
 */
public record CurrentPrincipal(UUID id, ActorType actorType, String activeRole, Set<String> permissions) {

    public static final String CLAIM_ACTOR = "actor";
    public static final String CLAIM_ROLE = "role";
    public static final String CLAIM_PERMISSIONS = "perms";

    public static CurrentPrincipal from(Jwt jwt) {
        List<String> perms = jwt.getClaimAsStringList(CLAIM_PERMISSIONS);
        return new CurrentPrincipal(
                UUID.fromString(jwt.getSubject()),
                ActorType.valueOf(jwt.getClaimAsString(CLAIM_ACTOR)),
                jwt.getClaimAsString(CLAIM_ROLE),
                perms == null ? Set.of() : Set.copyOf(perms));
    }

    public boolean can(String permission) {
        return permissions.contains(permission);
    }
}
