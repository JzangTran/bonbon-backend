package com.bonbon.backend.identity.authentication;

import java.sql.Timestamp;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import com.bonbon.backend.identity.authentication.repository.UserRepository;
import com.bonbon.backend.identity.authentication.service.TokenService;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * The identity side of deleting an account (flows/account/delete-account.md): marking it pending, undoing that when the person signs
 * in again, and at the end anonymizing it in place. The row is kept so orders, revenue and audit references stay valid; what makes
 * it a person (name, email, phone, avatar, password, sign-in methods, tokens) goes.
 */
@Service
public class AccountLifecycle {

    public static final String DELETED_NAME = "Người dùng đã xóa";

    /** {@code status} is ACTIVE, PENDING_DELETION or DELETED. */
    public record State(String status, String email, boolean hasPassword, Instant scheduledAt) {
    }

    private final JdbcClient jdbc;
    private final PasswordEncoder passwords;
    private final UserRepository users;
    private final TokenService tokens;

    AccountLifecycle(JdbcClient jdbc, PasswordEncoder passwords, UserRepository users, TokenService tokens) {
        this.jdbc = jdbc;
        this.passwords = passwords;
        this.users = users;
        this.tokens = tokens;
    }

    @Transactional(readOnly = true)
    public Optional<State> state(UUID userId) {
        return jdbc.sql("select status, email, password_hash is not null as has_password, deletion_scheduled_at from users where id = :id").param("id", userId)
                .query((rs, n) -> new State(rs.getString("status"), rs.getString("email"), rs.getBoolean("has_password"),
                        rs.getTimestamp("deletion_scheduled_at") == null ? null : rs.getTimestamp("deletion_scheduled_at").toInstant()))
                .optional();
    }

    @Transactional(readOnly = true)
    public boolean passwordMatches(UUID userId, String raw) {
        return raw != null && jdbc.sql("select password_hash from users where id = :id").param("id", userId).query(String.class).optional()
                .map(hash -> hash != null && passwords.matches(raw, hash)).orElse(false);
    }

    /** Every session ends now: access tokens issued before this moment are refused and the refresh tokens are revoked. */
    @Transactional
    public void markPendingDeletion(UUID userId, Instant now, Instant scheduledAt) {
        jdbc.sql("update users set status = 'PENDING_DELETION', deletion_requested_at = :now, deletion_scheduled_at = :at, updated_at = :now where id = :id and status = 'ACTIVE'")
                .param("now", Timestamp.from(now)).param("at", Timestamp.from(scheduledAt)).param("id", userId).update();
        // Ends every session the usual way (also refreshes the cached stamp the access-token check reads).
        users.findById(userId).ifPresent(tokens::invalidateAllSessions);
    }

    /** Signing in again during the grace period: true when a deletion was cancelled. */
    @Transactional
    public boolean restoreIfPending(UUID userId) {
        return jdbc.sql("update users set status = 'ACTIVE', deletion_requested_at = null, deletion_scheduled_at = null where id = :id and status = 'PENDING_DELETION'")
                .param("id", userId).update() > 0;
    }

    @Transactional(readOnly = true)
    public List<UUID> dueForDeletion(Instant now) {
        return jdbc.sql("select id from users where status = 'PENDING_DELETION' and deletion_scheduled_at <= :now order by deletion_scheduled_at, id").param("now", Timestamp.from(now))
                .query(UUID.class).list();
    }

    /**
     * Anonymizes the identity in place and returns the address it had, so the person can be told it is done. The address becomes free
     * for a new registration; the sign-in methods are released so the same Google account can register again as someone new.
     * Repeating it on an account that is already deleted changes nothing and returns null.
     */
    @Transactional
    public String anonymize(UUID userId, Instant now) {
        Optional<String> email = jdbc.sql("select email from users where id = :id and status = 'PENDING_DELETION'").param("id", userId).query(String.class).optional();
        if (email.isEmpty()) {
            return null;
        }
        jdbc.sql("""
                update users set status = 'DELETED', deleted_at = :now, name = :name, email = :email, phone = null, avatar_url = null, password_hash = null,
                       email_verified = false, tokens_valid_after = :now, deletion_scheduled_at = null, updated_at = :now where id = :id""")
                .param("now", Timestamp.from(now)).param("name", DELETED_NAME).param("email", "deleted-" + userId + "@deleted.invalid").param("id", userId).update();
        jdbc.sql("delete from user_auth_providers where user_id = :id").param("id", userId).update();
        jdbc.sql("delete from refresh_tokens where user_id = :id").param("id", userId).update();
        jdbc.sql("delete from email_verification_tokens where user_id = :id").param("id", userId).update();
        jdbc.sql("delete from password_reset_tokens where user_id = :id").param("id", userId).update();
        return email.get();
    }
}
