package com.bonbon.backend.identity.legal.service;

import java.nio.charset.StandardCharsets;
import java.sql.Timestamp;
import java.time.Clock;
import java.time.DayOfWeek;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneId;
import java.time.ZonedDateTime;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import com.bonbon.backend.common.exception.BusinessException;
import com.bonbon.backend.common.ratelimit.RateLimiter;
import com.bonbon.backend.common.security.CurrentPrincipal;
import com.bonbon.backend.common.storage.ObjectStorage;
import com.bonbon.backend.common.storage.ObjectStorage.Visibility;
import com.bonbon.backend.common.storage.ValidatedFile;
import com.bonbon.backend.identity.legal.DataRequestClosed;
import com.bonbon.backend.identity.legal.PersonalDataSource;
import com.bonbon.backend.identity.legal.dto.DataRequestDtos;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import tools.jackson.databind.json.JsonMapper;

/**
 * A person's requests about their personal data (data-subject-requests.md). The copy of one's data is self-service: the file is built at
 * once from every module's {@link PersonalDataSource} and handed out as a short signed link; it still writes a completed request, so the
 * whole history is in one place. Correcting, withdrawing, restricting and objecting go to an administrator, with the deadlines the law's
 * reading in flows/legal/README.md gives (all of them project settings, not verified numbers).
 */
@Service
public class DataRequestService {

    static final int ACK_WORKING_DAYS = 2;
    static final Map<String, Integer> COMPLETE_DAYS = Map.of("ACCESS", 10, "CORRECT", 10, "WITHDRAW", 15, "RESTRICT", 15, "OBJECT", 15, "DELETE", 20);
    private static final Duration LINK_TTL = Duration.ofMinutes(15);
    private static final Duration DOWNLOAD_WINDOW = Duration.ofDays(7);
    private static final ZoneId VN = ZoneId.of("Asia/Ho_Chi_Minh");
    private static final JsonMapper JSON = JsonMapper.builder().build();
    private static final int MAX_PAGE_SIZE = 100;

    private final JdbcClient jdbc;
    private final Clock clock;
    private final ObjectStorage storage;
    private final RateLimiter limiter;
    private final List<PersonalDataSource> sources;
    private final ApplicationEventPublisher events;

    DataRequestService(JdbcClient jdbc, Clock clock, ObjectStorage storage, RateLimiter limiter, List<PersonalDataSource> sources,
            ApplicationEventPublisher events) {
        this.jdbc = jdbc;
        this.clock = clock;
        this.storage = storage;
        this.limiter = limiter;
        this.sources = sources;
        this.events = events;
    }

    // --- the person

    /** Builds the file now: profile, addresses, consents, orders, reviews, messages they sent, their shop. Once an hour. */
    @Transactional
    public DataRequestDtos.View export(CurrentPrincipal caller) {
        if (!limiter.tryAcquire("data-export:" + caller.id(), 1, Duration.ofHours(1))) {
            throw new BusinessException(HttpStatus.TOO_MANY_REQUESTS, "EXPORT_TOO_SOON", "Bạn vừa tải dữ liệu. Hãy thử lại sau một giờ.");
        }
        Map<String, Object> file = new LinkedHashMap<>();
        file.put("generatedAt", clock.instant());
        file.put("about", "Dữ liệu cá nhân bonbon lưu về bạn. Giao dịch được giữ theo nghĩa vụ lưu trữ của pháp luật.");
        for (PersonalDataSource source : sources) {
            Object data = source.dataOf(caller.id());
            if (data != null) {
                file.put(source.section(), data);
            }
        }
        byte[] bytes = JSON.writerWithDefaultPrettyPrinter().writeValueAsString(file).getBytes(StandardCharsets.UTF_8);
        String key = storage.put(Visibility.PRIVATE, "exports/" + caller.id(), new ValidatedFile(bytes, ValidatedFile.FileType.JSON));
        Instant now = clock.instant();
        UUID id = UUID.randomUUID();
        jdbc.sql("""
                insert into identity.data_subject_requests (id, user_id, audience, type, status, received_at, ack_due_at, due_at, closed_at, export_key, response_note)
                values (:id, :u, :aud, 'ACCESS', 'COMPLETED', :now, :now, :now, :now, :key, 'Tự phục vụ: tệp được tạo ngay.')""")
                .param("id", id).param("u", caller.id()).param("aud", audience(caller)).param("now", Timestamp.from(now)).param("key", key).update();
        return mine(caller).stream().filter(v -> v.id().equals(id)).findFirst().orElseThrow();
    }

    @Transactional
    public DataRequestDtos.View create(CurrentPrincipal caller, DataRequestDtos.Create request) {
        long open = jdbc.sql("select count(*) from identity.data_subject_requests where user_id = :u and status in ('RECEIVED', 'IN_PROGRESS')")
                .param("u", caller.id()).query(Long.class).single();
        if (open >= 3) {
            throw BusinessException.conflict("TOO_MANY_OPEN_REQUESTS", "Bạn đang có 3 yêu cầu chưa xử lý xong. Hãy chờ kết quả trước khi gửi thêm.");
        }
        Instant now = clock.instant();
        UUID id = UUID.randomUUID();
        jdbc.sql("""
                insert into identity.data_subject_requests (id, user_id, audience, type, details, status, received_at, ack_due_at, due_at)
                values (:id, :u, :aud, :type, :details, 'RECEIVED', :now, :ack, :due)""")
                .param("id", id).param("u", caller.id()).param("aud", audience(caller)).param("type", request.type())
                .param("details", request.details().strip()).param("now", Timestamp.from(now))
                .param("ack", Timestamp.from(addWorkingDays(now, ACK_WORKING_DAYS)))
                .param("due", Timestamp.from(now.plus(Duration.ofDays(COMPLETE_DAYS.get(request.type()))))).update();
        return mine(caller).stream().filter(v -> v.id().equals(id)).findFirst().orElseThrow();
    }

    @Transactional(readOnly = true)
    public List<DataRequestDtos.View> mine(CurrentPrincipal caller) {
        Instant downloadable = clock.instant().minus(DOWNLOAD_WINDOW);
        return jdbc.sql("select * from identity.data_subject_requests where user_id = :u order by received_at desc limit 50").param("u", caller.id())
                .query((rs, n) -> {
                    Timestamp closed = rs.getTimestamp("closed_at");
                    String key = rs.getString("export_key");
                    String url = key != null && closed != null && closed.toInstant().isAfter(downloadable) ? storage.signedUrl(key, LINK_TTL) : null;
                    return new DataRequestDtos.View(rs.getObject("id", UUID.class), rs.getString("type"), rs.getString("status"), rs.getString("details"),
                            rs.getTimestamp("received_at").toInstant(), rs.getTimestamp("ack_due_at").toInstant(), rs.getTimestamp("due_at").toInstant(),
                            rs.getBoolean("extended"), rs.getString("extension_reason"), rs.getString("response_note"),
                            closed == null ? null : closed.toInstant(), url);
                }).list();
    }

    // --- administrators

    @Transactional(readOnly = true)
    public DataRequestDtos.Page queue(String status, int page, int size) {
        if (page < 0 || size < 1 || size > MAX_PAGE_SIZE) {
            throw new BusinessException(HttpStatus.BAD_REQUEST, "INVALID_PAGE", "Trang hoặc kích thước trang không hợp lệ.");
        }
        String s = status == null || status.isBlank() ? "OPEN" : status;
        if (!s.matches("OPEN|COMPLETED|REJECTED|ALL")) {
            throw new BusinessException(HttpStatus.BAD_REQUEST, "INVALID_STATUS", "Trạng thái không hợp lệ.");
        }
        String where = switch (s) {
            case "OPEN" -> " where r.status in ('RECEIVED', 'IN_PROGRESS')";
            case "ALL" -> " where true";
            default -> " where r.status = '" + s + "'";
        };
        long total = jdbc.sql("select count(*) from identity.data_subject_requests r" + where).query(Long.class).single();
        Instant now = clock.instant();
        List<DataRequestDtos.AdminView> items = jdbc.sql("""
                select r.*, u.email, u.name from identity.data_subject_requests r join identity.users u on u.id = r.user_id""" + where
                        + " order by r.due_at, r.received_at limit :limit offset :offset")
                .param("limit", size).param("offset", (long) page * size)
                .query((rs, n) -> {
                    Instant due = rs.getTimestamp("due_at").toInstant();
                    String st = rs.getString("status");
                    Timestamp closed = rs.getTimestamp("closed_at");
                    return new DataRequestDtos.AdminView(rs.getObject("id", UUID.class), rs.getObject("user_id", UUID.class), rs.getString("email"),
                            rs.getString("name"), rs.getString("audience"), rs.getString("type"), st, rs.getString("details"),
                            rs.getTimestamp("received_at").toInstant(), rs.getTimestamp("ack_due_at").toInstant(), due,
                            (st.equals("RECEIVED") || st.equals("IN_PROGRESS")) && due.isBefore(now), rs.getBoolean("extended"),
                            rs.getString("extension_reason"), rs.getString("response_note"), closed == null ? null : closed.toInstant());
                }).list();
        return new DataRequestDtos.Page(items, page, size, total);
    }

    @Transactional
    public void handle(UUID adminId, UUID id, DataRequestDtos.Handle request) {
        record R(UUID user, String audience, String type, String status, boolean extended, Timestamp due) {
        }
        R r = jdbc.sql("select user_id, audience, type, status, extended, due_at from identity.data_subject_requests where id = :id for update").param("id", id)
                .query((rs, n) -> new R(rs.getObject(1, UUID.class), rs.getString(2), rs.getString(3), rs.getString(4), rs.getBoolean(5), rs.getTimestamp(6)))
                .optional().orElseThrow(() -> BusinessException.notFound("DATA_REQUEST_NOT_FOUND", "Không tìm thấy yêu cầu."));
        if (!r.status().equals("RECEIVED") && !r.status().equals("IN_PROGRESS")) {
            throw BusinessException.conflict("DATA_REQUEST_CLOSED", "Yêu cầu đã đóng.");
        }
        String note = request.note() == null ? null : request.note().strip();
        Timestamp now = Timestamp.from(clock.instant());
        switch (request.action()) {
            case "START" -> jdbc.sql("update identity.data_subject_requests set status = 'IN_PROGRESS', handled_by = :a where id = :id")
                    .param("a", adminId).param("id", id).update();
            case "EXTEND" -> {
                if (r.extended()) {
                    throw BusinessException.conflict("ALREADY_EXTENDED", "Mỗi yêu cầu chỉ được gia hạn một lần.");
                }
                if (note == null || note.isEmpty() || request.extendDays() == null) {
                    throw new BusinessException(HttpStatus.BAD_REQUEST, "EXTENSION_REASON_REQUIRED", "Gia hạn cần số ngày và lý do.");
                }
                jdbc.sql("update identity.data_subject_requests set extended = true, extension_reason = :n, due_at = due_at + make_interval(days => :d) where id = :id")
                        .param("n", note).param("d", request.extendDays()).param("id", id).update();
            }
            default -> {
                boolean completed = request.action().equals("COMPLETE");
                if (!completed && (note == null || note.isEmpty())) {
                    throw new BusinessException(HttpStatus.BAD_REQUEST, "REASON_REQUIRED", "Từ chối cần nêu lý do, ví dụ dữ liệu phải lưu theo luật.");
                }
                jdbc.sql("""
                        update identity.data_subject_requests set status = :s, response_note = :n, handled_by = :a, closed_at = :now where id = :id""")
                        .param("s", completed ? "COMPLETED" : "REJECTED").param("n", note).param("a", adminId).param("now", now).param("id", id).update();
                events.publishEvent(new DataRequestClosed(id, r.user(), r.audience(), r.type(), completed, note));
            }
        }
    }

    private static String audience(CurrentPrincipal caller) {
        return "SELLER".equals(caller.activeRole()) ? "SELLER" : "CUSTOMER";
    }

    /** Counts Monday to Friday in Vietnam time; public holidays are not known to the system, so they are not skipped. */
    static Instant addWorkingDays(Instant from, int days) {
        ZonedDateTime t = from.atZone(VN);
        int added = 0;
        while (added < days) {
            t = t.plusDays(1);
            if (t.getDayOfWeek() != DayOfWeek.SATURDAY && t.getDayOfWeek() != DayOfWeek.SUNDAY) {
                added++;
            }
        }
        return t.toInstant();
    }
}
