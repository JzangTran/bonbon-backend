package com.bonbon.backend.identity.sellerteam.controller;

import java.util.List;
import java.util.UUID;

import com.bonbon.backend.common.openapi.ApiError;
import com.bonbon.backend.common.openapi.ApiTags;
import com.bonbon.backend.common.security.CurrentPrincipal;
import com.bonbon.backend.identity.sellerteam.dto.TeamDtos;
import com.bonbon.backend.identity.sellerteam.service.TeamAccountService;
import com.bonbon.backend.identity.sellerteam.service.TeamLinkService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import org.springframework.http.HttpStatus;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

/** The shop account's side of linking: who asks to manage this shop, authorize, decline, end (link-shop.md). Only the shop account itself. */
@Tag(name = ApiTags.SELLER_TEAM)
@RestController
@RequestMapping("/api/merchant/link-requests")
@PreAuthorize("hasAuthority('vendor:create')")
class ShopLinkController {

    private final TeamLinkService links;
    private final TeamAccountService accounts;

    ShopLinkController(TeamLinkService links, TeamAccountService accounts) {
        this.links = links;
        this.accounts = accounts;
    }

    @Operation(operationId = "listIncomingLinks", summary = "Tài khoản chính xin quản lý cửa hàng", description = "Mới nhất trước, gồm cả đã đồng ý, từ chối, hết hạn.")
    @GetMapping
    List<TeamDtos.Incoming> incoming(CurrentPrincipal principal) {
        return links.incoming(principal.id());
    }

    @Operation(operationId = "getOwnMainAccount", summary = "Tài khoản chính do tài khoản cửa hàng này tạo")
    @ApiError(status = 404, code = "MAIN_ACCOUNT_NOT_FOUND", when = "Chưa tạo tài khoản chính.")
    @GetMapping("/main-account")
    TeamDtos.MainAccount ownMainAccount(CurrentPrincipal principal) {
        return accounts.mineAsShop(principal.id());
    }

    @Operation(operationId = "authorizeLink", summary = "Đồng ý cho tài khoản chính quản lý cửa hàng",
            description = "Nếu lần đăng nhập thật gần nhất đã hơn 10 phút thì cần gửi kèm mật khẩu.")
    @ApiError(status = 403, code = "REAUTH_REQUIRED", when = "Cần nhập lại mật khẩu.")
    @ApiError(status = 401, code = "INVALID_CREDENTIALS", when = "Mật khẩu không đúng.")
    @ApiError(status = 409, code = "LINK_NOT_PENDING", when = "Yêu cầu không còn chờ.")
    @PostMapping("/{id}/authorize")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    void authorize(CurrentPrincipal principal, @AuthenticationPrincipal Jwt jwt, @PathVariable UUID id,
            @RequestBody(required = false) TeamDtos.Authorize request) {
        links.authorize(principal, SellerTeamController.authTime(jwt), id, request == null ? null : request.password());
    }

    @Operation(operationId = "declineLink", summary = "Từ chối yêu cầu liên kết")
    @ApiError(status = 409, code = "LINK_NOT_PENDING", when = "Yêu cầu không còn chờ.")
    @PostMapping("/{id}/decline")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    void decline(CurrentPrincipal principal, @PathVariable UUID id) {
        links.decline(principal, id);
    }

    @Operation(operationId = "endLinkFromShop", summary = "Gỡ tài khoản chính khỏi cửa hàng", description = "Có hiệu lực ngay với tài khoản chính và các thành viên.")
    @ApiError(status = 409, code = "LINK_ALREADY_ENDED", when = "Liên kết đã kết thúc.")
    @DeleteMapping("/{id}")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    void end(CurrentPrincipal principal, @PathVariable UUID id) {
        links.endFromShop(principal, id);
    }
}
