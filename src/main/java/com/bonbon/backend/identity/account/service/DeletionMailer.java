package com.bonbon.backend.identity.account.service;

import java.time.ZoneId;
import java.time.format.DateTimeFormatter;

import com.bonbon.backend.identity.account.AccountDeletionRequested;
import com.bonbon.backend.identity.account.AccountErased;
import com.bonbon.backend.common.mail.EmailSender;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Async;
import org.springframework.stereotype.Component;
import org.springframework.transaction.event.TransactionPhase;
import org.springframework.transaction.event.TransactionalEventListener;

/** The two emails of a deletion, sent after the change is saved: how to cancel, and the last one when it is done. */
@Component
class DeletionMailer {

    private static final Logger log = LoggerFactory.getLogger(DeletionMailer.class);
    private static final DateTimeFormatter DAY = DateTimeFormatter.ofPattern("dd/MM/yyyy").withZone(ZoneId.of("Asia/Ho_Chi_Minh"));

    private final EmailSender email;

    DeletionMailer(EmailSender email) {
        this.email = email;
    }

    @Async
    @TransactionalEventListener(phase = TransactionPhase.AFTER_COMMIT)
    void onRequested(AccountDeletionRequested e) {
        send(e.email(), "Bonbon: yêu cầu xoá tài khoản", "Chúng tôi đã nhận yêu cầu xoá tài khoản Bonbon của bạn. Tài khoản sẽ được xoá vào " + DAY.format(e.scheduledAt())
                + ". Muốn giữ lại, chỉ cần đăng nhập lại trước ngày đó và việc xoá sẽ được huỷ.");
    }

    @Async
    @TransactionalEventListener(phase = TransactionPhase.AFTER_COMMIT)
    void onErased(AccountErased e) {
        send(e.formerEmail(), "Bonbon: tài khoản đã được xoá", "Tài khoản Bonbon của bạn đã được xoá. Thông tin cá nhân đã được gỡ; đơn hàng và chứng từ giao dịch được giữ theo quy định nhưng không còn gắn với tên bạn.");
    }

    private void send(String to, String subject, String body) {
        try {
            email.send(new EmailSender.EmailMessage(to, subject, body));
        } catch (RuntimeException ex) {
            log.warn("Deletion email failed", ex);
        }
    }
}
