package com.bonbon.backend.identity.sellerteam.service;

import java.sql.Timestamp;
import java.time.Clock;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;

import com.bonbon.backend.common.exception.BusinessException;
import com.bonbon.backend.common.persistence.ActorType;
import com.bonbon.backend.common.security.CurrentPrincipal;
import com.bonbon.backend.common.security.JwtProperties;
import com.bonbon.backend.common.security.TokenRevocationCheck;
import com.bonbon.backend.identity.authentication.Role;
import com.bonbon.backend.identity.authentication.dto.TokenResponse;
import com.bonbon.backend.identity.authentication.dto.UserSummary;
import com.bonbon.backend.identity.authentication.service.SecureTokens;
import com.bonbon.backend.identity.authentication.service.TokenService.TokenPair;
import com.bonbon.backend.identity.sellerteam.TeamShops;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.security.oauth2.jose.jws.MacAlgorithm;
import org.springframework.security.oauth2.jwt.JwsHeader;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.security.oauth2.jwt.JwtClaimsSet;
import org.springframework.security.oauth2.jwt.JwtEncoder;
import org.springframework.security.oauth2.jwt.JwtEncoderParameters;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Tokens for main accounts and members. With a shop open the subject is that shop's owner, so every shop-scoped lookup on the seller
 * side finds the shop unchanged, while {@code team_id} says who really acts and {@code TeamScopeFilter} keeps the owner's personal
 * endpoints closed. The permissions are what this principal may do on that shop: a main account gets the whole seller set (never
 * opening a new shop) plus {@code team:manage}; a member gets its role on that shop. Reducing access bumps {@code access_version};
 * a token carries the version it was issued at, so the change takes effect on the next request, with no clock involved.
 */
@Service
public class TeamSessions implements TokenRevocationCheck {

    static final String MANAGE = "team:manage";
    static final String CLAIM_VERSION = "team_ver";
    /** What a role can grant (create-seller-role.md): the seller set without opening a shop, never team management. */
    static final List<String> PICKABLE = List.of("order:read", "order:write", "vendor:read", "vendor:write", "voucher:read", "voucher:write",
            "stats:read", "earnings:read", "conversation:read", "conversation:write", "review:respond");

    private final JwtEncoder encoder;
    private final JwtProperties props;
    private final JdbcClient jdbc;
    private final TeamShops shops;
    private final Clock clock = Clock.systemUTC();

    TeamSessions(JwtEncoder encoder, JwtProperties props, JdbcClient jdbc, TeamShops shops) {
        this.encoder = encoder;
        this.props = props;
        this.jdbc = jdbc;
        this.shops = shops;
    }

    /** Who a session belongs to and what it shows: login, display name, shops it may open. */
    record Principal(String kind, UUID id, String login, String displayName) {
    }

    // --- issuing

    /** {@code vendorId} null opens the console (main account) or the first assigned shop (member). */
    @Transactional
    public TokenResponse issue(String kind, UUID principalId, UUID vendorId, Instant authTime) {
        return issue(kind, principalId, vendorId, authTime, UUID.randomUUID());
    }

    private TokenResponse issue(String kind, UUID principalId, UUID vendorId, Instant authTime, UUID family) {
        Principal who = principal(kind, principalId).orElseThrow(TeamSessions::invalidRefresh);
        Map<UUID, List<String>> grants = grants(kind, principalId);
        Map<UUID, TeamShops.TeamShop> shopInfo = shops.byIds(grants.keySet());
        List<UUID> usable = shopInfo.values().stream()
                .filter(s -> !"CLOSED".equals(s.status()))
                .sorted(Comparator.comparing(s -> s.name() == null ? "" : s.name()))
                .map(TeamShops.TeamShop::vendorId).toList();
        UUID shop = vendorId;
        if (shop != null && !usable.contains(shop)) {
            throw new BusinessException(HttpStatus.FORBIDDEN, "SHOP_NOT_ASSIGNED", "Bạn không được làm việc ở cửa hàng này.");
        }
        if (shop == null && "MEMBER".equals(kind) && !usable.isEmpty()) {
            shop = usable.get(0);
        }
        Set<String> permissions = new LinkedHashSet<>();
        if ("MAIN_ACCOUNT".equals(kind)) {
            permissions.add(MANAGE);
            if (shop != null) {
                permissions.addAll(PICKABLE);
            }
        } else if (shop != null) {
            grants.getOrDefault(shop, List.of()).stream().filter(PICKABLE::contains).forEach(permissions::add);
        }
        UUID subject = shop == null ? principalId : shopInfo.get(shop).ownerUserId();

        Instant now = clock.instant();
        JwtClaimsSet.Builder claims = JwtClaimsSet.builder()
                .issuer("bonbon")
                .subject(subject.toString())
                .id(UUID.randomUUID().toString())
                .issuedAt(now)
                .expiresAt(now.plus(props.accessTtl()))
                .claim(CurrentPrincipal.CLAIM_ACTOR, ("MAIN_ACCOUNT".equals(kind) ? ActorType.MAIN_ACCOUNT : ActorType.MEMBER).name())
                .claim(CurrentPrincipal.CLAIM_ROLE, Role.SELLER.name())
                .claim(CurrentPrincipal.CLAIM_PERMISSIONS, List.copyOf(permissions))
                .claim(CurrentPrincipal.CLAIM_AUTH_TIME, authTime.getEpochSecond())
                .claim(CurrentPrincipal.CLAIM_TEAM, principalId.toString())
                .claim(CLAIM_VERSION, version(kind, principalId));
        if (shop != null) {
            claims.claim(CurrentPrincipal.CLAIM_SHOP, shop.toString());
        }
        String access = encoder.encode(JwtEncoderParameters.from(JwsHeader.with(MacAlgorithm.HS256).build(), claims.build())).getTokenValue();
        String rawRefresh = SecureTokens.newRawToken();
        jdbc.sql("""
                insert into identity.team_refresh_tokens (principal_id, kind, vendor_id, token_hash, family_id, auth_time, expires_at)
                values (:p, :k, :v, :h, :f, :a, :e)""")
                .param("p", principalId).param("k", kind).param("v", shop).param("h", SecureTokens.hash(rawRefresh)).param("f", family)
                .param("a", Timestamp.from(authTime)).param("e", Timestamp.from(now.plus(props.refreshTtl()))).update();

        List<UserSummary.ShopChoice> choices = new ArrayList<>();
        for (UUID id : usable) {
            TeamShops.TeamShop s = shopInfo.get(id);
            choices.add(new UserSummary.ShopChoice(id, s.name(), s.status()));
        }
        UserSummary.TeamSession team = new UserSummary.TeamSession(kind, principalId, who.login(), who.displayName(), shop,
                shop == null ? null : shopInfo.get(shop).name(), choices, List.copyOf(permissions));
        UserSummary user = new UserSummary(principalId, null, who.displayName(), Role.SELLER, Set.of(Role.SELLER), team);
        return TokenResponse.of(new TokenPair(access, rawRefresh, props.accessTtl().toSeconds(), Role.SELLER)).withUser(user);
    }

    /** True when this refresh token belongs to a team session, so the shared refresh endpoint hands it here. */
    public boolean owns(String rawRefreshToken) {
        return rawRefreshToken != null && !rawRefreshToken.isBlank() && jdbc.sql("select count(*) from identity.team_refresh_tokens where token_hash = :h")
                .param("h", SecureTokens.hash(rawRefreshToken)).query(Long.class).single() > 0;
    }

    /** Rotation, as for users: a token presented twice revokes its whole family. Keeps the shop that was open. */
    @Transactional(noRollbackFor = BusinessException.class)
    public TokenResponse refresh(String rawRefreshToken) {
        record Row(UUID id, UUID principal, String kind, UUID vendor, UUID family, Instant authTime, Instant expires, Instant revoked, Instant created) {
        }
        Row r = jdbc.sql("select * from identity.team_refresh_tokens where token_hash = :h").param("h", SecureTokens.hash(rawRefreshToken))
                .query((rs, n) -> new Row(rs.getObject("id", UUID.class), rs.getObject("principal_id", UUID.class), rs.getString("kind"),
                        rs.getObject("vendor_id", UUID.class), rs.getObject("family_id", UUID.class), rs.getTimestamp("auth_time").toInstant(),
                        rs.getTimestamp("expires_at").toInstant(), ts(rs.getTimestamp("revoked_at")), rs.getTimestamp("created_at").toInstant()))
                .optional().orElseThrow(TeamSessions::invalidRefresh);
        Timestamp now = Timestamp.from(clock.instant());
        if (r.revoked() != null) {
            jdbc.sql("update identity.team_refresh_tokens set revoked_at = :now where family_id = :f and revoked_at is null")
                    .param("now", now).param("f", r.family()).update();
            throw invalidRefresh();
        }
        if (r.expires().isBefore(now.toInstant()) || version(r.kind(), r.principal()) == null) {
            throw invalidRefresh();
        }
        if (jdbc.sql("update identity.team_refresh_tokens set revoked_at = :now where id = :id and revoked_at is null")
                .param("now", now).param("id", r.id()).update() == 0) {
            throw invalidRefresh();
        }
        UUID shop = r.vendor() != null && grants(r.kind(), r.principal()).containsKey(r.vendor()) ? r.vendor() : null;
        return issue(r.kind(), r.principal(), shop, r.authTime(), r.family());
    }

    @Transactional
    public void revoke(String rawRefreshToken) {
        if (rawRefreshToken == null || rawRefreshToken.isBlank()) {
            return;
        }
        jdbc.sql("update identity.team_refresh_tokens set revoked_at = now() where token_hash = :h and revoked_at is null")
                .param("h", SecureTokens.hash(rawRefreshToken)).update();
    }

    /** Every token of this principal issued before now stops working, and its refresh tokens are revoked: a new password, a disabled member. */
    @Transactional
    public void invalidate(String kind, UUID principalId) {
        reduce(kind, principalId);
        jdbc.sql("update identity.team_refresh_tokens set revoked_at = now() where principal_id = :id and revoked_at is null").param("id", principalId).update();
    }

    /** Access shrank (a shop unlinked, a smaller role): current access tokens stop working, a silent refresh picks up the new grants. */
    @Transactional
    public void reduce(String kind, UUID principalId) {
        String table = "MAIN_ACCOUNT".equals(kind) ? "identity.main_accounts" : "identity.team_members";
        jdbc.sql("update " + table + " set access_version = access_version + 1 where id = :id").param("id", principalId).update();
    }

    // --- checking

    @Override
    public boolean isRevoked(Jwt jwt) {
        String team = jwt.getClaimAsString(CurrentPrincipal.CLAIM_TEAM);
        if (team == null) {
            return false;
        }
        String kind = "MAIN_ACCOUNT".equals(jwt.getClaimAsString(CurrentPrincipal.CLAIM_ACTOR)) ? "MAIN_ACCOUNT" : "MEMBER";
        Integer current = version(kind, UUID.fromString(team));
        Object issued = jwt.getClaims().get(CLAIM_VERSION);
        return current == null || !(issued instanceof Number n) || n.intValue() < current;
    }

    /**
     * The access version a token must carry, or null when the principal is gone or the member disabled. A member's version also moves
     * with its main account's, so ending a link reaches every member at once.
     */
    private Integer version(String kind, UUID id) {
        String sql = "MAIN_ACCOUNT".equals(kind)
                ? "select access_version from identity.main_accounts where id = :id"
                : """
                  select m.access_version + a.access_version from identity.team_members m
                  join identity.main_accounts a on a.id = m.main_account_id where m.id = :id and m.status = 'ACTIVE'""";
        return jdbc.sql(sql).param("id", id).query(Integer.class).optional().orElse(null);
    }

    // --- what a principal may open

    /** Shop → permissions: every authorized link for a main account; for a member, its live assignments on shops still linked. */
    Map<UUID, List<String>> grants(String kind, UUID principalId) {
        Map<UUID, List<String>> out = new java.util.LinkedHashMap<>();
        if ("MAIN_ACCOUNT".equals(kind)) {
            jdbc.sql("select vendor_id from identity.main_account_shop_links where main_account_id = :id and status = 'AUTHORIZED'")
                    .param("id", principalId).query(UUID.class).list().forEach(v -> out.put(v, PICKABLE));
        } else {
            jdbc.sql("""
                    select s.vendor_id, r.permissions from identity.shop_assignments s
                    join identity.team_members m on m.id = s.member_id
                    join identity.seller_roles r on r.id = s.role_id
                    join identity.main_account_shop_links l on l.main_account_id = m.main_account_id and l.vendor_id = s.vendor_id and l.status = 'AUTHORIZED'
                    where s.member_id = :id and s.removed_at is null""")
                    .param("id", principalId)
                    .query((rs, n) -> Map.entry(rs.getObject(1, UUID.class), List.of((String[]) rs.getArray(2).getArray())))
                    .list().forEach(e -> out.put(e.getKey(), e.getValue()));
        }
        return out;
    }

    Optional<Principal> principal(String kind, UUID id) {
        if ("MAIN_ACCOUNT".equals(kind)) {
            return jdbc.sql("select username, account_name from identity.main_accounts where id = :id").param("id", id)
                    .query((rs, n) -> new Principal(kind, id, rs.getString(1) + ":main", rs.getString(2))).optional();
        }
        return jdbc.sql("""
                select a.username, m.login_code, m.member_name from identity.team_members m join identity.main_accounts a on a.id = m.main_account_id
                where m.id = :id""").param("id", id)
                .query((rs, n) -> new Principal(kind, id, rs.getString(1) + ":" + rs.getString(2), rs.getString(3))).optional();
    }

    private static Instant ts(Timestamp t) {
        return t == null ? null : t.toInstant();
    }

    private static BusinessException invalidRefresh() {
        return new BusinessException(HttpStatus.UNAUTHORIZED, "INVALID_REFRESH_TOKEN", "Phiên đăng nhập đã hết hạn, vui lòng đăng nhập lại.");
    }
}
