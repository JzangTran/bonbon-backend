package com.bonbon.backend.authentication;

/** The role an identity holds; one role is active per session. ADMIN never combines with the others. */
public enum Role {
    CUSTOMER,
    SELLER,
    ADMIN
}
