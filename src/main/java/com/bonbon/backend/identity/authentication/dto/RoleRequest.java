package com.bonbon.backend.identity.authentication.dto;

import com.bonbon.backend.identity.authentication.Role;
import jakarta.validation.constraints.NotNull;

public record RoleRequest(@NotNull Role role, String roleToken) {
}
