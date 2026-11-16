package com.bonbon.backend.marketplace.merchant.service;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.UUID;

import com.bonbon.backend.identity.legal.PersonalDataSource;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Component;

/**
 * The person's shop, if they have one, for the personal-data export. Identity document numbers are stored encrypted and shown here only
 * by their last digits; the document pictures stay out of the file and can be requested from support.
 */
@Component
class ShopPersonalData implements PersonalDataSource {

    private final JdbcClient jdbc;

    ShopPersonalData(JdbcClient jdbc) {
        this.jdbc = jdbc;
    }

    @Override
    public String section() {
        return "shop";
    }

    @Override
    public Object dataOf(UUID userId) {
        return jdbc.sql("""
                select v.name, v.phone, v.email, v.status, v.created_at, i.full_name, i.doc_type, i.doc_number_last4
                from marketplace.vendors v left join marketplace.vendor_identity i on i.vendor_id = v.id where v.owner_user_id = :u""")
                .param("u", userId).query((rs, n) -> {
                    Map<String, Object> s = new LinkedHashMap<>();
                    s.put("name", rs.getString("name"));
                    s.put("phone", rs.getString("phone"));
                    s.put("email", rs.getString("email"));
                    s.put("status", rs.getString("status"));
                    s.put("createdAt", rs.getTimestamp("created_at").toInstant());
                    s.put("identityName", rs.getString("full_name"));
                    s.put("identityDocument", rs.getString("doc_type"));
                    s.put("identityNumberEndsWith", rs.getString("doc_number_last4"));
                    return s;
                }).optional().orElse(null);
    }
}
