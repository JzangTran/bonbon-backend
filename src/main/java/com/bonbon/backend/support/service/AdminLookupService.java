package com.bonbon.backend.support.service;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import com.bonbon.backend.authentication.CustomerDirectory;
import com.bonbon.backend.common.exception.BusinessException;
import com.bonbon.backend.common.security.CurrentPrincipal;
import com.bonbon.backend.messaging.ConversationLookup;
import com.bonbon.backend.order.AdminOrders;
import com.bonbon.backend.order.AdminOrders.AdminOrder;
import com.bonbon.backend.order.AdminOrders.AdminOrderLine;
import com.bonbon.backend.payment.PaymentLookup;
import com.bonbon.backend.shopperformance.OrderCaseLookup;
import com.bonbon.backend.support.dto.LookupRequests;
import com.bonbon.backend.support.dto.LookupViews;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * The administrators' lookup of orders and customers (flows/support/admin-order-lookup.md). It is for finding a case, not
 * for browsing people: a search needs an exact identifier, returns at most {@value #CAP} records, and shows contact details masked
 * until the administrator gives a reason to see them. Everything is read only and everything is audited.
 */
@Service
public class AdminLookupService {

    static final int CAP = 20;
    private static final int RECENT_ORDERS = 10;

    private final AdminOrders orders;
    private final PaymentLookup payments;
    private final OrderCaseLookup cases;
    private final ConversationLookup conversations;
    private final CustomerDirectory customers;
    private final LookupAudit audit;

    AdminLookupService(AdminOrders orders, PaymentLookup payments, OrderCaseLookup cases, ConversationLookup conversations, CustomerDirectory customers,
            LookupAudit audit) {
        this.orders = orders;
        this.payments = payments;
        this.cases = cases;
        this.conversations = conversations;
        this.customers = customers;
        this.audit = audit;
    }

    // --- orders

    @Transactional
    public LookupViews.OrderResults searchOrders(CurrentPrincipal admin, String code, String customerEmail, String customerPhone) {
        int given = (blank(code) ? 0 : 1) + (blank(customerEmail) ? 0 : 1) + (blank(customerPhone) ? 0 : 1);
        if (given != 1) {
            throw new BusinessException(HttpStatus.BAD_REQUEST, "IDENTIFIER_REQUIRED", "Nhập đúng một trong: mã đơn, email khách hoặc số điện thoại khách.");
        }
        List<AdminOrderLine> found = new ArrayList<>();
        String action;
        String query;
        if (!blank(code)) {
            query = code.strip();
            orders.byNumber(number(code)).ifPresent(o -> found.add(new AdminOrderLine(o.id(), o.number(), o.status(), o.shopName(), o.grandTotal(), o.placedAt())));
        } else if (!blank(customerEmail)) {
            query = customerEmail.strip();
            customers.byEmail(query).filter(a -> !a.roles().contains("ADMIN")).ifPresent(a -> found.addAll(orders.ofCustomer(a.id(), CAP)));
        } else {
            query = customerPhone.strip();
            customers.byPhone(query, CAP).stream().filter(a -> !a.roles().contains("ADMIN")).forEach(a -> found.addAll(orders.ofCustomer(a.id(), CAP)));
            found.sort(Comparator.comparing(AdminOrderLine::placedAt).reversed());
        }
        List<LookupViews.OrderRow> rows = found.stream().limit(CAP).map(AdminLookupService::row).toList();
        audit.record(admin.id(), "SEARCH_ORDERS", query, null, rows.size(), null, null);
        return new LookupViews.OrderResults(rows);
    }

    @Transactional
    public LookupViews.OrderDetail order(CurrentPrincipal admin, UUID orderId) {
        AdminOrder o = orders.get(orderId).orElseThrow(() -> new BusinessException(HttpStatus.NOT_FOUND, "ORDER_NOT_FOUND", "Không tìm thấy đơn."));
        audit.record(admin.id(), "OPEN_ORDER", null, o.id(), null, null, null);
        Optional<CustomerDirectory.Account> account = customers.get(o.customerId());
        LookupViews.Person person = account.map(a -> new LookupViews.Person(a.id(), a.name(), Masking.email(a.email()), Masking.phone(a.phone())))
                .orElse(new LookupViews.Person(o.customerId(), "Người dùng đã xóa", null, null));
        List<LookupViews.Item> items = o.items().stream().map(i -> new LookupViews.Item(i.name(), i.quantity(), i.unitPrice(), i.lineTotal(), i.note(),
                i.options().stream().map(op -> new LookupViews.ItemOption(op.group(), op.name(), op.priceDelta())).toList())).toList();
        List<LookupViews.Step> timeline = o.timeline().stream()
                .map(s -> new LookupViews.Step(s.from() == null ? null : s.from().name(), s.to().name(), s.by(), s.reason(), s.at())).toList();
        LookupViews.Payment payment = payments.ofOrder(o.id()).map(p -> new LookupViews.Payment(p.method(), p.provider(), p.status(), p.amount(), p.refundedAmount(),
                p.refunds().stream().map(r -> new LookupViews.Refund(r.reason(), r.amount(), r.status(), r.mode(), r.requestedAt())).toList())).orElse(null);
        List<LookupViews.CaseRef> caseRefs = cases.ofOrder(o.id()).stream().map(c -> new LookupViews.CaseRef(c.id(), c.type(), c.status(), c.refundAmount(), c.openedAt())).toList();
        UUID conversation = conversations.between(o.customerId(), o.vendorId()).orElse(null);
        return new LookupViews.OrderDetail(o.id(), o.number(), o.status().name(), o.vendorId(), o.shopName(), person, o.deliveryName(), Masking.phone(o.deliveryPhone()),
                Masking.address(o.deliveryAddress()), o.note(), o.paymentMethod(), o.paymentStatus(), items, o.itemsTotal(), o.discount(), o.deliveryFee(), o.grandTotal(),
                o.commissionAmount(), o.placedAt(), o.confirmedAt(), o.outForDeliveryAt(), o.finishedAt(), timeline, payment, caseRefs, conversation);
    }

    @Transactional
    public LookupViews.RevealedOrder revealOrder(CurrentPrincipal admin, UUID orderId, LookupRequests.Reveal request) {
        checkReason(request);
        AdminOrder o = orders.get(orderId).orElseThrow(() -> new BusinessException(HttpStatus.NOT_FOUND, "ORDER_NOT_FOUND", "Không tìm thấy đơn."));
        audit.record(admin.id(), "REVEAL_ORDER", null, o.id(), null, request.reason(), note(request));
        Optional<CustomerDirectory.Account> account = customers.get(o.customerId());
        return new LookupViews.RevealedOrder(account.map(CustomerDirectory.Account::email).orElse(null), account.map(CustomerDirectory.Account::phone).orElse(null),
                o.deliveryPhone(), o.deliveryAddress());
    }

    // --- customers

    @Transactional
    public LookupViews.CustomerResults searchCustomers(CurrentPrincipal admin, String email, String phone) {
        if (blank(email) == blank(phone)) {
            throw new BusinessException(HttpStatus.BAD_REQUEST, "IDENTIFIER_REQUIRED", "Nhập đúng một trong: email hoặc số điện thoại (khớp chính xác).");
        }
        String query = blank(email) ? phone.strip() : email.strip();
        List<CustomerDirectory.Account> found = blank(email) ? customers.byPhone(query, CAP) : customers.byEmail(query).stream().toList();
        List<LookupViews.CustomerRow> rows = found.stream().filter(a -> !a.roles().contains("ADMIN"))
                .map(a -> new LookupViews.CustomerRow(a.id(), a.name(), Masking.email(a.email()), Masking.phone(a.phone()), a.roles(), a.createdAt())).toList();
        audit.record(admin.id(), "SEARCH_CUSTOMERS", query, null, rows.size(), null, null);
        return new LookupViews.CustomerResults(rows);
    }

    @Transactional
    public LookupViews.CustomerDetail customer(CurrentPrincipal admin, UUID userId) {
        CustomerDirectory.Account a = account(userId);
        audit.record(admin.id(), "OPEN_CUSTOMER", null, a.id(), null, null, null);
        List<LookupViews.OrderRow> recent = orders.ofCustomer(a.id(), RECENT_ORDERS).stream().map(AdminLookupService::row).toList();
        return new LookupViews.CustomerDetail(a.id(), a.name(), Masking.email(a.email()), Masking.phone(a.phone()), a.roles(), a.emailVerified(), a.createdAt(), recent);
    }

    @Transactional
    public LookupViews.RevealedCustomer revealCustomer(CurrentPrincipal admin, UUID userId, LookupRequests.Reveal request) {
        checkReason(request);
        CustomerDirectory.Account a = account(userId);
        audit.record(admin.id(), "REVEAL_CUSTOMER", null, a.id(), null, request.reason(), note(request));
        return new LookupViews.RevealedCustomer(a.email(), a.phone());
    }

    // --- internals

    private CustomerDirectory.Account account(UUID userId) {
        return customers.get(userId).filter(a -> !a.roles().contains("ADMIN"))
                .orElseThrow(() -> new BusinessException(HttpStatus.NOT_FOUND, "CUSTOMER_NOT_FOUND", "Không tìm thấy tài khoản."));
    }

    private static void checkReason(LookupRequests.Reveal request) {
        if ("OTHER".equals(request.reason()) && blank(request.note())) {
            throw new BusinessException(HttpStatus.BAD_REQUEST, "NOTE_REQUIRED", "Lý do OTHER cần ghi chú.");
        }
    }

    private static String note(LookupRequests.Reveal request) {
        return blank(request.note()) ? null : request.note().strip();
    }

    private static long number(String code) {
        try {
            return Long.parseLong(code.strip().replaceFirst("^#", ""));
        } catch (NumberFormatException e) {
            throw new BusinessException(HttpStatus.BAD_REQUEST, "CODE_INVALID", "Mã đơn phải là một số.");
        }
    }

    private static LookupViews.OrderRow row(AdminOrderLine o) {
        return new LookupViews.OrderRow(o.id(), o.number(), o.status().name(), o.shopName(), o.grandTotal(), o.placedAt());
    }

    private static boolean blank(String s) {
        return s == null || s.isBlank();
    }
}
