package com.bonbon.backend.payment.service;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;

import com.bonbon.backend.common.crypto.FieldCipher;
import com.bonbon.backend.common.exception.BusinessException;
import com.bonbon.backend.payment.dto.RefundRequests;
import com.bonbon.backend.payment.dto.RefundViews;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * The people side of refunds (process-refund.md): the customer giving a bank account for a transfer, and an admin
 * working the queue. An admin can only complete or fail a row, never edit its amount; every step leaves a log row.
 */
@Service
public class RefundDesk {

    private static final int MAX_PAGE_SIZE = 50;
    private static final Set<String> STATUSES = Set.of("REQUESTED", "NEEDS_DESTINATION", "COMPLETED", "ALL");

    private final JdbcClient jdbc;
    private final FieldCipher cipher;
    private final PaymentRefunds refunds;

    RefundDesk(JdbcClient jdbc, FieldCipher cipher, PaymentRefunds refunds) {
        this.jdbc = jdbc;
        this.cipher = cipher;
        this.refunds = refunds;
    }

    // --- the customer

    @Transactional
    public RefundViews.Mine setDestination(UUID customerId, UUID orderId, RefundRequests.Destination destination) {
        Optional<RefundRow> found = jdbc.sql("""
                select r.id, r.status from payment_refunds r join payments p on p.id = r.payment_id
                where p.order_id = :o and p.customer_id = :c and r.mode = 'MANUAL'
                order by r.created_at desc limit 1 for update of r""")
                .param("o", orderId).param("c", customerId)
                .query((rs, n) -> new RefundRow(rs.getObject("id", UUID.class), rs.getString("status"))).optional();
        RefundRow refund = found.orElseThrow(() -> BusinessException.notFound("REFUND_NOT_FOUND", "Đơn này không có khoản hoàn tiền nào chờ tài khoản."));
        if (!"NEEDS_DESTINATION".equals(refund.status())) {
            throw BusinessException.conflict("REFUND_NOT_ACCEPTING_DESTINATION", "Khoản hoàn tiền này không cần nhập tài khoản.");
        }
        String number = destination.accountNumber();
        jdbc.sql("""
                update payment_refunds set destination_bank = :bank, destination_number = :number, destination_name = :name,
                       destination_last4 = :last4, failure_reason = null, status = 'REQUESTED', updated_at = now() where id = :id""")
                .param("bank", destination.bankName().strip()).param("number", cipher.encrypt(number)).param("name", destination.accountName().strip())
                .param("last4", number.substring(number.length() - 4)).param("id", refund.id()).update();
        refunds.log(refund.id(), "DESTINATION", "CUSTOMER", customerId, destination.bankName().strip());
        return mine(refund.id());
    }

    private record RefundRow(UUID id, String status) {
    }

    private RefundViews.Mine mine(UUID refundId) {
        return jdbc.sql("select status, mode, amount, failure_reason, destination_last4 from payment_refunds where id = :id").param("id", refundId)
                .query((rs, n) -> new RefundViews.Mine(rs.getString("status"), rs.getString("mode"), rs.getInt("amount"),
                        "NEEDS_DESTINATION".equals(rs.getString("status")), rs.getString("failure_reason"), rs.getString("destination_last4")))
                .single();
    }

    // --- the admin

    /** The open manual queue by default (oldest first); {@code COMPLETED} for the done ones, {@code ALL} for every refund. */
    @Transactional(readOnly = true)
    public RefundViews.Page list(String status, int page, int size) {
        if (page < 0 || size < 1 || size > MAX_PAGE_SIZE) {
            throw new BusinessException(HttpStatus.BAD_REQUEST, "INVALID_PAGE", "Trang hoặc kích thước trang không hợp lệ.");
        }
        if (status != null && !STATUSES.contains(status)) {
            throw new BusinessException(HttpStatus.BAD_REQUEST, "INVALID_STATUS", "Trạng thái phải là REQUESTED, NEEDS_DESTINATION, COMPLETED hoặc ALL.");
        }
        String where;
        String order = " order by r.created_at asc, r.id";
        if (status == null) {
            where = " where r.mode = 'MANUAL' and r.status in ('REQUESTED', 'NEEDS_DESTINATION')";
        } else if ("ALL".equals(status)) {
            where = " where true";
            order = " order by r.created_at desc, r.id";
        } else if ("COMPLETED".equals(status)) {
            where = " where r.status = 'COMPLETED'";
            order = " order by r.completed_at desc, r.id";
        } else {
            where = " where r.mode = 'MANUAL' and r.status = '" + status + "'";
        }
        long total = jdbc.sql("select count(*) from payment_refunds r" + where).query(Long.class).single();
        List<RefundViews.Row> items = new ArrayList<>(jdbc.sql(SELECT + where + order + " limit :limit offset :offset")
                .param("limit", size).param("offset", (long) page * size).query((rs, n) -> row(rs)).list());
        return new RefundViews.Page(items, page, size, total);
    }

    @Transactional
    public RefundViews.Row complete(UUID adminId, UUID refundId, RefundRequests.Complete request) {
        int changed;
        try {
            changed = jdbc.sql("""
                    update payment_refunds set status = 'COMPLETED', bank_reference = :ref, transferred_at = :at, completed_by = :admin,
                           completed_at = now(), updated_at = now()
                    where id = :id and mode = 'MANUAL' and status = 'REQUESTED'""")
                    .param("ref", request.bankReference().strip()).param("at", PaymentRefunds.ts(request.transferredAt() == null ? Instant.now() : request.transferredAt()))
                    .param("admin", adminId).param("id", refundId).update();
        } catch (DataIntegrityViolationException e) {
            throw BusinessException.conflict("DUPLICATE_BANK_REFERENCE", "Mã giao dịch này đã được dùng cho một khoản hoàn khác.");
        }
        if (changed == 0) {
            throw notPayable(refundId);
        }
        refunds.log(refundId, "COMPLETED", "ADMIN", adminId, request.bankReference().strip());
        int amount = jdbc.sql("select amount from payment_refunds where id = :id").param("id", refundId).query(Integer.class).single();
        UUID paymentId = jdbc.sql("select payment_id from payment_refunds where id = :id").param("id", refundId).query(UUID.class).single();
        refunds.applyToPayment(paymentId, amount);
        refunds.publishCompleted(refundId);
        return get(refundId);
    }

    @Transactional
    public RefundViews.Row fail(UUID adminId, UUID refundId, RefundRequests.Fail request) {
        int changed = jdbc.sql("""
                update payment_refunds set status = 'NEEDS_DESTINATION', failure_reason = :why, destination_bank = null, destination_number = null,
                       destination_name = null, destination_last4 = null, updated_at = now()
                where id = :id and mode = 'MANUAL' and status = 'REQUESTED'""")
                .param("why", request.reason().strip()).param("id", refundId).update();
        if (changed == 0) {
            throw notPayable(refundId);
        }
        refunds.log(refundId, "TRANSFER_FAILED", "ADMIN", adminId, request.reason().strip());
        refunds.askForDestination(refundId);
        return get(refundId);
    }

    private BusinessException notPayable(UUID refundId) {
        boolean exists = jdbc.sql("select count(*) from payment_refunds where id = :id").param("id", refundId).query(Long.class).single() > 0;
        return exists
                ? BusinessException.conflict("REFUND_NOT_PAYABLE", "Khoản hoàn này không ở trạng thái chờ chuyển khoản (đã xử lý, chờ khách nhập tài khoản hoặc đang hoàn qua MoMo).")
                : BusinessException.notFound("REFUND_NOT_FOUND", "Không tìm thấy khoản hoàn tiền.");
    }

    private RefundViews.Row get(UUID id) {
        return jdbc.sql(SELECT + " where r.id = :id").param("id", id).query((rs, n) -> row(rs)).single();
    }

    private static final String SELECT = """
            select r.id, p.order_id, p.order_number, p.customer_id, r.amount, r.reason, r.status, r.mode, r.gateway_result_code,
                   r.destination_bank, r.destination_number, r.destination_name, r.failure_reason, r.bank_reference, r.transferred_at,
                   r.created_at, r.completed_at
            from payment_refunds r join payments p on p.id = r.payment_id""";

    private RefundViews.Row row(java.sql.ResultSet rs) throws java.sql.SQLException {
        RefundViews.Destination destination = rs.getString("destination_number") == null ? null
                : new RefundViews.Destination(rs.getString("destination_bank"), cipher.decrypt(rs.getString("destination_number")), rs.getString("destination_name"));
        return new RefundViews.Row(rs.getObject("id", UUID.class), rs.getObject("order_id", UUID.class), rs.getLong("order_number"),
                rs.getObject("customer_id", UUID.class), rs.getInt("amount"), rs.getString("reason"), rs.getString("status"), rs.getString("mode"),
                (Integer) rs.getObject("gateway_result_code"), destination, rs.getString("failure_reason"), rs.getString("bank_reference"),
                instant(rs.getTimestamp("transferred_at")), instant(rs.getTimestamp("created_at")), instant(rs.getTimestamp("completed_at")));
    }

    private static Instant instant(java.sql.Timestamp t) {
        return t == null ? null : t.toInstant();
    }
}
