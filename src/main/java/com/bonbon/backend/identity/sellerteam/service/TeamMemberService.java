package com.bonbon.backend.identity.sellerteam.service;

import java.sql.Array;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

import com.bonbon.backend.common.exception.BusinessException;
import com.bonbon.backend.common.security.CurrentPrincipal;
import com.bonbon.backend.common.validation.PasswordPolicy;
import com.bonbon.backend.identity.sellerteam.TeamShops;
import com.bonbon.backend.identity.sellerteam.dto.TeamDtos;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Roles and members of one main account (create-member.md, create-seller-role.md, manage-shop-assignment.md). A member has one role per
 * shop; a set of permissions ticked on the spot is kept as an unnamed role so every assignment points at a role. Anything that shrinks
 * a member's access (smaller role, shop removed, disabled, new password) takes effect on its next request.
 */
@Service
public class TeamMemberService {

    static final int MAX_MEMBERS = 50;
    static final int MAX_ROLES = 30;
    private static final Map<String, String[]> LABELS = new LinkedHashMap<>();

    static {
        LABELS.put("order:read", new String[] {"Xem đơn hàng", "Đơn hàng"});
        LABELS.put("order:write", new String[] {"Xác nhận và cập nhật đơn", "Đơn hàng"});
        LABELS.put("vendor:read", new String[] {"Xem thông tin cửa hàng, thực đơn", "Cửa hàng"});
        LABELS.put("vendor:write", new String[] {"Sửa cửa hàng, thực đơn, giờ mở cửa", "Cửa hàng"});
        LABELS.put("voucher:read", new String[] {"Xem voucher", "Khuyến mãi"});
        LABELS.put("voucher:write", new String[] {"Tạo và sửa voucher", "Khuyến mãi"});
        LABELS.put("stats:read", new String[] {"Xem thống kê bán hàng", "Báo cáo"});
        LABELS.put("earnings:read", new String[] {"Xem thu nhập, sổ cái, đối soát", "Báo cáo"});
        LABELS.put("conversation:read", new String[] {"Đọc tin nhắn với khách", "Tin nhắn"});
        LABELS.put("conversation:write", new String[] {"Trả lời tin nhắn khách", "Tin nhắn"});
        LABELS.put("review:respond", new String[] {"Phản hồi đánh giá", "Đánh giá"});
    }

    private final JdbcClient jdbc;
    private final PasswordEncoder passwords;
    private final TeamSessions sessions;
    private final TeamShops shops;

    TeamMemberService(JdbcClient jdbc, PasswordEncoder passwords, TeamSessions sessions, TeamShops shops) {
        this.jdbc = jdbc;
        this.passwords = passwords;
        this.sessions = sessions;
        this.shops = shops;
    }

    public List<TeamDtos.Permission> permissions() {
        return LABELS.entrySet().stream().map(e -> new TeamDtos.Permission(e.getKey(), e.getValue()[0], e.getValue()[1])).toList();
    }

    // --- roles

    @Transactional(readOnly = true)
    public List<TeamDtos.Role> roles(CurrentPrincipal main) {
        return jdbc.sql("""
                select r.id, r.name, r.description, r.permissions,
                       (select count(distinct s.member_id) from identity.shop_assignments s where s.role_id = r.id and s.removed_at is null) as members
                from identity.seller_roles r where r.main_account_id = :m and r.is_template order by lower(r.name)""")
                .param("m", main.teamId())
                .query((rs, n) -> new TeamDtos.Role(rs.getObject(1, UUID.class), rs.getString(2), rs.getString(3), list(rs.getArray(4)), rs.getInt(5)))
                .list();
    }

    @Transactional
    public TeamDtos.Role createRole(CurrentPrincipal main, TeamDtos.RoleRequest r) {
        if (jdbc.sql("select count(*) from identity.seller_roles where main_account_id = :m and is_template").param("m", main.teamId())
                .query(Long.class).single() >= MAX_ROLES) {
            throw BusinessException.conflict("TOO_MANY_ROLES", "Tối đa " + MAX_ROLES + " vai trò.");
        }
        uniqueName(main.teamId(), r.name(), null);
        UUID id = insertRole(main.teamId(), r.name().strip(), blank(r.description()), checked(r.permissions()), true);
        return role(main, id);
    }

    @Transactional
    public TeamDtos.Role updateRole(CurrentPrincipal main, UUID id, TeamDtos.RoleRequest r) {
        List<String> before = ownRole(main, id);
        uniqueName(main.teamId(), r.name(), id);
        List<String> after = checked(r.permissions());
        jdbc.sql("update identity.seller_roles set name = :n, description = :d, permissions = :p, updated_at = now() where id = :id")
                .param("n", r.name().strip()).param("d", blank(r.description())).param("p", after.toArray(String[]::new)).param("id", id).update();
        if (!after.containsAll(before)) {
            membersOnRole(id).forEach(m -> sessions.reduce("MEMBER", m));
        }
        return role(main, id);
    }

    @Transactional
    public void deleteRole(CurrentPrincipal main, UUID id) {
        ownRole(main, id);
        if (!membersOnRole(id).isEmpty()) {
            throw BusinessException.conflict("ROLE_IN_USE", "Vai trò đang được gán cho thành viên. Hãy đổi vai trò của họ trước.");
        }
        jdbc.sql("delete from identity.shop_assignments where role_id = :id").param("id", id).update();
        jdbc.sql("delete from identity.seller_roles where id = :id").param("id", id).update();
    }

    // --- members

    @Transactional(readOnly = true)
    public List<TeamDtos.Member> members(CurrentPrincipal main) {
        String username = username(main.teamId());
        List<TeamDtos.Member> raw = jdbc.sql("select * from identity.team_members where main_account_id = :m order by created_at").param("m", main.teamId())
                .query((rs, n) -> new TeamDtos.Member(rs.getObject("id", UUID.class), username + ":" + rs.getString("login_code"), rs.getString("login_code"),
                        rs.getString("member_name"), rs.getString("phone"), rs.getString("email"), rs.getString("status"),
                        rs.getTimestamp("created_at").toInstant(), List.of()))
                .list();
        Map<UUID, List<TeamDtos.Assignment>> byMember = assignments(main.teamId());
        return raw.stream().map(m -> withShops(m, byMember.getOrDefault(m.id(), List.of()))).toList();
    }

    @Transactional
    public TeamDtos.Created create(CurrentPrincipal main, TeamDtos.MemberCreate r) {
        if (jdbc.sql("select count(*) from identity.team_members where main_account_id = :m").param("m", main.teamId()).query(Long.class).single() >= MAX_MEMBERS) {
            throw BusinessException.conflict("TOO_MANY_MEMBERS", "Mỗi tài khoản chính có tối đa " + MAX_MEMBERS + " thành viên.");
        }
        String code = r.loginCode().strip().toLowerCase();
        if (code.equals("main")) {
            throw TeamAccountService.fieldError("loginCode", "Mã \"main\" dành riêng cho tài khoản chính.");
        }
        if (!r.password().equals(r.confirmPassword())) {
            throw TeamAccountService.fieldError("confirmPassword", "Mật khẩu nhập lại không khớp.");
        }
        PasswordPolicy.problem(r.password(), r.email(), r.memberName()).ifPresent(p -> {
            throw TeamAccountService.fieldError("password", p);
        });
        if (jdbc.sql("select count(*) from identity.team_members where main_account_id = :m and login_code = :c").param("m", main.teamId()).param("c", code)
                .query(Long.class).single() > 0) {
            throw BusinessException.conflict("LOGIN_CODE_TAKEN", "Mã đăng nhập này đã dùng cho một thành viên khác.");
        }
        Set<UUID> linked = linkedShops(main.teamId());
        if (!linked.containsAll(r.vendorIds())) {
            throw TeamAccountService.fieldError("vendorIds", "Chỉ chọn được cửa hàng đã liên kết với tài khoản chính.");
        }
        UUID role = roleFor(main, r.roleId(), r.permissions());
        UUID id = UUID.randomUUID();
        jdbc.sql("""
                insert into identity.team_members (id, main_account_id, login_code, member_name, phone, email, password_hash)
                values (:id, :m, :c, :n, :p, :e, :h)""")
                .param("id", id).param("m", main.teamId()).param("c", code).param("n", r.memberName().strip()).param("p", blank(r.phone()))
                .param("e", blank(r.email())).param("h", passwords.encode(r.password())).update();
        for (UUID vendor : new HashSet<>(r.vendorIds())) {
            jdbc.sql("insert into identity.shop_assignments (member_id, vendor_id, role_id) values (:m, :v, :r)")
                    .param("m", id).param("v", vendor).param("r", role).update();
        }
        TeamDtos.Member member = member(main, id);
        return new TeamDtos.Created(member, member.login());
    }

    @Transactional
    public TeamDtos.Member update(CurrentPrincipal main, UUID id, TeamDtos.MemberUpdate r) {
        ownMember(main, id);
        if (r.memberName() != null) {
            jdbc.sql("update identity.team_members set member_name = :v where id = :id").param("v", r.memberName().strip()).param("id", id).update();
        }
        if (r.phone() != null) {
            jdbc.sql("update identity.team_members set phone = :v where id = :id").param("v", blank(r.phone())).param("id", id).update();
        }
        if (r.email() != null) {
            jdbc.sql("update identity.team_members set email = :v where id = :id").param("v", blank(r.email())).param("id", id).update();
        }
        if (r.status() != null) {
            jdbc.sql("update identity.team_members set status = :v where id = :id").param("v", r.status()).param("id", id).update();
            if (r.status().equals("DISABLED")) {
                sessions.invalidate("MEMBER", id);
            }
        }
        return member(main, id);
    }

    @Transactional
    public void resetPassword(CurrentPrincipal main, UUID id, TeamDtos.MemberPassword r) {
        ownMember(main, id);
        if (!r.password().equals(r.confirmPassword())) {
            throw TeamAccountService.fieldError("confirmPassword", "Mật khẩu nhập lại không khớp.");
        }
        PasswordPolicy.problem(r.password(), null, null).ifPresent(p -> {
            throw TeamAccountService.fieldError("password", p);
        });
        jdbc.sql("update identity.team_members set password_hash = :h where id = :id").param("h", passwords.encode(r.password())).param("id", id).update();
        sessions.invalidate("MEMBER", id);
    }

    /** One role per shop: assigning again changes it, re-adding a removed shop brings the same row back. */
    @Transactional
    public TeamDtos.Member assign(CurrentPrincipal main, UUID memberId, UUID vendorId, TeamDtos.AssignmentRequest r) {
        ownMember(main, memberId);
        if (!linkedShops(main.teamId()).contains(vendorId)) {
            throw BusinessException.notFound("SHOP_NOT_LINKED", "Cửa hàng chưa liên kết với tài khoản chính.");
        }
        List<String> before = jdbc.sql("""
                select r.permissions from identity.shop_assignments s join identity.seller_roles r on r.id = s.role_id
                where s.member_id = :m and s.vendor_id = :v and s.removed_at is null""")
                .param("m", memberId).param("v", vendorId).query((rs, n) -> list(rs.getArray(1))).optional().orElse(List.of());
        UUID role = roleFor(main, r.roleId(), r.permissions());
        jdbc.sql("""
                insert into identity.shop_assignments (member_id, vendor_id, role_id) values (:m, :v, :r)
                on conflict (member_id, vendor_id) do update set role_id = excluded.role_id, removed_at = null, assigned_at = now()""")
                .param("m", memberId).param("v", vendorId).param("r", role).update();
        List<String> after = jdbc.sql("select permissions from identity.seller_roles where id = :id").param("id", role).query((rs, n) -> list(rs.getArray(1))).single();
        if (!after.containsAll(before)) {
            sessions.reduce("MEMBER", memberId);
        }
        return member(main, memberId);
    }

    @Transactional
    public TeamDtos.Member unassign(CurrentPrincipal main, UUID memberId, UUID vendorId) {
        ownMember(main, memberId);
        if (jdbc.sql("update identity.shop_assignments set removed_at = now() where member_id = :m and vendor_id = :v and removed_at is null")
                .param("m", memberId).param("v", vendorId).update() == 0) {
            throw BusinessException.notFound("ASSIGNMENT_NOT_FOUND", "Thành viên không làm ở cửa hàng này.");
        }
        sessions.reduce("MEMBER", memberId);
        return member(main, memberId);
    }

    // --- internals

    private TeamDtos.Member member(CurrentPrincipal main, UUID id) {
        return members(main).stream().filter(m -> m.id().equals(id)).findFirst()
                .orElseThrow(() -> BusinessException.notFound("MEMBER_NOT_FOUND", "Không tìm thấy thành viên."));
    }

    private static TeamDtos.Member withShops(TeamDtos.Member m, List<TeamDtos.Assignment> shops) {
        return new TeamDtos.Member(m.id(), m.login(), m.loginCode(), m.memberName(), m.phone(), m.email(), m.status(), m.createdAt(), shops);
    }

    private Map<UUID, List<TeamDtos.Assignment>> assignments(UUID mainId) {
        record A(UUID member, UUID vendor, UUID role, String roleName, boolean template, List<String> permissions) {
        }
        List<A> rows = jdbc.sql("""
                select s.member_id, s.vendor_id, r.id, r.name, r.is_template, r.permissions from identity.shop_assignments s
                join identity.team_members m on m.id = s.member_id join identity.seller_roles r on r.id = s.role_id
                where m.main_account_id = :m and s.removed_at is null""")
                .param("m", mainId)
                .query((rs, n) -> new A(rs.getObject(1, UUID.class), rs.getObject(2, UUID.class), rs.getObject(3, UUID.class), rs.getString(4),
                        rs.getBoolean(5), list(rs.getArray(6))))
                .list();
        Map<UUID, TeamShops.TeamShop> names = shops.byIds(rows.stream().map(A::vendor).collect(java.util.stream.Collectors.toSet()));
        Map<UUID, List<TeamDtos.Assignment>> out = new LinkedHashMap<>();
        for (A a : rows) {
            TeamShops.TeamShop shop = names.get(a.vendor());
            out.computeIfAbsent(a.member(), k -> new ArrayList<>()).add(new TeamDtos.Assignment(a.vendor(), shop == null ? null : shop.name(),
                    a.template() ? a.role() : null, a.template() ? a.roleName() : null, a.permissions()));
        }
        return out;
    }

    /** A named role of this main account, or an unnamed one made from the ticked permissions; exactly one of the two. */
    private UUID roleFor(CurrentPrincipal main, UUID roleId, List<String> permissions) {
        if ((roleId == null) == (permissions == null)) {
            throw TeamAccountService.fieldError("roleId", "Chọn một vai trò có sẵn hoặc tự chọn quyền, không chọn cả hai.");
        }
        if (roleId != null) {
            ownRole(main, roleId);
            return roleId;
        }
        return insertRole(main.teamId(), null, null, checked(permissions), false);
    }

    private UUID insertRole(UUID mainId, String name, String description, List<String> permissions, boolean template) {
        UUID id = UUID.randomUUID();
        jdbc.sql("""
                insert into identity.seller_roles (id, main_account_id, name, description, permissions, is_template)
                values (:id, :m, :n, :d, :p, :t)""")
                .param("id", id).param("m", mainId).param("n", name).param("d", description).param("p", permissions.toArray(String[]::new))
                .param("t", template).update();
        return id;
    }

    private TeamDtos.Role role(CurrentPrincipal main, UUID id) {
        return roles(main).stream().filter(r -> r.id().equals(id)).findFirst().orElseThrow();
    }

    private List<String> ownRole(CurrentPrincipal main, UUID id) {
        return jdbc.sql("select permissions from identity.seller_roles where id = :id and main_account_id = :m and is_template")
                .param("id", id).param("m", main.teamId()).query((rs, n) -> list(rs.getArray(1))).optional()
                .orElseThrow(() -> BusinessException.notFound("ROLE_NOT_FOUND", "Không tìm thấy vai trò."));
    }

    private void ownMember(CurrentPrincipal main, UUID id) {
        if (jdbc.sql("select count(*) from identity.team_members where id = :id and main_account_id = :m").param("id", id).param("m", main.teamId())
                .query(Long.class).single() == 0) {
            throw BusinessException.notFound("MEMBER_NOT_FOUND", "Không tìm thấy thành viên.");
        }
    }

    private List<UUID> membersOnRole(UUID roleId) {
        return jdbc.sql("select distinct member_id from identity.shop_assignments where role_id = :r and removed_at is null").param("r", roleId)
                .query(UUID.class).list();
    }

    private void uniqueName(UUID mainId, String name, UUID except) {
        if (jdbc.sql("select count(*) from identity.seller_roles where main_account_id = :m and is_template and lower(name) = lower(:n) and id <> :x")
                .param("m", mainId).param("n", name.strip()).param("x", except == null ? new UUID(0, 0) : except).query(Long.class).single() > 0) {
            throw BusinessException.conflict("ROLE_NAME_TAKEN", "Đã có vai trò cùng tên.");
        }
    }

    private Set<UUID> linkedShops(UUID mainId) {
        return new HashSet<>(jdbc.sql("select vendor_id from identity.main_account_shop_links where main_account_id = :m and status = 'AUTHORIZED'")
                .param("m", mainId).query(UUID.class).list());
    }

    private String username(UUID mainId) {
        return jdbc.sql("select username from identity.main_accounts where id = :id").param("id", mainId).query(String.class).single();
    }

    private static List<String> checked(List<String> permissions) {
        List<String> out = permissions.stream().map(String::strip).distinct().toList();
        List<String> bad = out.stream().filter(p -> !TeamSessions.PICKABLE.contains(p)).toList();
        if (!bad.isEmpty()) {
            throw TeamAccountService.fieldError("permissions", "Quyền không chọn được: " + String.join(", ", bad));
        }
        return out;
    }

    private static List<String> list(Array array) throws java.sql.SQLException {
        return array == null ? List.of() : List.of((String[]) array.getArray());
    }

    private static String blank(String s) {
        return s == null || s.isBlank() ? null : s.strip();
    }
}
