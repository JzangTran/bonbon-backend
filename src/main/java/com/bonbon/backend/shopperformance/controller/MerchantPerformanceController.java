package com.bonbon.backend.shopperformance.controller;

import java.time.LocalDate;
import java.util.List;

import com.bonbon.backend.common.openapi.ApiError;
import com.bonbon.backend.common.openapi.ApiTags;
import com.bonbon.backend.common.security.CurrentPrincipal;
import com.bonbon.backend.shopperformance.dto.PerformanceViews;
import com.bonbon.backend.shopperformance.service.PerformanceService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

@Tag(name = ApiTags.SELLER_PERFORMANCE, description = "Chất lượng phục vụ của quán: tỷ lệ đơn thất bại do quán theo tuần, điểm phạt đang có và hậu quả hiện tại. Mỗi thứ Hai hệ thống đánh giá tuần vừa đóng; vượt ngưỡng thì cộng 1 điểm, điểm hết hạn sau 90 ngày. Từ 3 điểm quán được báo trước và 5 ngày sau bị hạn chế hiển thị (vẫn đặt được nếu khách mở thẳng quán).")
@RestController
@RequestMapping("/api/merchant/performance")
@PreAuthorize("hasAuthority('vendor:read')")
class MerchantPerformanceController {

    private final PerformanceService performance;

    MerchantPerformanceController(PerformanceService performance) {
        this.performance = performance;
    }

    @Operation(operationId = "getMyPerformance", summary = "Hiệu suất của quán", description = "Tuần hiện tại (tạm tính) và 8 tuần đã đóng: số đơn kết thúc, số đơn thất bại do quán và tỷ lệ; điểm phạt còn hiệu lực với ngày hết hạn từng điểm; hậu quả hiện tại (cảnh báo, đã báo sẽ hạn chế, đang hạn chế) và ngày bắt đầu. Đơn khách huỷ, thanh toán quá hạn, hoàn một phần và khách vắng mặt do lỗi khách không bị tính.")
    @ApiError(status = 409, code = "SHOP_NOT_APPROVED", when = "Người gọi chưa có cửa hàng được duyệt.")
    @GetMapping
    PerformanceViews.Summary summary(CurrentPrincipal principal) {
        return performance.summary(principal);
    }

    @Operation(operationId = "listMyShopFaults", summary = "Các đơn lỗi trong một tuần", description = "Các đơn thất bại do quán trong tuần bắt đầu từ thứ Hai `week`, kèm lý do: quán từ chối, không trả lời kịp, không giao đi kịp, huỷ sau khi nhận, khiếu nại hoàn cả đơn, hoặc quán không đến khi giao.")
    @ApiError(status = 409, code = "SHOP_NOT_APPROVED", when = "Người gọi chưa có cửa hàng được duyệt.")
    @ApiError(status = 400, code = "INVALID_WEEK", when = "`week` không phải một ngày thứ Hai.")
    @GetMapping("/faults")
    List<PerformanceViews.Fault> faults(CurrentPrincipal principal, @RequestParam @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate week) {
        return performance.faults(principal, week);
    }
}
