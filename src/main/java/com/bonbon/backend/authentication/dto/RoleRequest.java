package com.bonbon.backend.authentication.dto;

import com.bonbon.backend.authentication.Role;
import jakarta.validation.constraints.NotNull;

public record RoleRequest(@NotNull Role role, String roleToken) {
}
