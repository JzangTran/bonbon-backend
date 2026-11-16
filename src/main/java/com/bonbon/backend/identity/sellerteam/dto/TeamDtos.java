package com.bonbon.backend.identity.sellerteam.dto;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

import com.bonbon.backend.common.validation.PersonName;
import com.bonbon.backend.common.validation.RealEmail;
import com.bonbon.backend.common.validation.VnPhone;
import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;

/** Seller team: main accounts, links to shops, roles and members (flows/seller-team). */
public final class TeamDtos {

    private TeamDtos() {
    }

    // --- sign-in and recovery

    @Schema(name = "TeamLogin", description = "`<tên>:main` cho tài khoản chính, `<tên>:<mã>` cho thành viên.")
    public record Login(@NotBlank @Size(max = 82) String login, @NotBlank @Size(max = 100) String password, String captchaToken) {
    }

    @Schema(name = "TeamEmail")
    public record Email(@NotBlank @RealEmail @Size(max = 254) String email) {
    }

    @Schema(name = "TeamRecover")
    public record Recover(@NotBlank @RealEmail String email, @NotBlank @Pattern(regexp = "\\d{6}", message = "Mã gồm 6 chữ số.") String code,
            @NotBlank @Size(min = 8, max = 100) String newPassword) {
    }

    @Schema(name = "TeamSwitchShop", description = "`vendorId` rỗng là về trang quản lý nhóm, không mở cửa hàng nào.")
    public record SwitchShop(UUID vendorId) {
    }

    // --- main account

    @Schema(name = "MainAccountRegister")
    public record Register(
            @Schema(description = "Tên hiển thị của tài khoản chính.") @NotBlank @Size(max = 100) String accountName,
            @Size(max = 60) String region,
            @NotBlank @RealEmail @Size(max = 254) String email,
            @VnPhone String phone,
            @Schema(description = "3–40 ký tự a-z, 0-9, dấu chấm, gạch dưới, gạch ngang. Không đổi được; hệ thống thêm `:main` khi đăng nhập.")
            @NotBlank @Pattern(regexp = "[a-z0-9._-]{3,40}", message = "Tên đăng nhập 3–40 ký tự, chỉ gồm chữ thường không dấu, số, . _ -") String username,
            @NotBlank @Size(min = 8, max = 100) String password,
            @NotBlank String confirmPassword,
            @Size(max = 60) String nickname,
            @NotBlank @Pattern(regexp = "\\d{6}", message = "Mã gồm 6 chữ số.") String code,
            @Schema(description = "Mật khẩu của tài khoản cửa hàng đang đăng nhập. Không cần nếu vừa đăng nhập trong 10 phút.") String currentPassword) {
    }

    @Schema(name = "MainAccount")
    public record MainAccount(UUID id, String login, String accountName, String region, String email, String phone, String nickname,
            Instant createdAt, List<Link> links) {
    }

    // --- links

    @Schema(name = "ShopLinkRequest", description = "Email đăng nhập của tài khoản cửa hàng muốn liên kết, kèm mã gửi tới email tài khoản chính.")
    public record LinkRequest(@NotBlank @RealEmail String shopEmail, @NotBlank @Pattern(regexp = "\\d{6}", message = "Mã gồm 6 chữ số.") String code) {
    }

    @Schema(name = "ShopLink", description = "`status`: PENDING_AUTHORIZATION, AUTHORIZED, DECLINED, CANCELLED, EXPIRED, UNLINKED.")
    public record Link(UUID id, UUID vendorId, String shopName, String status, Instant requestedAt, Instant expiresAt, Instant decidedAt,
            Instant endedAt) {
    }

    @Schema(name = "IncomingLinkRequest", description = "Một tài khoản chính xin quản lý cửa hàng này.")
    public record Incoming(UUID id, String mainLogin, String mainAccountName, String status, Instant requestedAt, Instant expiresAt,
            Instant decidedAt) {
    }

    @Schema(name = "AuthorizeLink", description = "Cần mật khẩu khi lần đăng nhập thật gần nhất đã hơn 10 phút.")
    public record Authorize(String password) {
    }

    // --- roles

    @Schema(name = "SellerRoleRequest")
    public record RoleRequest(@NotBlank @Size(max = 60) String name, @Size(max = 300) String description,
            @Schema(description = "Tập con của các quyền chọn được (`GET /api/seller-team/permissions`).") @NotNull List<String> permissions) {
    }

    @Schema(name = "SellerRole")
    public record Role(UUID id, String name, String description, List<String> permissions, int memberCount) {
    }

    @Schema(name = "SellerPermission")
    public record Permission(String code, String label, String group) {
    }

    // --- members

    @Schema(name = "MemberCreate", description = "Chọn đúng một trong `roleId` hoặc `permissions`; áp dụng cho mọi cửa hàng trong `vendorIds`.")
    public record MemberCreate(
            @NotBlank @PersonName @Size(max = 100) String memberName,
            @VnPhone String phone,
            @Schema(description = "2–40 ký tự a-z, 0-9, . _ -; không đổi được; `main` đã dành riêng.")
            @NotBlank @Pattern(regexp = "[a-z0-9._-]{2,40}", message = "Mã đăng nhập 2–40 ký tự, chỉ gồm chữ thường không dấu, số, . _ -") String loginCode,
            @NotBlank @Size(min = 8, max = 100) String password,
            @NotBlank String confirmPassword,
            @RealEmail @Size(max = 254) String email,
            UUID roleId,
            List<String> permissions,
            @NotEmpty List<UUID> vendorIds) {
    }

    @Schema(name = "MemberUpdate", description = "Chỉ gửi trường cần đổi. `status`: ACTIVE hoặc DISABLED.")
    public record MemberUpdate(@PersonName @Size(max = 100) String memberName, @VnPhone String phone, @RealEmail @Size(max = 254) String email,
            @Pattern(regexp = "ACTIVE|DISABLED", message = "Trạng thái không hợp lệ.") String status) {
    }

    @Schema(name = "MemberPassword")
    public record MemberPassword(@NotBlank @Size(min = 8, max = 100) String password, @NotBlank String confirmPassword) {
    }

    @Schema(name = "AssignmentRequest", description = "Chọn đúng một trong `roleId` hoặc `permissions`.")
    public record AssignmentRequest(UUID roleId, List<String> permissions) {
    }

    @Schema(name = "TeamMember")
    public record Member(UUID id, String login, String loginCode, String memberName, String phone, String email, String status, Instant createdAt,
            List<Assignment> shops) {
    }

    @Schema(name = "ShopAssignment", description = "`roleName` rỗng là bộ quyền chọn riêng.")
    public record Assignment(UUID vendorId, String shopName, UUID roleId, String roleName, List<String> permissions) {
    }

    @Schema(name = "MemberCreated", description = "`login` là tên đăng nhập đầy đủ để giao cho thành viên; mật khẩu không bao giờ được trả lại.")
    public record Created(Member member, String login) {
    }
}
