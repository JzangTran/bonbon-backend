package com.bonbon.backend.authentication.entity;

import java.time.Instant;
import java.util.UUID;

import com.bonbon.backend.authentication.Role;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

/** One login session (per device and role); the raw token is never stored, only its SHA-256. */
@Entity
@Table(name = "refresh_tokens")
public class RefreshToken {

    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    private UUID id;

    @Column(name = "user_id", nullable = false)
    private UUID userId;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, columnDefinition = "text")
    private Role role;

    @Column(name = "token_hash", nullable = false, unique = true, columnDefinition = "text")
    private String tokenHash;

    @Column(name = "expires_at", nullable = false)
    private Instant expiresAt;

    @Column(name = "revoked_at")
    private Instant revokedAt;

    /** All tokens rotated from one login share a family; reuse of a revoked one revokes the family. */
    @Column(name = "family_id", nullable = false)
    private UUID familyId;

    /** When the user actually authenticated (password or OAuth), carried across rotations. */
    @Column(name = "auth_time", nullable = false)
    private Instant authTime;

    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt;

    protected RefreshToken() {
    }

    public RefreshToken(UUID userId, Role role, String tokenHash, Instant expiresAt, UUID familyId, Instant authTime) {
        this.userId = userId;
        this.role = role;
        this.tokenHash = tokenHash;
        this.expiresAt = expiresAt;
        this.familyId = familyId;
        this.authTime = authTime;
        this.createdAt = Instant.now();
    }

    public boolean isUsable(Instant now) {
        return revokedAt == null && expiresAt.isAfter(now);
    }

    public void revoke(Instant now) {
        if (revokedAt == null) {
            revokedAt = now;
        }
    }

    public UUID getId() {
        return id;
    }

    public UUID getUserId() {
        return userId;
    }

    public Role getRole() {
        return role;
    }

    public Instant getExpiresAt() {
        return expiresAt;
    }

    public Instant getRevokedAt() {
        return revokedAt;
    }

    public UUID getFamilyId() {
        return familyId;
    }

    public Instant getAuthTime() {
        return authTime;
    }
}
