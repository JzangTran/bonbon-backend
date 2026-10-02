package com.bonbon.backend.authentication.service;

import java.time.Instant;
import java.util.EnumSet;
import java.util.Optional;
import java.util.Set;

import com.bonbon.backend.authentication.Role;
import com.bonbon.backend.authentication.dto.LoginResponse;
import com.bonbon.backend.authentication.dto.TokenResponse;
import com.bonbon.backend.authentication.dto.UserSummary;
import com.bonbon.backend.authentication.entity.User;
import com.bonbon.backend.authentication.repository.UserRepository;
import com.bonbon.backend.common.exception.BusinessException;
import com.bonbon.backend.common.security.CaptchaVerifier;
import com.bonbon.backend.common.security.CurrentPrincipal;
import org.springframework.http.HttpStatus;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Customer/Seller sign-in (flows/authentication/login-email-password.md), role selection for identities
 * holding both roles, and switching the active role (switch-role.md). Admins sign in elsewhere.
 */
@Service
public class LoginService {

    private static final Set<Role> APP_ROLES = EnumSet.of(Role.CUSTOMER, Role.SELLER);

    private final UserRepository users;
    private final PasswordEncoder passwords;
    private final LoginAttemptGuard guard;
    private final CaptchaVerifier captcha;
    private final TokenService tokens;
    private final RoleSelectionStore roleSelections;

    LoginService(UserRepository users, PasswordEncoder passwords, LoginAttemptGuard guard, CaptchaVerifier captcha,
            TokenService tokens, RoleSelectionStore roleSelections) {
        this.users = users;
        this.passwords = passwords;
        this.guard = guard;
        this.captcha = captcha;
        this.tokens = tokens;
        this.roleSelections = roleSelections;
    }

    @Transactional
    public LoginResponse login(String email, String password, String captchaToken, String ip) {
        User user = authenticate(email, password, captchaToken, ip, APP_ROLES);
        Instant authTime = Instant.now();
        Set<Role> roles = EnumSet.copyOf(user.getRoles());
        roles.retainAll(APP_ROLES);
        if (roles.size() > 1) {
            return LoginResponse.selectRole(roles, roleSelections.create(user.getId(), authTime));
        }
        Role role = roles.iterator().next();
        return LoginResponse.signedIn(TokenResponse.of(tokens.issue(user, role, authTime)), UserSummary.of(user, role));
    }

    @Transactional
    public LoginResponse selectRole(String roleToken, Role role) {
        RoleSelectionStore.PendingSelection pending = Optional.ofNullable(roleToken).flatMap(roleSelections::consume)
                .orElseThrow(() -> new BusinessException(HttpStatus.BAD_REQUEST, "INVALID_ROLE_TOKEN",
                        "Phiên chọn vai trò đã hết hạn, vui lòng đăng nhập lại."));
        User user = users.findById(pending.userId()).orElseThrow();
        requireRole(user, role);
        return LoginResponse.signedIn(TokenResponse.of(tokens.issue(user, role, pending.authTime())), UserSummary.of(user, role));
    }

    /** Issues tokens for the other role; the current session is left alone (one active role is a UI rule). */
    @Transactional
    public LoginResponse switchRole(Jwt current, Role role) {
        CurrentPrincipal principal = CurrentPrincipal.from(current);
        User user = users.findById(principal.id()).orElseThrow();
        requireRole(user, role);
        Object claim = current.getClaims().get(CurrentPrincipal.CLAIM_AUTH_TIME);
        Instant authTime = claim instanceof Number n ? Instant.ofEpochSecond(n.longValue())
                : claim instanceof Instant i ? i : Instant.now();
        return LoginResponse.signedIn(TokenResponse.of(tokens.issue(user, role, authTime)), UserSummary.of(user, role));
    }

    /**
     * Shared password check behind the brute-force guard. Unknown email, wrong password, a role outside
     * {@code allowedRoles} and an account without a password all give the same answer.
     */
    public User authenticate(String email, String password, String captchaToken, String ip, Set<Role> allowedRoles) {
        String normalized = email.trim();
        if (guard.requiresCaptcha(normalized, ip) && !captcha.isValid(captchaToken, ip)) {
            throw new BusinessException(HttpStatus.BAD_REQUEST, "CAPTCHA_REQUIRED",
                    "Vui lòng xác nhận bạn không phải robot.");
        }
        Optional<User> found = users.findByEmail(normalized)
                .filter(u -> u.getRoles().stream().anyMatch(allowedRoles::contains))
                .filter(User::hasPassword);
        if (found.isEmpty() || !passwords.matches(password, found.get().getPasswordHash())) {
            guard.recordFailure(normalized, ip);
            throw new BusinessException(HttpStatus.UNAUTHORIZED, "INVALID_CREDENTIALS", "Email hoặc mật khẩu không đúng.");
        }
        User user = found.get();
        guard.recordSuccess(normalized, ip);
        if (!user.isEmailVerified()) {
            throw new BusinessException(HttpStatus.FORBIDDEN, "EMAIL_NOT_VERIFIED",
                    "Email chưa được xác thực. Hãy mở liên kết trong email hoặc yêu cầu gửi lại.");
        }
        return user;
    }

    private static void requireRole(User user, Role role) {
        if (role == Role.ADMIN || !user.hasRole(role)) {
            throw new BusinessException(HttpStatus.FORBIDDEN, "ROLE_NOT_HELD", "Tài khoản không có vai trò này.");
        }
    }
}
