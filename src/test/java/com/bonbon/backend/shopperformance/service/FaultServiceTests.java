package com.bonbon.backend.shopperformance.service;

import java.util.UUID;

import com.bonbon.backend.common.persistence.ActorType;
import com.bonbon.backend.order.OrderStatus;
import com.bonbon.backend.order.OrderStatusChanged;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/** Whose fault an ending is (flows/shop-performance/README.md, "What counts as a seller-fault order"). */
class FaultServiceTests {

    private static String type(OrderStatus from, OrderStatus to, ActorType by) {
        UUID id = UUID.randomUUID();
        return FaultService.typeOf(new OrderStatusChanged(id, 1, id, id, from, to, by));
    }

    @Test
    void theShopNeverComingIsAFaultAndTheCustomerBeingAbsentIsNot() {
        assertThat(type(OrderStatus.OUT_FOR_DELIVERY, OrderStatus.CANCELLED, ActorType.SYSTEM)).isEqualTo("NO_SHOW_SHOP_AT_FAULT");
        assertThat(type(OrderStatus.OUT_FOR_DELIVERY, OrderStatus.NOT_DELIVERED, ActorType.CUSTOMER)).isNull();
        assertThat(type(OrderStatus.OUT_FOR_DELIVERY, OrderStatus.NOT_DELIVERED, ActorType.SYSTEM)).isNull();
    }

    @Test
    void declinesTimeoutsAndCancellationsByTheShopCountButTheCustomersOwnDoNot() {
        assertThat(type(OrderStatus.PLACED, OrderStatus.REJECTED, ActorType.SHOP_ACCOUNT)).isEqualTo("SHOP_REJECTED");
        assertThat(type(OrderStatus.PLACED, OrderStatus.REJECTED, ActorType.SYSTEM)).isEqualTo("NO_RESPONSE");
        assertThat(type(OrderStatus.CONFIRMED, OrderStatus.CANCELLED, ActorType.SYSTEM)).isEqualTo("HANDOVER_TIMEOUT");
        assertThat(type(OrderStatus.PREPARING, OrderStatus.CANCELLED, ActorType.MEMBER)).isEqualTo("SHOP_CANCELLED");
        assertThat(type(OrderStatus.PLACED, OrderStatus.CANCELLED, ActorType.CUSTOMER)).isNull();
        assertThat(type(OrderStatus.PENDING_PAYMENT, OrderStatus.CANCELLED, ActorType.SYSTEM)).isNull();
        assertThat(type(OrderStatus.OUT_FOR_DELIVERY, OrderStatus.DELIVERED, ActorType.CUSTOMER)).isNull();
    }
}
