package com.bonbon.backend.authentication.dto;

import java.util.Set;

import com.bonbon.backend.authentication.Role;
import com.fasterxml.jackson.annotation.JsonInclude;

/**
 * Either tokens for the only role the identity holds, or {@code needsRoleSelection} with a single-use
 * {@code roleToken} to pass to /api/auth/select-role. The client may remember the last choice per device.
 */
@JsonInclude(JsonInclude.Include.NON_NULL)
public record LoginResponse(boolean needsRoleSelection, Set<Role> availableRoles, String roleToken,
        TokenResponse tokens, UserSummary user) {

    public static LoginResponse selectRole(Set<Role> roles, String roleToken) {
        return new LoginResponse(true, roles, roleToken, null, null);
    }

    public static LoginResponse signedIn(TokenResponse tokens, UserSummary user) {
        return new LoginResponse(false, user.availableRoles(), null, tokens, user);
    }
}
