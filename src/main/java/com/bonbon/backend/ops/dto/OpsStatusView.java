package com.bonbon.backend.ops.dto;

import java.time.Instant;
import java.util.List;

import io.swagger.v3.oas.annotations.media.Schema;

@Schema(name = "OpsStatus", description = "Tình trạng của phiên bản đang chạy và các thành phần nó dựa vào.")
public record OpsStatusView(
        @Schema(description = "`UP` khi mọi kiểm tra đều đạt, ngược lại `DEGRADED`.") String status,
        Instant checkedAt,
        Instant startedAt,
        long uptimeSeconds,
        @Schema(description = "Hồ sơ cấu hình đang dùng (dev, prod…).") List<String> profiles,
        String javaVersion,
        int processors,
        long heapUsedMb,
        long heapMaxMb,
        List<Check> checks) {

    @Schema(name = "OpsCheck")
    public record Check(String name, boolean ok, long latencyMs, String detail) {
    }
}
