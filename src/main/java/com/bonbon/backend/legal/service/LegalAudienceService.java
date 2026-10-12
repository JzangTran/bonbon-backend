package com.bonbon.backend.legal.service;

import java.util.List;

import com.bonbon.backend.legal.DocumentType;
import com.bonbon.backend.legal.LegalAudience;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
class LegalAudienceService implements LegalAudience {

    private final JdbcClient jdbc;

    LegalAudienceService(JdbcClient jdbc) {
        this.jdbc = jdbc;
    }

    @Override
    @Transactional(readOnly = true)
    public List<Recipient> of(DocumentType type) {
        return switch (type) {
            case CUSTOMER_TERMS -> accepted(DocumentType.CUSTOMER_TERMS, "CUSTOMER");
            case SELLER_TERMS -> accepted(DocumentType.SELLER_TERMS, "SHOP");
            case PRIVACY_POLICY -> {
                List<Recipient> all = new java.util.ArrayList<>(accepted(DocumentType.CUSTOMER_TERMS, "CUSTOMER"));
                all.addAll(accepted(DocumentType.SELLER_TERMS, "SHOP"));
                yield all;
            }
        };
    }

    private List<Recipient> accepted(DocumentType terms, String audience) {
        return jdbc.sql("""
                select distinct c.principal_id from consent_records c join legal_documents d on d.id = c.document_id
                where c.purpose = 'TERMS' and c.granted and d.type = :t order by c.principal_id""")
                .param("t", terms.name()).query((rs, n) -> new Recipient(rs.getObject("principal_id", java.util.UUID.class), audience)).list();
    }
}
