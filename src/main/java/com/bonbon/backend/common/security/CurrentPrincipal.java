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
 * @param permissions {@code permissions}: {@code <resource>:<action>} permissions granted to that role
 * @param teamId      {@code team_id}: for a seller-team session (main account or member), that principal; {@code id} is then the
 *                    selected shop's owner, so every shop-scoped lookup finds that shop, or equals {@code teamId} when no shop is selected
 * @param shopId      {@code shop_id}: the shop a seller-team session works on, or null
 */
public record CurrentPrincipal(UUID id, ActorType actorType, String activeRole, Set<String> permissions, UUID teamId, UUID shopId) {

    public static final String CLAIM_ACTOR = "actor";
    public static final String CLAIM_ROLE = "role";
    public static final String CLAIM_PERMISSIONS = "permissions";
    public static final String CLAIM_AUTH_TIME = "auth_time";
    public static final String CLAIM_TEAM = "team_id";
    public static final String CLAIM_SHOP = "shop_id";

    public static CurrentPrincipal from(Jwt jwt) {
        List<String> perms = jwt.getClaimAsStringList(CLAIM_PERMISSIONS);
        return new CurrentPrincipal(
                UUID.fromString(jwt.getSubject()),
                ActorType.valueOf(jwt.getClaimAsString(CLAIM_ACTOR)),
                jwt.getClaimAsString(CLAIM_ROLE),
                perms == null ? Set.of() : Set.copyOf(perms),
                uuid(jwt.getClaimAsString(CLAIM_TEAM)),
                uuid(jwt.getClaimAsString(CLAIM_SHOP)));
    }

    /** A main account or member session, not a person's own account: personal endpoints are closed to it (TeamScopeFilter). */
    public boolean isTeam() {
        return teamId != null;
    }

    private static UUID uuid(String value) {
        return value == null ? null : UUID.fromString(value);
    }

    public boolean can(String permission) {
        return permissions.contains(permission);
    }
}
