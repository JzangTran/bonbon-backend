package com.bonbon.backend.identity.sellerteam.controller;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

import com.bonbon.backend.common.openapi.ApiError;
import com.bonbon.backend.common.openapi.ApiTags;
import com.bonbon.backend.common.web.ClientContext;
import com.bonbon.backend.common.security.CurrentPrincipal;
import com.bonbon.backend.identity.authentication.dto.LoginResponse;
import com.bonbon.backend.identity.authentication.dto.TokenResponse;
import com.bonbon.backend.identity.sellerteam.dto.TeamDtos;
import com.bonbon.backend.identity.sellerteam.service.TeamAccountService;
import com.bonbon.backend.identity.sellerteam.service.TeamLinkService;
import com.bonbon.backend.identity.sellerteam.service.TeamMemberService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.Valid;
import org.springframework.http.HttpStatus;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

/** Seller team: sign-in of main accounts and members, the shop switcher, links, roles and members (flows/seller-team). */
@Tag(name = ApiTags.SELLER_TEAM)
@RestController
@RequestMapping("/api/seller-team")
class SellerTeamController {

    private final TeamAccountService accounts;
    private final TeamLinkService links;
    private final TeamMemberService members;

    SellerTeamController(TeamAccountService accounts, TeamLinkService links, TeamMemberService members) {
        this.accounts = accounts;
        this.links = links;
        this.members = members;
    }

    // --- sign-in

    @Operation(operationId = "teamLogin", summary = "Đăng nhập tài khoản chính hoặc thành viên",
            description = "`<tên>:main` là tài khoản chính, `<tên>:<mã>` là thành viên. Tài khoản chính vào trang quản lý nhóm; thành viên vào cửa hàng đầu tiên được giao. `user.team` có danh sách cửa hàng để đổi. Sai nhiều lần thì cần `captchaToken`.")
    @ApiError(status = 401, code = "INVALID_CREDENTIALS", when = "Tên đăng nhập hoặc mật khẩu không đúng (không nói cái nào sai).")
    @ApiError(status = 403, code = "MEMBER_DISABLED", when = "Thành viên đang bị khoá.")
    @ApiError(status = 400, code = "CAPTCHA_REQUIRED", when = "Sai nhiều lần liên tiếp: gửi lại kèm `captchaToken`.")
    @PostMapping("/login")
    LoginResponse login(@Valid @RequestBody TeamDtos.Login request, HttpServletRequest http) {
        TokenResponse t = accounts.login(request, ClientContext.from(http).ip());
        return LoginResponse.signedIn(t, t.user());
    }

    @Operation(operationId = "switchTeamShop", summary = "Đổi cửa hàng đang làm việc",
            description = "Cấp cặp token mới cho cửa hàng đã chọn. Tài khoản chính gửi `vendorId` rỗng để về trang quản lý nhóm.")
    @ApiError(status = 403, code = "SHOP_NOT_ASSIGNED", when = "Cửa hàng không liên kết hoặc không được giao.")
    @PostMapping("/switch-shop")
    @PreAuthorize("isAuthenticated()")
    TokenResponse switchShop(CurrentPrincipal principal, @AuthenticationPrincipal Jwt jwt, @RequestBody TeamDtos.SwitchShop request) {
        return accounts.switchShop(principal, authTime(jwt), request.vendorId());
    }

    @Operation(operationId = "teamSession", summary = "Phiên nhóm hiện tại", description = "Cấp lại token với quyền và danh sách cửa hàng mới nhất.")
    @PostMapping("/session")
    @PreAuthorize("isAuthenticated()")
    TokenResponse session(CurrentPrincipal principal, @AuthenticationPrincipal Jwt jwt) {
        return accounts.current(principal, authTime(jwt));
    }

    @Operation(operationId = "sendTeamRecoverCode", summary = "Gửi mã đặt lại mật khẩu tài khoản chính",
            description = "Luôn trả 202, không cho biết email có gắn với tài khoản chính hay không.")
    @PostMapping("/recover/code")
    @ResponseStatus(HttpStatus.ACCEPTED)
    void recoverCode(@Valid @RequestBody TeamDtos.Email request) {
        accounts.sendRecoverCode(request.email());
    }

    @Operation(operationId = "recoverMainAccount", summary = "Đặt lại mật khẩu tài khoản chính", description = "Mọi phiên cũ của tài khoản chính bị đăng xuất.")
    @ApiError(status = 400, code = "CODE_INVALID", when = "Mã sai.")
    @ApiError(status = 400, code = "CODE_EXPIRED", when = "Mã hết hạn hoặc sai quá 5 lần.")
    @PostMapping("/recover")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    void recover(@Valid @RequestBody TeamDtos.Recover request) {
        accounts.recover(request);
    }

    // --- registering, from a shop account

    @Operation(operationId = "sendMainAccountCode", summary = "Gửi mã xác nhận email tài khoản chính", description = "Từ tài khoản cửa hàng. Một phút một lần.")
    @ApiError(status = 409, code = "ALREADY_HAS_MAIN_ACCOUNT", when = "Tài khoản cửa hàng này đã tạo tài khoản chính.")
    @ApiError(status = 409, code = "EMAIL_TAKEN", when = "Email đã dùng cho tài khoản chính khác.")
    @ApiError(status = 429, code = "CODE_SENT_RECENTLY", when = "Vừa gửi mã trong một phút qua.")
    @PostMapping("/main-accounts/code")
    @PreAuthorize("hasAuthority('vendor:create')")
    @ResponseStatus(HttpStatus.ACCEPTED)
    void registerCode(CurrentPrincipal principal, @Valid @RequestBody TeamDtos.Email request) {
        accounts.sendRegisterCode(principal, request.email());
    }

    @Operation(operationId = "registerMainAccount", summary = "Tạo tài khoản chính",
            description = "Từ tài khoản cửa hàng: cần mã gửi tới email và mật khẩu tài khoản cửa hàng (bỏ qua nếu vừa đăng nhập trong 10 phút). Cửa hàng của tài khoản này được liên kết luôn.")
    @ApiError(status = 403, code = "REAUTH_REQUIRED", when = "Cần nhập lại mật khẩu tài khoản cửa hàng.")
    @ApiError(status = 401, code = "INVALID_CREDENTIALS", when = "Mật khẩu tài khoản cửa hàng không đúng.")
    @ApiError(status = 409, code = "USERNAME_TAKEN", when = "Tên đăng nhập đã có người dùng.")
    @ApiError(status = 400, code = "CODE_INVALID", when = "Mã xác nhận sai.")
    @PostMapping("/main-accounts")
    @PreAuthorize("hasAuthority('vendor:create')")
    @ResponseStatus(HttpStatus.CREATED)
    TeamDtos.MainAccount register(CurrentPrincipal principal, @AuthenticationPrincipal Jwt jwt, @Valid @RequestBody TeamDtos.Register request) {
        return accounts.register(principal, authTime(jwt), request);
    }

    // --- the main account

    @Operation(operationId = "getMainAccount", summary = "Tài khoản chính và các liên kết", description = "Kể cả yêu cầu đang chờ, bị từ chối, đã huỷ.")
    @GetMapping("/me")
    @PreAuthorize("hasAuthority('team:manage')")
    TeamDtos.MainAccount me(CurrentPrincipal principal) {
        return links.mainAccount(principal);
    }

    @Operation(operationId = "sendLinkCode", summary = "Gửi mã để liên kết cửa hàng", description = "Mã gửi tới email của tài khoản chính.")
    @PostMapping("/links/code")
    @PreAuthorize("hasAuthority('team:manage')")
    @ResponseStatus(HttpStatus.ACCEPTED)
    void linkCode(CurrentPrincipal principal) {
        links.sendLinkCode(principal);
    }

    @Operation(operationId = "requestShopLink", summary = "Xin liên kết một cửa hàng",
            description = "Nhập email đăng nhập của tài khoản cửa hàng. Yêu cầu chờ chủ cửa hàng đồng ý trong 7 ngày; trước đó tài khoản chính chưa làm được gì.")
    @ApiError(status = 404, code = "SHOP_NOT_FOUND", when = "Không có cửa hàng đang hoạt động với email này.")
    @ApiError(status = 409, code = "SHOP_ALREADY_LINKED", when = "Cửa hàng đã liên kết hoặc đang chờ tài khoản chính khác.")
    @ApiError(status = 400, code = "CODE_INVALID", when = "Mã xác nhận sai.")
    @PostMapping("/links")
    @PreAuthorize("hasAuthority('team:manage')")
    @ResponseStatus(HttpStatus.CREATED)
    TeamDtos.Link requestLink(CurrentPrincipal principal, @Valid @RequestBody TeamDtos.LinkRequest request) {
        return links.request(principal, request);
    }

    @Operation(operationId = "endShopLink", summary = "Huỷ yêu cầu hoặc rời cửa hàng", description = "Yêu cầu đang chờ thì huỷ; liên kết đang chạy thì kết thúc, thành viên ở cửa hàng đó mất quyền ngay.")
    @ApiError(status = 404, code = "LINK_NOT_FOUND", when = "Không có liên kết này.")
    @ApiError(status = 409, code = "LINK_ALREADY_ENDED", when = "Liên kết đã kết thúc.")
    @DeleteMapping("/links/{id}")
    @PreAuthorize("hasAuthority('team:manage')")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    void endLink(CurrentPrincipal principal, @PathVariable UUID id) {
        links.endFromMain(principal, id);
    }

    @Operation(operationId = "listTeamPermissions", summary = "Các quyền gán được cho thành viên", description = "Không gồm quản lý nhóm và mở cửa hàng mới.")
    @GetMapping("/permissions")
    @PreAuthorize("hasAuthority('team:manage')")
    List<TeamDtos.Permission> permissions() {
        return members.permissions();
    }

    @Operation(operationId = "listSellerRoles", summary = "Vai trò của nhóm", description = "Chỉ vai trò có tên; bộ quyền chọn riêng khi giao việc không hiện ở đây.")
    @GetMapping("/roles")
    @PreAuthorize("hasAuthority('team:manage')")
    List<TeamDtos.Role> roles(CurrentPrincipal principal) {
        return members.roles(principal);
    }

    @Operation(operationId = "createSellerRole", summary = "Tạo vai trò", description = "Không quyền nào được chọn sẵn.")
    @ApiError(status = 409, code = "ROLE_NAME_TAKEN", when = "Đã có vai trò cùng tên.")
    @ApiError(status = 400, code = "VALIDATION_ERROR", when = "Có quyền không chọn được.")
    @PostMapping("/roles")
    @PreAuthorize("hasAuthority('team:manage')")
    @ResponseStatus(HttpStatus.CREATED)
    TeamDtos.Role createRole(CurrentPrincipal principal, @Valid @RequestBody TeamDtos.RoleRequest request) {
        return members.createRole(principal, request);
    }

    @Operation(operationId = "updateSellerRole", summary = "Sửa vai trò", description = "Bớt quyền thì thành viên đang dùng vai trò này mất quyền đó ngay.")
    @ApiError(status = 404, code = "ROLE_NOT_FOUND", when = "Không có vai trò này.")
    @PutMapping("/roles/{id}")
    @PreAuthorize("hasAuthority('team:manage')")
    TeamDtos.Role updateRole(CurrentPrincipal principal, @PathVariable UUID id, @Valid @RequestBody TeamDtos.RoleRequest request) {
        return members.updateRole(principal, id, request);
    }

    @Operation(operationId = "deleteSellerRole", summary = "Xoá vai trò", description = "Chỉ xoá được khi không còn ai dùng.")
    @ApiError(status = 409, code = "ROLE_IN_USE", when = "Vai trò đang được gán.")
    @DeleteMapping("/roles/{id}")
    @PreAuthorize("hasAuthority('team:manage')")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    void deleteRole(CurrentPrincipal principal, @PathVariable UUID id) {
        members.deleteRole(principal, id);
    }

    @Operation(operationId = "listTeamMembers", summary = "Thành viên", description = "Kèm cửa hàng và vai trò ở từng cửa hàng.")
    @GetMapping("/members")
    @PreAuthorize("hasAuthority('team:manage')")
    List<TeamDtos.Member> members(CurrentPrincipal principal) {
        return members.members(principal);
    }

    @Operation(operationId = "createTeamMember", summary = "Tạo thành viên",
            description = "Một vai trò (hoặc bộ quyền chọn riêng) áp dụng cho mọi cửa hàng đã chọn; sau đó đổi được từng cửa hàng. Tối đa 50 thành viên.")
    @ApiError(status = 409, code = "LOGIN_CODE_TAKEN", when = "Mã đăng nhập đã dùng trong nhóm.")
    @ApiError(status = 409, code = "TOO_MANY_MEMBERS", when = "Đã đủ 50 thành viên.")
    @ApiError(status = 400, code = "VALIDATION_ERROR", when = "Mật khẩu yếu hoặc không khớp, cửa hàng chưa liên kết, chọn cả vai trò lẫn quyền.")
    @PostMapping("/members")
    @PreAuthorize("hasAuthority('team:manage')")
    @ResponseStatus(HttpStatus.CREATED)
    TeamDtos.Created createMember(CurrentPrincipal principal, @Valid @RequestBody TeamDtos.MemberCreate request) {
        return members.create(principal, request);
    }

    @Operation(operationId = "updateTeamMember", summary = "Sửa hoặc khoá/mở thành viên", description = "Khoá thì mọi phiên của thành viên bị đăng xuất ngay.")
    @ApiError(status = 404, code = "MEMBER_NOT_FOUND", when = "Không có thành viên này.")
    @PatchMapping("/members/{id}")
    @PreAuthorize("hasAuthority('team:manage')")
    TeamDtos.Member updateMember(CurrentPrincipal principal, @PathVariable UUID id, @Valid @RequestBody TeamDtos.MemberUpdate request) {
        return members.update(principal, id, request);
    }

    @Operation(operationId = "resetTeamMemberPassword", summary = "Đặt lại mật khẩu thành viên", description = "Thành viên bị đăng xuất khỏi mọi thiết bị.")
    @PostMapping("/members/{id}/password")
    @PreAuthorize("hasAuthority('team:manage')")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    void resetPassword(CurrentPrincipal principal, @PathVariable UUID id, @Valid @RequestBody TeamDtos.MemberPassword request) {
        members.resetPassword(principal, id, request);
    }

    @Operation(operationId = "assignTeamMemberShop", summary = "Giao hoặc đổi vai trò ở một cửa hàng", description = "Mỗi cửa hàng một vai trò; giao lại là đổi.")
    @ApiError(status = 404, code = "SHOP_NOT_LINKED", when = "Cửa hàng chưa liên kết với tài khoản chính.")
    @PutMapping("/members/{id}/shops/{vendorId}")
    @PreAuthorize("hasAuthority('team:manage')")
    TeamDtos.Member assign(CurrentPrincipal principal, @PathVariable UUID id, @PathVariable UUID vendorId,
            @RequestBody TeamDtos.AssignmentRequest request) {
        return members.assign(principal, id, vendorId, request);
    }

    @Operation(operationId = "unassignTeamMemberShop", summary = "Bỏ thành viên khỏi một cửa hàng", description = "Giữ lại lịch sử; các cửa hàng khác không đổi.")
    @ApiError(status = 404, code = "ASSIGNMENT_NOT_FOUND", when = "Thành viên không làm ở cửa hàng này.")
    @DeleteMapping("/members/{id}/shops/{vendorId}")
    @PreAuthorize("hasAuthority('team:manage')")
    TeamDtos.Member unassign(CurrentPrincipal principal, @PathVariable UUID id, @PathVariable UUID vendorId) {
        return members.unassign(principal, id, vendorId);
    }

    static Instant authTime(Jwt jwt) {
        return jwt == null ? null : jwt.getClaimAsInstant(CurrentPrincipal.CLAIM_AUTH_TIME);
    }
}
