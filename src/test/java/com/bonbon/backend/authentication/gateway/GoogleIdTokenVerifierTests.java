package com.bonbon.backend.authentication.gateway;

import java.security.KeyPair;
import java.security.KeyPairGenerator;
import java.security.interfaces.RSAPrivateKey;
import java.security.interfaces.RSAPublicKey;
import java.time.Instant;
import java.util.List;
import java.util.Map;

import com.nimbusds.jose.jwk.JWKSet;
import com.nimbusds.jose.jwk.RSAKey;
import com.nimbusds.jose.jwk.source.ImmutableJWKSet;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.springframework.security.oauth2.jose.jws.SignatureAlgorithm;
import org.springframework.security.oauth2.jwt.JwsHeader;
import org.springframework.security.oauth2.jwt.JwtClaimsSet;
import org.springframework.security.oauth2.jwt.JwtEncoderParameters;
import org.springframework.security.oauth2.jwt.NimbusJwtDecoder;
import org.springframework.security.oauth2.jwt.NimbusJwtEncoder;

import static org.assertj.core.api.Assertions.assertThat;

/** Signature, issuer, audience and expiry checks, with a local RSA key standing in for Google's. */
class GoogleIdTokenVerifierTests {

    private static final String CLIENT_ID = "bonbon-web.apps.googleusercontent.com";

    static NimbusJwtEncoder google;
    static NimbusJwtEncoder impostor;
    static RSAPublicKey googlePublicKey;

    @BeforeAll
    static void keys() throws Exception {
        KeyPairGenerator generator = KeyPairGenerator.getInstance("RSA");
        generator.initialize(2048);
        KeyPair real = generator.generateKeyPair();
        KeyPair other = generator.generateKeyPair();
        googlePublicKey = (RSAPublicKey) real.getPublic();
        google = encoder(real);
        impostor = encoder(other);
    }

    @Test
    void validTokenYieldsIdentity() {
        GoogleIdentity identity = verifier().verify(token(google, "https://accounts.google.com", CLIENT_ID, 300, true))
                .orElseThrow();
        assertThat(identity.subject()).isEqualTo("1234567890");
        assertThat(identity.email()).isEqualTo("an@gmail.com");
        assertThat(identity.emailVerified()).isTrue();
        assertThat(identity.name()).isEqualTo("An Nguyễn");
    }

    @Test
    void shortIssuerFormIsAccepted() {
        assertThat(verifier().verify(token(google, "accounts.google.com", CLIENT_ID, 300, false)))
                .hasValueSatisfying(identity -> assertThat(identity.emailVerified()).isFalse());
    }

    @Test
    void tokenForAnotherApplicationIsRejected() {
        assertThat(verifier().verify(token(google, "https://accounts.google.com", "other-app", 300, true))).isEmpty();
    }

    @Test
    void foreignIssuerIsRejected() {
        assertThat(verifier().verify(token(google, "https://evil.example.com", CLIENT_ID, 300, true))).isEmpty();
    }

    @Test
    void expiredTokenIsRejected() {
        assertThat(verifier().verify(token(google, "https://accounts.google.com", CLIENT_ID, -600, true))).isEmpty();
    }

    @Test
    void tokenSignedByAnotherKeyIsRejected() {
        assertThat(verifier().verify(token(impostor, "https://accounts.google.com", CLIENT_ID, 300, true))).isEmpty();
    }

    @Test
    void withoutClientIdsGoogleSignInIsDisabled() {
        GoogleIdTokenVerifier disabled = new GoogleIdTokenVerifier(NimbusJwtDecoder.withPublicKey(googlePublicKey).build(),
                List.of(""));
        assertThat(disabled.isEnabled()).isFalse();
        assertThat(disabled.verify(token(google, "https://accounts.google.com", CLIENT_ID, 300, true))).isEmpty();
    }

    @Test
    void garbageIsRejected() {
        assertThat(verifier().verify("not-a-jwt")).isEmpty();
    }

    private static GoogleIdTokenVerifier verifier() {
        return new GoogleIdTokenVerifier(NimbusJwtDecoder.withPublicKey(googlePublicKey).build(),
                List.of(" " + CLIENT_ID + " ", "android-client"));
    }

    private static String token(NimbusJwtEncoder signer, String issuer, String audience, long expiresInSeconds,
            boolean emailVerified) {
        Instant now = Instant.now();
        JwtClaimsSet claims = JwtClaimsSet.builder()
                .issuer(issuer)
                .audience(List.of(audience))
                .subject("1234567890")
                .issuedAt(now.minusSeconds(Math.max(0, -expiresInSeconds) + 60))
                .expiresAt(now.plusSeconds(expiresInSeconds))
                .claims(c -> c.putAll(Map.of("email", "an@gmail.com", "email_verified", emailVerified,
                        "name", "An Nguyễn", "picture", "https://example.com/an.png")))
                .build();
        JwsHeader header = JwsHeader.with(SignatureAlgorithm.RS256).build();
        return signer.encode(JwtEncoderParameters.from(header, claims)).getTokenValue();
    }

    private static NimbusJwtEncoder encoder(KeyPair pair) {
        RSAKey key = new RSAKey.Builder((RSAPublicKey) pair.getPublic()).privateKey((RSAPrivateKey) pair.getPrivate()).build();
        return new NimbusJwtEncoder(new ImmutableJWKSet<>(new JWKSet(key)));
    }
}
