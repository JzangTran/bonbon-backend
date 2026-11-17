package com.bonbon.backend.common.mail;

import java.util.List;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;

/**
 * Delivery through Brevo's transactional email API (POST /v3/smtp/email with an API key), for when the account has an API key but
 * no SMTP key. Chosen with {@code bonbon.mail.provider=brevo}; the SMTP sender is used otherwise.
 */
@Component
@ConditionalOnProperty(name = "bonbon.mail.provider", havingValue = "brevo")
class BrevoEmailSender implements EmailSender {

    /** "bonbon <no-reply@bonbon.com>" or a bare address. */
    private static final Pattern NAMED = Pattern.compile("^\\s*(.*?)\\s*<\\s*([^>]+?)\\s*>\\s*$");

    private final RestClient client;
    private final String fromName;
    private final String fromEmail;

    BrevoEmailSender(@Value("${bonbon.mail.brevo.api-key:}") String apiKey,
            @Value("${bonbon.mail.brevo.url:https://api.brevo.com}") String url,
            @Value("${bonbon.mail.from:bonbon <no-reply@bonbon.local>}") String from) {
        if (apiKey.isBlank()) {
            throw new IllegalStateException("bonbon.mail.provider=brevo needs BREVO_API_KEY");
        }
        this.client = RestClient.builder().baseUrl(url)
                .defaultHeader("api-key", apiKey)
                .defaultHeader("accept", MediaType.APPLICATION_JSON_VALUE)
                .build();
        Matcher m = NAMED.matcher(from);
        this.fromName = m.matches() && !m.group(1).isBlank() ? m.group(1) : "bonbon";
        this.fromEmail = m.matches() ? m.group(2) : from.strip();
    }

    @Override
    public void send(EmailMessage message) {
        client.post().uri("/v3/smtp/email")
                .contentType(MediaType.APPLICATION_JSON)
                .body(Map.of(
                        "sender", Map.of("name", fromName, "email", fromEmail),
                        "to", List.of(Map.of("email", message.to())),
                        // a line break in a subject must not start another header
                        "subject", message.subject().replaceAll("[\r\n]+", " "),
                        "textContent", message.textBody()))
                .retrieve()
                .toBodilessEntity();
    }
}
