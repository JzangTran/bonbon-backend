package com.bonbon.backend.legal;

import java.time.Instant;
import java.util.Collection;
import java.util.List;
import java.util.Set;
import java.util.UUID;

import com.bonbon.backend.common.exception.BusinessException;
import com.bonbon.backend.common.web.ClientContext;
import com.bonbon.backend.legal.entity.ConsentRecord;
import com.bonbon.backend.legal.entity.LegalDocument;
import com.bonbon.backend.legal.repository.ConsentRecordRepository;
import com.bonbon.backend.legal.repository.LegalDocumentRepository;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

/**
 * Public API of the legal module: the documents in force, and recording consent in the caller's
 * transaction (no account without its evidence).
 */
@Service
public class LegalConsentService {

    private final LegalDocumentRepository documents;
    private final ConsentRecordRepository consents;

    LegalConsentService(LegalDocumentRepository documents, ConsentRecordRepository consents) {
        this.documents = documents;
        this.consents = consents;
    }

    @Transactional(readOnly = true)
    public LegalDocumentView current(DocumentType type) {
        return findCurrent(type).toView();
    }

    /**
     * Checks that {@code acceptedDocumentIds} are exactly the current versions of {@code requiredTypes}
     * and writes one TERMS row per document, plus the optional marketing decision.
     *
     * @throws BusinessException 400 CONSENT_REQUIRED when a required document is missing,
     *                           409 LEGAL_DOCUMENTS_CHANGED when an id is not the version in force
     */
    @Transactional(propagation = Propagation.MANDATORY)
    public void recordAcceptance(String principalType, UUID principalId, Collection<DocumentType> requiredTypes,
            Set<UUID> acceptedDocumentIds, Boolean marketingConsent, ClientContext client) {
        if (acceptedDocumentIds == null || acceptedDocumentIds.isEmpty()) {
            throw new BusinessException(HttpStatus.BAD_REQUEST, "CONSENT_REQUIRED",
                    "Bạn cần đồng ý với điều khoản và chính sách quyền riêng tư.");
        }
        List<LegalDocument> required = requiredTypes.stream().map(this::findCurrent).toList();
        Set<UUID> requiredIds = required.stream().map(LegalDocument::getId).collect(java.util.stream.Collectors.toSet());
        if (!acceptedDocumentIds.containsAll(requiredIds)) {
            boolean anyKnownButStale = acceptedDocumentIds.stream().anyMatch(id -> !requiredIds.contains(id));
            throw anyKnownButStale
                    ? new BusinessException(HttpStatus.CONFLICT, "LEGAL_DOCUMENTS_CHANGED",
                            "Điều khoản vừa được cập nhật, vui lòng xem lại và đồng ý phiên bản mới.")
                    : new BusinessException(HttpStatus.BAD_REQUEST, "CONSENT_REQUIRED",
                            "Bạn cần đồng ý với điều khoản và chính sách quyền riêng tư.");
        }
        for (LegalDocument doc : required) {
            consents.save(new ConsentRecord(principalType, principalId, "TERMS", doc.getId(), true,
                    client.channel(), client.appVersion(), client.ip()));
        }
        if (marketingConsent != null) {
            consents.save(new ConsentRecord(principalType, principalId, "MARKETING", null, marketingConsent,
                    client.channel(), client.appVersion(), client.ip()));
        }
    }

    /**
     * The separate, unticked-by-default consent to processing identity data for shop verification
     * (open-shop.md step 4), evidenced against the privacy policy version shown.
     *
     * @throws BusinessException 409 LEGAL_DOCUMENTS_CHANGED when {@code privacyPolicyId} is not the version in force
     */
    @Transactional(propagation = Propagation.MANDATORY)
    public void recordIdentityVerificationConsent(String principalType, UUID principalId, UUID privacyPolicyId,
            ClientContext client) {
        LegalDocument privacy = findCurrent(DocumentType.PRIVACY_POLICY);
        if (!privacy.getId().equals(privacyPolicyId)) {
            throw new BusinessException(HttpStatus.CONFLICT, "LEGAL_DOCUMENTS_CHANGED",
                    "Chính sách quyền riêng tư vừa được cập nhật, vui lòng xem lại và đồng ý phiên bản mới.");
        }
        consents.save(new ConsentRecord(principalType, principalId, "IDENTITY_VERIFICATION", privacy.getId(), true,
                client.channel(), client.appVersion(), client.ip()));
    }

    private LegalDocument findCurrent(DocumentType type) {
        return documents.findCurrent(type, Instant.now())
                .orElseThrow(() -> new IllegalStateException("No published " + type + " document"));
    }
}
