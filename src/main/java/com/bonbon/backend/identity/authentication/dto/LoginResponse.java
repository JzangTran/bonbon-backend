package com.bonbon.backend.identity.authentication.dto;

import java.util.Set;

import com.bonbon.backend.identity.authentication.Role;
import com.fasterxml.jackson.annotation.JsonInclude;

/**
 * Either tokens for the only role the identity holds, or {@code needsRoleSelection} with a single-use
 * {@code roleToken} to pass to /api/auth/select-role. The client may remember the last choice per device.
 */
@JsonInclude(JsonInclude.Include.NON_NULL)
public record LoginResponse(boolean needsRoleSelection, Set<Role> availableRoles, String roleToken,
        TokenResponse tokens, UserSummary user,
        @io.swagger.v3.oas.annotations.media.Schema(description = "true khi tài khoản đang chờ xoá và lần đăng nhập này đã huỷ việc xoá; rỗng trong các trường hợp khác.") Boolean deletionCancelled) {

    public static LoginResponse selectRole(Set<Role> roles, String roleToken) {
        return selectRole(roles, roleToken, false);
    }

    public static LoginResponse selectRole(Set<Role> roles, String roleToken, boolean deletionCancelled) {
        return new LoginResponse(true, roles, roleToken, null, null, deletionCancelled ? Boolean.TRUE : null);
    }

    public static LoginResponse signedIn(TokenResponse tokens, UserSummary user) {
        return signedIn(tokens, user, false);
    }

    public static LoginResponse signedIn(TokenResponse tokens, UserSummary user, boolean deletionCancelled) {
        return new LoginResponse(false, user.availableRoles(), null, tokens, user, deletionCancelled ? Boolean.TRUE : null);
    }
}
