package com.bonbon.backend.authentication.service;

import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Optional;

import com.bonbon.backend.authentication.Role;
import com.bonbon.backend.authentication.dto.RegisterRequest;
import com.bonbon.backend.authentication.dto.RegisterResponse;
import com.bonbon.backend.authentication.dto.TokenResponse;
import com.bonbon.backend.authentication.entity.User;
import com.bonbon.backend.authentication.repository.UserRepository;
import com.bonbon.backend.common.exception.BusinessException;
import com.bonbon.backend.common.ratelimit.RateLimiter;
import com.bonbon.backend.common.security.CaptchaVerifier;
import com.bonbon.backend.common.security.CurrentPrincipal;
import com.bonbon.backend.common.settings.SystemSettingsService;
import com.bonbon.backend.common.web.ClientContext;
import com.bonbon.backend.legal.DocumentType;
import com.bonbon.backend.legal.LegalConsentService;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.http.HttpStatus;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Register as Customer or Seller (flows/authentication/register-customer.md): a brand-new identity, or
 * the other role added to an existing one after proving it is the same person.
 */
@Service
public class RegistrationService {

    private static final String PRINCIPAL_TYPE = "USER";

    private final UserRepository users;
    private final PasswordEncoder passwords;
    private final LegalConsentService legal;
    private final EmailVerificationService verification;
    private final LoginAttemptGuard guard;
    private final CaptchaVerifier captcha;
    private final RateLimiter rateLimiter;
    private final SystemSettingsService settings;
    private final TokenService tokens;

    RegistrationService(UserRepository users, PasswordEncoder passwords, LegalConsentService legal,
            EmailVerificationService verification, LoginAttemptGuard guard, CaptchaVerifier captcha,
            RateLimiter rateLimiter, SystemSettingsService settings, TokenService tokens) {
        this.users = users;
        this.passwords = passwords;
        this.legal = legal;
        this.verification = verification;
        this.guard = guard;
        this.captcha = captcha;
        this.rateLimiter = rateLimiter;
        this.settings = settings;
        this.tokens = tokens;
    }

    @Transactional
    public RegisterResponse register(RegisterRequest req, Optional<CurrentPrincipal> caller, ClientContext client) {
        if (req.role() == Role.ADMIN) {
            throw new BusinessException(HttpStatus.BAD_REQUEST, "ROLE_NOT_SELF_REGISTERABLE",
                    "Không thể tự đăng ký tài khoản quản trị.");
        }
        int ipLimit = settings.getInt("auth.register_per_ip_per_hour", 5);
        if (!rateLimiter.tryAcquire("register_count:ip:" + client.ip(), ipLimit, Duration.ofHours(1))) {
            throw new BusinessException(HttpStatus.TOO_MANY_REQUESTS, "TOO_MANY_REQUESTS",
                    "Bạn thao tác quá nhanh, vui lòng thử lại sau.");
        }
        if (caller.isPresent()) {
            return addRoleToSignedInIdentity(caller.get(), req, client);
        }
        if (!captcha.isValid(req.captchaToken(), client.ip())) {
            throw new BusinessException(HttpStatus.BAD_REQUEST, "CAPTCHA_INVALID", "Vui lòng xác nhận bạn không phải robot.");
        }
        Optional<User> existing = users.findByEmail(req.email().trim());
        return existing.isPresent()
                ? addRoleWithPassword(existing.get(), req, client)
                : createIdentity(req, client);
    }

    private RegisterResponse createIdentity(RegisterRequest req, ClientContext client) {
        if (req.password() == null) {
            throw new BusinessException(HttpStatus.BAD_REQUEST, "PASSWORD_REQUIRED", "Vui lòng nhập mật khẩu.");
        }
        if (req.name() == null || req.name().isBlank()) {
            throw new BusinessException(HttpStatus.BAD_REQUEST, "NAME_REQUIRED", "Vui lòng nhập họ tên.");
        }
        User user = new User(req.email().trim(), passwords.encode(req.password()), req.name().trim());
        user.addRole(req.role());
        try {
            user = users.saveAndFlush(user);
        } catch (DataIntegrityViolationException e) {
            throw emailTaken();
        }
        legal.recordAcceptance(PRINCIPAL_TYPE, user.getId(), List.of(termsFor(req.role()), DocumentType.PRIVACY_POLICY),
                req.acceptedDocumentIds(), req.marketingConsent(), client);
        verification.issue(user);
        return new RegisterResponse(RegisterResponse.Status.VERIFICATION_SENT, null);
    }

    private RegisterResponse addRoleWithPassword(User user, RegisterRequest req, ClientContext client) {
        if (user.hasRole(req.role()) || user.hasRole(Role.ADMIN)) {
            throw emailTaken();
        }
        if (!user.isEmailVerified()) {
            throw new BusinessException(HttpStatus.CONFLICT, "IDENTITY_NOT_VERIFIED",
                    "Tài khoản này chưa xác thực email. Hãy xác thực trước khi thêm vai trò.");
        }
        if (!user.hasPassword()) {
            throw new BusinessException(HttpStatus.CONFLICT, "SIGN_IN_TO_ADD_ROLE",
                    "Tài khoản này đăng nhập bằng Google/Facebook. Hãy đăng nhập rồi thêm vai trò trong ứng dụng.");
        }
        String email = user.getEmail();
        if (req.password() == null || !passwords.matches(req.password(), user.getPasswordHash())) {
            guard.recordFailure(email, client.ip());
            throw new BusinessException(HttpStatus.UNAUTHORIZED, "INVALID_CREDENTIALS", "Email hoặc mật khẩu không đúng.");
        }
        guard.recordSuccess(email, client.ip());
        addRole(user, req, client);
        return new RegisterResponse(RegisterResponse.Status.ROLE_ADDED, null);
    }

    private RegisterResponse addRoleToSignedInIdentity(CurrentPrincipal caller, RegisterRequest req, ClientContext client) {
        User user = users.findById(caller.id()).orElseThrow(RegistrationService::emailTaken);
        if (user.hasRole(req.role())) {
            throw new BusinessException(HttpStatus.CONFLICT, "ROLE_ALREADY_HELD", "Bạn đã có vai trò này.");
        }
        if (user.hasRole(Role.ADMIN)) {
            throw new BusinessException(HttpStatus.FORBIDDEN, "FORBIDDEN", "Tài khoản quản trị không thể thêm vai trò khác.");
        }
        addRole(user, req, client);
        Role active = Role.valueOf(caller.activeRole());
        return new RegisterResponse(RegisterResponse.Status.ROLE_ADDED,
                TokenResponse.of(tokens.issue(user, active, Instant.now())));
    }

    private void addRole(User user, RegisterRequest req, ClientContext client) {
        user.addRole(req.role());
        users.saveAndFlush(user);
        legal.recordAcceptance(PRINCIPAL_TYPE, user.getId(), List.of(termsFor(req.role())),
                req.acceptedDocumentIds(), req.marketingConsent(), client);
    }

    private static DocumentType termsFor(Role role) {
        return role == Role.SELLER ? DocumentType.SELLER_TERMS : DocumentType.CUSTOMER_TERMS;
    }

    private static BusinessException emailTaken() {
        return new BusinessException(HttpStatus.CONFLICT, "EMAIL_ALREADY_REGISTERED",
                "Email này đã được đăng ký cho vai trò này.");
    }
}
