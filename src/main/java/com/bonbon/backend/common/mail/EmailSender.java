package com.bonbon.backend.common.mail;

/** Sends one transactional email. Implementations must be called after the database commit. */
public interface EmailSender {

    void send(EmailMessage message);

    record EmailMessage(String to, String subject, String textBody) {
    }
}
