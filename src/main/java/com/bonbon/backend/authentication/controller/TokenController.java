package com.bonbon.backend.authentication.controller;

import com.bonbon.backend.authentication.dto.RefreshRequest;
import com.bonbon.backend.authentication.dto.TokenResponse;
import com.bonbon.backend.authentication.service.TokenService;
import jakarta.validation.Valid;
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

    TokenController(TokenService tokens) {
        this.tokens = tokens;
    }

    /** Exchanges a refresh token for a new pair; the presented token is revoked (rotation). */
    @Operation(operationId = "refreshTokens", summary = "Làm mới token", description = "Đổi refresh token lấy cặp token mới; refresh token cũ bị thu hồi (xoay vòng). Dùng lại token cũ sẽ khoá cả chuỗi phiên.")
    @ApiError(status = 401, code = "INVALID_REFRESH_TOKEN", when = "Refresh token sai, hết hạn hoặc đã bị thu hồi; đăng nhập lại.")
    @PostMapping("/refresh")
    TokenResponse refresh(@Valid @RequestBody RefreshRequest request) {
        return TokenResponse.of(tokens.refresh(request.refreshToken()));
    }
}
