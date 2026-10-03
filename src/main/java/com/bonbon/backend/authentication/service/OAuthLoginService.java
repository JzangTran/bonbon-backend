package com.bonbon.backend.authentication.service;

import java.time.Instant;
import java.util.ArrayList;
import java.util.EnumSet;
import java.util.List;
import java.util.Set;

import com.bonbon.backend.authentication.Role;
import com.bonbon.backend.authentication.dto.LoginResponse;
import com.bonbon.backend.authentication.dto.OAuthLoginRequest;
import com.bonbon.backend.authentication.entity.AuthProvider;
import com.bonbon.backend.authentication.entity.User;
import com.bonbon.backend.authentication.entity.UserAuthProvider;
import com.bonbon.backend.authentication.gateway.GoogleIdTokenVerifier;
import com.bonbon.backend.authentication.gateway.GoogleIdentity;
import com.bonbon.backend.authentication.repository.UserAuthProviderRepository;
import com.bonbon.backend.authentication.repository.UserRepository;
import com.bonbon.backend.common.exception.BusinessException;
import com.bonbon.backend.common.web.ClientContext;
import com.bonbon.backend.legal.DocumentType;
import com.bonbon.backend.legal.LegalConsentService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Sign in with Google (flows/authentication/login-oauth.md). A provider identity seen before signs in
 * directly; a new one either creates a bonbon identity (role and consent required) or links to the
 * account that already owns its email, but only after that account proves ownership with its password,
 * never on a matching email alone.
 */
@Service
public class OAuthLoginService {

    private static final Logger log = LoggerFactory.getLogger(OAuthLoginService.class);
    private static final Set<Role> APP_ROLES = EnumSet.of(Role.CUSTOMER, Role.SELLER);
    private static final String PRINCIPAL_TYPE = "USER";

    /** What the client must do before Google can be attached to the existing account with this email. */
    enum LinkMethod { PASSWORD, VERIFY_EMAIL_FIRST, SIGN_IN_FIRST }

    private final GoogleIdTokenVerifier google;
    private final UserRepository users;
    private final UserAuthProviderRepository providers;
    private final LoginService login;
    private final LegalConsentService legal;

    OAuthLoginService(GoogleIdTokenVerifier google, UserRepository users, UserAuthProviderRepository providers,
            LoginService login, LegalConsentService legal) {
        this.google = google;
        this.users = users;
        this.providers = providers;
        this.login = login;
        this.legal = legal;
    }

    @Transactional
    public LoginResponse login(OAuthLoginRequest req, ClientContext client) {
        if (req.provider() != AuthProvider.GOOGLE || !google.isEnabled()) {
            throw new BusinessException(HttpStatus.BAD_REQUEST, "PROVIDER_NOT_SUPPORTED",
                    "Phương thức đăng nhập này chưa được hỗ trợ.");
        }
        if (req.role() == Role.ADMIN) {
            throw new BusinessException(HttpStatus.BAD_REQUEST, "ROLE_NOT_SELF_REGISTERABLE",
                    "Không thể tự đăng ký tài khoản quản trị.");
        }
        GoogleIdentity identity = google.verify(req.token()).orElseThrow(() -> new BusinessException(
                HttpStatus.UNAUTHORIZED, "INVALID_OAUTH_TOKEN",
                "Phiên đăng nhập Google không hợp lệ hoặc đã hết hạn. Vui lòng thử lại."));
        Instant authTime = Instant.now();
        User user = providers.findByProviderIdentity(AuthProvider.GOOGLE, identity.subject())
                .map(link -> syncProfile(users.findById(link.getUserId()).orElseThrow(), identity))
                .orElseGet(() -> firstGoogleSignIn(identity, req, client));
        if (req.role() != null && !user.hasRole(req.role())) {
            user.addRole(req.role());
            users.saveAndFlush(user);
            legal.recordAcceptance(PRINCIPAL_TYPE, user.getId(), List.of(termsFor(req.role())),
                    req.acceptedDocumentIds(), req.marketingConsent(), client);
        }
        return login.complete(user, req.role(), authTime);
    }

    private User firstGoogleSignIn(GoogleIdentity identity, OAuthLoginRequest req, ClientContext client) {
        if (!identity.emailVerified() || identity.email() == null) {
            throw new BusinessException(HttpStatus.BAD_REQUEST, "OAUTH_EMAIL_UNVERIFIED",
                    "Email của tài khoản Google này chưa được Google xác minh.");
        }
        return users.findByEmail(identity.email())
                .map(existing -> linkToExisting(existing, identity, req, client))
                .orElseGet(() -> createIdentity(identity, req, client));
    }

    private User createIdentity(GoogleIdentity identity, OAuthLoginRequest req, ClientContext client) {
        if (req.role() == null) {
            throw new BusinessException(HttpStatus.CONFLICT, "OAUTH_SIGNUP_REQUIRED",
                    "Chưa có tài khoản bonbon cho email này. Chọn vai trò và đồng ý điều khoản để tạo tài khoản.")
                    .withProperty("email", identity.email())
                    .withProperty("name", displayName(identity));
        }
        User user = new User(identity.email(), null, displayName(identity));
        user.markEmailVerified();
        user.setAvatarUrl(identity.pictureUrl());
        user.addRole(req.role());
        try {
            user = users.saveAndFlush(user);
        } catch (DataIntegrityViolationException e) {
            throw new BusinessException(HttpStatus.CONFLICT, "EMAIL_ALREADY_REGISTERED",
                    "Email này vừa được đăng ký. Vui lòng thử lại.");
        }
        List<DocumentType> required = new ArrayList<>(List.of(termsFor(req.role()), DocumentType.PRIVACY_POLICY));
        legal.recordAcceptance(PRINCIPAL_TYPE, user.getId(), required, req.acceptedDocumentIds(), req.marketingConsent(),
                client);
        providers.save(new UserAuthProvider(user.getId(), AuthProvider.GOOGLE, identity.subject()));
        return user;
    }

    private User linkToExisting(User existing, GoogleIdentity identity, OAuthLoginRequest req, ClientContext client) {
        if (!existing.isEmailVerified()) {
            throw linkRequired(LinkMethod.VERIFY_EMAIL_FIRST,
                    "Email này đã có tài khoản bonbon nhưng chưa xác thực. Hãy xác thực email, đăng nhập bằng mật khẩu rồi liên kết Google.");
        }
        if (providers.isLinked(existing.getId(), AuthProvider.GOOGLE)) {
            throw linkRequired(LinkMethod.SIGN_IN_FIRST,
                    "Tài khoản bonbon có email này đã liên kết với một tài khoản Google khác.");
        }
        if (!existing.hasPassword()) {
            throw linkRequired(LinkMethod.SIGN_IN_FIRST,
                    "Email này đã có tài khoản bonbon. Hãy đăng nhập bằng phương thức đã dùng trước đó.");
        }
        if (req.password() == null || req.password().isEmpty()) {
            throw linkRequired(LinkMethod.PASSWORD,
                    "Email này đã có tài khoản bonbon. Nhập mật khẩu của tài khoản đó để liên kết với Google.");
        }
        // Same brute-force guard and CAPTCHA escalation as the password login form.
        User owner = login.authenticate(existing.getEmail(), req.password(), req.captchaToken(), client.ip(), APP_ROLES);
        providers.save(new UserAuthProvider(owner.getId(), AuthProvider.GOOGLE, identity.subject()));
        if (owner.getAvatarUrl() == null) {
            owner.setAvatarUrl(identity.pictureUrl());
        }
        return owner;
    }

    /**
     * Keeps the email and avatar in step with Google. The name is not overwritten: the user may have
     * edited it in bonbon. An email that another account already owns is left unsynced for this sign-in.
     */
    private User syncProfile(User user, GoogleIdentity identity) {
        if (identity.pictureUrl() != null) {
            user.setAvatarUrl(identity.pictureUrl());
        }
        String email = identity.email();
        if (identity.emailVerified() && email != null && !email.equalsIgnoreCase(user.getEmail())) {
            boolean taken = users.findByEmail(email).filter(other -> !other.getId().equals(user.getId())).isPresent();
            if (taken) {
                log.warn("Google email of user {} is owned by another account; email not synced", user.getId());
            } else {
                user.changeEmail(email);
            }
        }
        return user;
    }

    private static BusinessException linkRequired(LinkMethod method, String message) {
        return new BusinessException(HttpStatus.CONFLICT, "ACCOUNT_EXISTS_LINK_REQUIRED", message)
                .withProperty("linkMethod", method.name());
    }

    private static String displayName(GoogleIdentity identity) {
        String name = identity.name() == null || identity.name().isBlank()
                ? identity.email().substring(0, identity.email().indexOf('@'))
                : identity.name().trim();
        return name.length() > 100 ? name.substring(0, 100) : name;
    }

    private static DocumentType termsFor(Role role) {
        return role == Role.SELLER ? DocumentType.SELLER_TERMS : DocumentType.CUSTOMER_TERMS;
    }
}
