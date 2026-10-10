package com.bonbon.backend.authentication;

import java.util.Arrays;
import java.util.Map;
import java.util.function.Function;
import java.util.stream.Collectors;

/**
 * Fixed catalog of {@code <resource>:<action>} permissions (docs: reference/architecture/permissions.md).
 * Which role holds which is data ({@code role_permissions}); the names themselves only change with code.
 */
public enum Permission {
    ORDER_CREATE("order:create"),
    ORDER_CANCEL("order:cancel"),
    ORDER_REPORT("order:report"),
    ORDER_READ("order:read"),
    ORDER_WRITE("order:write"),
    REVIEW_CREATE("review:create"),
    REVIEW_RESPOND("review:respond"),
    VENDOR_CREATE("vendor:create"),
    VENDOR_READ("vendor:read"),
    VENDOR_WRITE("vendor:write"),
    VOUCHER_READ("voucher:read"),
    VOUCHER_WRITE("voucher:write"),
    STATS_READ("stats:read"),
    EARNINGS_READ("earnings:read"),
    CONVERSATION_READ("conversation:read"),
    CONVERSATION_WRITE("conversation:write"),
    MERCHANT_APPROVAL_READ("merchant-approval:read"),
    MERCHANT_APPROVAL_READ_IDENTITY("merchant-approval:read-identity"),
    MERCHANT_APPROVAL_DECIDE("merchant-approval:decide"),
    MERCHANT_APPROVAL_SUSPEND("merchant-approval:suspend"),
    MERCHANT_APPROVAL_WRITE_SETTINGS("merchant-approval:write-settings"),
    ADMIN_CONVERSATION_READ("admin-conversation:read"),
    HELP_CENTER_WRITE("help-center:write"),
    PLATFORM_STATS_READ("platform-stats:read"),
    ADMIN_WRITE("admin:write"),
    SHOP_PENALTY_READ("shop-penalty:read"),
    SHOP_PENALTY_WRITE("shop-penalty:write"),
    REVIEW_MODERATE("review:moderate"),
    ORDER_CASE_READ("order-case:read"),
    ORDER_CASE_DECIDE("order-case:decide"),
    TICKET_READ("ticket:read"),
    TICKET_REPLY("ticket:reply"),
    COMMISSION_WRITE("commission:write"),
    DISH_MODERATE("dish:moderate"),
    CATEGORY_WRITE("category:write"),
    SETTLEMENT_READ("settlement:read"),
    SETTLEMENT_WRITE("settlement:write"),
    REFUND_PROCESS("refund:process"),
    LEGAL_DOCUMENT_WRITE("legal-document:write"),
    LEGAL_DOCUMENT_READ_CONSENTS("legal-document:read-consents"),
    DATA_REQUEST_HANDLE("data-request:handle"),
    REPORT_READ("report:read"),
    REPORT_DECIDE("report:decide"),
    MODERATION_WRITE("moderation:write"),
    CUSTOMER_ABUSE_READ("customer-abuse:read"),
    CUSTOMER_ABUSE_DECIDE("customer-abuse:decide"),
    ADMIN_ORDER_READ("admin-order:read"),
    ADMIN_CUSTOMER_READ("admin-customer:read"),
    APP_CONFIG_WRITE("app-config:write");

    private static final Map<String, Permission> BY_CODE =
            Arrays.stream(values()).collect(Collectors.toUnmodifiableMap(Permission::code, Function.identity()));

    private final String code;

    Permission(String code) {
        this.code = code;
    }

    public String code() {
        return code;
    }

    public static Permission fromCode(String code) {
        Permission p = BY_CODE.get(code);
        if (p == null) {
            throw new IllegalArgumentException("Unknown permission: " + code);
        }
        return p;
    }
}
