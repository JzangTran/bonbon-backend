package com.bonbon.backend.common.vietqr;

import java.io.IOException;
import java.io.InputStream;
import java.io.UncheckedIOException;
import java.text.Normalizer;
import java.util.List;
import java.util.Locale;
import java.util.Optional;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import tools.jackson.databind.json.JsonMapper;

/**
 * VietQR (NAPAS) transfer codes: the EMVCo payload a banking app reads to fill in the bank, the account, the amount and the
 * message of a transfer. Building the code moves no money and calls nobody; the customer's own bank sends it, straight to the
 * account in the code. Banks are the ones that take NAPAS 24/7 transfers, from {@code vietqr/banks.json} (api.vietqr.io).
 */
public final class VietQr {

    /** A bank of the NAPAS list. */
    @JsonIgnoreProperties(ignoreUnknown = true)
    public record Bank(String bin, String code, String shortName, String name) {
    }

    private static final List<Bank> BANKS = load();
    private static final String NAPAS_GUID = "A000000727";
    private static final String TO_ACCOUNT = "QRIBFTTA";

    private VietQr() {
    }

    public static List<Bank> banks() {
        return BANKS;
    }

    /** The bank a shop typed: its short name ("Vietcombank"), code ("VCB") or full name, ignoring case, accents and spaces. */
    public static Optional<Bank> bank(String typed) {
        if (typed == null || typed.isBlank()) {
            return Optional.empty();
        }
        String key = fold(typed);
        return BANKS.stream().filter(b -> key.equals(fold(b.shortName())) || key.equals(fold(b.code())) || key.equals(fold(b.name())))
                .findFirst();
    }

    /**
     * The payload of a transfer to {@code account} at the bank {@code bin} for {@code amount} đồng with {@code message} (letters and
     * digits only: banks drop the rest), ending with its CRC.
     */
    public static String payload(String bin, String account, long amount, String message) {
        String beneficiary = field("00", bin) + field("01", account);
        String merchant = field("00", NAPAS_GUID) + field("01", beneficiary) + field("02", TO_ACCOUNT);
        String body = field("00", "01") + field("01", "12") + field("38", merchant) + field("53", "704")
                + field("54", Long.toString(amount)) + field("58", "VN") + field("62", field("08", message)) + "6304";
        return body + crc(body);
    }

    private static String field(String id, String value) {
        return id + String.format("%02d", value.length()) + value;
    }

    /** CRC-16/CCITT-FALSE (polynomial 0x1021, start 0xFFFF), as four upper-case hex digits. */
    static String crc(String text) {
        int crc = 0xFFFF;
        for (byte b : text.getBytes(java.nio.charset.StandardCharsets.UTF_8)) {
            crc ^= (b & 0xFF) << 8;
            for (int i = 0; i < 8; i++) {
                crc = (crc & 0x8000) != 0 ? (crc << 1) ^ 0x1021 : crc << 1;
            }
            crc &= 0xFFFF;
        }
        return String.format("%04X", crc);
    }

    private static String fold(String text) {
        return Normalizer.normalize(text, Normalizer.Form.NFD).replaceAll("\\p{M}", "").replace('đ', 'd').replace('Đ', 'D')
                .replaceAll("[^A-Za-z0-9]", "").toLowerCase(Locale.ROOT);
    }

    private static List<Bank> load() {
        try (InputStream in = VietQr.class.getResourceAsStream("/vietqr/banks.json")) {
            return List.of(JsonMapper.builder().build().readValue(in, Bank[].class));
        } catch (IOException e) {
            throw new UncheckedIOException("vietqr/banks.json is missing or broken", e);
        }
    }
}
