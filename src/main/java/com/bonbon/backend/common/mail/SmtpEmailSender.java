package com.bonbon.backend.common.mail;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.mail.SimpleMailMessage;
import org.springframework.mail.javamail.JavaMailSender;
import org.springframework.stereotype.Component;

/**
 * SMTP delivery. In development the SMTP server is Mailpit (deploy/compose.dev.yaml); in production the
 * provider's SMTP host and credentials come from SPRING_MAIL_* environment variables.
 */
@Component
class SmtpEmailSender implements EmailSender {

    private final JavaMailSender mailSender;
    private final String from;

    SmtpEmailSender(JavaMailSender mailSender, @Value("${bonbon.mail.from:bonbon <no-reply@bonbon.local>}") String from) {
        this.mailSender = mailSender;
        this.from = from;
    }

    @Override
    public void send(EmailMessage message) {
        SimpleMailMessage mail = new SimpleMailMessage();
        mail.setFrom(from);
        mail.setTo(message.to());
        mail.setSubject(message.subject());
        mail.setText(message.textBody());
        mailSender.send(mail);
    }
}
