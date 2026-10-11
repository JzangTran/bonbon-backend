package com.bonbon.backend.notification.service;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import com.bonbon.backend.common.exception.BusinessException;
import com.bonbon.backend.common.persistence.ActorType;
import com.bonbon.backend.merchant.ShopOrdering;
import com.bonbon.backend.notification.dto.NotificationViews;
import com.bonbon.backend.notification.entity.Notification;
import com.bonbon.backend.notification.repository.NotificationRepository;
import com.bonbon.backend.order.OrderStatus;
import com.bonbon.backend.order.OrderStatusChanged;
import com.bonbon.backend.payment.RefundCompleted;
import com.bonbon.backend.support.TicketAnswered;
import com.bonbon.backend.settlement.CommissionStageChanged;
import com.bonbon.backend.settlement.CommissionStatementIssued;
import com.bonbon.backend.settlement.CommissionStatementReminder;
import com.bonbon.backend.settlement.PayoutRecorded;
import com.bonbon.backend.shopperformance.OrderCaseDecided;
import com.bonbon.backend.shopperformance.OrderCaseEscalated;
import com.bonbon.backend.merchantapproval.ShopSuspensionChanged;
import com.bonbon.backend.shopperformance.NoShowReported;
import com.bonbon.backend.shopperformance.OrderCaseOpened;
import com.bonbon.backend.shopperformance.OrderCaseReopened;
import com.bonbon.backend.shopperformance.ShopPenaltyDecided;
import com.bonbon.backend.shopperformance.ShopPenaltyIssued;
import com.bonbon.backend.shopperformance.ShopRestrictionChanged;
import com.bonbon.backend.payment.RefundNeedsDestination;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.context.event.EventListener;
import org.springframework.data.domain.PageRequest;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Turns order changes into stored notifications (notifications.md) and serves the in-app list. The rows are written
 * in the same transaction as the order change, before any push is tried, so nothing is lost while a recipient is
 * offline; sending the push happens after commit ({@link PushDispatcher}).
 */
@Service
public class NotificationService {

    public static final String CUSTOMER = "CUSTOMER";
    public static final String SHOP = "SHOP";
    private static final int MAX_PAGE_SIZE = 50;

    private final NotificationRepository notifications;
    private final ShopOrdering shops;
    private final ApplicationEventPublisher events;

    NotificationService(NotificationRepository notifications, ShopOrdering shops, ApplicationEventPublisher events) {
        this.notifications = notifications;
        this.shops = shops;
        this.events = events;
    }

    /** What was stored, handed to the dispatcher once the surrounding transaction has committed. */
    record Created(List<Notification> items) {
    }

    // --- raising

    /** Runs inside the order transaction (a plain event listener, on purpose). */
    @EventListener
    void onOrderChanged(OrderStatusChanged e) {
        if (e.from() == OrderStatus.PLACED) {
            // The shop's own answer (or the timeout) settles the "new order" alert.
            notifications.resolveNewOrderAlerts(e.orderId(), Instant.now());
        }
        if (e.to() == OrderStatus.PENDING_PAYMENT) {
            // An unpaid online order is nobody's business yet: not the shop's, and the customer is on the payment screen.
            return;
        }
        List<Notification> drafts = new ArrayList<>();
        String n = "#" + e.number();
        boolean paid = e.from() == OrderStatus.PENDING_PAYMENT && e.to() == OrderStatus.PLACED;
        if (e.from() == OrderStatus.PENDING_PAYMENT && e.to() == OrderStatus.CANCELLED) {
            // Only the customer's own order was ever unpaid; the shop never knew about it.
            if (e.by() == ActorType.SYSTEM) {
                toCustomer(drafts, e, "ORDER_CANCELLED", "Đơn " + n + " đã bị huỷ", "Bạn chưa thanh toán trong thời hạn nên đơn đã tự huỷ.");
            }
        } else if (e.from() == null || paid) {
            if (paid) {
                toCustomer(drafts, e, "ORDER_PAID", "Đã thanh toán đơn " + n, "Quán sẽ xác nhận đơn của bạn trong ít phút.");
            }
            shop(e).ifPresent(owner -> drafts.add(draft(owner, SHOP, "ORDER_NEW", e, "Đơn mới " + n, "Có đơn mới, hãy xác nhận trong vài phút.")));
        } else {
            ActorType by = e.by();
            boolean customerActed = by == ActorType.CUSTOMER;
            boolean systemActed = by == ActorType.SYSTEM;
            switch (e.to()) {
                case CONFIRMED -> toCustomer(drafts, e, "ORDER_CONFIRMED", "Quán đã nhận đơn " + n, "Quán đang chuẩn bị món cho bạn.");
                case PREPARING -> toCustomer(drafts, e, "ORDER_PREPARING", "Quán đang chuẩn bị đơn " + n, "Món của bạn đang được làm.");
                case OUT_FOR_DELIVERY -> toCustomer(drafts, e, "ORDER_OUT_FOR_DELIVERY", "Đơn " + n + " đang được giao", "Hãy chú ý điện thoại để nhận món.");
                case DELIVERED -> {
                    if (customerActed) {
                        shop(e).ifPresent(owner -> drafts.add(draft(owner, SHOP, "ORDER_DELIVERED", e, "Khách đã nhận đơn " + n, "Đơn đã hoàn tất.")));
                    } else {
                        toCustomer(drafts, e, "ORDER_DELIVERED", "Đơn " + n + " đã giao xong", systemActed ? "Đơn được tự động xác nhận đã giao." : "Chúc bạn ngon miệng!");
                    }
                }
                case REJECTED -> {
                    toCustomer(drafts, e, "ORDER_REJECTED", "Quán đã từ chối đơn " + n, "Rất tiếc, quán không thể làm đơn này.");
                    if (systemActed) {
                        shop(e).ifPresent(owner -> drafts.add(draft(owner, SHOP, "ORDER_AUTO_REJECTED", e, "Đơn " + n + " tự động bị từ chối",
                                "Quán chưa phản hồi kịp thời nên đơn đã bị từ chối.")));
                    }
                }
                case CANCELLED -> {
                    if (customerActed) {
                        shop(e).ifPresent(owner -> drafts.add(draft(owner, SHOP, "ORDER_CANCELLED", e, "Khách đã huỷ đơn " + n, "Hãy dừng chuẩn bị đơn này.")));
                    } else {
                        toCustomer(drafts, e, "ORDER_CANCELLED", "Đơn " + n + " đã bị huỷ", systemActed ? "Quán chưa giao món trong thời hạn." : "Quán đã huỷ đơn của bạn.");
                        if (systemActed) {
                            shop(e).ifPresent(owner -> drafts.add(draft(owner, SHOP, "ORDER_CANCELLED", e, "Đơn " + n + " tự động bị huỷ",
                                    "Đơn chưa được giao đi trong thời hạn nên đã bị huỷ.")));
                        }
                    }
                }
                default -> {
                }
            }
        }
        if (!drafts.isEmpty()) {
            notifications.saveAll(drafts);
            events.publishEvent(new Created(List.copyOf(drafts)));
        }
    }

    /** The admin recorded a payout to this shop: the owner is told, with the bank reference to check against their statement. */
    @EventListener
    void onPayoutRecorded(PayoutRecorded e) {
        shops.ownerOf(e.vendorId()).ifPresent(owner -> {
            Notification n = new Notification(owner, SHOP, "PAYOUT_RECORDED", null, null, "Đã chuyển " + e.amount() + " ₫ cho quán",
                    "Mã giao dịch " + e.reference() + ". Kiểm tra trong sao kê ngân hàng của bạn.");
            notifications.save(n);
            events.publishEvent(new Created(List.of(n)));
        });
    }

    /** A statement for unpaid commission was issued: the shop is told how much and by when. */
    @EventListener
    void onCommissionStatement(CommissionStatementIssued e) {
        toShop(e.vendorId(), "COMMISSION_STATEMENT", "Sao kê hoa hồng: " + vnd(e.amountDue()),
                "Quán cần trả " + vnd(e.amountDue()) + " hoa hồng đơn tiền mặt trước " + day(e.dueAt()) + ". Xem chi tiết ở mục Thu nhập.");
    }

    @EventListener
    void onCommissionReminder(CommissionStatementReminder e) {
        toShop(e.vendorId(), "COMMISSION_REMINDER", "Sắp đến hạn trả hoa hồng",
                "Còn " + vnd(e.unpaid()) + " cần trả trước " + day(e.dueAt()) + ". Quá hạn sẽ bị hạn chế hiển thị.");
    }

    /** Overdue commission: the first message is the notice of what comes next and when (never sooner than 5 days). */
    @EventListener
    void onCommissionStage(CommissionStageChanged e) {
        switch (e.stage()) {
            case "OVERDUE" -> toShop(e.vendorId(), "COMMISSION_OVERDUE", "Hoa hồng đã quá hạn",
                    "Quán còn nợ " + vnd(e.overdueAmount()) + " quá hạn. Từ " + day(e.nextAt()) + " quán sẽ bị hạn chế hiển thị nếu chưa trả.");
            case "RESTRICTED" -> toShop(e.vendorId(), "COMMISSION_RESTRICTED", "Quán bị hạn chế hiển thị",
                    "Quán không hiện khi khách tìm kiếm và xếp cuối danh sách. Từ " + day(e.nextAt()) + " quán sẽ tạm ngưng nhận đơn nếu chưa trả "
                            + vnd(e.overdueAmount()) + ".");
            case "PAUSED" -> toShop(e.vendorId(), "COMMISSION_PAUSED", "Quán tạm ngưng nhận đơn",
                    "Quán không nhận đơn mới vì hoa hồng quá hạn. Trả " + vnd(e.overdueAmount()) + " để nhận đơn trở lại; đơn đang làm không bị ảnh hưởng.");
            case "NONE" -> toShop(e.vendorId(), "COMMISSION_CLEARED", "Đã gỡ hạn chế", "Hoa hồng đã trả đủ. Quán hiển thị và nhận đơn bình thường.");
            default -> {
            }
        }
    }

    /** A customer filed a case: the shop has until the due time to answer, and the customer sees it was received. */
    @EventListener
    void onOrderCaseOpened(OrderCaseOpened e) {
        toShop(e.vendorId(), "ORDER_CASE_OPENED", e.orderId(), e.orderNumber(), "Khách báo vấn đề với đơn #" + e.orderNumber(),
                "Hãy xem và trả lời trong " + java.time.Duration.between(java.time.Instant.now(), e.responseDueAt()).toHours() + " giờ tới. Nếu không, quản trị viên sẽ quyết định.");
        raise(e.customerId(), "ORDER_CASE_RECEIVED", e.orderId(), e.orderNumber(), "Đã gửi báo cáo đơn #" + e.orderNumber(),
                "Quán sẽ trả lời sớm. Nếu hai bên không đồng ý, quản trị viên sẽ xem xét.");
    }

    /** The shop could not reach the customer at the door: the customer is asked to answer, with the time they have. */
    @EventListener
    void onNoShowReported(NoShowReported e) {
        raise(e.customerId(), "NO_SHOW_REPORTED", e.orderId(), e.orderNumber(), "Quán không liên lạc được với bạn (đơn #" + e.orderNumber() + ")",
                "Hãy cho biết bạn đã nhận được hay chưa, trước " + day(e.answerDueAt()) + " " + java.time.format.DateTimeFormatter.ofPattern("HH:mm")
                        .withZone(java.time.ZoneId.of("Asia/Ho_Chi_Minh")).format(e.answerDueAt()) + ".");
    }

    /** The shop disagreed or ran out of time: the customer is told an administrator decides now. */
    @EventListener
    void onOrderCaseEscalated(OrderCaseEscalated e) {
        if ("SHOP_NEVER_CAME".equals(e.why()) || "CUSTOMER_NO_REPLY".equals(e.why())) {
            toShop(e.vendorId(), "NO_SHOW_ESCALATED", e.orderId(), e.orderNumber(), "Báo cáo khách vắng mặt đơn #" + e.orderNumber() + " đang được xem xét",
                    "SHOP_NEVER_CAME".equals(e.why()) ? "Khách nói quán không đến hoặc không gọi. Quản trị viên sẽ xem và quyết định." : "Khách chưa trả lời kịp. Quản trị viên sẽ xem và quyết định.");
            return;
        }
        raise(e.customerId(), "ORDER_CASE_ESCALATED", e.orderId(), e.orderNumber(), "Báo cáo đơn #" + e.orderNumber() + " đang được xem xét",
                "DISPUTED".equals(e.why()) ? "Quán không đồng ý với báo cáo của bạn. Quản trị viên sẽ xem và quyết định."
                        : "Quán chưa trả lời kịp. Quản trị viên sẽ xem và quyết định.");
    }

    /** A case was settled: the customer hears the outcome and the reason; the shop hears it when it did not decide itself. */
    @EventListener
    void onOrderCaseDecided(OrderCaseDecided e) {
        if ("CUSTOMER_NO_SHOW".equals(e.type())) {
            String title;
            String body;
            switch (e.noShowOutcome() == null ? "" : e.noShowOutcome()) {
                case "CUSTOMER_AT_FAULT" -> {
                    title = "Đơn #" + e.orderNumber() + " kết thúc: chưa giao được";
                    body = "Đơn được ghi nhận là không giao được vì không có người nhận. Quán vẫn được tính tiền món nếu bạn đã trả online.";
                }
                case "SHOP_NEVER_CAME" -> {
                    title = "Đơn #" + e.orderNumber() + " bị huỷ";
                    body = "Quản trị viên xác nhận quán không đến. Nếu bạn đã trả online, bạn được hoàn đủ. " + (e.reason() == null ? "" : e.reason());
                }
                default -> {
                    title = "Đơn #" + e.orderNumber() + " đã được ghi nhận là đã giao";
                    body = e.reason() == null ? "" : e.reason();
                }
            }
            raise(e.customerId(), "NO_SHOW_DECIDED", e.orderId(), e.orderNumber(), title, body);
            toShop(e.vendorId(), "NO_SHOW_DECIDED", e.orderId(), e.orderNumber(), title, body);
            return;
        }
        boolean upheld = "UPHELD".equals(e.outcome());
        raise(e.customerId(), upheld ? "ORDER_CASE_UPHELD" : "ORDER_CASE_DISMISSED", e.orderId(), e.orderNumber(),
                upheld ? "Báo cáo đơn #" + e.orderNumber() + " được chấp nhận" : "Báo cáo đơn #" + e.orderNumber() + " không được chấp nhận",
                upheld ? "Bạn sẽ được hoàn " + vnd(e.refundAmount()) + ". " + (e.reason() == null ? "" : e.reason())
                        : (e.reason() == null ? "Báo cáo của bạn không được chấp nhận." : e.reason()));
        if (!"SHOP".equals(e.decidedBy())) {
            toShop(e.vendorId(), upheld ? "ORDER_CASE_UPHELD" : "ORDER_CASE_DISMISSED", e.orderId(), e.orderNumber(),
                    upheld ? "Báo cáo đơn #" + e.orderNumber() + " được chấp nhận" : "Báo cáo đơn #" + e.orderNumber() + " bị bác bỏ",
                    upheld ? "Quán chịu " + vnd(e.refundAmount()) + " (hoa hồng phần này được hoàn lại). " + (e.reason() == null ? "" : e.reason())
                            : (e.reason() == null ? "Khiếu nại của khách không được chấp nhận." : e.reason()));
        }
    }

    /** A decided case is being looked at again: both sides are told, so a second decision is never a surprise. */
    @EventListener
    void onOrderCaseReopened(OrderCaseReopened e) {
        raise(e.customerId(), "ORDER_CASE_REOPENED", e.orderId(), e.orderNumber(), "Báo cáo đơn #" + e.orderNumber() + " được xem lại",
                "Quản trị viên mở lại báo cáo của bạn để xem xét. " + e.reason());
        toShop(e.vendorId(), "ORDER_CASE_REOPENED", e.orderId(), e.orderNumber(), "Khiếu nại đơn #" + e.orderNumber() + " được xem lại",
                "Quản trị viên mở lại khiếu nại để xem xét. " + e.reason());
    }

    /** The weekly evaluation found too many failed orders: a point, and a warning when it is only one or two. */
    @EventListener
    void onShopPenaltyIssued(ShopPenaltyIssued e) {
        toShop(e.vendorId(), "SHOP_PENALTY", "Quán bị cộng 1 điểm phạt",
                "Tuần " + e.weekStart().format(java.time.format.DateTimeFormatter.ofPattern("dd/MM")) + " có " + String.format(java.util.Locale.forLanguageTag("vi-VN"), "%.1f", e.ratePercent())
                        + "% đơn thất bại do quán. Quán đang có " + e.activePoints() + " điểm"
                        + (e.activePoints() >= 3 ? ": quán sẽ bị hạn chế hiển thị." : ", chỉ là cảnh báo. Từ 3 điểm quán bị hạn chế hiển thị."));
    }

    /** A suspension: the notice first (the shop keeps working until the date), then its start, or its end. */
    @EventListener
    void onShopSuspensionChanged(ShopSuspensionChanged e) {
        String reason = e.reason() == null ? "" : " Lý do: " + e.reason();
        switch (e.status()) {
            case "SCHEDULED" -> toShop(e.vendorId(), "SHOP_SUSPENSION_SCHEDULED", "Quán sẽ bị đình chỉ",
                    "Từ " + day(e.effectiveAt()) + " quán sẽ bị đình chỉ: khách không tìm thấy và không đặt thêm được. Quán vẫn hoạt động bình thường đến lúc đó." + reason);
            case "APPLIED" -> toShop(e.vendorId(), "SHOP_SUSPENDED", "Quán đang bị đình chỉ",
                    "Khách không tìm thấy quán và không đặt thêm được. Quán vẫn hoàn tất các đơn đang làm và xem được thu nhập." + reason);
            case "CANCELLED" -> toShop(e.vendorId(), "SHOP_SUSPENSION_CANCELLED", "Đã huỷ lịch đình chỉ", "Quán tiếp tục hoạt động bình thường." + reason);
            default -> toShop(e.vendorId(), "SHOP_REINSTATED", "Quán đã được khôi phục", "Quán hiển thị với khách và nhận đơn trở lại." + reason);
        }
    }

    /** An administrator waived, added or ruled on a point: the shop reads what happened and why. */
    @EventListener
    void onShopPenaltyDecided(ShopPenaltyDecided e) {
        String reason = e.reason() == null ? "" : " Lý do: " + e.reason();
        switch (e.kind()) {
            case "WAIVED" -> toShop(e.vendorId(), "SHOP_PENALTY_WAIVED", "Một điểm phạt đã được miễn", "Quán còn " + e.activePoints() + " điểm." + reason);
            case "APPEAL_ACCEPTED" -> toShop(e.vendorId(), "SHOP_APPEAL_ACCEPTED", "Kháng nghị được chấp nhận", "Điểm phạt đã được miễn, quán còn " + e.activePoints() + " điểm." + reason);
            case "APPEAL_REJECTED" -> toShop(e.vendorId(), "SHOP_APPEAL_REJECTED", "Kháng nghị không được chấp nhận", "Điểm phạt được giữ nguyên, quán có " + e.activePoints() + " điểm." + reason);
            default -> toShop(e.vendorId(), "SHOP_PENALTY_ADDED", "Quán bị cộng điểm phạt", "Quản trị viên cộng điểm phạt, quán có " + e.activePoints() + " điểm." + reason);
        }
    }

    /** What penalty points do to the shop's visibility: the notice first (never less than 5 days ahead), then the start, or the end of it. */
    @EventListener
    void onShopRestrictionChanged(ShopRestrictionChanged e) {
        switch (e.status()) {
            case "SCHEDULED" -> toShop(e.vendorId(), "SHOP_RESTRICTION_SCHEDULED", "Quán sắp bị hạn chế hiển thị",
                    "Quán có " + e.activePoints() + " điểm phạt. Từ " + day(e.startsAt()) + " quán sẽ không hiện khi khách tìm kiếm và xếp cuối danh sách, nhưng vẫn nhận đơn từ khách mở thẳng quán. Điểm giảm dưới 3 trước ngày đó thì không bị hạn chế.");
            case "APPLIED" -> toShop(e.vendorId(), "SHOP_RESTRICTED", "Quán đang bị hạn chế hiển thị",
                    "Quán có " + e.activePoints() + " điểm phạt nên không hiện khi tìm kiếm và xếp cuối danh sách. Hạn chế được gỡ ngay khi điểm xuống dưới 3.");
            case "LIFTED" -> toShop(e.vendorId(), "SHOP_RESTRICTION_LIFTED", "Đã gỡ hạn chế hiển thị", "Điểm phạt đã xuống dưới 3. Quán hiển thị bình thường.");
            default -> toShop(e.vendorId(), "SHOP_RESTRICTION_CANCELLED", "Không còn bị hạn chế hiển thị", "Điểm phạt đã xuống dưới 3 trước ngày hạn chế bắt đầu.");
        }
    }

    private void toShop(UUID vendorId, String type, String title, String body) {
        toShop(vendorId, type, null, null, title, body);
    }

    private void toShop(UUID vendorId, String type, UUID orderId, Long orderNumber, String title, String body) {
        shops.ownerOf(vendorId).ifPresent(owner -> {
            Notification n = new Notification(owner, SHOP, type, orderId, orderNumber, title, body);
            notifications.save(n);
            events.publishEvent(new Created(List.of(n)));
        });
    }

    private static String vnd(long amount) {
        return String.format(java.util.Locale.forLanguageTag("vi-VN"), "%,d ₫", amount);
    }

    private static String day(java.time.Instant at) {
        return at == null ? "" : java.time.format.DateTimeFormatter.ofPattern("dd/MM/yyyy").withZone(java.time.ZoneId.of("Asia/Ho_Chi_Minh")).format(at);
    }

    /** The customer has their money back (through MoMo or by bank transfer). */
    @EventListener
    void onRefundCompleted(RefundCompleted e) {
        String how = "MANUAL".equals(e.mode()) ? "bằng chuyển khoản" : "về ví MoMo";
        raise(e.customerId(), "REFUND_DONE", e.orderId(), e.orderNumber(), "Đã hoàn tiền đơn #" + e.orderNumber(),
                "Đã hoàn " + e.amount() + " ₫ " + how + ".");
    }

    /** A refund can only go back by bank transfer: the customer has to say to which account. */
    @EventListener
    void onRefundNeedsDestination(RefundNeedsDestination e) {
        raise(e.customerId(), "REFUND_NEEDS_ACCOUNT", e.orderId(), e.orderNumber(), "Cần tài khoản nhận hoàn tiền đơn #" + e.orderNumber(),
                "Hãy nhập tài khoản ngân hàng để nhận lại " + e.amount() + " ₫.");
    }

    /** Support answered a ticket: the person hears it in the app (and by push or email, as they chose). */
    @EventListener
    void onTicketAnswered(TicketAnswered e) {
        Notification n = new Notification(e.userId(), e.audience(), "SUPPORT_REPLY", null, null, "Hỗ trợ đã trả lời phiếu của bạn", "Phiếu: " + e.subject());
        notifications.save(n);
        events.publishEvent(new Created(List.of(n)));
    }

    private void raise(UUID customerId, String type, UUID orderId, long orderNumber, String title, String body) {
        Notification n = new Notification(customerId, CUSTOMER, type, orderId, orderNumber, title, body);
        notifications.save(n);
        events.publishEvent(new Created(List.of(n)));
    }

    private Optional<UUID> shop(OrderStatusChanged e) {
        return shops.ownerOf(e.vendorId());
    }

    private static void toCustomer(List<Notification> drafts, OrderStatusChanged e, String type, String title, String body) {
        drafts.add(draft(e.customerId(), CUSTOMER, type, e, title, body));
    }

    private static Notification draft(UUID recipient, String audience, String type, OrderStatusChanged e, String title, String body) {
        return new Notification(recipient, audience, type, e.orderId(), e.number(), title, body);
    }

    // --- reading

    /** Newest first; {@code unreadOnly} is the "still pending" view a client shows after reconnecting. */
    @Transactional(readOnly = true)
    public NotificationViews.Page list(UUID userId, String audience, boolean unreadOnly, int page, int size) {
        if (page < 0 || size < 1 || size > MAX_PAGE_SIZE) {
            throw new BusinessException(HttpStatus.BAD_REQUEST, "INVALID_PAGE", "Trang hoặc kích thước trang không hợp lệ.");
        }
        PageRequest paging = PageRequest.of(page, size);
        var result = unreadOnly ? notifications.findUnreadFor(userId, audience, paging) : notifications.findAllFor(userId, audience, paging);
        return new NotificationViews.Page(result.getContent().stream().map(NotificationViews.Item::of).toList(),
                notifications.countUnread(userId, audience), page, size, result.getTotalElements());
    }

    /** Idempotent: a repeat call (a retry after a lost response) is a harmless no-op. */
    @Transactional
    public void acknowledge(UUID userId, UUID id) {
        if (notifications.acknowledge(id, userId, Instant.now()) == 0 && !notifications.existsById(id)) {
            throw BusinessException.notFound("NOTIFICATION_NOT_FOUND", "Không tìm thấy thông báo.");
        }
    }

    @Transactional
    public int acknowledgeAll(UUID userId, String audience) {
        return notifications.acknowledgeAll(userId, audience, Instant.now());
    }
}
