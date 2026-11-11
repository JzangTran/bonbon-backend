package com.bonbon.backend.payments.payment.service;

import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import com.bonbon.backend.common.exception.BusinessException;
import com.bonbon.backend.payments.payment.dto.PaymentSettingsViews;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * A customer's linked payment methods and payment PIN. Linking records which wallet the customer pays with; the money still moves
 * through the provider's own checkout. Five wrong PINs lock changes for 15 minutes.
 */
@Service
public class PaymentSettingsService {

    /** The providers a customer can link, with the name shown. Cash needs no linking. */
    private static final Map<String, String> PROVIDERS = Map.of("MOMO", "MoMo");
    private static final int MAX_ATTEMPTS = 5;
    private static final Duration LOCK = Duration.ofMinutes(15);

    private final JdbcClient jdbc;
    private final PasswordEncoder encoder;

    PaymentSettingsService(JdbcClient jdbc, PasswordEncoder encoder) {
        this.jdbc = jdbc;
        this.encoder = encoder;
    }

    @Transactional(readOnly = true)
    public PaymentSettingsViews.Methods methods(UUID customerId) {
        List<PaymentSettingsViews.Method> linked = jdbc.sql("""
                select id, provider, account_last4, is_primary from payments.payment_methods
                where customer_id = :customer order by is_primary desc, created_at""")
                .param("customer", customerId)
                .query((rs, i) -> view(rs.getObject("id", UUID.class), rs.getString("provider"), rs.getString("account_last4"),
                        rs.getBoolean("is_primary")))
                .list();
        List<String> linkable = PROVIDERS.keySet().stream().sorted()
                .filter(p -> linked.stream().noneMatch(m -> m.provider().equals(p))).toList();
        return new PaymentSettingsViews.Methods(linked, linkable);
    }

    @Transactional
    public PaymentSettingsViews.Method link(UUID customerId, PaymentSettingsViews.Link request) {
        String provider = request.provider().strip().toUpperCase();
        if (!PROVIDERS.containsKey(provider)) {
            throw new BusinessException(HttpStatus.BAD_REQUEST, "UNSUPPORTED_PAYMENT_PROVIDER", "Phương thức thanh toán này chưa được hỗ trợ.");
        }
        boolean first = jdbc.sql("select count(*) from payments.payment_methods where customer_id = :customer")
                .param("customer", customerId).query(Long.class).single() == 0;
        String digits = request.account().replaceAll("\\D", "");
        UUID id = UUID.randomUUID();
        int added = jdbc.sql("""
                insert into payments.payment_methods (id, customer_id, provider, account_last4, is_primary)
                values (:id, :customer, :provider, :last4, :primary) on conflict (customer_id, provider) do nothing""")
                .param("id", id).param("customer", customerId).param("provider", provider)
                .param("last4", digits.substring(digits.length() - 4)).param("primary", first).update();
        if (added == 0) {
            throw BusinessException.conflict("PAYMENT_METHOD_ALREADY_LINKED", "Bạn đã liên kết phương thức này rồi.");
        }
        return view(id, provider, digits.substring(digits.length() - 4), first);
    }

    @Transactional
    public void setPrimary(UUID customerId, UUID methodId, boolean primary) {
        owned(customerId, methodId);
        if (primary) {
            jdbc.sql("update payments.payment_methods set is_primary = false where customer_id = :customer and id <> :id")
                    .param("customer", customerId).param("id", methodId).update();
        }
        jdbc.sql("update payments.payment_methods set is_primary = :primary where id = :id")
                .param("primary", primary).param("id", methodId).update();
    }

    @Transactional
    public void unlink(UUID customerId, UUID methodId) {
        owned(customerId, methodId);
        jdbc.sql("delete from payments.payment_methods where id = :id").param("id", methodId).update();
    }

    @Transactional(readOnly = true)
    public PaymentSettingsViews.PinStatus pin(UUID customerId) {
        return new PaymentSettingsViews.PinStatus(storedPin(customerId) != null);
    }

    /** Creates the PIN, or changes it after checking the current one. */
    @Transactional(noRollbackFor = BusinessException.class)
    public void changePin(UUID customerId, PaymentSettingsViews.PinChange request) {
        Pin stored = storedPin(customerId);
        Instant now = Instant.now();
        if (stored != null) {
            if (stored.lockedUntil() != null && stored.lockedUntil().isAfter(now)) {
                throw new BusinessException(HttpStatus.TOO_MANY_REQUESTS, "PIN_LOCKED", "Bạn nhập sai mã PIN quá nhiều lần. Hãy thử lại sau 15 phút.");
            }
            if (request.currentPin() == null || !encoder.matches(request.currentPin(), stored.hash())) {
                int attempts = stored.failedAttempts() + 1;
                jdbc.sql("update payments.payment_pins set failed_attempts = :attempts, locked_until = :until where customer_id = :customer")
                        .param("attempts", attempts >= MAX_ATTEMPTS ? 0 : attempts)
                        .param("until", attempts >= MAX_ATTEMPTS ? java.sql.Timestamp.from(now.plus(LOCK)) : null)
                        .param("customer", customerId).update();
                throw BusinessException.unprocessable("PIN_INCORRECT", "Mã PIN hiện tại không đúng.");
            }
        }
        jdbc.sql("""
                insert into payments.payment_pins (customer_id, pin_hash) values (:customer, :hash)
                on conflict (customer_id) do update set pin_hash = :hash, failed_attempts = 0, locked_until = null, updated_at = now()""")
                .param("customer", customerId).param("hash", encoder.encode(request.newPin())).update();
    }

    private void owned(UUID customerId, UUID methodId) {
        boolean mine = jdbc.sql("select count(*) from payments.payment_methods where id = :id and customer_id = :customer")
                .param("id", methodId).param("customer", customerId).query(Long.class).single() > 0;
        if (!mine) {
            throw BusinessException.notFound("PAYMENT_METHOD_NOT_FOUND", "Không tìm thấy phương thức thanh toán này.");
        }
    }

    private Pin storedPin(UUID customerId) {
        return jdbc.sql("select pin_hash, failed_attempts, locked_until from payments.payment_pins where customer_id = :customer")
                .param("customer", customerId)
                .query((rs, i) -> new Pin(rs.getString(1), rs.getInt(2),
                        rs.getTimestamp(3) == null ? null : rs.getTimestamp(3).toInstant()))
                .optional().orElse(null);
    }

    /** MoMo is a phone number: shown the way MoMo shows it, all but the last four hidden. */
    private static PaymentSettingsViews.Method view(UUID id, String provider, String last4, boolean primary) {
        return new PaymentSettingsViews.Method(id, provider, PROVIDERS.getOrDefault(provider, provider), "******" + last4, primary);
    }

    private record Pin(String hash, int failedAttempts, Instant lockedUntil) {
    }
}
