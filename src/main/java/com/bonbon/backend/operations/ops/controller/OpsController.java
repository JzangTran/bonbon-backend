package com.bonbon.backend.operations.ops.controller;

import com.bonbon.backend.common.openapi.ApiTags;
import com.bonbon.backend.operations.ops.dto.OpsStatusView;
import com.bonbon.backend.operations.ops.service.OpsStatusService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@Tag(name = ApiTags.ADMIN_OPS, description = "Tình trạng hạ tầng cho quản trị viên vận hành (quyền `ops:read`). Chỉ đọc.")
@RestController
@RequestMapping("/api/admin/ops")
@PreAuthorize("hasAuthority('ops:read')")
class OpsController {

    private final OpsStatusService status;

    OpsController(OpsStatusService status) {
        this.status = status;
    }

    @Operation(operationId = "getOpsStatus", summary = "Tình trạng hệ thống", description = "Ping cơ sở dữ liệu và Redis, kèm thời gian chạy, bộ nhớ và số lõi của phiên bản đang trả lời. `status` là `DEGRADED` nếu có kiểm tra không đạt.")
    @GetMapping("/status")
    OpsStatusView status() {
        return status.status();
    }
}
