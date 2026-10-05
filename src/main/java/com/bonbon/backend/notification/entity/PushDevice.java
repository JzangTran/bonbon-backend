package com.bonbon.backend.notification.entity;

import java.time.Instant;
import java.util.UUID;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.PrePersist;
import jakarta.persistence.Table;

/** One installed app that can receive pushes; the token is unique, so it always belongs to one user at a time. */
@Entity
@Table(name = "push_devices")
public class PushDevice {

    public enum Status { ACTIVE, INVALID, REVOKED }

    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    private UUID id;

    @Column(name = "user_id", nullable = false)
    private UUID userId;

    @Column(nullable = false, updatable = false, columnDefinition = "text")
    private String kind = "EXPO";

    @Column(nullable = false, updatable = false, columnDefinition = "text")
    private String token;

    @Column(nullable = false, columnDefinition = "text")
    private String platform;

    @Column(name = "app_version", columnDefinition = "text")
    private String appVersion;

    @jakarta.persistence.Enumerated(jakarta.persistence.EnumType.STRING)
    @Column(nullable = false, columnDefinition = "text")
    private Status status = Status.ACTIVE;

    @Column(name = "last_seen_at", nullable = false)
    private Instant lastSeenAt;

    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt;

    protected PushDevice() {
    }

    public PushDevice(UUID userId, String token, String platform, String appVersion) {
        this.userId = userId;
        this.token = token;
        this.platform = platform;
        this.appVersion = appVersion;
    }

    @PrePersist
    void onCreate() {
        createdAt = Instant.now();
        lastSeenAt = createdAt;
    }

    /** Registering again (new owner, new version) brings the device back to life. */
    public void claim(UUID userId, String platform, String appVersion) {
        this.userId = userId;
        this.platform = platform;
        this.appVersion = appVersion;
        this.status = Status.ACTIVE;
        this.lastSeenAt = Instant.now();
    }

    public void mark(Status status) {
        this.status = status;
    }

    public UUID getId() {
        return id;
    }

    public UUID getUserId() {
        return userId;
    }

    public String getToken() {
        return token;
    }

    public Status getStatus() {
        return status;
    }
}
