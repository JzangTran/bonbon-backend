package com.bonbon.backend.authentication.dto;

import java.util.Set;
import java.util.UUID;

import com.bonbon.backend.authentication.Role;
import com.bonbon.backend.authentication.entity.User;

/** Who is signed in, which role this session uses, and which others they could switch to. */
public record UserSummary(UUID id, String email, String name, Role activeRole, Set<Role> availableRoles) {

    public static UserSummary of(User user, Role activeRole) {
        return new UserSummary(user.getId(), user.getEmail(), user.getName(), activeRole, user.getRoles());
    }
}
