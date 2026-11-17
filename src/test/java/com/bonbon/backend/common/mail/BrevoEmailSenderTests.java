package com.bonbon.backend.common.mail;

import java.io.IOException;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.atomic.AtomicReference;

import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.web.client.HttpClientErrorException;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/** Brevo's HTTP API: the request it sends (key header, sender split from "name <address>", UTF-8 text), and a refusal surfacing. */
class BrevoEmailSenderTests {

    HttpServer server;
    final AtomicReference<String> path = new AtomicReference<>();
    final AtomicReference<String> key = new AtomicReference<>();
    final AtomicReference<String> body = new AtomicReference<>();
    volatile int status = 201;

    @BeforeEach
    void start() throws IOException {
        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/", exchange -> {
            path.set(exchange.getRequestURI().getPath());
            key.set(exchange.getRequestHeaders().getFirst("api-key"));
            body.set(new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8));
            byte[] out = "{\"messageId\":\"<1@brevo>\"}".getBytes(StandardCharsets.UTF_8);
            exchange.getResponseHeaders().add("Content-Type", "application/json");
            exchange.sendResponseHeaders(status, out.length);
            exchange.getResponseBody().write(out);
            exchange.close();
        });
        server.start();
    }

    @AfterEach
    void stop() {
        server.stop(0);
    }

    @Test
    void sendsTheMessageWithTheKeyAndTheNamedSender() {
        BrevoEmailSender sender = new BrevoEmailSender("xkeysib-test", "http://127.0.0.1:" + server.getAddress().getPort(),
                "bonbon <no-reply@bonbon.pro.vn>");
        sender.send(new EmailSender.EmailMessage("khach@example.com", "Mã xác nhận\r\nBcc: x@y.z", "Chào bạn, mã là 123456."));

        assertThat(path.get()).isEqualTo("/v3/smtp/email");
        assertThat(key.get()).isEqualTo("xkeysib-test");
        JsonNode json = JsonMapper.builder().build().readTree(body.get());
        assertThat(json.at("/sender/name").asString()).isEqualTo("bonbon");
        assertThat(json.at("/sender/email").asString()).isEqualTo("no-reply@bonbon.pro.vn");
        assertThat(json.at("/to/0/email").asString()).isEqualTo("khach@example.com");
        assertThat(json.at("/subject").asString()).isEqualTo("Mã xác nhận Bcc: x@y.z");
        assertThat(json.at("/textContent").asString()).isEqualTo("Chào bạn, mã là 123456.");
    }

    @Test
    void aRefusalIsAnErrorAndAMissingKeyStopsStartup() {
        status = 401;
        BrevoEmailSender sender = new BrevoEmailSender("wrong", "http://127.0.0.1:" + server.getAddress().getPort(), "no-reply@bonbon.pro.vn");
        assertThatThrownBy(() -> sender.send(new EmailSender.EmailMessage("a@b.c", "x", "y"))).isInstanceOf(HttpClientErrorException.class);
        assertThatThrownBy(() -> new BrevoEmailSender("", "http://127.0.0.1", "x@y.z")).isInstanceOf(IllegalStateException.class);
    }
}
