package com.bonbon.backend.authentication.service;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.UUID;

import com.bonbon.backend.authentication.Role;
import com.bonbon.backend.authentication.entity.RefreshToken;
import com.bonbon.backend.authentication.entity.User;
import com.bonbon.backend.authentication.repository.RefreshTokenRepository;
import com.bonbon.backend.authentication.repository.RolePermissionRepository;
import com.bonbon.backend.authentication.repository.UserRepository;
import com.bonbon.backend.common.exception.BusinessException;
import com.bonbon.backend.common.persistence.ActorType;
import com.bonbon.backend.common.security.CurrentPrincipal;
import com.bonbon.backend.common.security.JwtProperties;
import org.springframework.http.HttpStatus;
import org.springframework.security.oauth2.jose.jws.MacAlgorithm;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.security.oauth2.jwt.JwsHeader;
import org.springframework.security.oauth2.jwt.JwtClaimsSet;
import org.springframework.security.oauth2.jwt.JwtEncoder;
import org.springframework.security.oauth2.jwt.JwtEncoderParameters;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Issues access tokens (signed JWT, 30 min) and refresh tokens (opaque, 7 days, rotated on every use).
 * Presenting a refresh token that was already rotated or revoked is treated as theft: the whole family
 * from that login is revoked and the user must sign in again.
 */
@Service
public class TokenService {

    private static final String ISSUER = "bonbon";

    private final JwtEncoder encoder;
    private final JwtProperties props;
    private final RefreshTokenRepository refreshTokens;
    private final RolePermissionRepository rolePermissions;
    private final UserRepository users;
    private final TokenRevocationStore revocationStore;
    private final Clock clock;

    TokenService(JwtEncoder encoder, JwtProperties props, RefreshTokenRepository refreshTokens,
            RolePermissionRepository rolePermissions, UserRepository users, TokenRevocationStore revocationStore) {
        this.encoder = encoder;
        this.props = props;
        this.refreshTokens = refreshTokens;
        this.rolePermissions = rolePermissions;
        this.users = users;
        this.revocationStore = revocationStore;
        this.clock = Clock.systemUTC();
    }

    public record TokenPair(String accessToken, String refreshToken, long expiresInSeconds, Role role) {
    }

    /** A new login: starts a new refresh-token family. */
    @Transactional
    public TokenPair issue(User user, Role role, Instant authTime) {
        return issue(user, role, authTime, UUID.randomUUID());
    }

    @Transactional(noRollbackFor = BusinessException.class)
    public TokenPair refresh(String rawRefreshToken) {
        Instant now = clock.instant();
        RefreshToken current = refreshTokens.findByTokenHash(SecureTokens.hash(rawRefreshToken))
                .orElseThrow(TokenService::invalidRefresh);

        if (current.getRevokedAt() != null) {
            refreshTokens.revokeFamily(current.getFamilyId(), now);
            throw invalidRefresh();
        }
        if (!current.isUsable(now)) {
            throw invalidRefresh();
        }
        User user = users.findById(current.getUserId()).orElseThrow(TokenService::invalidRefresh);
        if (!user.hasRole(current.getRole())) {
            refreshTokens.revokeIfActive(current.getId(), now);
            throw invalidRefresh();
        }
        if (refreshTokens.revokeIfActive(current.getId(), now) == 0) {
            // Another request rotated it first: the same token was presented twice.
            refreshTokens.revokeFamily(current.getFamilyId(), now);
            throw invalidRefresh();
        }
        return issue(user, current.getRole(), current.getAuthTime(), current.getFamilyId());
    }

    /** Logout of one device: the access token stops working at once, its refresh token is revoked. */
    @Transactional
    public void revokeSession(Jwt accessToken, String rawRefreshToken) {
        Instant now = clock.instant();
        if (accessToken.getExpiresAt() != null) {
            revocationStore.blacklist(accessToken.getId(), Duration.between(now, accessToken.getExpiresAt()));
        }
        if (rawRefreshToken != null) {
            refreshTokens.findByTokenHash(SecureTokens.hash(rawRefreshToken))
                    .filter(t -> t.getUserId().toString().equals(accessToken.getSubject()))
                    .ifPresent(t -> t.revoke(now));
        }
    }

    /** Password change/reset or logout-all: every token issued before now stops working. */
    @Transactional
    public Instant invalidateAllSessions(User user) {
        Instant now = clock.instant();
        user.invalidateTokensIssuedBefore(now);
        users.save(user);
        refreshTokens.revokeAllForUser(user.getId(), now);
        revocationStore.cacheValidAfter(user.getId(), now);
        return now;
    }

    private TokenPair issue(User user, Role role, Instant authTime, UUID familyId) {
        Instant now = clock.instant();
        List<String> permissions = rolePermissions.findPermissionCodes(role.name());
        JwtClaimsSet claims = JwtClaimsSet.builder()
                .issuer(ISSUER)
                .subject(user.getId().toString())
                .id(UUID.randomUUID().toString())
                .issuedAt(now)
                .expiresAt(now.plus(props.accessTtl()))
                .claim(CurrentPrincipal.CLAIM_ACTOR, actorOf(role).name())
                .claim(CurrentPrincipal.CLAIM_ROLE, role.name())
                .claim(CurrentPrincipal.CLAIM_PERMISSIONS, permissions)
                .claim(CurrentPrincipal.CLAIM_AUTH_TIME, authTime.getEpochSecond())
                .build();
        JwsHeader header = JwsHeader.with(MacAlgorithm.HS256).build();
        String accessToken = encoder.encode(JwtEncoderParameters.from(header, claims)).getTokenValue();

        String rawRefresh = SecureTokens.newRawToken();
        refreshTokens.save(new RefreshToken(user.getId(), role, SecureTokens.hash(rawRefresh),
                now.plus(props.refreshTtl()), familyId, authTime));
        return new TokenPair(accessToken, rawRefresh, props.accessTtl().toSeconds(), role);
    }

    private static ActorType actorOf(Role role) {
        return switch (role) {
            case CUSTOMER -> ActorType.CUSTOMER;
            case SELLER -> ActorType.SHOP_ACCOUNT;
            case ADMIN -> ActorType.ADMIN;
        };
    }

    private static BusinessException invalidRefresh() {
        return new BusinessException(HttpStatus.UNAUTHORIZED, "INVALID_REFRESH_TOKEN",
                "Phiên đăng nhập đã hết hạn, vui lòng đăng nhập lại.");
    }
}
