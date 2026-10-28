package com.bonbon.backend.communication.notification.service;

import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

import com.bonbon.backend.identity.authentication.CustomerDirectory;
import com.bonbon.backend.common.mail.EmailSender;
import com.bonbon.backend.identity.legal.DocumentType;
import com.bonbon.backend.identity.legal.LegalAudience;
import com.bonbon.backend.identity.legal.LegalDocumentCancelled;
import com.bonbon.backend.identity.legal.LegalDocumentPublished;
import com.bonbon.backend.identity.legal.LegalDocumentReminder;
import com.bonbon.backend.communication.notification.entity.Notification;
import com.bonbon.backend.communication.notification.repository.NotificationRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.scheduling.annotation.Async;
import org.springframework.stereotype.Component;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.event.TransactionPhase;
import org.springframework.transaction.event.TransactionalEventListener;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * Telling the people a legal document concerns (publish-legal-document.md): when a version is published, a few days before a
 * material one takes effect, and when one is withdrawn. Each person gets an in-app notification (and the push that goes with it)
 * and an email with the summary and the date. These notices cannot be switched off: they are about the terms the person agreed to.
 */
@Component
class LegalNotifier {

    private static final Logger log = LoggerFactory.getLogger(LegalNotifier.class);
    private static final DateTimeFormatter DAY = DateTimeFormatter.ofPattern("dd/MM/yyyy").withZone(ZoneId.of("Asia/Ho_Chi_Minh"));

    private final LegalAudience audience;
    private final NotificationRepository notifications;
    private final CustomerDirectory accounts;
    private final EmailSender email;
    private final ApplicationEventPublisher events;
    private final TransactionTemplate tx;

    LegalNotifier(LegalAudience audience, NotificationRepository notifications, CustomerDirectory accounts, EmailSender email, ApplicationEventPublisher events,
            PlatformTransactionManager transactions) {
        this.audience = audience;
        this.notifications = notifications;
        this.accounts = accounts;
        this.email = email;
        this.events = events;
        this.tx = new TransactionTemplate(transactions);
    }

    @Async
    @TransactionalEventListener(phase = TransactionPhase.AFTER_COMMIT)
    void onPublished(LegalDocumentPublished e) {
        String when = DAY.format(e.effectiveAt());
        String body = (e.summary() == null || e.summary().isBlank() ? "" : e.summary() + " ")
                + (e.material() ? "Thay đổi có hiệu lực từ " + when + "; bạn sẽ cần đồng ý lại để tiếp tục dùng Bonbon." : "Có hiệu lực từ " + when + ".");
        tell(e.type(), "LEGAL_PUBLISHED", "Cập nhật: " + e.title(), body);
    }

    @Async
    @TransactionalEventListener(phase = TransactionPhase.AFTER_COMMIT)
    void onReminder(LegalDocumentReminder e) {
        tell(e.type(), "LEGAL_REMINDER", "Sắp có hiệu lực: " + e.title(), "Thay đổi có hiệu lực từ " + DAY.format(e.effectiveAt()) + ". Bạn sẽ cần đồng ý lại để tiếp tục dùng Bonbon.");
    }

    @Async
    @TransactionalEventListener(phase = TransactionPhase.AFTER_COMMIT)
    void onCancelled(LegalDocumentCancelled e) {
        tell(e.type(), "LEGAL_CANCELLED", "Không áp dụng: " + e.title(), "Thay đổi đã thông báo sẽ không áp dụng." + (e.reason() == null ? "" : " Lý do: " + e.reason()));
    }

    private void tell(DocumentType type, String notificationType, String title, String body) {
        try {
            List<Notification> created = new ArrayList<>();
            for (LegalAudience.Recipient r : audience.of(type)) {
                Notification n = new Notification(r.principalId(), r.audience(), notificationType, null, null, title, body);
                tx.executeWithoutResult(status -> notifications.save(n));
                created.add(n);
                Optional<CustomerDirectory.Account> account = accounts.get(r.principalId()).filter(CustomerDirectory.Account::emailVerified);
                account.ifPresent(a -> email.send(new EmailSender.EmailMessage(a.email(), title, body + "\n\nXem chi tiết trong ứng dụng Bonbon, mục Tài khoản.")));
            }
            if (!created.isEmpty()) {
                // Inside a transaction, so the push goes out when it commits (PushDispatcher listens after commit).
                tx.executeWithoutResult(status -> events.publishEvent(new NotificationService.Created(List.copyOf(created))));
            }
        } catch (RuntimeException e) {
            log.warn("Legal notification failed", e);
        }
    }
}
