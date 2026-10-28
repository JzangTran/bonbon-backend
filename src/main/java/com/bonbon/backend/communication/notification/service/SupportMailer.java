package com.bonbon.backend.communication.notification.service;

import com.bonbon.backend.identity.authentication.CustomerDirectory;
import com.bonbon.backend.common.mail.EmailSender;
import com.bonbon.backend.support.ContactRequestAnswered;
import com.bonbon.backend.support.TicketAnswered;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Async;
import org.springframework.stereotype.Component;
import org.springframework.transaction.event.TransactionPhase;
import org.springframework.transaction.event.TransactionalEventListener;

/** The email that goes with a support answer, after the answer is saved and only if the person left email on for support replies. */
@Component
class SupportMailer {

    private static final Logger log = LoggerFactory.getLogger(SupportMailer.class);

    private final PreferenceService preferences;
    private final CustomerDirectory accounts;
    private final EmailSender email;

    SupportMailer(PreferenceService preferences, CustomerDirectory accounts, EmailSender email) {
        this.preferences = preferences;
        this.accounts = accounts;
        this.email = email;
    }

    @Async
    @TransactionalEventListener(phase = TransactionPhase.AFTER_COMMIT)
    void onAnswered(TicketAnswered event) {
        try {
            if (!preferences.allows(event.userId(), NotificationCategory.SUPPORT_REPLIES, "EMAIL")) {
                return;
            }
            accounts.get(event.userId()).filter(CustomerDirectory.Account::emailVerified).ifPresent(a -> email.send(new EmailSender.EmailMessage(a.email(),
                    "Bonbon đã trả lời phiếu hỗ trợ của bạn", "Phiếu \"" + event.subject() + "\" đã có câu trả lời. Mở ứng dụng Bonbon, mục Trợ giúp, để xem và trả lời.")));
        } catch (RuntimeException e) {
            log.warn("Support email failed", e);
        }
    }

    /** The answer to the public contact form: the person may have no account, so it always goes to the email they typed. */
    @Async
    @TransactionalEventListener(phase = TransactionPhase.AFTER_COMMIT)
    void onContactAnswered(ContactRequestAnswered event) {
        try {
            String question = event.question().length() > 500 ? event.question().substring(0, 499) + "…" : event.question();
            email.send(new EmailSender.EmailMessage(event.email(), "Bonbon trả lời câu hỏi của bạn",
                    "Chào " + event.fullName() + ",\n\n" + event.answer() + "\n\n— Đội hỗ trợ Bonbon\n\nBạn đã hỏi:\n" + question
                            + "\n\nCần hỏi thêm, hãy gửi một yêu cầu mới ở trang Liên hệ hỗ trợ."));
        } catch (RuntimeException e) {
            log.warn("Contact answer email failed", e);
        }
    }
}
