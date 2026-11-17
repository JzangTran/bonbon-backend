package com.bonbon.backend.identity.authentication.dto;

import java.util.Set;
import java.util.UUID;

import com.bonbon.backend.identity.authentication.Role;
import com.bonbon.backend.identity.authentication.entity.User;

/** Who is signed in, which role this session uses, and which others they could switch to. */
public record UserSummary(UUID id, String email, String name, Role activeRole, Set<Role> availableRoles,
        @io.swagger.v3.oas.annotations.media.Schema(description = "Chỉ có với phiên của tài khoản chính hoặc thành viên: ai đăng nhập, cửa hàng đang làm việc và các cửa hàng được chọn.")
        @com.fasterxml.jackson.annotation.JsonInclude(com.fasterxml.jackson.annotation.JsonInclude.Include.NON_NULL) TeamSession team) {

    public static UserSummary of(User user, Role activeRole) {
        // the apps switch between customer and seller only; the developer role is used on the developer portal alone
        java.util.Set<Role> roles = java.util.EnumSet.noneOf(Role.class);
        roles.addAll(user.getRoles());
        if (activeRole != Role.DEVELOPER) {
            roles.remove(Role.DEVELOPER);
        }
        return new UserSummary(user.getId(), user.getEmail(), user.getName(), activeRole, roles, null);
    }

    /** A main account or member. {@code kind} is MAIN_ACCOUNT or MEMBER; {@code shopId} null means the team console with no shop open. */
    public record TeamSession(String kind, UUID principalId, String login, String displayName, UUID shopId, String shopName,
            java.util.List<ShopChoice> shops, java.util.List<String> permissions) {
    }

    public record ShopChoice(UUID vendorId, String name, String status) {
    }
}
