package com.bonbon.backend.payments.payment.service;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.security.SecureRandom;
import java.sql.Timestamp;
import java.util.Base64;
import java.util.HexFormat;
import java.util.List;
import java.util.UUID;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import com.bonbon.backend.common.exception.BusinessException;
import com.bonbon.backend.common.security.CurrentPrincipal;
import com.bonbon.backend.marketplace.merchant.ShopOrdering;
import com.bonbon.backend.ordering.order.ShopOrders;
import com.bonbon.backend.payments.payment.dto.SepayViews;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Confirms bank transfers by itself: the shop links its bank account in SePay and points SePay's webhook at bonbon, then every
 * incoming transfer whose content carries {@code BONBON<number>} and covers the order total marks that order paid. Each shop has
 * its own secret, shown once; only its SHA-256 is kept. The shop can still mark a transfer by hand.
 */
@Service
public class SepayService {

    private static final Logger log = LoggerFactory.getLogger(SepayService.class);
    /** Banks drop or add spaces and dashes; the order number is the digits right after the prefix. */
    private static final Pattern CODE = Pattern.compile("BONBON[\\s\\-_.]*(\\d{1,18})", Pattern.CASE_INSENSITIVE);
    private static final SecureRandom RANDOM = new SecureRandom();

    private final JdbcClient jdbc;
    private final ShopOrders orders;
    private final ShopOrdering shops;
    private final String webhookBase;

    SepayService(JdbcClient jdbc, ShopOrders orders, ShopOrdering shops, @Value("${bonbon.payment.sepay.webhook-base-url:http://localhost:8080}") String webhookBase) {
        this.jdbc = jdbc;
        this.orders = orders;
        this.shops = shops;
        this.webhookBase = webhookBase.replaceAll("/+$", "");
    }

    @Transactional(readOnly = true)
    public SepayViews.Status status(CurrentPrincipal caller) {
        UUID vendorId = vendorOf(caller);
        var connection = jdbc.sql("select created_at, last_event_at from payments.sepay_connections where vendor_id = :v")
                .param("v", vendorId)
                .query((rs, n) -> new java.time.Instant[] {rs.getTimestamp(1).toInstant(), instant(rs.getTimestamp(2))})
                .optional();
        List<SepayViews.Event> recent = jdbc.sql("""
                select sepay_id, amount, content, order_number, outcome, received_at from payments.sepay_events
                where vendor_id = :v order by received_at desc limit 10""")
                .param("v", vendorId)
                .query((rs, n) -> new SepayViews.Event(rs.getLong(1), rs.getLong(2), rs.getString(3), (Long) rs.getObject(4), rs.getString(5),
                        rs.getTimestamp(6).toInstant()))
                .list();
        return new SepayViews.Status(connection.isPresent(), webhookUrl(vendorId), connection.map(c -> c[0]).orElse(null),
                connection.map(c -> c[1]).orElse(null), recent);
    }

    /** Creates the connection, or replaces the secret: the old one stops working at once. */
    @Transactional
    public SepayViews.Connection connect(CurrentPrincipal caller) {
        UUID vendorId = vendorOf(caller);
        byte[] raw = new byte[24];
        RANDOM.nextBytes(raw);
        String secret = "bb_" + Base64.getUrlEncoder().withoutPadding().encodeToString(raw);
        jdbc.sql("""
                insert into payments.sepay_connections (vendor_id, secret_hash) values (:v, :h)
                on conflict (vendor_id) do update set secret_hash = excluded.secret_hash, created_at = now()""")
                .param("v", vendorId).param("h", sha256(secret)).update();
        return new SepayViews.Connection(webhookUrl(vendorId), secret);
    }

    @Transactional
    public void disconnect(CurrentPrincipal caller) {
        UUID vendorId = vendorOf(caller);
        jdbc.sql("delete from payments.sepay_connections where vendor_id = :v").param("v", vendorId).update();
    }

    /**
     * One transaction from SePay. A wrong or missing key is refused; anything else is acknowledged, even a transfer that matches no
     * order, so SePay stops retrying. The same SePay id is handled once.
     */
    @Transactional
    public SepayViews.Ack receive(UUID vendorId, String authorization, SepayViews.Webhook hook) {
        String stored = jdbc.sql("select secret_hash from payments.sepay_connections where vendor_id = :v").param("v", vendorId)
                .query(String.class).optional().orElse(null);
        String given = authorization == null ? "" : authorization.replaceFirst("(?i)^\\s*apikey\\s+", "").strip();
        if (stored == null || given.isEmpty() || !MessageDigest.isEqual(stored.getBytes(StandardCharsets.US_ASCII),
                sha256(given).getBytes(StandardCharsets.US_ASCII))) {
            throw new BusinessException(HttpStatus.UNAUTHORIZED, "SEPAY_UNAUTHORIZED", "Khoá webhook không đúng.");
        }
        if (hook == null || hook.id() == null || hook.transferAmount() == null) {
            throw new BusinessException(HttpStatus.BAD_REQUEST, "SEPAY_PAYLOAD_INVALID", "Thiếu mã giao dịch hoặc số tiền.");
        }
        jdbc.sql("update payments.sepay_connections set last_event_at = now() where vendor_id = :v").param("v", vendorId).update();
        boolean seen = jdbc.sql("select count(*) from payments.sepay_events where vendor_id = :v and sepay_id = :i")
                .param("v", vendorId).param("i", hook.id()).query(Long.class).single() > 0;
        if (seen) {
            return new SepayViews.Ack(true);
        }
        Long number = orderNumber(hook);
        String outcome;
        if (!"in".equalsIgnoreCase(hook.transferType())) {
            outcome = "OUTGOING";
        } else if (number == null) {
            outcome = "NO_CODE";
        } else {
            outcome = orders.matchTransfer(vendorId, number, hook.transferAmount()).name();
        }
        jdbc.sql("""
                insert into payments.sepay_events (vendor_id, sepay_id, amount, content, account_number, order_number, outcome)
                values (:v, :i, :a, :c, :n, :o, :out) on conflict do nothing""")
                .param("v", vendorId).param("i", hook.id()).param("a", hook.transferAmount())
                .param("c", truncate(hook.content(), 500)).param("n", truncate(hook.accountNumber(), 40))
                .param("o", number).param("out", outcome).update();
        if (!"PAID".equals(outcome) && !"OUTGOING".equals(outcome)) {
            log.info("SePay transfer {} for shop {} not matched: {}", hook.id(), vendorId, outcome);
        }
        return new SepayViews.Ack(true);
    }

    /** SePay's own {@code code} field when the shop set the BONBON prefix there, otherwise the transfer content. */
    static Long orderNumber(SepayViews.Webhook hook) {
        for (String text : new String[] {hook.code(), hook.content()}) {
            if (text == null) {
                continue;
            }
            Matcher m = CODE.matcher(text);
            if (m.find()) {
                try {
                    return Long.parseLong(m.group(1));
                } catch (NumberFormatException e) {
                    return null;
                }
            }
        }
        return null;
    }

    private UUID vendorOf(CurrentPrincipal caller) {
        return shops.operatingVendorOwnedBy(caller.id()).orElseThrow(() -> new BusinessException(HttpStatus.CONFLICT, "SHOP_NOT_APPROVED",
                "Bạn chưa có cửa hàng được duyệt."));
    }

    private String webhookUrl(UUID vendorId) {
        return webhookBase + "/api/payments/sepay/" + vendorId;
    }

    private static String sha256(String value) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(value.getBytes(StandardCharsets.UTF_8)));
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException(e);
        }
    }

    private static java.time.Instant instant(Timestamp t) {
        return t == null ? null : t.toInstant();
    }

    private static String truncate(String s, int max) {
        return s == null || s.length() <= max ? s : s.substring(0, max);
    }
}
