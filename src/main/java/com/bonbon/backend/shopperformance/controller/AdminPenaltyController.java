package com.bonbon.backend.shopperformance.controller;

import java.util.UUID;

import com.bonbon.backend.common.openapi.ApiError;
import com.bonbon.backend.common.openapi.ApiTags;
import com.bonbon.backend.common.security.CurrentPrincipal;
import com.bonbon.backend.shopperformance.dto.PenaltyRequests;
import com.bonbon.backend.shopperformance.dto.PenaltyViews;
import com.bonbon.backend.shopperformance.service.PenaltyService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

@Tag(name = ApiTags.ADMIN_PENALTIES, description = "Điểm phạt của các quán: xem quán nào đang có điểm, miễn một điểm khi lỗi do nền tảng, cộng điểm thủ công và quyết định kháng nghị của quán. Miễn hoặc chấp nhận kháng nghị tính lại điểm ngay nên có thể gỡ hạn chế hiển thị tức thì. Quán từ 6 điểm được đánh dấu để xem xét đình chỉ; hệ thống không bao giờ tự đình chỉ.")
@RestController
@RequestMapping("/api/admin")
@Validated
class AdminPenaltyController {

    private final PenaltyService penalties;

    AdminPenaltyController(PenaltyService penalties) {
        this.penalties = penalties;
    }

    @Operation(operationId = "listShopPenalties", summary = "Các quán đang có điểm phạt", description = "Quán có ít nhất `minPoints` điểm còn hiệu lực (mặc định 1), nhiều điểm nhất trước; quán có kháng nghị đang chờ luôn hiện. Kèm hậu quả hiện tại, ngày hạn chế bắt đầu và cờ xem xét đình chỉ (từ 6 điểm).")
    @ApiError(status = 400, code = "INVALID_MIN_POINTS", when = "`minPoints` âm.")
    @ApiError(status = 400, code = "INVALID_PAGE", when = "`page` âm hoặc `size` ngoài 1–100.")
    @GetMapping("/shop-penalties")
    @PreAuthorize("hasAuthority('shop-penalty:read')")
    PenaltyViews.ShopPage list(@RequestParam(defaultValue = "1") int minPoints, @RequestParam(defaultValue = "0") int page, @RequestParam(defaultValue = "20") int size) {
        return penalties.shops(minPoints, page, size);
    }

    @Operation(operationId = "listPenaltyAppeals", summary = "Kháng nghị đang chờ quyết", description = "Cũ nhất trước, kèm số đơn và số đơn lỗi của tuần bị phạt để cân nhắc. Kháng nghị đang chờ không làm gỡ hạn chế.")
    @ApiError(status = 400, code = "INVALID_PAGE", when = "`page` âm hoặc `size` ngoài 1–100.")
    @GetMapping("/shop-penalties/appeals")
    @PreAuthorize("hasAuthority('shop-penalty:read')")
    PenaltyViews.AppealPage appeals(@RequestParam(defaultValue = "0") int page, @RequestParam(defaultValue = "20") int size) {
        return penalties.appeals(page, size);
    }

    @Operation(operationId = "getShopPenaltyHistory", summary = "Lịch sử điểm phạt của một quán", description = "Mọi điểm đã cấp (đánh giá hằng tuần hoặc cộng thủ công) kèm trạng thái, hạn, lý do, kháng nghị và quyết định; cùng hậu quả hiện tại.")
    @ApiError(status = 404, code = "VENDOR_NOT_FOUND", when = "Không có quán với id này.")
    @GetMapping("/shops/{id}/penalties")
    @PreAuthorize("hasAuthority('shop-penalty:read')")
    PenaltyViews.History history(@PathVariable UUID id) {
        return penalties.history(id);
    }

    @Operation(operationId = "waiveShopPenalty", summary = "Miễn một điểm phạt", description = "Cho điểm còn hiệu lực, kèm lý do (ví dụ nền tảng gặp sự cố làm đơn thất bại); quán được báo. Điểm của quán được tính lại ngay, nên có thể gỡ hạn chế hiển thị lập tức.")
    @ApiError(status = 404, code = "PENALTY_NOT_FOUND", when = "Không có điểm phạt với id này.")
    @ApiError(status = 409, code = "PENALTY_NOT_ACTIVE", when = "Điểm này đã được miễn.")
    @PostMapping("/shop-penalties/{id}/waive")
    @PreAuthorize("hasAuthority('shop-penalty:write')")
    PenaltyViews.History waive(CurrentPrincipal principal, @PathVariable UUID id, @Valid @RequestBody PenaltyRequests.Waive request) {
        return penalties.waive(principal, id, request.reason());
    }

    @Operation(operationId = "addShopPenalty", summary = "Cộng điểm phạt thủ công", description = "1 đến 3 điểm với lý do quán đọc được; hết hạn sau 90 ngày như mọi điểm khác. Điểm của quán được tính lại ngay, nên có thể kích hoạt thông báo hạn chế.")
    @ApiError(status = 404, code = "VENDOR_NOT_FOUND", when = "Không có quán với id này.")
    @PostMapping("/shops/{id}/penalties")
    @PreAuthorize("hasAuthority('shop-penalty:write')")
    PenaltyViews.History add(CurrentPrincipal principal, @PathVariable UUID id, @Valid @RequestBody PenaltyRequests.Add request) {
        return penalties.add(principal, id, request.points(), request.reason());
    }

    @Operation(operationId = "decideShopPenaltyAppeal", summary = "Quyết kháng nghị điểm phạt", description = "`ACCEPT` miễn điểm (và tính lại hậu quả ngay), `REJECT` giữ nguyên. Cả hai đều cần lý do và quán được báo. Mỗi điểm chỉ kháng nghị một lần.")
    @ApiError(status = 404, code = "PENALTY_NOT_FOUND", when = "Không có điểm phạt với id này.")
    @ApiError(status = 409, code = "APPEAL_NOT_PENDING", when = "Điểm này không có kháng nghị đang chờ.")
    @PostMapping("/shop-penalties/{id}/appeal-decision")
    @PreAuthorize("hasAuthority('shop-penalty:write')")
    PenaltyViews.History decide(CurrentPrincipal principal, @PathVariable UUID id, @Valid @RequestBody PenaltyRequests.AppealDecision request) {
        return penalties.decideAppeal(principal, id, request.decision(), request.reason());
    }
}
