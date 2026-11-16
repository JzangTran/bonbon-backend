package com.bonbon.backend.identity.authentication.controller;

import com.bonbon.backend.identity.sellerteam.service.TeamSessions;

import com.bonbon.backend.identity.authentication.dto.RefreshRequest;
import com.bonbon.backend.identity.authentication.dto.TokenResponse;
import com.bonbon.backend.identity.authentication.dto.UserSummary;
import com.bonbon.backend.identity.authentication.service.TokenService;
import jakarta.servlet.http.HttpServletRequest;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import com.bonbon.backend.common.openapi.ApiError;
import com.bonbon.backend.common.openapi.ApiTags;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;

@Tag(name = ApiTags.SESSION)
@RestController
@RequestMapping("/api/auth")
class TokenController {

    private final TokenService tokens;
    private final RefreshCookies cookies;
    private final TeamSessions teamSessions;

    TokenController(TokenService tokens, RefreshCookies cookies, TeamSessions teamSessions) {
        this.tokens = tokens;
        this.cookies = cookies;
        this.teamSessions = teamSessions;
    }

    /** Exchanges a refresh token for a new pair; the presented token is revoked (rotation). */
    @Operation(operationId = "refreshTokens", summary = "Làm mới token", description = "Đổi refresh token lấy cặp token mới; refresh token cũ bị thu hồi (xoay vòng). Dùng lại token cũ sẽ khoá cả chuỗi phiên. Ứng dụng điện thoại gửi `refreshToken` trong body; web không gửi gì: token nằm trong cookie HttpOnly và token mới cũng được đặt lại vào cookie. Kết quả kèm `user` để web dựng lại phiên sau khi tải lại trang.")
    @ApiError(status = 401, code = "INVALID_REFRESH_TOKEN", when = "Refresh token sai, hết hạn hoặc đã bị thu hồi; đăng nhập lại.")
    @PostMapping("/refresh")
    TokenResponse refresh(@RequestBody(required = false) RefreshRequest request, HttpServletRequest http) {
        String raw = cookies.pick(request == null ? null : request.refreshToken(), http);
        // main accounts and members keep their own sessions (seller-team)
        if (teamSessions.owns(raw)) {
            return teamSessions.refresh(raw);
        }
        TokenService.Refreshed r = tokens.refreshSession(raw);
        return TokenResponse.of(r.pair()).withUser(UserSummary.of(r.user(), r.pair().role()));
    }
}
