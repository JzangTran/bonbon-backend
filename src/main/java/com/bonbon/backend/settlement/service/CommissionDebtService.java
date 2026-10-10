package com.bonbon.backend.settlement.service;

import java.sql.Timestamp;
import java.time.Clock;
import java.time.DayOfWeek;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.time.temporal.ChronoUnit;
import java.time.temporal.TemporalAdjusters;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

import com.bonbon.backend.common.exception.BusinessException;
import com.bonbon.backend.common.security.CurrentPrincipal;
import com.bonbon.backend.common.settings.SystemSettingsService;
import com.bonbon.backend.merchant.ShopCommissionStanding;
import com.bonbon.backend.merchant.ShopCommissionStanding.Stage;
import com.bonbon.backend.merchant.ShopOrdering;
import com.bonbon.backend.settlement.CommissionStageChanged;
import com.bonbon.backend.settlement.CommissionStatementIssued;
import com.bonbon.backend.settlement.CommissionStatementReminder;
import com.bonbon.backend.settlement.dto.DebtRequests;
import com.bonbon.backend.settlement.dto.DebtViews;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.context.event.EventListener;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Commission a shop owes and what happens when it is not paid (flows/settlement/collect-commission-debt.md).
 *
 * <p>Nothing about "how much is still unpaid" is stored: a statement bills the balance at its closing instant, and the
 * credits posted after it pay it off, oldest statement first, so the ledger stays the only truth. What is stored is the
 * statements themselves, the notices sent, and the stage cached on the shop. Every method is safe to run twice and a
 * missed run is caught by the next, because each one recomputes from the ledger and the clock it is given.
 */
@Service
public class CommissionDebtService {

    private static final ZoneId VIETNAM = ZoneId.of("Asia/Ho_Chi_Minh");
    private static final int MAX_EXTENSION_DAYS = 30;

    private final JdbcClient jdbc;
    private final Clock clock;
    private final SystemSettingsService settings;
    private final ShopCommissionStanding standing;
    private final ShopOrdering shops;
    private final ApplicationEventPublisher events;

    CommissionDebtService(JdbcClient jdbc, Clock clock, SystemSettingsService settings, ShopCommissionStanding standing, ShopOrdering shops,
            ApplicationEventPublisher events) {
        this.jdbc = jdbc;
        this.clock = clock;
        this.settings = settings;
        this.standing = standing;
        this.shops = shops;
        this.events = events;
    }

    /** Every number below is a project assumption kept as a setting, except the 5-day notice, the legal minimum. */
    record Rules(int dueDays, int minAmount, int reminderDays, int noticeAfterDays, int restrictAfterDays, int pauseAfterDays, int reviewAfterDays,
            long debtLimit, int limitDueDays, int noticeDays) {
    }

    Rules rules() {
        return new Rules(settings.getInt("settlement.debt_due_days", 7), settings.getInt("settlement.min_statement_amount", 50_000),
                settings.getInt("settlement.debt_reminder_days", 2), settings.getInt("settlement.debt_notice_after_days", 1),
                settings.getInt("settlement.debt_restrict_after_days", 7), settings.getInt("settlement.debt_pause_after_days", 14),
                settings.getInt("settlement.debt_review_after_days", 30), settings.getLong("settlement.debt_limit", 2_000_000),
                settings.getInt("settlement.debt_limit_due_days", 3), settings.getInt("legal.seller_restriction_notice_days", 5));
    }

    private record StatementRow(UUID id, UUID vendorId, String kind, LocalDate periodStart, Instant periodEnd, long amountDue, Instant dueAt,
            String status, Instant reminderSentAt, Instant extendedAt, String extensionReason) {
    }

    private static final org.springframework.jdbc.core.RowMapper<StatementRow> ROW = (rs, n) -> new StatementRow(rs.getObject("id", UUID.class),
            rs.getObject("vendor_id", UUID.class), rs.getString("kind"), rs.getDate("period_start").toLocalDate(), rs.getTimestamp("period_end").toInstant(),
            rs.getLong("amount_due"), rs.getTimestamp("due_at").toInstant(), rs.getString("status"),
            rs.getTimestamp("reminder_sent_at") == null ? null : rs.getTimestamp("reminder_sent_at").toInstant(),
            rs.getTimestamp("extended_at") == null ? null : rs.getTimestamp("extended_at").toInstant(), rs.getString("extension_reason"));

    private static final String ROW_COLUMNS = "id, vendor_id, kind, period_start, period_end, amount_due, due_at, status, reminder_sent_at, extended_at, extension_reason";

    // --- the jobs

    /** Both jobs in one: bills last week, bills shops over the limit, then reminds and escalates every shop with an open statement. */
    @Transactional
    public void runDaily(Instant now) {
        issueWeekly(now);
        for (UUID vendor : jdbc.sql("select distinct vendor_id from commission_statements where status <> 'PAID'").query(UUID.class).list()) {
            refresh(vendor, now);
        }
    }

    /** Bills the calendar week (Monday to Monday, Vietnam time) that ended last, to each shop that ended it owing at least the minimum. */
    @Transactional
    public int issueWeekly(Instant now) {
        Rules r = rules();
        LocalDate monday = now.atZone(VIETNAM).toLocalDate().with(TemporalAdjusters.previousOrSame(DayOfWeek.MONDAY));
        Instant end = monday.atStartOfDay(VIETNAM).toInstant();
        List<UUID> owing = jdbc.sql("select vendor_id from ledger_entries where created_at < :end group by vendor_id having sum(amount) <= :min")
                .param("end", Timestamp.from(end)).param("min", -(long) r.minAmount()).query(UUID.class).list();
        int issued = 0;
        for (UUID vendor : owing) {
            lock(vendor);
            syncStatuses(vendor, now);
            long debt = -balanceBefore(vendor, end);
            if (debt < r.minAmount() || coveredByOpenStatement(vendor, debt, null)) {
                continue;
            }
            if (insertStatement(vendor, monday.minusDays(7), end, "WEEKLY", debt, end.plus(r.dueDays(), ChronoUnit.DAYS), now)) {
                issued++;
            }
            refresh(vendor, now);
        }
        return issued;
    }

    // --- recomputing one shop

    @EventListener
    void onPosted(LedgerService.Posted posted) {
        refresh(posted.vendorId(), clock.instant());
    }

    /**
     * Brings one shop up to date with the ledger and the clock: bills it at once when its debt passed the limit, settles
     * the statuses, sends reminders, and moves it between stages. It lifts every restriction the moment nothing is overdue.
     */
    @Transactional
    public void refresh(UUID vendorId, Instant now) {
        lock(vendorId);
        Rules r = rules();
        long debt = Math.max(0, -balanceBefore(vendorId, null));
        if (debt > r.debtLimit()) {
            Instant end = now.truncatedTo(ChronoUnit.MICROS).plus(1, ChronoUnit.MICROS);
            if (!coveredByOpenStatement(vendorId, debt, now.plus(r.limitDueDays(), ChronoUnit.DAYS))) {
                insertStatement(vendorId, now.atZone(VIETNAM).toLocalDate(), end, "LIMIT", debt, now.plus(r.limitDueDays(), ChronoUnit.DAYS), now);
            }
        }
        List<StatementRow> rows = syncStatuses(vendorId, now);
        for (StatementRow s : rows) {
            if (!"PAID".equals(s.status()) && s.reminderSentAt() == null && !now.isBefore(s.dueAt().minus(r.reminderDays(), ChronoUnit.DAYS))
                    && s.dueAt().isAfter(now)) {
                jdbc.sql("update commission_statements set reminder_sent_at = :at where id = :id").param("at", Timestamp.from(now)).param("id", s.id()).update();
                events.publishEvent(new CommissionStatementReminder(vendorId, s.id(), unpaid(s), s.dueAt()));
            }
        }
        escalate(vendorId, overdueAmount(rows, now), rows, now, r);
    }

    /** Sets each statement's status from the ledger and returns them, oldest first. */
    private List<StatementRow> syncStatuses(UUID vendorId, Instant now) {
        List<StatementRow> rows = statements(vendorId, true);
        List<StatementRow> result = new ArrayList<>();
        for (StatementRow s : rows) {
            String status = unpaid(s) == 0 ? "PAID" : !s.dueAt().isAfter(now) ? "OVERDUE" : "OPEN";
            if (!status.equals(s.status())) {
                jdbc.sql("update commission_statements set status = :status where id = :id").param("status", status).param("id", s.id()).update();
            }
            result.add(new StatementRow(s.id(), s.vendorId(), s.kind(), s.periodStart(), s.periodEnd(), s.amountDue(), s.dueAt(), status,
                    s.reminderSentAt(), s.extendedAt(), s.extensionReason()));
        }
        return result;
    }

    private long unpaid(StatementRow s) {
        return Math.max(0, s.amountDue() - creditsSince(s.vendorId(), s.periodEnd()));
    }

    /**
     * {@code max(0, amountDue − credits since the closing instant)} for the latest statement already due. Debits posted
     * after it are not yet due. Commission held back for an undecided order case would be subtracted here too; order
     * cases do not exist yet, so nothing is held.
     */
    private long overdueAmount(List<StatementRow> rows, Instant now) {
        StatementRow latestDue = null;
        for (StatementRow s : rows) {
            if (!s.dueAt().isAfter(now)) {
                latestDue = s;
            }
        }
        return latestDue == null ? 0 : unpaid(latestDue);
    }

    // --- escalation

    private void escalate(UUID vendorId, long overdue, List<StatementRow> rows, Instant now, Rules r) {
        ShopCommissionStanding.Standing current = standing.of(vendorId);
        if (overdue == 0) {
            if (current.stage() != Stage.NONE || current.overdueSince() != null) {
                standing.set(vendorId, Stage.NONE, null);
                if (current.stage() != Stage.NONE) {
                    events.publishEvent(new CommissionStageChanged(vendorId, Stage.NONE.name(), 0, null));
                }
            }
            return;
        }
        Instant since = current.overdueSince();
        if (since == null) {
            since = rows.stream().filter(s -> !s.dueAt().isAfter(now)).map(StatementRow::dueAt).reduce((a, b) -> b).orElse(now);
        }
        Stage stage = current.stage();
        Stage target = stage;
        if (target == Stage.NONE && !now.isBefore(since.plus(r.noticeAfterDays(), ChronoUnit.DAYS))) {
            target = Stage.OVERDUE;
            recordNotice(vendorId, "RESTRICT", since, now);
        }
        if (target == Stage.OVERDUE && !now.isBefore(restrictAt(vendorId, since, r))) {
            target = Stage.RESTRICTED;
            recordNotice(vendorId, "PAUSE", since, now);
        }
        if (target == Stage.RESTRICTED && !now.isBefore(pauseAt(vendorId, since, r))) {
            target = Stage.PAUSED;
        }
        if (target == Stage.PAUSED && !now.isBefore(since.plus(r.reviewAfterDays(), ChronoUnit.DAYS))) {
            target = Stage.REVIEW;
        }
        if (target != stage || current.overdueSince() == null) {
            standing.set(vendorId, target, since);
        }
        if (target != stage) {
            Instant next = switch (target) {
                case OVERDUE -> restrictAt(vendorId, since, r);
                case RESTRICTED -> pauseAt(vendorId, since, r);
                case PAUSED -> since.plus(r.reviewAfterDays(), ChronoUnit.DAYS);
                default -> null;
            };
            events.publishEvent(new CommissionStageChanged(vendorId, target.name(), overdue, next));
        }
    }

    /** Never earlier than the notice plus the legal minimum: if a notice went out late, the step waits. */
    private Instant restrictAt(UUID vendorId, Instant since, Rules r) {
        Instant sent = noticeSent(vendorId, "RESTRICT", since);
        Instant notice = sent != null ? sent : since.plus(r.noticeAfterDays(), ChronoUnit.DAYS);
        return later(since.plus(r.restrictAfterDays(), ChronoUnit.DAYS), notice.plus(r.noticeDays(), ChronoUnit.DAYS));
    }

    private Instant pauseAt(UUID vendorId, Instant since, Rules r) {
        Instant sent = noticeSent(vendorId, "PAUSE", since);
        Instant notice = sent != null ? sent : restrictAt(vendorId, since, r);
        return later(since.plus(r.pauseAfterDays(), ChronoUnit.DAYS), notice.plus(r.noticeDays(), ChronoUnit.DAYS));
    }

    private static Instant later(Instant a, Instant b) {
        return a.isAfter(b) ? a : b;
    }

    private Instant noticeSent(UUID vendorId, String step, Instant since) {
        return jdbc.sql("select sent_at from commission_notices where vendor_id = :v and step = :step and overdue_since = :since")
                .param("v", vendorId).param("step", step).param("since", Timestamp.from(since)).query((rs, n) -> rs.getTimestamp("sent_at").toInstant()).optional().orElse(null);
    }

    private void recordNotice(UUID vendorId, String step, Instant since, Instant now) {
        jdbc.sql("insert into commission_notices (vendor_id, step, overdue_since, sent_at) values (:v, :step, :since, :at) on conflict do nothing")
                .param("v", vendorId).param("step", step).param("since", Timestamp.from(since)).param("at", Timestamp.from(now)).update();
    }

    // --- statements

    private boolean insertStatement(UUID vendorId, LocalDate periodStart, Instant periodEnd, String kind, long amount, Instant dueAt, Instant now) {
        UUID id = jdbc.sql("""
                insert into commission_statements (vendor_id, period_start, period_end, kind, amount_due, due_at, original_due_at, created_at)
                values (:v, :start, :end, :kind, :amount, :due, :due, :now)
                on conflict (vendor_id, period_start) do nothing returning id""")
                .param("v", vendorId).param("start", java.sql.Date.valueOf(periodStart)).param("end", Timestamp.from(periodEnd)).param("kind", kind)
                .param("amount", amount).param("due", Timestamp.from(dueAt)).param("now", Timestamp.from(now)).query(UUID.class).optional().orElse(null);
        if (id == null) {
            return false;
        }
        events.publishEvent(new CommissionStatementIssued(vendorId, id, amount, dueAt));
        return true;
    }

    /** An unpaid statement already bills at least {@code amount}, or (when {@code dueBefore} is given) is already due that soon. */
    private boolean coveredByOpenStatement(UUID vendorId, long amount, Instant dueBefore) {
        return jdbc.sql("""
                select exists (select 1 from commission_statements where vendor_id = :v and status <> 'PAID'
                               and (amount_due >= :amount or (cast(:due as timestamptz) is not null and due_at <= cast(:due as timestamptz))))""")
                .param("v", vendorId).param("amount", amount).param("due", dueBefore == null ? null : Timestamp.from(dueBefore)).query(Boolean.class).single();
    }

    private List<StatementRow> statements(UUID vendorId, boolean oldestFirst) {
        return jdbc.sql("select " + ROW_COLUMNS + " from commission_statements where vendor_id = :v order by period_end " + (oldestFirst ? "asc" : "desc") + ", id")
                .param("v", vendorId).query(ROW).list();
    }

    // --- reading and changing

    /** The shop's own view: where it stands and every statement, newest first. */
    @Transactional(readOnly = true)
    public DebtViews.Statements statementsFor(CurrentPrincipal caller) {
        UUID vendor = shops.operatingVendorOwnedBy(caller.id()).orElseThrow(() -> new BusinessException(HttpStatus.CONFLICT, "SHOP_NOT_APPROVED",
                "Bạn chưa có cửa hàng được duyệt."));
        return view(vendor);
    }

    @Transactional(readOnly = true)
    public DebtViews.Statements view(UUID vendorId) {
        Instant now = clock.instant();
        Rules r = rules();
        List<StatementRow> rows = statements(vendorId, true);
        ShopCommissionStanding.Standing current = standing.of(vendorId);
        long overdue = overdueAmount(rows, now);
        Instant since = current.overdueSince();
        long owed = Math.max(0, -balanceBefore(vendorId, null));
        DebtViews.Standing view = since == null || overdue == 0
                ? new DebtViews.Standing(current.stage().name(), owed, overdue, null, null, null, null)
                : new DebtViews.Standing(current.stage().name(), owed, overdue, since, restrictAt(vendorId, since, r), pauseAt(vendorId, since, r),
                        later(since.plus(r.reviewAfterDays(), ChronoUnit.DAYS), pauseAt(vendorId, since, r)));
        List<DebtViews.Statement> items = new ArrayList<>();
        for (int i = rows.size() - 1; i >= 0; i--) {
            items.add(toView(rows.get(i), now));
        }
        return new DebtViews.Statements(view, items);
    }

    private DebtViews.Statement toView(StatementRow s, Instant now) {
        long unpaid = unpaid(s);
        String status = unpaid == 0 ? "PAID" : !s.dueAt().isAfter(now) ? "OVERDUE" : "OPEN";
        return new DebtViews.Statement(s.id(), s.kind(), s.periodStart(), s.periodEnd(), s.amountDue(), unpaid, s.dueAt(), status, s.extendedAt() != null,
                s.extensionReason());
    }

    /** Moves the due date of an open statement, with a reason on record. The escalation clock restarts from the new date. */
    @Transactional
    public DebtViews.Statement extend(CurrentPrincipal admin, UUID statementId, DebtRequests.Extend request) {
        StatementRow s = jdbc.sql("select " + ROW_COLUMNS + " from commission_statements where id = :id").param("id", statementId).query(ROW)
                .optional().orElseThrow(() -> BusinessException.notFound("STATEMENT_NOT_FOUND", "Không tìm thấy sao kê."));
        lock(s.vendorId());
        Instant now = clock.instant();
        if (unpaid(s) == 0) {
            throw BusinessException.conflict("STATEMENT_PAID", "Sao kê này đã được trả đủ, không cần gia hạn.");
        }
        if (!request.dueAt().isAfter(s.dueAt())) {
            throw new BusinessException(HttpStatus.BAD_REQUEST, "DUE_DATE_INVALID", "Hạn mới phải sau hạn hiện tại.");
        }
        if (request.dueAt().isAfter(now.plus(MAX_EXTENSION_DAYS, ChronoUnit.DAYS))) {
            throw new BusinessException(HttpStatus.BAD_REQUEST, "DUE_DATE_TOO_FAR", "Chỉ gia hạn tối đa " + MAX_EXTENSION_DAYS + " ngày kể từ hôm nay.");
        }
        jdbc.sql("""
                update commission_statements set due_at = :due, extended_by_id = :admin, extended_at = :now, extension_reason = :reason,
                       reminder_sent_at = null where id = :id""")
                .param("due", Timestamp.from(request.dueAt())).param("admin", admin.id()).param("now", Timestamp.from(now))
                .param("reason", request.reason().strip()).param("id", s.id()).update();
        refresh(s.vendorId(), now);
        return toView(statements(s.vendorId(), true).stream().filter(x -> x.id().equals(s.id())).findFirst().orElseThrow(), now);
    }

    // --- ledger reads

    private long balanceBefore(UUID vendorId, Instant end) {
        return jdbc.sql("select coalesce(sum(amount), 0) from ledger_entries where vendor_id = :v and (cast(:end as timestamptz) is null or created_at < cast(:end as timestamptz))")
                .param("v", vendorId).param("end", end == null ? null : Timestamp.from(end)).query(Long.class).single();
    }

    private long creditsSince(UUID vendorId, Instant instant) {
        return jdbc.sql("select coalesce(sum(amount), 0) from ledger_entries where vendor_id = :v and amount > 0 and created_at >= :at")
                .param("v", vendorId).param("at", Timestamp.from(instant)).query(Long.class).single();
    }

    /** One shop at a time, the same lock a payout takes, so the balance read here is still true when written. */
    private void lock(UUID vendorId) {
        jdbc.sql("select pg_advisory_xact_lock(hashtextextended(:v, 0))").param("v", vendorId.toString()).query().singleRow();
    }
}
