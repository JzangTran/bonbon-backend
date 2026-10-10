package com.bonbon.backend.support.dto;

import java.time.Instant;
import java.util.List;
import java.util.Set;
import java.util.UUID;

import io.swagger.v3.oas.annotations.media.Schema;

public final class LookupViews {

    private LookupViews() {
    }

    @Schema(name = "AdminOrderRow")
    public record OrderRow(UUID id, @Schema(description = "Mã đơn.") long number, String status, String shopName, int grandTotal, Instant placedAt) {
    }

    @Schema(name = "AdminOrderSearchResult", description = "Tối đa 20 đơn, mới nhất trước.")
    public record OrderResults(List<OrderRow> items) {
    }

    @Schema(name = "AdminOrderPerson", description = "Người đặt, đã che bớt. Dùng `reveal` để xem đầy đủ.")
    public record Person(UUID id, String name, String email, String phone) {
    }

    @Schema(name = "AdminOrderItemOption")
    public record ItemOption(String group, String name, int priceDelta) {
    }

    @Schema(name = "AdminOrderItem", description = "Món như lúc đặt (tên và giá được chốt).")
    public record Item(String name, int quantity, int unitPrice, int lineTotal, String note, List<ItemOption> options) {
    }

    @Schema(name = "AdminOrderStep", description = "`by` là CUSTOMER, SHOP, SYSTEM hoặc ADMIN.")
    public record Step(String from, String to, String by, String reason, Instant at) {
    }

    @Schema(name = "AdminOrderRefund")
    public record Refund(String reason, int amount, String status, String mode, Instant requestedAt) {
    }

    @Schema(name = "AdminOrderPayment")
    public record Payment(String method, String provider, String status, int amount, int refundedAmount, List<Refund> refunds) {
    }

    @Schema(name = "AdminOrderCaseRef", description = "Khiếu nại hoặc báo cáo khách vắng mặt của đơn.")
    public record CaseRef(UUID id, String type, String status, int refundAmount, Instant openedAt) {
    }

    @Schema(name = "AdminOrderDetail")
    public record OrderDetail(
            UUID id, long number, String status, UUID vendorId, String shopName, Person customer,
            @Schema(description = "Tên người nhận.") String deliveryName,
            @Schema(description = "Số điện thoại người nhận, đã che.") String deliveryPhone,
            @Schema(description = "Địa chỉ giao đến cấp phường, phần chi tiết đã che.") String deliveryAddress,
            String note, String paymentMethod, String paymentStatus,
            List<Item> items, int itemsTotal, int discount, int deliveryFee, int grandTotal,
            @Schema(description = "Hoa hồng nền tảng thu trên đơn này.") int commissionAmount,
            Instant placedAt, Instant confirmedAt, Instant outForDeliveryAt, Instant finishedAt,
            List<Step> timeline, Payment payment, List<CaseRef> cases,
            @Schema(description = "Id cuộc trò chuyện giữa khách và quán nếu có; đọc cần quyền `admin-conversation:read`.") UUID conversationId) {
    }

    @Schema(name = "RevealedOrderContact", description = "Thông tin liên hệ đầy đủ; mỗi lần xem đều được ghi nhật ký.")
    public record RevealedOrder(String customerEmail, String customerPhone, String deliveryPhone, String deliveryAddress) {
    }

    @Schema(name = "AdminCustomerRow")
    public record CustomerRow(UUID id, String name, String email, String phone, Set<String> roles, Instant createdAt) {
    }

    @Schema(name = "AdminCustomerSearchResult", description = "Tối đa 20 tài khoản.")
    public record CustomerResults(List<CustomerRow> items) {
    }

    @Schema(name = "AdminCustomerDetail", description = "Không bao giờ có mật khẩu, token hay thiết bị nhận thông báo.")
    public record CustomerDetail(UUID id, String name, String email, String phone, Set<String> roles, boolean emailVerified, Instant createdAt,
            @Schema(description = "10 đơn gần nhất, mới nhất trước.") List<OrderRow> recentOrders) {
    }

    @Schema(name = "RevealedCustomerContact")
    public record RevealedCustomer(String email, String phone) {
    }
}
