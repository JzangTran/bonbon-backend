package com.bonbon.backend.support.service;

import java.sql.Timestamp;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

import com.bonbon.backend.common.exception.BusinessException;
import com.bonbon.backend.common.ratelimit.RateLimiter;
import com.bonbon.backend.common.security.CurrentPrincipal;
import com.bonbon.backend.common.storage.ObjectStorage;
import com.bonbon.backend.marketplace.merchant.ShopNames;
import com.bonbon.backend.marketplace.merchant.ShopOrdering;
import com.bonbon.backend.ordering.order.ReviewReports;
import com.bonbon.backend.support.ReportDecided;
import com.bonbon.backend.support.ReportedMessages;
import com.bonbon.backend.support.dto.ReportDtos;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import tools.jackson.core.type.TypeReference;
import tools.jackson.databind.json.JsonMapper;

/**
 * Reporting content (flows/reporting/): a customer or a seller files a report with the evidence copied at that moment, an administrator
 * decides it with a reason, and the person affected may contest an action once. A report is a request for review, never a penalty on
 * its own: no number of reports hides anything, and the reported content stays as it is until someone decides.
 */
@Service
public class ReportService {

    static final int DAILY_LIMIT = 10;
    static final int CONTEXT_MESSAGES = 10;
    static final Duration RESPONSE_TARGET = Duration.ofHours(24);
    static final Duration CONTEST_WINDOW = Duration.ofDays(7);
    private static final Duration IMAGE_URL_TTL = Duration.ofMinutes(15);
    private static final JsonMapper JSON = JsonMapper.builder().build();
    private static final int MAX_PAGE_SIZE = 100;

    private final JdbcClient jdbc;
    private final Clock clock;
    private final RateLimiter limiter;
    private final ShopOrdering shops;
    private final ShopNames shopNames;
    private final ReviewReports reviews;
    private final ReportedMessages messages;
    private final ObjectStorage storage;
    private final ApplicationEventPublisher events;

    ReportService(JdbcClient jdbc, Clock clock, RateLimiter limiter, ShopOrdering shops, ShopNames shopNames, ReviewReports reviews,
            ReportedMessages messages, ObjectStorage storage, ApplicationEventPublisher events) {
        this.jdbc = jdbc;
        this.clock = clock;
        this.limiter = limiter;
        this.shops = shops;
        this.shopNames = shopNames;
        this.reviews = reviews;
        this.messages = messages;
        this.storage = storage;
        this.events = events;
    }

    /** Who the report is about and what it looked like when reported. */
    private record Target(UUID vendorId, UUID ownerUserId, String ownerAudience, String label, Map<String, Object> evidence) {
    }

    // --- filing

    @Transactional
    public ReportDtos.Receipt file(CurrentPrincipal caller, ReportDtos.Create request) {
        String side = switch (caller.activeRole()) {
            case "CUSTOMER" -> "CUSTOMER";
            case "SELLER" -> "SELLER";
            default -> throw new BusinessException(HttpStatus.FORBIDDEN, "NOT_A_REPORTER", "Chỉ khách và người bán mới báo cáo được.");
        };
        if ("OTHER".equals(request.reasonCode()) && (request.note() == null || request.note().isBlank())) {
            throw new BusinessException(HttpStatus.BAD_REQUEST, "NOTE_REQUIRED", "Hãy ghi rõ lý do khi chọn Khác.")
                    .withProperty("errors", List.of(Map.of("field", "note", "message", "Hãy ghi rõ lý do khi chọn Khác.")));
        }
        Optional<ReportDtos.Receipt> open = jdbc.sql("""
                select id, status, created_at from support.content_reports
                where reporter_user_id = :me and target_type = :type and target_id = :target and status = 'OPEN'""")
                .param("me", caller.id()).param("type", request.targetType()).param("target", request.targetId())
                .query((rs, n) -> new ReportDtos.Receipt(rs.getObject(1, UUID.class), rs.getString(2), rs.getTimestamp(3).toInstant(), true)).optional();
        if (open.isPresent()) {
            return open.get();
        }
        Target target = target(caller, side, request.targetType(), request.targetId());
        if (caller.id().equals(target.ownerUserId())) {
            throw new BusinessException(HttpStatus.BAD_REQUEST, "OWN_CONTENT", "Bạn không báo cáo được nội dung của chính mình.");
        }
        if (!limiter.tryAcquire("report-daily:" + caller.id(), DAILY_LIMIT, Duration.ofDays(1))) {
            throw new BusinessException(HttpStatus.TOO_MANY_REQUESTS, "TOO_MANY_REPORTS", "Bạn đã gửi " + DAILY_LIMIT + " báo cáo hôm nay. Hãy thử lại vào ngày mai.");
        }
        UUID id = UUID.randomUUID();
        Instant now = clock.instant();
        holdImages(id, target.evidence());
        jdbc.sql("""
                insert into support.content_reports (id, reporter_user_id, reporter_side, target_type, target_id, target_vendor_id, target_owner_user_id,
                    reason_code, note, evidence, created_at)
                values (:id, :me, :side, :type, :target, :vendor, :owner, :reason, :note, cast(:evidence as jsonb), :now)""")
                .param("id", id).param("me", caller.id()).param("side", side).param("type", request.targetType()).param("target", request.targetId())
                .param("vendor", target.vendorId()).param("owner", target.ownerUserId()).param("reason", request.reasonCode())
                .param("note", request.note() == null || request.note().isBlank() ? null : request.note().strip())
                .param("evidence", JSON.writeValueAsString(target.evidence())).param("now", Timestamp.from(now)).update();
        return new ReportDtos.Receipt(id, "OPEN", now, false);
    }

    private Target target(CurrentPrincipal caller, String side, String type, UUID id) {
        boolean seller = "SELLER".equals(side);
        UUID myShop = seller ? shops.operatingVendorOwnedBy(caller.id())
                .orElseThrow(() -> new BusinessException(HttpStatus.CONFLICT, "SHOP_NOT_APPROVED", "Bạn chưa có cửa hàng được duyệt.")) : null;
        return switch (type) {
            case "SHOP" -> {
                if (seller) {
                    throw new BusinessException(HttpStatus.FORBIDDEN, "NOT_ALLOWED", "Người bán không báo cáo cửa hàng khác ở đây; hãy gửi phiếu hỗ trợ.");
                }
                String name = shopNames.names(List.of(id)).get(id);
                UUID owner = shops.ownerOf(id).orElse(null);
                if (name == null || owner == null) {
                    throw notFound();
                }
                Map<String, Object> e = new LinkedHashMap<>();
                e.put("shopId", id);
                e.put("shopName", name);
                yield new Target(id, owner, "SHOP", name, e);
            }
            case "MESSAGE" -> {
                ReportedMessages.Evidence ev = messages.evidence(id, CONTEXT_MESSAGES).orElseThrow(ReportService::notFound);
                boolean participant = seller ? ev.vendorId().equals(myShop) : ev.customerId().equals(caller.id());
                // a person reports the other side's message, never their own side's
                if (!participant || ev.message().senderType().equals(seller ? "SHOP" : "CUSTOMER")) {
                    throw notFound();
                }
                boolean fromShop = "SHOP".equals(ev.message().senderType());
                UUID owner = fromShop ? shops.ownerOf(ev.vendorId()).orElse(null) : ev.customerId();
                Map<String, Object> e = new LinkedHashMap<>();
                e.put("conversationId", ev.conversationId());
                e.put("customerId", ev.customerId());
                e.put("vendorId", ev.vendorId());
                e.put("shopName", shopNames.names(List.of(ev.vendorId())).getOrDefault(ev.vendorId(), "Cửa hàng"));
                e.put("message", message(ev.message()));
                e.put("before", ev.before().stream().map(ReportService::message).toList());
                e.put("after", ev.after().stream().map(ReportService::message).toList());
                String text = ev.message().text() == null ? "[ảnh]" : ev.message().text();
                yield new Target(ev.vendorId(), owner, fromShop ? "SHOP" : "CUSTOMER", "Tin nhắn: " + abbreviate(text), e);
            }
            case "REVIEW" -> {
                ReviewReports.ReviewSnapshot r = reviews.review(id).orElseThrow(ReportService::notFound);
                if (seller && !r.vendorId().equals(myShop)) {
                    throw notFound();
                }
                Map<String, Object> e = new LinkedHashMap<>();
                e.put("reviewId", r.id());
                e.put("orderId", r.orderId());
                e.put("vendorId", r.vendorId());
                e.put("shopName", shopNames.names(List.of(r.vendorId())).getOrDefault(r.vendorId(), "Cửa hàng"));
                e.put("authorId", r.customerId());
                e.put("reviewerName", r.reviewerName());
                e.put("rating", r.rating());
                e.put("comment", r.comment());
                e.put("createdAt", r.createdAt());
                yield new Target(r.vendorId(), r.customerId(), "CUSTOMER", r.rating() + "★ " + abbreviate(r.comment() == null ? "" : r.comment()), e);
            }
            case "REVIEW_RESPONSE" -> {
                if (seller) {
                    throw new BusinessException(HttpStatus.FORBIDDEN, "NOT_ALLOWED", "Người bán không báo cáo phản hồi của quán.");
                }
                ReviewReports.ResponseSnapshot r = reviews.response(id).orElseThrow(ReportService::notFound);
                UUID owner = shops.ownerOf(r.vendorId()).orElse(null);
                Map<String, Object> e = new LinkedHashMap<>();
                e.put("responseId", r.id());
                e.put("reviewId", r.reviewId());
                e.put("vendorId", r.vendorId());
                e.put("shopName", shopNames.names(List.of(r.vendorId())).getOrDefault(r.vendorId(), "Cửa hàng"));
                e.put("text", r.text());
                e.put("createdAt", r.createdAt());
                yield new Target(r.vendorId(), owner, "SHOP", "Phản hồi của quán: " + abbreviate(r.text()), e);
            }
            default -> throw new BusinessException(HttpStatus.BAD_REQUEST, "INVALID_TYPE", "Loại nội dung không hợp lệ.");
        };
    }

    /**
     * Copies every picture in the evidence to a hold prefix, so deleting the original under the chat retention rule cannot remove it
     * while the report is open (report-content.md). The evidence then points at the copy; a copy that fails keeps the original key and
     * the report is still filed.
     */
    @SuppressWarnings("unchecked")
    private void holdImages(UUID reportId, Map<String, Object> evidence) {
        for (String part : List.of("message", "before", "after")) {
            Object value = evidence.get(part);
            List<Object> items = value instanceof List<?> l ? (List<Object>) l : value == null ? List.of() : List.of(value);
            for (Object item : items) {
                if (item instanceof Map<?, ?> m && m.get("imageKey") instanceof String key) {
                    try {
                        ((Map<String, Object>) m).put("imageKey", storage.copyPrivate(key, "hold/reports/" + reportId));
                        ((Map<String, Object>) m).put("originalImageKey", key);
                    } catch (RuntimeException e) {
                        org.slf4j.LoggerFactory.getLogger(ReportService.class).warn("Could not hold {} for report {}", key, reportId, e);
                    }
                }
            }
        }
    }

    // --- the administrators' queue

    @Transactional(readOnly = true)
    public ReportDtos.Page queue(String status, String targetType, int page, int size) {
        if (page < 0 || size < 1 || size > MAX_PAGE_SIZE) {
            throw new BusinessException(HttpStatus.BAD_REQUEST, "INVALID_PAGE", "Trang hoặc kích thước trang không hợp lệ.");
        }
        String s = status == null || status.isBlank() ? "OPEN" : status;
        if (!s.matches("OPEN|ACTIONED|DISMISSED|ALL")) {
            throw new BusinessException(HttpStatus.BAD_REQUEST, "INVALID_STATUS", "Trạng thái không hợp lệ.");
        }
        if (targetType != null && !targetType.isBlank() && !targetType.matches(ReportDtos.TARGETS)) {
            throw new BusinessException(HttpStatus.BAD_REQUEST, "INVALID_TYPE", "Loại nội dung không hợp lệ.");
        }
        String where = " where (:status = 'ALL' or status = :status) and (cast(:type as text) is null or target_type = :type)";
        Map<String, Object> params = new HashMap<>();
        params.put("status", s);
        params.put("type", targetType == null || targetType.isBlank() ? null : targetType);
        long total = jdbc.sql("select count(*) from support.content_reports" + where).params(params).query(Long.class).single();
        Instant overdueBefore = clock.instant().minus(RESPONSE_TARGET);
        List<ReportDtos.Row> items = jdbc.sql("""
                select id, target_type, target_id, evidence, reason_code, status, decision_code, contested_at, created_at
                from support.content_reports""" + where + " order by contested_at is null, created_at, id limit :limit offset :offset")
                .params(params).param("limit", size).param("offset", (long) page * size)
                .query((rs, n) -> {
                    Instant created = rs.getTimestamp("created_at").toInstant();
                    String st = rs.getString("status");
                    return new ReportDtos.Row(rs.getObject("id", UUID.class), rs.getString("target_type"), rs.getObject("target_id", UUID.class),
                            label(rs.getString("target_type"), read(rs.getString("evidence"))), rs.getString("reason_code"), st, rs.getString("decision_code"),
                            "OPEN".equals(st) && created.isBefore(overdueBefore), rs.getTimestamp("contested_at") != null, created);
                }).list();
        return new ReportDtos.Page(items, page, size, total);
    }

    @Transactional(readOnly = true)
    public ReportDtos.Detail detail(CurrentPrincipal admin, UUID id) {
        record R(UUID id, String type, UUID target, UUID vendor, UUID owner, UUID reporter, String side, String reason, String note, String evidence,
                String status, String decision, String decisionReason, Timestamp decidedAt, Timestamp contestedAt, String contestNote, Timestamp createdAt) {
        }
        R r = jdbc.sql("select * from support.content_reports where id = :id").param("id", id)
                .query((rs, n) -> new R(rs.getObject("id", UUID.class), rs.getString("target_type"), rs.getObject("target_id", UUID.class),
                        rs.getObject("target_vendor_id", UUID.class), rs.getObject("target_owner_user_id", UUID.class), rs.getObject("reporter_user_id", UUID.class),
                        rs.getString("reporter_side"), rs.getString("reason_code"), rs.getString("note"), rs.getString("evidence"), rs.getString("status"),
                        rs.getString("decision_code"), rs.getString("decision_reason"), rs.getTimestamp("decided_at"), rs.getTimestamp("contested_at"),
                        rs.getString("contest_note"), rs.getTimestamp("created_at")))
                .optional().orElseThrow(ReportService::reportNotFound);
        Timestamp since = Timestamp.from(clock.instant().minus(Duration.ofDays(90)));
        long reporterTotal = jdbc.sql("select count(*) from support.content_reports where reporter_user_id = :u and created_at >= :since")
                .param("u", r.reporter()).param("since", since).query(Long.class).single();
        long reporterDismissed = jdbc.sql("select count(*) from support.content_reports where reporter_user_id = :u and created_at >= :since and status = 'DISMISSED'")
                .param("u", r.reporter()).param("since", since).query(Long.class).single();
        long targetActioned = r.owner() == null ? 0 : jdbc.sql("select count(*) from support.content_reports where target_owner_user_id = :o and status = 'ACTIONED' and id <> :id")
                .param("o", r.owner()).param("id", id).query(Long.class).single();
        Map<String, Object> evidence = withImageLinks(read(r.evidence()));
        return new ReportDtos.Detail(r.id(), r.type(), r.target(), label(r.type(), evidence), r.vendor(), r.reason(), r.note(), r.side(), reporterTotal,
                reporterDismissed, targetActioned, evidence, r.status(), r.decision(), r.decisionReason(), instant(r.decidedAt()), instant(r.contestedAt()),
                r.contestNote(), r.createdAt().toInstant(), allowedActions(admin, r.type()));
    }

    /** What this administrator may do with this kind of content: report:decide alone does not unlock hiding a review or restricting. */
    static List<String> allowedActions(CurrentPrincipal admin, String type) {
        List<String> actions = new ArrayList<>(List.of("DISMISS", "DUPLICATE"));
        if ("MESSAGE".equals(type)) {
            actions.add("HIDE");
            if (admin.permissions().contains("moderation:write")) {
                actions.add("RESTRICT");
            }
        }
        if (("REVIEW".equals(type) || "REVIEW_RESPONSE".equals(type)) && admin.permissions().contains("review:moderate")) {
            actions.add("HIDE");
        }
        actions.add("ESCALATE");
        return actions;
    }

    /**
     * Decides the report and every other open report on the same content, each reporter being told the outcome. A second decision after
     * a contest that finds no violation undoes what the first one did.
     */
    @Transactional
    public ReportDtos.Detail decide(CurrentPrincipal admin, UUID id, ReportDtos.Decide request) {
        record R(String type, UUID target, UUID owner, String status, String decision, Timestamp contestedAt, String evidence) {
        }
        R r = jdbc.sql("select target_type, target_id, target_owner_user_id, status, decision_code, contested_at, evidence from support.content_reports where id = :id for update")
                .param("id", id).query((rs, n) -> new R(rs.getString(1), rs.getObject(2, UUID.class), rs.getObject(3, UUID.class), rs.getString(4),
                        rs.getString(5), rs.getTimestamp(6), rs.getString(7)))
                .optional().orElseThrow(ReportService::reportNotFound);
        if (!"OPEN".equals(r.status())) {
            throw BusinessException.conflict("REPORT_DECIDED", "Báo cáo này đã được quyết định.");
        }
        if (!allowedActions(admin, r.type()).contains(request.action())) {
            throw new BusinessException(HttpStatus.FORBIDDEN, "ACTION_NOT_ALLOWED", "Hành động này không áp dụng cho loại nội dung này hoặc bạn không có quyền.");
        }
        String reason = request.reason().strip();
        Instant now = clock.instant();
        String code = switch (request.action()) {
            case "DISMISS" -> "NO_VIOLATION";
            case "DUPLICATE" -> "DUPLICATE";
            case "HIDE" -> "CONTENT_HIDDEN";
            case "RESTRICT" -> "MESSAGING_RESTRICTED";
            default -> "ESCALATED";
        };
        boolean actioned = !code.equals("NO_VIOLATION") && !code.equals("DUPLICATE");
        // a contested action that is now found to be no violation is undone
        if (r.contestedAt() != null && !actioned) {
            undo(admin.id(), id, r.type(), r.target(), r.owner(), r.decision());
        }
        switch (request.action()) {
            case "HIDE" -> hide(admin.id(), r.type(), r.target(), reason);
            case "RESTRICT" -> {
                if (request.restrictDays() == null) {
                    throw new BusinessException(HttpStatus.BAD_REQUEST, "RESTRICT_DAYS_REQUIRED", "Hãy chọn số ngày hạn chế (1–30).");
                }
                hide(admin.id(), r.type(), r.target(), reason);
                if (r.owner() != null) {
                    messages.restrict(r.owner(), now.plus(Duration.ofDays(request.restrictDays())), reason, id, admin.id());
                }
            }
            default -> {
            }
        }
        String status = actioned ? "ACTIONED" : "DISMISSED";
        List<UUID> decided = jdbc.sql("""
                update support.content_reports set status = :status, decision_code = :code, decision_reason = :reason, decided_by = :admin, decided_at = :now
                where (id = :id or (target_type = :type and target_id = :target and status = 'OPEN')) returning id""")
                .param("status", status).param("code", code).param("reason", reason).param("admin", admin.id()).param("now", Timestamp.from(now))
                .param("id", id).param("type", r.type()).param("target", r.target()).query(UUID.class).list();
        for (UUID decidedId : decided) {
            jdbc.sql("select reporter_user_id, reporter_side, target_owner_user_id from support.content_reports where id = :id").param("id", decidedId)
                    .query((rs, n) -> {
                        UUID reporter = rs.getObject(1, UUID.class);
                        String audience = "SELLER".equals(rs.getString(2)) ? "SHOP" : "CUSTOMER";
                        UUID affected = actioned && decidedId.equals(id) ? rs.getObject(3, UUID.class) : null;
                        events.publishEvent(new ReportDecided(decidedId, reporter, audience, actioned, affected, ownerAudience(r.type(), read(r.evidence())),
                                r.type(), code, reason, actioned ? now.plus(CONTEST_WINDOW) : null));
                        return 0;
                    }).list();
        }
        return detail(admin, id);
    }

    /** The person whose content was actioned asks for a second look, once, within seven days. */
    @Transactional
    public void contest(CurrentPrincipal caller, UUID id, ReportDtos.Contest request) {
        record R(UUID owner, String status, Timestamp decidedAt, Timestamp contestedAt) {
        }
        R r = jdbc.sql("select target_owner_user_id, status, decided_at, contested_at from support.content_reports where id = :id for update")
                .param("id", id).query((rs, n) -> new R(rs.getObject(1, UUID.class), rs.getString(2), rs.getTimestamp(3), rs.getTimestamp(4)))
                .optional().filter(x -> caller.id().equals(x.owner())).orElseThrow(ReportService::reportNotFound);
        if (!"ACTIONED".equals(r.status())) {
            throw BusinessException.conflict("NOT_CONTESTABLE", "Chỉ khiếu nại được quyết định đã xử lý nội dung của bạn.");
        }
        if (r.contestedAt() != null) {
            throw BusinessException.conflict("ALREADY_CONTESTED", "Bạn đã khiếu nại quyết định này rồi.");
        }
        if (r.decidedAt().toInstant().plus(CONTEST_WINDOW).isBefore(clock.instant())) {
            throw BusinessException.conflict("CONTEST_WINDOW_CLOSED", "Đã quá 7 ngày kể từ quyết định nên không khiếu nại được nữa.");
        }
        jdbc.sql("update support.content_reports set status = 'OPEN', contested_at = :now, contest_note = :note where id = :id")
                .param("now", Timestamp.from(clock.instant())).param("note", request.note().strip()).param("id", id).update();
    }

    // --- internals

    private void hide(UUID adminId, String type, UUID target, String reason) {
        switch (type) {
            case "MESSAGE" -> messages.hide(target, reason);
            case "REVIEW" -> reviews.hideReview(adminId, target, reason);
            case "REVIEW_RESPONSE" -> reviews.hideResponse(adminId, target, reason);
            default -> throw new BusinessException(HttpStatus.BAD_REQUEST, "ACTION_NOT_ALLOWED", "Không ẩn được loại nội dung này.");
        }
    }

    private void undo(UUID adminId, UUID reportId, String type, UUID target, UUID owner, String previous) {
        if ("CONTENT_HIDDEN".equals(previous) || "MESSAGING_RESTRICTED".equals(previous)) {
            switch (type) {
                case "MESSAGE" -> messages.unhide(target);
                case "REVIEW" -> reviews.unhideReview(adminId, target);
                case "REVIEW_RESPONSE" -> reviews.unhideResponse(adminId, target);
                default -> {
                }
            }
        }
        if ("MESSAGING_RESTRICTED".equals(previous) && owner != null) {
            messages.liftRestriction(owner, reportId);
        }
    }

    private static String ownerAudience(String type, Map<String, Object> evidence) {
        return switch (type) {
            case "SHOP", "REVIEW_RESPONSE" -> "SHOP";
            case "MESSAGE" -> {
                Object m = evidence.get("message");
                yield m instanceof Map<?, ?> map && "SHOP".equals(map.get("senderType")) ? "SHOP" : "CUSTOMER";
            }
            default -> "CUSTOMER";
        };
    }

    private static Map<String, Object> message(ReportedMessages.MessageSnapshot m) {
        Map<String, Object> e = new LinkedHashMap<>();
        e.put("id", m.id());
        e.put("senderType", m.senderType());
        e.put("senderId", m.senderId());
        e.put("text", m.text());
        e.put("imageKey", m.imageKey());
        e.put("createdAt", m.createdAt());
        return e;
    }

    /** The administrator sees images through short-lived links, never the storage keys. */
    @SuppressWarnings("unchecked")
    private Map<String, Object> withImageLinks(Map<String, Object> evidence) {
        for (String part : List.of("message", "before", "after")) {
            Object value = evidence.get(part);
            List<Object> items = value instanceof List<?> l ? (List<Object>) l : value == null ? List.of() : List.of(value);
            for (Object item : items) {
                if (item instanceof Map<?, ?> m && m.get("imageKey") instanceof String key) {
                    ((Map<String, Object>) m).put("imageUrl", storage.signedUrl(key, IMAGE_URL_TTL));
                    ((Map<String, Object>) m).remove("imageKey");
                    ((Map<String, Object>) m).remove("originalImageKey");
                }
            }
        }
        return evidence;
    }

    private static String label(String type, Map<String, Object> e) {
        return switch (type) {
            case "SHOP" -> "Cửa hàng " + e.getOrDefault("shopName", "");
            case "MESSAGE" -> {
                Object m = e.get("message");
                Object text = m instanceof Map<?, ?> map ? map.get("text") : null;
                yield "Tin nhắn với " + e.getOrDefault("shopName", "cửa hàng") + ": " + abbreviate(text == null ? "[ảnh]" : text.toString());
            }
            case "REVIEW" -> "Đánh giá " + e.getOrDefault("rating", "?") + "★ cho " + e.getOrDefault("shopName", "cửa hàng") + ": "
                    + abbreviate(String.valueOf(e.getOrDefault("comment", "")));
            default -> "Phản hồi của " + e.getOrDefault("shopName", "cửa hàng") + ": " + abbreviate(String.valueOf(e.getOrDefault("text", "")));
        };
    }

    private static Map<String, Object> read(String json) {
        return JSON.readValue(json, new TypeReference<LinkedHashMap<String, Object>>() {
        });
    }

    private static String abbreviate(String text) {
        String t = text == null ? "" : text.strip().replaceAll("\\s+", " ");
        return t.length() <= 80 ? t : t.substring(0, 79) + "…";
    }

    private static Instant instant(Timestamp t) {
        return t == null ? null : t.toInstant();
    }

    private static BusinessException reportNotFound() {
        return BusinessException.notFound("REPORT_NOT_FOUND", "Không tìm thấy báo cáo.");
    }

    private static BusinessException notFound() {
        return BusinessException.notFound("REPORT_TARGET_NOT_FOUND", "Không tìm thấy nội dung này.");
    }
}
