package com.bonbon.backend.authentication.service;

import com.bonbon.backend.common.mail.EmailSender;
import com.bonbon.backend.common.mail.EmailSender.EmailMessage;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.scheduling.annotation.Async;
import org.springframework.stereotype.Component;
import org.springframework.transaction.event.TransactionPhase;
import org.springframework.transaction.event.TransactionalEventListener;
import org.springframework.web.util.UriComponentsBuilder;

/** Sends authentication emails only after the transaction that created the token has committed. */
@Component
class AuthEmails {

    record VerificationEmailRequested(String email, String name, String rawToken) {
    }

    record PasswordLinkRequested(String email, String name, String rawToken, boolean initialPassword) {
    }

    private final EmailSender sender;
    private final String webUrl;

    AuthEmails(EmailSender sender, @Value("${bonbon.app.web-url:http://localhost:5173}") String webUrl) {
        this.sender = sender;
        this.webUrl = webUrl;
    }

    @Async
    @TransactionalEventListener(phase = TransactionPhase.AFTER_COMMIT)
    void onVerificationRequested(VerificationEmailRequested event) {
        String link = link("/verify-email", event.rawToken());
        sender.send(new EmailMessage(event.email(), "Xác thực email tài khoản bonbon",
                "Chào " + event.name() + ",\n\n"
                        + "Bấm vào liên kết dưới đây để xác thực email (hiệu lực 24 giờ):\n" + link + "\n\n"
                        + "Nếu bạn không đăng ký bonbon, hãy bỏ qua email này."));
    }

    @Async
    @TransactionalEventListener(phase = TransactionPhase.AFTER_COMMIT)
    void onPasswordLinkRequested(PasswordLinkRequested event) {
        String path = event.initialPassword() ? "/set-password" : "/reset-password";
        String link = link(path, event.rawToken());
        String subject = event.initialPassword() ? "Đặt mật khẩu tài khoản quản trị bonbon" : "Đặt lại mật khẩu bonbon";
        String validity = event.initialPassword() ? "24 giờ" : "1 giờ";
        sender.send(new EmailMessage(event.email(), subject,
                "Chào " + event.name() + ",\n\n"
                        + "Bấm vào liên kết dưới đây để đặt mật khẩu (hiệu lực " + validity + "):\n" + link + "\n\n"
                        + "Nếu bạn không yêu cầu, hãy bỏ qua email này; mật khẩu hiện tại vẫn giữ nguyên."));
    }

    private String link(String path, String rawToken) {
        return UriComponentsBuilder.fromUriString(webUrl).path(path).queryParam("token", rawToken).toUriString();
    }
}
