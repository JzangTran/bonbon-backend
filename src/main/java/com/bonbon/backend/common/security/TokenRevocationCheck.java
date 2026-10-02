package com.bonbon.backend.common.security;

import org.springframework.security.oauth2.jwt.Jwt;

/**
 * Extension point for refusing a token that is signed and unexpired but no longer valid (logout blacklist,
 * tokens issued before a password change). Implemented by the module that owns the identity data,
 * so common/ stays free of business code.
 */
public interface TokenRevocationCheck {

    boolean isRevoked(Jwt jwt);
}
