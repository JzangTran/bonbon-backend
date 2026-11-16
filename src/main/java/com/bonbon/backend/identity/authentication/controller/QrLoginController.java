package com.bonbon.backend.identity.authentication.controller;

import java.util.UUID;

import com.bonbon.backend.common.exception.BusinessException;
import com.bonbon.backend.common.openapi.ApiError;
import com.bonbon.backend.common.openapi.ApiTags;
import com.bonbon.backend.common.persistence.ActorType;
import com.bonbon.backend.common.security.CurrentPrincipal;
import com.bonbon.backend.common.web.ClientContext;
import com.bonbon.backend.identity.authentication.dto.LoginResponse;
import com.bonbon.backend.identity.authentication.dto.QrLoginDtos.Inspect;
import com.bonbon.backend.identity.authentication.dto.QrLoginDtos.Poll;
import com.bonbon.backend.identity.authentication.dto.QrLoginDtos.Scan;
import com.bonbon.backend.identity.authentication.dto.QrLoginDtos.Started;
import com.bonbon.backend.identity.authentication.dto.QrLoginDtos.Status;
import com.bonbon.backend.identity.authentication.service.QrLoginService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.Valid;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

@Tag(name = ApiTags.LOGIN)
@RestController
@RequestMapping("/api/auth/qr-login")
class QrLoginController {

    private final QrLoginService qr;

    QrLoginController(QrLoginService qr) {
        this.qr = qr;
    }

    @Operation(operationId = "startQrLogin", summary = "Tạo mã QR đăng nhập (web)",
            description = "Trang đăng nhập của người bán gọi để có mã QR, hiệu lực 3 phút. Vẽ `qrContent` thành mã QR, giữ `pollToken` trong trình duyệt "
                    + "rồi hỏi `status` khoảng 2 giây một lần.")
    @ApiError(status = 429, code = "RATE_LIMITED", when = "Một địa chỉ IP tạo quá 10 mã trong một phút.")
    @PostMapping("/start")
    Started start(HttpServletRequest http) {
        return qr.start(http.getHeader(HttpHeaders.USER_AGENT), ClientContext.from(http).ip());
    }

    @Operation(operationId = "qrLoginStatus", summary = "Trạng thái mã QR đăng nhập (web)",
            description = "`EXPIRED` cả khi hết hạn lẫn khi `pollToken` sai, để không ai dò được phiên của người khác.")
    @PostMapping("/status")
    Status status(@Valid @RequestBody Poll request) {
        return new Status(qr.status(request.sessionId(), request.pollToken()));
    }

    @Operation(operationId = "scanQrLogin", summary = "Quét mã QR đăng nhập (điện thoại)",
            description = "Ứng dụng di động đã đăng nhập gửi mã phiên đọc từ mã QR. Không đăng nhập ngay: trả về trình duyệt và IP đang chờ để người bán đối chiếu, "
                    + "sau đó gọi `confirm` hoặc `reject`.")
    @ApiError(status = 401, code = "UNAUTHENTICATED", when = "Cần đăng nhập trên điện thoại.")
    @ApiError(status = 403, code = "NO_SELLER_ACCESS", when = "Tài khoản không có vai trò người bán.")
    @ApiError(status = 409, code = "QR_ALREADY_SCANNED", when = "Mã đã được quét bằng tài khoản khác.")
    @ApiError(status = 410, code = "QR_EXPIRED", when = "Mã hết hạn, đã bị từ chối hoặc đã được dùng.")
    @PostMapping("/scan")
    Inspect scan(@Valid @RequestBody Scan request, @AuthenticationPrincipal Jwt jwt) {
        return qr.scan(userId(jwt), request.sessionId());
    }

    @Operation(operationId = "confirmQrLogin", summary = "Xác nhận đăng nhập web (điện thoại)",
            description = "Chỉ tài khoản đã quét mới xác nhận được. Trình duyệt sẽ thấy `CONFIRMED` và lấy token bằng `exchange`.")
    @ApiError(status = 401, code = "UNAUTHENTICATED", when = "Cần đăng nhập trên điện thoại.")
    @ApiError(status = 403, code = "NO_SELLER_ACCESS", when = "Tài khoản không còn vai trò người bán.")
    @ApiError(status = 409, code = "QR_ALREADY_SCANNED", when = "Mã đã được quét bằng tài khoản khác.")
    @ApiError(status = 410, code = "QR_EXPIRED", when = "Mã hết hạn hoặc chưa được quét.")
    @PostMapping("/confirm")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    void confirm(@Valid @RequestBody Scan request, @AuthenticationPrincipal Jwt jwt) {
        qr.confirm(userId(jwt), request.sessionId());
    }

    @Operation(operationId = "rejectQrLogin", summary = "Từ chối đăng nhập web (điện thoại)",
            description = "Người bán không nhận ra trình duyệt đang chờ. Trình duyệt sẽ thấy `REJECTED`.")
    @ApiError(status = 401, code = "UNAUTHENTICATED", when = "Cần đăng nhập trên điện thoại.")
    @ApiError(status = 409, code = "QR_ALREADY_SCANNED", when = "Mã đã được quét bằng tài khoản khác.")
    @ApiError(status = 410, code = "QR_EXPIRED", when = "Mã hết hạn hoặc chưa được quét.")
    @PostMapping("/reject")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    void reject(@Valid @RequestBody Scan request, @AuthenticationPrincipal Jwt jwt) {
        qr.reject(userId(jwt), request.sessionId());
    }

    @Operation(operationId = "exchangeQrLogin", summary = "Lấy token sau khi điện thoại xác nhận (web)",
            description = "Dùng được một lần. Phiên đăng nhập luôn ở vai trò người bán; refresh token nằm trong cookie như đăng nhập thường.")
    @ApiError(status = 409, code = "QR_NOT_CONFIRMED", when = "Điện thoại chưa xác nhận.")
    @ApiError(status = 410, code = "QR_EXPIRED", when = "Mã hết hạn, sai `pollToken` hoặc đã được dùng.")
    @PostMapping("/exchange")
    LoginResponse exchange(@Valid @RequestBody Poll request) {
        return qr.exchange(request.sessionId(), request.pollToken());
    }

    /** The /api/auth/** paths are public, so a signed-in caller is checked here. */
    private static UUID userId(Jwt jwt) {
        if (jwt == null) {
            throw new BusinessException(HttpStatus.UNAUTHORIZED, "UNAUTHENTICATED", "Vui lòng đăng nhập.");
        }
        CurrentPrincipal principal = CurrentPrincipal.from(jwt);
        // A customer or shop-account session of the same identity; the seller role itself is checked against the database.
        if (principal.actorType() != ActorType.CUSTOMER && principal.actorType() != ActorType.SHOP_ACCOUNT) {
            throw new BusinessException(HttpStatus.UNAUTHORIZED, "UNAUTHENTICATED", "Vui lòng đăng nhập bằng tài khoản người bán.");
        }
        return principal.id();
    }
}
