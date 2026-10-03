package com.bonbon.backend.authentication.entity;

import java.io.Serializable;
import java.time.Instant;
import java.util.UUID;

import jakarta.persistence.Column;
import jakarta.persistence.Embeddable;
import jakarta.persistence.EmbeddedId;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Table;

/** One external sign-in method attached to a user (Google today; Facebook later). */
@Entity
@Table(name = "user_auth_providers")
public class UserAuthProvider {

    @EmbeddedId
    private Key key;

    @Column(name = "provider_id", nullable = false, columnDefinition = "text")
    private String providerId;

    @Column(name = "linked_at", nullable = false, updatable = false)
    private Instant linkedAt;

    protected UserAuthProvider() {
    }

    public UserAuthProvider(UUID userId, AuthProvider provider, String providerId) {
        this.key = new Key(userId, provider);
        this.providerId = providerId;
        this.linkedAt = Instant.now();
    }

    public UUID getUserId() {
        return key.userId;
    }

    public AuthProvider getProvider() {
        return key.provider;
    }

    public String getProviderId() {
        return providerId;
    }

    @Embeddable
    public static class Key implements Serializable {

        @Column(name = "user_id", nullable = false)
        private UUID userId;

        @Column(nullable = false, columnDefinition = "text")
        @Enumerated(EnumType.STRING)
        private AuthProvider provider;

        protected Key() {
        }

        Key(UUID userId, AuthProvider provider) {
            this.userId = userId;
            this.provider = provider;
        }

        @Override
        public boolean equals(Object o) {
            return o instanceof Key k && k.userId.equals(userId) && k.provider == provider;
        }

        @Override
        public int hashCode() {
            return userId.hashCode() * 31 + provider.hashCode();
        }
    }
}
