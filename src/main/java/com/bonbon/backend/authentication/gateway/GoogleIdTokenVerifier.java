package com.bonbon.backend.authentication.gateway;

import java.util.List;
import java.util.Optional;
import java.util.Set;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.security.oauth2.core.DelegatingOAuth2TokenValidator;
import org.springframework.security.oauth2.core.OAuth2TokenValidator;
import org.springframework.security.oauth2.jwt.BadJwtException;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.security.oauth2.jwt.JwtClaimValidator;
import org.springframework.security.oauth2.jwt.JwtException;
import org.springframework.security.oauth2.jwt.JwtTimestampValidator;
import org.springframework.security.oauth2.jwt.NimbusJwtDecoder;
import org.springframework.stereotype.Component;

/**
 * Verifies a Google ID token the client obtained with Google Sign-In: RS256 signature against Google's
 * published keys (fetched lazily and cached), issuer, expiry, and an audience that is one of bonbon's own
 * OAuth client IDs, so a token minted for another application is refused.
 */
@Component
public class GoogleIdTokenVerifier {

    private static final Logger log = LoggerFactory.getLogger(GoogleIdTokenVerifier.class);

    static final String JWK_SET_URI = "https://www.googleapis.com/oauth2/v3/certs";
    private static final Set<String> ISSUERS = Set.of("accounts.google.com", "https://accounts.google.com");

    private final NimbusJwtDecoder decoder;
    private final boolean enabled;

    @Autowired
    GoogleIdTokenVerifier(@Value("${bonbon.oauth.google.client-ids:}") List<String> clientIds) {
        this(NimbusJwtDecoder.withJwkSetUri(JWK_SET_URI).build(), clientIds);
    }

    GoogleIdTokenVerifier(NimbusJwtDecoder decoder, List<String> clientIds) {
        List<String> audiences = clientIds.stream().map(String::trim).filter(id -> !id.isEmpty()).toList();
        OAuth2TokenValidator<Jwt> validator = new DelegatingOAuth2TokenValidator<>(
                new JwtTimestampValidator(),
                new JwtClaimValidator<String>("iss", ISSUERS::contains),
                new JwtClaimValidator<List<String>>("aud", aud -> aud != null && aud.stream().anyMatch(audiences::contains)));
        decoder.setJwtValidator(validator);
        this.decoder = decoder;
        this.enabled = !audiences.isEmpty();
    }

    /** False when no client ID is configured; Google sign-in is then unavailable. */
    public boolean isEnabled() {
        return enabled;
    }

    /** Empty when the token is malformed, forged, expired or meant for another application. */
    public Optional<GoogleIdentity> verify(String idToken) {
        if (!enabled) {
            return Optional.empty();
        }
        try {
            Jwt jwt = decoder.decode(idToken);
            Object verified = jwt.getClaims().get("email_verified");
            return Optional.of(new GoogleIdentity(jwt.getSubject(), jwt.getClaimAsString("email"),
                    Boolean.TRUE.equals(verified) || "true".equals(verified),
                    jwt.getClaimAsString("name"), jwt.getClaimAsString("picture")));
        } catch (BadJwtException e) {
            return Optional.empty();
        } catch (JwtException e) {
            // Not the token's fault (e.g. Google's key set unreachable); surfaced so an outage is noticed.
            log.warn("Google ID token could not be verified: {}", e.getMessage());
            return Optional.empty();
        }
    }
}
