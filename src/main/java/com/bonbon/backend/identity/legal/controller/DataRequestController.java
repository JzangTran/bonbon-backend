package com.bonbon.backend.identity.legal.controller;

import java.util.List;
import java.util.UUID;

import com.bonbon.backend.common.openapi.ApiError;
import com.bonbon.backend.common.openapi.ApiTags;
import com.bonbon.backend.common.security.CurrentPrincipal;
import com.bonbon.backend.identity.legal.dto.DataRequestDtos;
import com.bonbon.backend.identity.legal.service.DataRequestService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import org.springframework.http.HttpStatus;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

@Tag(name = ApiTags.LEGAL)
@RestController
class DataRequestController {

    private final DataRequestService requests;

    DataRequestController(DataRequestService requests) {
        this.requests = requests;
    }

    @Operation(operationId = "exportMyData", summary = "Tải dữ liệu cá nhân của tôi",
            description = "Tạo ngay tệp JSON gồm hồ sơ, địa chỉ, các lần đồng ý, đơn hàng, đánh giá, tin nhắn đã gửi và thông tin cửa hàng (nếu có). Liên kết tải sống 15 phút và lấy lại được trong 7 ngày ở danh sách yêu cầu. Một lần mỗi giờ.")
    @ApiError(status = 429, code = "EXPORT_TOO_SOON", when = "Đã tải trong một giờ qua.")
    @PostMapping("/api/me/data-export")
    @PreAuthorize("isAuthenticated()")
    @ResponseStatus(HttpStatus.CREATED)
    DataRequestDtos.View export(CurrentPrincipal principal) {
        return requests.export(principal);
    }

    @Operation(operationId = "createDataRequest", summary = "Gửi yêu cầu về dữ liệu cá nhân",
            description = "Sửa dữ liệu không tự sửa được, rút lại đồng ý, hạn chế hoặc phản đối xử lý. Quản trị viên phản hồi trong 2 ngày làm việc và hoàn thành trong 10–15 ngày; được gia hạn một lần có lý do.")
    @ApiError(status = 409, code = "TOO_MANY_OPEN_REQUESTS", when = "Đã có 3 yêu cầu chưa xong.")
    @PostMapping("/api/me/data-requests")
    @PreAuthorize("isAuthenticated()")
    @ResponseStatus(HttpStatus.CREATED)
    DataRequestDtos.View create(CurrentPrincipal principal, @Valid @RequestBody DataRequestDtos.Create request) {
        return requests.create(principal, request);
    }

    @Operation(operationId = "listMyDataRequests", summary = "Các yêu cầu về dữ liệu của tôi", description = "Mới nhất trước, kể cả các lần tải dữ liệu.")
    @GetMapping("/api/me/data-requests")
    @PreAuthorize("isAuthenticated()")
    List<DataRequestDtos.View> mine(CurrentPrincipal principal) {
        return requests.mine(principal);
    }

    @Operation(operationId = "listDataRequests", summary = "Hàng đợi yêu cầu về dữ liệu", description = "Mặc định các yêu cầu chưa xong, hạn gần nhất trước; `overdue` là đã quá hạn.")
    @ApiError(status = 400, code = "INVALID_STATUS", when = "`status` không phải OPEN, COMPLETED, REJECTED hoặc ALL.")
    @GetMapping("/api/admin/data-requests")
    @PreAuthorize("hasAuthority('data-request:handle')")
    DataRequestDtos.Page queue(@RequestParam(required = false) String status, @RequestParam(defaultValue = "0") int page,
            @RequestParam(defaultValue = "20") int size) {
        return requests.queue(status, page, size);
    }

    @Operation(operationId = "handleDataRequest", summary = "Xử lý một yêu cầu về dữ liệu",
            description = "Bắt đầu, hoàn thành, từ chối kèm lý do (ví dụ dữ liệu giao dịch phải lưu theo luật) hoặc gia hạn một lần. Người yêu cầu được báo khi hoàn thành hoặc bị từ chối.")
    @ApiError(status = 404, code = "DATA_REQUEST_NOT_FOUND", when = "Không có yêu cầu này.")
    @ApiError(status = 409, code = "DATA_REQUEST_CLOSED", when = "Yêu cầu đã đóng.")
    @ApiError(status = 409, code = "ALREADY_EXTENDED", when = "Đã gia hạn một lần.")
    @ApiError(status = 400, code = "REASON_REQUIRED", when = "Từ chối mà không có lý do.")
    @PatchMapping("/api/admin/data-requests/{id}")
    @PreAuthorize("hasAuthority('data-request:handle')")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    void handle(CurrentPrincipal principal, @PathVariable UUID id, @Valid @RequestBody DataRequestDtos.Handle request) {
        requests.handle(principal.id(), id, request);
    }
}
