package com.bonbon.backend.notification.repository;

import java.time.Instant;
import java.util.Collection;
import java.util.UUID;

import com.bonbon.backend.notification.entity.Notification;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface NotificationRepository extends JpaRepository<Notification, UUID> {

    @Query("select n from Notification n where n.recipientId = :recipient and n.audience = :audience order by n.createdAt desc")
    Page<Notification> findAllFor(@Param("recipient") UUID recipient, @Param("audience") String audience, Pageable pageable);

    @Query("select n from Notification n where n.recipientId = :recipient and n.audience = :audience and n.acknowledgedAt is null order by n.createdAt desc")
    Page<Notification> findUnreadFor(@Param("recipient") UUID recipient, @Param("audience") String audience, Pageable pageable);

    @Query("select count(n) from Notification n where n.recipientId = :recipient and n.audience = :audience and n.acknowledgedAt is null")
    long countUnread(@Param("recipient") UUID recipient, @Param("audience") String audience);

    /** Idempotent: acknowledging twice changes nothing the second time. */
    @Modifying(flushAutomatically = true, clearAutomatically = true)
    @Query("update Notification n set n.acknowledgedAt = :now where n.id = :id and n.recipientId = :recipient and n.acknowledgedAt is null")
    int acknowledge(@Param("id") UUID id, @Param("recipient") UUID recipient, @Param("now") Instant now);

    @Modifying(flushAutomatically = true, clearAutomatically = true)
    @Query("update Notification n set n.acknowledgedAt = :now where n.recipientId = :recipient and n.audience = :audience and n.acknowledgedAt is null")
    int acknowledgeAll(@Param("recipient") UUID recipient, @Param("audience") String audience, @Param("now") Instant now);

    /** A new-order alert is resolved by the shop answering: once the order leaves PLACED nobody needs to be nagged. */
    @Modifying(flushAutomatically = true, clearAutomatically = true)
    @Query("update Notification n set n.acknowledgedAt = :now where n.orderId = :orderId and n.type = 'ORDER_NEW' and n.acknowledgedAt is null")
    int resolveNewOrderAlerts(@Param("orderId") UUID orderId, @Param("now") Instant now);

    @Modifying(flushAutomatically = true, clearAutomatically = true)
    @Query("update Notification n set n.deliveredAt = :now where n.id in :ids and n.deliveredAt is null")
    int markDelivered(@Param("ids") Collection<UUID> ids, @Param("now") Instant now);
}
