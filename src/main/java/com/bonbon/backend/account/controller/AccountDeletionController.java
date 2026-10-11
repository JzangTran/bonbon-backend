package com.bonbon.backend.account.controller;

import java.time.Instant;

import com.bonbon.backend.account.dto.DeletionDtos;
import com.bonbon.backend.account.service.AccountDeletionService;
import com.bonbon.backend.common.openapi.ApiError;
import com.bonbon.backend.common.openapi.ApiTags;
import com.bonbon.backend.common.security.CurrentPrincipal;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@Tag(name = ApiTags.ACCOUNT, description = "Thông tin cá nhân, mật khẩu, địa chỉ giao hàng và xoá tài khoản.")
@RestController
@RequestMapping("/api/me/account")
class AccountDeletionController {

    private final AccountDeletionService deletions;

    AccountDeletionController(AccountDeletionService deletions) {
        this.deletions = deletions;
    }

    @Operation(operationId = "checkAccountDeletion", summary = "Có xoá được tài khoản không", description = "Danh sách việc còn chặn: đơn đang làm, khiếu nại hoặc phiếu hỗ trợ chưa xong, cửa hàng chưa đóng. `link` cho biết nơi xử lý từng việc. Xoá áp dụng cho cả tài khoản (cả vai trò khách và người bán). Còn cho biết số ngày chờ và có cần mật khẩu không.")
    @ApiError(status = 403, code = "NOT_A_PERSON_ACCOUNT", when = "Tài khoản quản trị không tự xoá được.")
    @GetMapping("/deletion-check")
    DeletionDtos.Check check(CurrentPrincipal principal) {
        return deletions.check(principal);
    }

    @Operation(operationId = "deleteMyAccount", summary = "Xoá tài khoản", description = "Cần đăng nhập trong 10 phút gần nhất, gõ `DELETE` hoặc đúng email, và mật khẩu nếu tài khoản có mật khẩu. Mọi phiên đăng nhập và thiết bị nhận thông báo kết thúc ngay. Sau số ngày chờ (mặc định 7) tài khoản được ẩn danh tại chỗ: tên, email, số điện thoại, ảnh, mật khẩu, địa chỉ giao hàng, cách đăng nhập và tuỳ chọn thông báo bị xoá; đơn hàng, thanh toán, sổ cái, đánh giá và tin nhắn được giữ dưới tên \"Người dùng đã xóa\". Đăng nhập lại trong thời gian chờ sẽ huỷ việc xoá (phản hồi đăng nhập có `deletionCancelled`).")
    @ApiError(status = 403, code = "REAUTH_REQUIRED", when = "Đăng nhập đã quá 10 phút.")
    @ApiError(status = 400, code = "CONFIRMATION_MISMATCH", when = "Từ xác nhận không phải DELETE hoặc email của tài khoản.")
    @ApiError(status = 401, code = "INVALID_CREDENTIALS", when = "Mật khẩu không đúng.")
    @ApiError(status = 409, code = "DELETION_BLOCKED", when = "Còn việc chưa xong; chi tiết ở `blockers`.")
    @ApiError(status = 409, code = "ALREADY_PENDING", when = "Tài khoản đã đang chờ xoá.")
    @PostMapping("/delete")
    DeletionDtos.Scheduled delete(CurrentPrincipal principal, @AuthenticationPrincipal Jwt jwt, @Valid @RequestBody DeletionDtos.Delete request) {
        Object claim = jwt.getClaims().get(CurrentPrincipal.CLAIM_AUTH_TIME);
        return deletions.request(principal, claim instanceof Number n ? Instant.ofEpochSecond(n.longValue()) : null, request);
    }
}
