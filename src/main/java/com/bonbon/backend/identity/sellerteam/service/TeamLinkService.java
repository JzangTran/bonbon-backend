package com.bonbon.backend.identity.sellerteam.service;

import java.sql.Timestamp;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.stream.Collectors;

import com.bonbon.backend.common.exception.BusinessException;
import com.bonbon.backend.common.ratelimit.RateLimiter;
import com.bonbon.backend.common.security.CurrentPrincipal;
import com.bonbon.backend.identity.sellerteam.TeamShops;
import com.bonbon.backend.identity.sellerteam.dto.TeamDtos;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Linking a shop (link-shop.md): the main account asks, confirming with a code mailed to itself; the shop account authorizes or
 * declines from its own session. A request lives 7 days. Either side can end a link; ending it closes access at once for the main
 * account and every member assigned to that shop.
 */
@Service
public class TeamLinkService {

    static final Duration PENDING_FOR = Duration.ofDays(7);

    private final JdbcClient jdbc;
    private final TeamCodes codes;
    private final TeamShops shops;
    private final TeamSessions sessions;
    private final TeamAccountService accounts;
    private final RateLimiter limiter;

    TeamLinkService(JdbcClient jdbc, TeamCodes codes, TeamShops shops, TeamSessions sessions, TeamAccountService accounts, RateLimiter limiter) {
        this.jdbc = jdbc;
        this.codes = codes;
        this.shops = shops;
        this.sessions = sessions;
        this.accounts = accounts;
        this.limiter = limiter;
    }

    // --- the main account

    @Transactional(readOnly = true)
    public void sendLinkCode(CurrentPrincipal main) {
        String email = jdbc.sql("select email from identity.main_accounts where id = :id").param("id", main.teamId()).query(String.class).single();
        codes.send("link", main.teamId().toString(), email, "liên kết cửa hàng với tài khoản chính");
    }

    @Transactional
    public TeamDtos.Link request(CurrentPrincipal main, TeamDtos.LinkRequest r) {
        expireOld();
        if (!limiter.tryAcquire("team-link:" + main.teamId(), 20, Duration.ofDays(1))) {
            throw new BusinessException(HttpStatus.TOO_MANY_REQUESTS, "TOO_MANY_LINK_REQUESTS", "Đã gửi quá nhiều yêu cầu liên kết hôm nay.");
        }
        codes.verify("link", main.teamId().toString(), r.code());
        UUID owner = jdbc.sql("""
                select u.id from identity.users u join identity.user_roles r on r.user_id = u.id and r.role = 'SELLER'
                where lower(u.email) = :e""").param("e", r.shopEmail().strip().toLowerCase()).query(UUID.class).optional().orElse(null);
        TeamShops.TeamShop shop = owner == null ? null : shops.ofOwner(owner).filter(s -> "APPROVED".equals(s.status())).orElse(null);
        if (shop == null) {
            throw BusinessException.notFound("SHOP_NOT_FOUND", "Không tìm thấy cửa hàng đang hoạt động với email này.");
        }
        if (!limiter.tryAcquire("team-link-shop:" + shop.vendorId(), 5, Duration.ofDays(1))) {
            throw new BusinessException(HttpStatus.TOO_MANY_REQUESTS, "TOO_MANY_LINK_REQUESTS", "Cửa hàng này đã nhận quá nhiều yêu cầu hôm nay.");
        }
        long active = jdbc.sql("select count(*) from identity.main_account_shop_links where vendor_id = :v and status in ('PENDING_AUTHORIZATION', 'AUTHORIZED')")
                .param("v", shop.vendorId()).query(Long.class).single();
        if (active > 0) {
            throw BusinessException.conflict("SHOP_ALREADY_LINKED", "Cửa hàng này đã liên kết hoặc đang chờ một tài khoản chính khác.");
        }
        UUID id = UUID.randomUUID();
        jdbc.sql("""
                insert into identity.main_account_shop_links (id, main_account_id, vendor_id, owner_user_id, status, expires_at)
                values (:id, :m, :v, :o, 'PENDING_AUTHORIZATION', :exp)""")
                .param("id", id).param("m", main.teamId()).param("v", shop.vendorId()).param("o", owner)
                .param("exp", Timestamp.from(Instant.now().plus(PENDING_FOR))).update();
        return links(main.teamId()).stream().filter(l -> l.id().equals(id)).findFirst().orElseThrow();
    }

    @Transactional
    public List<TeamDtos.Link> links(UUID mainAccountId) {
        expireOld();
        record Row(UUID id, UUID vendor, String status, Instant requested, Instant expires, Instant decided, Instant ended) {
        }
        List<Row> rows = jdbc.sql("select * from identity.main_account_shop_links where main_account_id = :m order by requested_at desc")
                .param("m", mainAccountId)
                .query((rs, n) -> new Row(rs.getObject("id", UUID.class), rs.getObject("vendor_id", UUID.class), rs.getString("status"),
                        rs.getTimestamp("requested_at").toInstant(), rs.getTimestamp("expires_at").toInstant(), ts(rs.getTimestamp("decided_at")),
                        ts(rs.getTimestamp("ended_at"))))
                .list();
        Map<UUID, TeamShops.TeamShop> names = shops.byIds(rows.stream().map(Row::vendor).collect(Collectors.toSet()));
        return rows.stream().map(r -> new TeamDtos.Link(r.id(), r.vendor(), names.containsKey(r.vendor()) ? names.get(r.vendor()).name() : null,
                r.status(), r.requested(), r.expires(), r.decided(), r.ended())).toList();
    }

    /** A pending request is cancelled; an authorized link is ended. */
    @Transactional
    public void endFromMain(CurrentPrincipal main, UUID linkId) {
        end(linkId, "main_account_id = :who", main.teamId());
    }

    @Transactional
    public TeamDtos.MainAccount mainAccount(CurrentPrincipal main) {
        TeamDtos.MainAccount m = accounts.view(main.teamId());
        return new TeamDtos.MainAccount(m.id(), m.login(), m.accountName(), m.region(), m.email(), m.phone(), m.nickname(), m.createdAt(),
                links(main.teamId()));
    }

    // --- the shop account

    @Transactional
    public List<TeamDtos.Incoming> incoming(UUID shopAccountId) {
        expireOld();
        return jdbc.sql("""
                select l.id, a.username, a.account_name, l.status, l.requested_at, l.expires_at, l.decided_at
                from identity.main_account_shop_links l join identity.main_accounts a on a.id = l.main_account_id
                where l.owner_user_id = :o order by l.requested_at desc limit 50""")
                .param("o", shopAccountId)
                .query((rs, n) -> new TeamDtos.Incoming(rs.getObject(1, UUID.class), rs.getString(2) + ":main", rs.getString(3), rs.getString(4),
                        rs.getTimestamp(5).toInstant(), rs.getTimestamp(6).toInstant(), ts(rs.getTimestamp(7))))
                .list();
    }

    @Transactional
    public void authorize(CurrentPrincipal shopAccount, Instant authTime, UUID linkId, String password) {
        expireOld();
        String status = statusFor(linkId, shopAccount.id());
        if (!"PENDING_AUTHORIZATION".equals(status)) {
            throw BusinessException.conflict("LINK_NOT_PENDING", "Yêu cầu này không còn chờ trả lời.");
        }
        accounts.stepUp(shopAccount.id(), authTime, password);
        jdbc.sql("update identity.main_account_shop_links set status = 'AUTHORIZED', decided_at = now() where id = :id").param("id", linkId).update();
    }

    @Transactional
    public void decline(CurrentPrincipal shopAccount, UUID linkId) {
        expireOld();
        if (!"PENDING_AUTHORIZATION".equals(statusFor(linkId, shopAccount.id()))) {
            throw BusinessException.conflict("LINK_NOT_PENDING", "Yêu cầu này không còn chờ trả lời.");
        }
        jdbc.sql("update identity.main_account_shop_links set status = 'DECLINED', decided_at = now() where id = :id").param("id", linkId).update();
    }

    @Transactional
    public void endFromShop(CurrentPrincipal shopAccount, UUID linkId) {
        end(linkId, "owner_user_id = :who", shopAccount.id());
    }

    // --- internals

    private void end(UUID linkId, String ownership, UUID who) {
        record L(UUID main, UUID vendor, String status) {
        }
        L l = jdbc.sql("select main_account_id, vendor_id, status from identity.main_account_shop_links where id = :id and " + ownership + " for update")
                .param("id", linkId).param("who", who)
                .query((rs, n) -> new L(rs.getObject(1, UUID.class), rs.getObject(2, UUID.class), rs.getString(3))).optional()
                .orElseThrow(() -> BusinessException.notFound("LINK_NOT_FOUND", "Không tìm thấy liên kết."));
        switch (l.status()) {
            case "PENDING_AUTHORIZATION" -> jdbc.sql("update identity.main_account_shop_links set status = 'CANCELLED', ended_at = now() where id = :id")
                    .param("id", linkId).update();
            case "AUTHORIZED" -> {
                jdbc.sql("update identity.main_account_shop_links set status = 'UNLINKED', ended_at = now() where id = :id").param("id", linkId).update();
                // access shrinks: the main account and every member working this shop must get new tokens
                sessions.reduce("MAIN_ACCOUNT", l.main());
                jdbc.sql("""
                        select distinct s.member_id from identity.shop_assignments s join identity.team_members m on m.id = s.member_id
                        where m.main_account_id = :m and s.vendor_id = :v and s.removed_at is null""")
                        .param("m", l.main()).param("v", l.vendor()).query(UUID.class).list()
                        .forEach(member -> sessions.reduce("MEMBER", member));
            }
            default -> throw BusinessException.conflict("LINK_ALREADY_ENDED", "Liên kết này đã kết thúc.");
        }
    }

    private String statusFor(UUID linkId, UUID owner) {
        return jdbc.sql("select status from identity.main_account_shop_links where id = :id and owner_user_id = :o").param("id", linkId).param("o", owner)
                .query(String.class).optional()
                .orElseThrow(() -> BusinessException.notFound("LINK_NOT_FOUND", "Không tìm thấy yêu cầu liên kết."));
    }

    private void expireOld() {
        jdbc.sql("update identity.main_account_shop_links set status = 'EXPIRED', ended_at = now() where status = 'PENDING_AUTHORIZATION' and expires_at < now()")
                .update();
    }

    private static Instant ts(Timestamp t) {
        return t == null ? null : t.toInstant();
    }
}
