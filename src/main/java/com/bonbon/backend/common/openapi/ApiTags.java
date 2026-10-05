package com.bonbon.backend.common.openapi;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * The names of the API reference sections, grouped by who calls them. Controllers use these constants in
 * {@code @Tag}, so a name is changed in one place and the groups below always match.
 */
public final class ApiTags {

    private ApiTags() {
    }

    // Chung: everyone
    public static final String LOGIN = "Đăng nhập";
    public static final String REGISTRATION = "Đăng ký & xác thực email";
    public static final String SESSION = "Phiên & mật khẩu";
    public static final String ACCOUNT = "Tài khoản";
    public static final String NOTIFICATIONS = "Thông báo";
    public static final String PUSH_DEVICES = "Thiết bị nhận thông báo đẩy";
    public static final String ADDRESS_SUGGESTIONS = "Gợi ý địa chỉ";
    public static final String CATEGORIES = "Ngành hàng";
    public static final String LEGAL = "Điều khoản & chính sách";

    // Khách
    public static final String CUSTOMER_ADDRESSES = "Khách — Địa chỉ giao hàng";
    public static final String CUSTOMER_BROWSE = "Khách — Tìm quán & thực đơn";
    public static final String CUSTOMER_ORDERS = "Khách — Đơn hàng";
    public static final String CUSTOMER_REVIEWS = "Khách — Đánh giá";

    // Người bán
    public static final String SELLER_OPEN_SHOP = "Người bán — Mở cửa hàng";
    public static final String SELLER_SHOP = "Người bán — Thông tin cửa hàng";
    public static final String SELLER_MENU = "Người bán — Thực đơn";
    public static final String SELLER_OPTIONS = "Người bán — Nhóm lựa chọn";
    public static final String SELLER_ORDERS = "Người bán — Đơn hàng";
    public static final String SELLER_REVIEWS = "Người bán — Đánh giá";

    // Quản trị
    public static final String ADMIN_AUTH = "Quản trị — Đăng nhập";
    public static final String ADMIN_ADMINS = "Quản trị — Quản trị viên";
    public static final String ADMIN_CATEGORIES = "Quản trị — Ngành hàng";
    public static final String ADMIN_SHOP_REVIEW = "Quản trị — Duyệt cửa hàng";
    public static final String ADMIN_REVIEWS = "Quản trị — Kiểm duyệt đánh giá";

    /** Sidebar groups of the reference (the {@code x-tagGroups} extension Scalar reads), in display order. */
    public static final Map<String, List<String>> GROUPS = groups();

    private static Map<String, List<String>> groups() {
        Map<String, List<String>> g = new LinkedHashMap<>();
        g.put("Chung", List.of(LOGIN, REGISTRATION, SESSION, ACCOUNT, NOTIFICATIONS, PUSH_DEVICES, ADDRESS_SUGGESTIONS, CATEGORIES, LEGAL));
        g.put("Khách", List.of(CUSTOMER_ADDRESSES, CUSTOMER_BROWSE, CUSTOMER_ORDERS, CUSTOMER_REVIEWS));
        g.put("Người bán", List.of(SELLER_OPEN_SHOP, SELLER_SHOP, SELLER_MENU, SELLER_OPTIONS, SELLER_ORDERS, SELLER_REVIEWS));
        g.put("Quản trị", List.of(ADMIN_AUTH, ADMIN_ADMINS, ADMIN_CATEGORIES, ADMIN_SHOP_REVIEW, ADMIN_REVIEWS));
        return g;
    }
}
