package com.bonbon.backend.identity.legal.service;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.UUID;

import com.bonbon.backend.identity.legal.PersonalDataSource;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Component;

/** The account itself for the export: profile, delivery addresses, and every consent given or withdrawn. Never the password hash. */
@Component
class IdentityPersonalData implements PersonalDataSource {

    private final JdbcClient jdbc;

    IdentityPersonalData(JdbcClient jdbc) {
        this.jdbc = jdbc;
    }

    @Override
    public String section() {
        return "account";
    }

    @Override
    public Object dataOf(UUID userId) {
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("profile", jdbc.sql("select email, name, phone, email_verified, created_at from identity.users where id = :u").param("u", userId)
                .query((rs, n) -> {
                    Map<String, Object> p = new LinkedHashMap<>();
                    p.put("email", rs.getString("email"));
                    p.put("name", rs.getString("name"));
                    p.put("phone", rs.getString("phone"));
                    p.put("emailVerified", rs.getBoolean("email_verified"));
                    p.put("createdAt", rs.getTimestamp("created_at").toInstant());
                    return p;
                }).optional().orElse(null));
        out.put("roles", jdbc.sql("select role from identity.user_roles where user_id = :u").param("u", userId).query(String.class).list());
        out.put("addresses", jdbc.sql("""
                select label, formatted_address, detail, recipient_name, recipient_phone, note, created_at from identity.addresses where customer_id = :u""")
                .param("u", userId).query((rs, n) -> {
                    Map<String, Object> a = new LinkedHashMap<>();
                    a.put("label", rs.getString("label"));
                    a.put("address", rs.getString("formatted_address"));
                    a.put("detail", rs.getString("detail"));
                    a.put("recipientName", rs.getString("recipient_name"));
                    a.put("recipientPhone", rs.getString("recipient_phone"));
                    a.put("note", rs.getString("note"));
                    a.put("createdAt", rs.getTimestamp("created_at").toInstant());
                    return a;
                }).list());
        out.put("consents", jdbc.sql("""
                select c.purpose, c.granted, c.at, c.channel, d.type as document_type, d.version
                from identity.consent_records c left join identity.legal_documents d on d.id = c.document_id
                where c.principal_id = :u order by c.at""")
                .param("u", userId).query((rs, n) -> {
                    Map<String, Object> c = new LinkedHashMap<>();
                    c.put("purpose", rs.getString("purpose"));
                    c.put("granted", rs.getBoolean("granted"));
                    c.put("at", rs.getTimestamp("at").toInstant());
                    c.put("channel", rs.getString("channel"));
                    c.put("document", rs.getString("document_type"));
                    c.put("version", rs.getObject("version"));
                    return c;
                }).list());
        return out;
    }
}
