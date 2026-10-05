package com.bonbon.backend.notification.dto;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;

/** Requests of an app registering its push token. */
public final class PushDeviceRequests {

    private PushDeviceRequests() {
    }

    private static final String EXPO_TOKEN = "^(Expo|Exponent)PushToken\\[[A-Za-z0-9_\\-]{3,100}\\]$";

    @Schema(name = "PushDeviceRequest")
    public record Register(
            @NotBlank @Pattern(regexp = EXPO_TOKEN, message = "Mã thông báo không hợp lệ") String token,
            @NotNull @Pattern(regexp = "ANDROID|IOS|WEB") String platform,
            @Size(max = 40) String appVersion) {
    }

    @Schema(name = "PushDeviceRevokeRequest")
    public record Revoke(@NotBlank @Pattern(regexp = EXPO_TOKEN, message = "Mã thông báo không hợp lệ") String token) {
    }
}
