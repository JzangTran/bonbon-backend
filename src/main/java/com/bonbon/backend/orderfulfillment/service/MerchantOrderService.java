package com.bonbon.backend.orderfulfillment.service;

import java.time.LocalDate;
import java.util.Collection;
import java.util.UUID;

import com.bonbon.backend.common.exception.BusinessException;
import com.bonbon.backend.common.security.CurrentPrincipal;
import com.bonbon.backend.merchant.ShopOrdering;
import com.bonbon.backend.order.OrderStatus;
import com.bonbon.backend.order.ShopOrders;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;

/** Works out which shop the caller acts for, then hands the order work to the order module. */
@Service
public class MerchantOrderService {

    private final ShopOrdering shops;
    private final ShopOrders orders;

    MerchantOrderService(ShopOrdering shops, ShopOrders orders) {
        this.shops = shops;
        this.orders = orders;
    }

    public ShopOrders.ShopOrderPage list(CurrentPrincipal caller, Collection<OrderStatus> statuses, LocalDate from, LocalDate to,
            boolean oldestFirst, int page, int size) {
        return orders.list(vendorOf(caller), statuses, from, to, oldestFirst, page, size);
    }

    public ShopOrders.ShopOrderDetail get(CurrentPrincipal caller, UUID orderId) {
        return orders.get(vendorOf(caller), orderId);
    }

    public ShopOrders.ShopOrderDetail move(CurrentPrincipal caller, UUID orderId, OrderStatus to, String reason) {
        return orders.transition(vendorOf(caller), orderId, to, reason, caller.actorType(), caller.id());
    }

    private UUID vendorOf(CurrentPrincipal caller) {
        return shops.operatingVendorOwnedBy(caller.id()).orElseThrow(() -> new BusinessException(HttpStatus.CONFLICT, "SHOP_NOT_APPROVED",
                "Bạn chưa có cửa hàng được duyệt."));
    }
}
