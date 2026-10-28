package com.bonbon.backend.identity.account.service;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

import com.bonbon.backend.identity.account.AccountDeletionRequested;
import com.bonbon.backend.identity.account.AccountErased;
import com.bonbon.backend.identity.account.DeletionBlocker;
import com.bonbon.backend.identity.account.dto.DeletionDtos;
import com.bonbon.backend.identity.authentication.AccountLifecycle;
import com.bonbon.backend.common.exception.BusinessException;
import com.bonbon.backend.common.security.CurrentPrincipal;
import com.bonbon.backend.common.settings.SystemSettingsService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * Deleting my account (flows/account/delete-account.md). The person asks, with a recent sign-in and a confirmation, once nothing
 * stands in the way; every session ends and the account waits for {@code account.deletion_grace_days} (signing in again cancels it);
 * then the identity is anonymized in place and the modules delete the personal data they hold. Orders, payments, the ledger,
 * reviews and chat history stay, under the name "Người dùng đã xóa". It is for the whole identity: both roles at once.
 */
@Service
public class AccountDeletionService {

    private static final Logger log = LoggerFactory.getLogger(AccountDeletionService.class);

    private final AccountLifecycle lifecycle;
    private final List<DeletionBlocker> blockers;
    private final SystemSettingsService settings;
    private final JdbcClient jdbc;
    private final Clock clock;
    private final ApplicationEventPublisher events;
    private final TransactionTemplate tx;

    AccountDeletionService(AccountLifecycle lifecycle, List<DeletionBlocker> blockers, SystemSettingsService settings, JdbcClient jdbc, Clock clock,
            ApplicationEventPublisher events, PlatformTransactionManager transactions) {
        this.lifecycle = lifecycle;
        this.blockers = blockers;
        this.settings = settings;
        this.jdbc = jdbc;
        this.clock = clock;
        this.events = events;
        this.tx = new TransactionTemplate(transactions);
    }

    @Transactional(readOnly = true)
    public DeletionDtos.Check check(CurrentPrincipal caller) {
        requirePerson(caller);
        AccountLifecycle.State state = lifecycle.state(caller.id()).orElseThrow();
        List<DeletionDtos.Blocker> found = blockersOf(caller.id());
        return new DeletionDtos.Check(found.isEmpty(), found, graceDays(), state.hasPassword(), state.email());
    }

    @Transactional
    public DeletionDtos.Scheduled request(CurrentPrincipal caller, Instant authTime, DeletionDtos.Delete request) {
        requirePerson(caller);
        Instant now = clock.instant();
        if (authTime == null || authTime.plus(Duration.ofMinutes(10)).isBefore(now)) {
            throw new BusinessException(HttpStatus.FORBIDDEN, "REAUTH_REQUIRED", "Hãy đăng nhập lại trước khi xoá tài khoản (trong vòng 10 phút).");
        }
        AccountLifecycle.State state = lifecycle.state(caller.id()).orElseThrow();
        if (!"ACTIVE".equals(state.status())) {
            throw BusinessException.conflict("ALREADY_PENDING", "Tài khoản đang chờ xoá.");
        }
        String typed = request.confirmation().strip();
        if (!typed.equals("DELETE") && !typed.equalsIgnoreCase(state.email())) {
            throw new BusinessException(HttpStatus.BAD_REQUEST, "CONFIRMATION_MISMATCH", "Hãy gõ DELETE hoặc đúng email của tài khoản để xác nhận.");
        }
        if (state.hasPassword() && !lifecycle.passwordMatches(caller.id(), request.password())) {
            throw new BusinessException(HttpStatus.UNAUTHORIZED, "INVALID_CREDENTIALS", "Mật khẩu không đúng.");
        }
        List<DeletionDtos.Blocker> found = blockersOf(caller.id());
        if (!found.isEmpty()) {
            throw BusinessException.conflict("DELETION_BLOCKED", "Còn việc chưa xong nên chưa xoá được tài khoản.").withProperty("blockers", found);
        }
        Instant executesAt = now.plus(Duration.ofDays(graceDays()));
        lifecycle.markPendingDeletion(caller.id(), now, executesAt);
        events.publishEvent(new AccountDeletionRequested(caller.id(), state.email(), executesAt));
        return new DeletionDtos.Scheduled(executesAt, graceDays());
    }

    /** Anonymizes every account whose grace period has ended, one transaction each; a failure is logged and tried again next time. Returns how many. */
    public int executeDue(Instant now) {
        int done = 0;
        for (UUID id : lifecycle.dueForDeletion(now)) {
            try {
                Boolean erased = tx.execute(status -> erase(id, now));
                if (Boolean.TRUE.equals(erased)) {
                    done++;
                }
            } catch (RuntimeException e) {
                log.warn("Could not delete account {}", id, e);
            }
        }
        return done;
    }

    private boolean erase(UUID userId, Instant now) {
        String formerEmail = lifecycle.anonymize(userId, now);
        if (formerEmail == null) {
            return false;
        }
        jdbc.sql("delete from addresses where customer_id = :id").param("id", userId).update();
        events.publishEvent(new AccountErased(userId, formerEmail));
        return true;
    }

    private List<DeletionDtos.Blocker> blockersOf(UUID userId) {
        List<DeletionDtos.Blocker> all = new ArrayList<>();
        for (DeletionBlocker b : blockers) {
            b.blockersOf(userId).forEach(x -> all.add(new DeletionDtos.Blocker(x.code(), x.message(), x.link(), x.count())));
        }
        return all;
    }

    private int graceDays() {
        return settings.getInt("account.deletion_grace_days", 7);
    }

    private static void requirePerson(CurrentPrincipal caller) {
        if (!"CUSTOMER".equals(caller.activeRole()) && !"SELLER".equals(caller.activeRole())) {
            throw new BusinessException(HttpStatus.FORBIDDEN, "NOT_A_PERSON_ACCOUNT", "Tài khoản quản trị không tự xoá được.");
        }
    }
}
