package com.bonbon.backend.notification.service;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Set;

/**
 * What a user can switch on or off (push-notifications.md "Notification preferences"). A locked category is never
 * filtered: an order that needs the shop's answer, or a security notice, must always arrive. For the others
 * {@link #defaults()} says which channels exist and whether they start on.
 */
public enum NotificationCategory {

    ORDER_ALERTS("Đơn hàng cần chú ý", "Đơn mới, đơn bị huỷ hoặc từ chối, khiếu nại và hoàn tiền. Không thể tắt.", true,
            Set.of("CUSTOMER", "SELLER"), channels("PUSH", true)),
    ACCOUNT_SECURITY("Tài khoản và bảo mật", "Đăng nhập từ thiết bị mới, đổi mật khẩu và các thay đổi tài khoản. Không thể tắt.", true,
            Set.of("CUSTOMER", "SELLER"), channels("PUSH", true, "EMAIL", true)),
    ORDER_PROGRESS("Tiến trình đơn hàng", "Quán đã nhận đơn, đang chuẩn bị, đang giao và đã giao.", false,
            Set.of("CUSTOMER"), channels("PUSH", true)),
    CHAT_MESSAGES("Tin nhắn", "Tin nhắn mới khi bạn không mở ứng dụng. Áp dụng cho mọi cuộc trò chuyện, không tắt riêng từng cuộc.", false,
            Set.of("CUSTOMER", "SELLER"), channels("PUSH", true, "EMAIL", false)),
    SHOP_NOTICES("Thông báo về cửa hàng", "Kết quả duyệt, tiền được chuyển, hoa hồng, điểm phạt và đình chỉ. Tắt đẩy vẫn còn trong ứng dụng.", false,
            Set.of("SELLER"), channels("PUSH", true));

    private final String title;
    private final String description;
    private final boolean locked;
    private final Set<String> roles;
    private final Map<String, Boolean> defaults;

    NotificationCategory(String title, String description, boolean locked, Set<String> roles, Map<String, Boolean> defaults) {
        this.title = title;
        this.description = description;
        this.locked = locked;
        this.roles = roles;
        this.defaults = defaults;
    }

    public String title() {
        return title;
    }

    public String description() {
        return description;
    }

    public boolean locked() {
        return locked;
    }

    /** The channels this category is sent on, with whether each starts on. */
    public Map<String, Boolean> defaults() {
        return defaults;
    }

    /** True for the roles (CUSTOMER, SELLER) that have this category. */
    public boolean forRole(String role) {
        return roles.contains(role);
    }

    /** The category of a stored notification, by who it is for and what it is about. */
    public static NotificationCategory of(String audience, String type) {
        if (NotificationService.SHOP.equals(audience)) {
            boolean notice = type.equals("PAYOUT_RECORDED") || type.startsWith("COMMISSION_") || type.startsWith("SHOP_");
            return notice ? SHOP_NOTICES : ORDER_ALERTS;
        }
        return switch (type) {
            case "ORDER_PAID", "ORDER_CONFIRMED", "ORDER_PREPARING", "ORDER_OUT_FOR_DELIVERY", "ORDER_DELIVERED" -> ORDER_PROGRESS;
            default -> ORDER_ALERTS;
        };
    }

    private static Map<String, Boolean> channels(Object... pairs) {
        Map<String, Boolean> map = new LinkedHashMap<>();
        for (int i = 0; i < pairs.length; i += 2) {
            map.put((String) pairs[i], (Boolean) pairs[i + 1]);
        }
        return map;
    }
}
