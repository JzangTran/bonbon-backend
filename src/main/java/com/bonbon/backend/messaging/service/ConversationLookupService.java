package com.bonbon.backend.messaging.service;

import java.util.Optional;
import java.util.UUID;

import com.bonbon.backend.messaging.ConversationLookup;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
class ConversationLookupService implements ConversationLookup {

    private final JdbcClient jdbc;

    ConversationLookupService(JdbcClient jdbc) {
        this.jdbc = jdbc;
    }

    @Override
    @Transactional(readOnly = true)
    public Optional<UUID> between(UUID customerId, UUID vendorId) {
        return jdbc.sql("select id from conversations where customer_id = :c and vendor_id = :v").param("c", customerId).param("v", vendorId).query(UUID.class).optional();
    }
}
