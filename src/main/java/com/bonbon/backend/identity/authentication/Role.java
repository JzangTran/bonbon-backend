package com.bonbon.backend.identity.authentication;

/**
 * The role an identity holds; one role is active per session. ADMIN never combines with the others. DEVELOPER is granted only when an
 * administrator approves an access request (developer portal) and only reads the API reference; the apps never sign in with it.
 */
public enum Role {
    CUSTOMER,
    SELLER,
    ADMIN,
    DEVELOPER
}
