package com.bonbon.backend.shopperformance.controller;

import java.util.UUID;

import com.bonbon.backend.common.openapi.ApiError;
import com.bonbon.backend.common.openapi.ApiTags;
import com.bonbon.backend.common.security.CurrentPrincipal;
import com.bonbon.backend.shopperformance.dto.PenaltyRequests;
import com.bonbon.backend.shopperformance.service.PenaltyService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import org.springframework.http.HttpStatus;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

@Tag(name = ApiTags.SELLER_PERFORMANCE, description = "Chất lượng phục vụ của quán: tỷ lệ đơn thất bại do quán theo tuần, điểm phạt đang có và hậu quả hiện tại. Mỗi thứ Hai hệ thống đánh giá tuần vừa đóng; vượt ngưỡng thì cộng 1 điểm, điểm hết hạn sau 90 ngày. Từ 3 điểm quán được báo trước và 5 ngày sau bị hạn chế hiển thị (vẫn đặt được nếu khách mở thẳng quán).")
@RestController
@RequestMapping("/api/merchant/penalties")
@PreAuthorize("hasAuthority('vendor:write')")
@Validated
class MerchantPenaltyController {

    private final PenaltyService penalties;

    MerchantPenaltyController(PenaltyService penalties) {
        this.penalties = penalties;
    }

    @Operation(operationId = "appealShopPenalty", summary = "Kháng nghị một điểm phạt", description = "Mỗi điểm chỉ kháng nghị một lần, trong 7 ngày kể từ khi cấp (`appeal_window_days`). Kháng nghị đang chờ không làm gỡ hạn chế; nếu được chấp nhận, điểm được miễn và hậu quả tính lại ngay. Quản trị viên quyết định và quán được báo kèm lý do.")
    @ApiError(status = 409, code = "SHOP_NOT_APPROVED", when = "Người gọi chưa có cửa hàng được duyệt.")
    @ApiError(status = 404, code = "PENALTY_NOT_FOUND", when = "Không có điểm phạt này ở cửa hàng của người gọi.")
    @ApiError(status = 409, code = "PENALTY_NOT_ACTIVE", when = "Điểm này đã được miễn.")
    @ApiError(status = 409, code = "APPEAL_ALREADY_FILED", when = "Điểm này đã được kháng nghị.")
    @ApiError(status = 409, code = "APPEAL_WINDOW_CLOSED", when = "Đã quá thời hạn kháng nghị; kèm `deadline`.")
    @ApiResponse(responseCode = "204", description = "Đã gửi kháng nghị.")
    @PostMapping("/{id}/appeal")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    void appeal(CurrentPrincipal principal, @PathVariable UUID id, @Valid @RequestBody PenaltyRequests.Appeal request) {
        penalties.appeal(principal, id, request.reason());
    }
}
