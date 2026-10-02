package com.bonbon.backend.authentication;

import java.time.Instant;
import java.util.Set;
import java.util.UUID;

import com.bonbon.backend.authentication.entity.User;
import com.bonbon.backend.authentication.repository.UserRepository;
import com.bonbon.backend.authentication.service.TokenService;
import com.bonbon.backend.authentication.service.TokenService.TokenPair;
import com.bonbon.backend.common.exception.BusinessException;
import org.springframework.http.HttpStatus;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Public API over the caller's own user row, used by the account module (update-personal-info.md,
 * change-password.md). Every method acts on the given id only; there is no way to address another user.
 */
@Service
public class UserProfileService {

    public record Profile(UUID id, String email, String name, String phone, String avatarUrl, boolean hasPassword,
            Set<Role> roles) {
    }

    private final UserRepository users;
    private final PasswordEncoder passwords;
    private final TokenService tokens;

    UserProfileService(UserRepository users, PasswordEncoder passwords, TokenService tokens) {
        this.users = users;
        this.passwords = passwords;
        this.tokens = tokens;
    }

    @Transactional(readOnly = true)
    public Profile get(UUID userId) {
        return toProfile(find(userId));
    }

    /** Null fields are left unchanged; email is never editable here. */
    @Transactional
    public Profile update(UUID userId, String name, String phone) {
        User user = find(userId);
        if (name != null) {
            user.setName(name.trim());
        }
        if (phone != null) {
            user.setPhone(phone.isBlank() ? null : phone.trim());
        }
        return toProfile(user);
    }

    /**
     * Other sessions are ended (tokens_valid_after = now); the caller keeps working with the fresh pair
     * returned here, issued after the stamp.
     */
    @Transactional
    public IssuedSession changePassword(UUID userId, Role activeRole, Instant authTime, String currentPassword,
            String newPassword) {
        User user = find(userId);
        if (!user.hasPassword()) {
            throw new BusinessException(HttpStatus.CONFLICT, "NO_PASSWORD",
                    "Tài khoản đăng nhập bằng Google/Facebook không có mật khẩu để đổi.");
        }
        if (!passwords.matches(currentPassword, user.getPasswordHash())) {
            throw new BusinessException(HttpStatus.UNAUTHORIZED, "INVALID_CREDENTIALS", "Mật khẩu hiện tại không đúng.");
        }
        user.setPasswordHash(passwords.encode(newPassword));
        tokens.invalidateAllSessions(user);
        TokenPair pair = tokens.issue(user, activeRole, authTime);
        return new IssuedSession(pair.accessToken(), pair.refreshToken(), pair.expiresInSeconds(), user.getId(),
                user.getEmail(), user.getName(), activeRole);
    }

    private User find(UUID userId) {
        return users.findById(userId).orElseThrow(() -> new BusinessException(HttpStatus.NOT_FOUND, "USER_NOT_FOUND",
                "Không tìm thấy tài khoản."));
    }

    private static Profile toProfile(User u) {
        return new Profile(u.getId(), u.getEmail(), u.getName(), u.getPhone(), u.getAvatarUrl(), u.hasPassword(), u.getRoles());
    }
}
