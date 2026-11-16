package com.bonbon.backend.communication.messaging.service;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.UUID;

import com.bonbon.backend.identity.legal.PersonalDataSource;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Component;

/** The chat messages the person wrote (not what others wrote to them), newest 5000, for the personal-data export. */
@Component
class ChatPersonalData implements PersonalDataSource {

    private final JdbcClient jdbc;

    ChatPersonalData(JdbcClient jdbc) {
        this.jdbc = jdbc;
    }

    @Override
    public String section() {
        return "messagesSent";
    }

    @Override
    public Object dataOf(UUID userId) {
        return jdbc.sql("""
                select m.text, m.image_key is not null as has_image, m.created_at, m.sender_type, c.vendor_id
                from communication.messages m join communication.conversations c on c.id = m.conversation_id
                where m.sender_id = :u and not m.is_auto_reply order by m.created_at desc limit 5000""")
                .param("u", userId).query((rs, n) -> {
                    Map<String, Object> m = new LinkedHashMap<>();
                    m.put("as", rs.getString("sender_type"));
                    m.put("shopId", rs.getObject("vendor_id", UUID.class));
                    m.put("text", rs.getString("text"));
                    m.put("hadPicture", rs.getBoolean("has_image"));
                    m.put("sentAt", rs.getTimestamp("created_at").toInstant());
                    return m;
                }).list();
    }
}
