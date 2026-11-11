package com.bonbon.backend.communication.notification.service;

import com.bonbon.backend.identity.account.AccountDeletionRequested;
import com.bonbon.backend.identity.account.AccountErased;
import org.springframework.context.event.EventListener;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Component;

/**
 * What the notification module keeps about a person who deletes the account: their phones stop receiving pushes the moment they
 * ask, and when the grace period ends their devices, preferences and stored notifications are deleted.
 */
@Component
class AccountErasure {

    private final JdbcClient jdbc;

    AccountErasure(JdbcClient jdbc) {
        this.jdbc = jdbc;
    }

    @EventListener
    void onRequested(AccountDeletionRequested e) {
        jdbc.sql("update push_devices set status = 'REVOKED' where user_id = :u and status = 'ACTIVE'").param("u", e.userId()).update();
    }

    @EventListener
    void onErased(AccountErased e) {
        jdbc.sql("delete from push_devices where user_id = :u").param("u", e.userId()).update();
        jdbc.sql("delete from notification_preferences where user_id = :u").param("u", e.userId()).update();
        jdbc.sql("delete from notification_quiet_hours where user_id = :u").param("u", e.userId()).update();
        jdbc.sql("delete from notifications where recipient_id = :u").param("u", e.userId()).update();
    }
}
