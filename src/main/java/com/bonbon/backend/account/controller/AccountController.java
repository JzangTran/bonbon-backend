package com.bonbon.backend.account.controller;

import java.time.Instant;

import com.bonbon.backend.authentication.IssuedSession;
import com.bonbon.backend.authentication.Role;
import com.bonbon.backend.authentication.UserProfileService;
import com.bonbon.backend.authentication.UserProfileService.Profile;
import com.bonbon.backend.common.security.CurrentPrincipal;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/** Self-service only: authenticated, and always the caller's own row (no id in the URL to spoof). */
@RestController
@RequestMapping("/api/account")
class AccountController {

    /** Vietnamese mobile number: 0 or +84 followed by 9 digits starting 3, 5, 7, 8 or 9. */
    static final String VN_MOBILE = "^(0|\\+84)(3|5|7|8|9)\\d{8}$";

    record UpdateProfileRequest(@Size(min = 1, max = 100) String name,
            @Pattern(regexp = VN_MOBILE + "|^$", message = "Số điện thoại di động Việt Nam không hợp lệ") String phone) {
    }

    record ChangePasswordRequest(@NotBlank String currentPassword, @NotBlank @Size(min = 8, max = 100) String newPassword) {
    }

    private final UserProfileService profiles;

    AccountController(UserProfileService profiles) {
        this.profiles = profiles;
    }

    @GetMapping("/me")
    Profile me(CurrentPrincipal principal) {
        return profiles.get(principal.id());
    }

    @PatchMapping("/me")
    Profile update(CurrentPrincipal principal, @Valid @RequestBody UpdateProfileRequest request) {
        return profiles.update(principal.id(), request.name(), request.phone());
    }

    /** Returns a fresh token pair: every other device is signed out, this one continues. */
    @PostMapping("/change-password")
    IssuedSession changePassword(CurrentPrincipal principal, @AuthenticationPrincipal Jwt jwt,
            @Valid @RequestBody ChangePasswordRequest request) {
        Object authTime = jwt.getClaims().get(CurrentPrincipal.CLAIM_AUTH_TIME);
        Instant since = authTime instanceof Number n ? Instant.ofEpochSecond(n.longValue()) : Instant.now();
        return profiles.changePassword(principal.id(), Role.valueOf(principal.activeRole()), since,
                request.currentPassword(), request.newPassword());
    }
}
