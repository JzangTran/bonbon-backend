package com.bonbon.backend.support.dto;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

import io.swagger.v3.oas.annotations.media.Schema;

public final class ContactViews {

    private ContactViews() {
    }

    @Schema(name = "ContactRequestReceipt")
    public record Receipt(UUID id, Instant createdAt) {
    }

    @Schema(name = "ContactRequestSummary")
    public record Summary(UUID id, String fullName, String email, String role, String topic,
            @Schema(description = "OPEN (chưa trả lời), ANSWERED (đã trả lời qua email) hoặc CLOSED.") String status,
            @Schema(description = "Đầu nội dung, tối đa 160 ký tự.") String preview, Instant createdAt, Instant updatedAt) {
    }

    @Schema(name = "ContactRequestPage")
    public record Page(List<Summary> items, int page, int size, long total) {
    }

    @Schema(name = "ContactRequestAnswer")
    public record Answer(UUID id, UUID adminId, String adminName, String body, Instant createdAt) {
    }

    @Schema(name = "ContactRequestDetail")
    public record Detail(UUID id, String fullName, String email, String role, String topic, String status, String message, Instant createdAt, Instant updatedAt,
            Instant closedAt, List<Answer> answers) {
    }
}
