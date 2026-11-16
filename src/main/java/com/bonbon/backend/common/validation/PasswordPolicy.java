package com.bonbon.backend.common.validation;

import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

import com.bonbon.backend.common.exception.BusinessException;
import org.springframework.http.HttpStatus;

/**
 * What a new password must not be, following NIST SP 800-63B: no composition rules (such as one capital and one symbol), but at least 8
 * characters and not something an attacker tries first — a well-known password, the user's own email or name, one repeated character
 * or a keyboard run. The same rules are shown live in the apps, so a refusal here is rare.
 */
public final class PasswordPolicy {

    public static final int MIN = 8;
    public static final int MAX = 100;

    /** The most common passwords in public breach lists, plus the Vietnamese ones that show up in local leaks. */
    static final Set<String> COMMON = Set.of(
            "12345678", "123456789", "1234567890", "123123123", "11111111", "00000000", "88888888", "66666666", "99999999",
            "87654321", "12341234", "11223344", "123456789a", "1234567a", "password", "password1", "password123", "passw0rd",
            "iloveyou", "qwertyui", "qwerty123", "qwertyuiop", "1qaz2wsx", "abc12345", "abcd1234", "a1234567", "aa123456",
            "asdfghjk", "zxcvbnm1", "admin123", "welcome1", "letmein1", "sunshine", "princess", "football", "baseball",
            "matkhau", "matkhau1", "matkhau123", "anhyeuem", "emyeuanh", "yeuemmaimai", "vietnam1", "vietnam123", "hanoi123",
            "saigon123", "bonbon123", "bonbon2026", "bonbon2027");

    private PasswordPolicy() {
    }

    /** Why {@code password} is not acceptable for an account with {@code email} and {@code name} (either may be null). */
    public static Optional<String> problem(String password, String email, String name) {
        if (password == null || password.length() < MIN) {
            return Optional.of("Mật khẩu cần ít nhất " + MIN + " ký tự.");
        }
        if (password.length() > MAX) {
            return Optional.of("Mật khẩu tối đa " + MAX + " ký tự.");
        }
        String lower = password.toLowerCase(Locale.ROOT);
        if (COMMON.contains(lower)) {
            return Optional.of("Mật khẩu này quá phổ biến và dễ bị đoán. Hãy chọn mật khẩu khác.");
        }
        if (password.chars().distinct().count() <= 2) {
            return Optional.of("Mật khẩu không được chỉ lặp lại một vài ký tự.");
        }
        if (isRun(lower)) {
            return Optional.of("Mật khẩu không được là dãy ký tự liên tiếp như 12345678 hay abcdefgh.");
        }
        if (email != null) {
            String local = email.trim().toLowerCase(Locale.ROOT).split("@")[0];
            if (local.length() >= 4 && lower.contains(local)) {
                return Optional.of("Mật khẩu không được chứa email của bạn.");
            }
        }
        if (name != null) {
            String compact = name.toLowerCase(Locale.ROOT).replaceAll("\\s+", "");
            if (compact.length() >= 4 && lower.replaceAll("\\s+", "").contains(compact)) {
                return Optional.of("Mật khẩu không được chứa họ tên của bạn.");
            }
        }
        return Optional.empty();
    }

    /** Refuses a weak password with 400 PASSWORD_TOO_WEAK, naming {@code field} so the form shows the message under it. */
    public static void require(String password, String email, String name, String field) {
        problem(password, email, name).ifPresent(message -> {
            throw new BusinessException(HttpStatus.BAD_REQUEST, "PASSWORD_TOO_WEAK", message)
                    .withProperty("errors", List.of(Map.of("field", field, "message", message)));
        });
    }

    /** Every character is the previous one plus or minus one (abcdefgh, 98765432). */
    private static boolean isRun(String s) {
        int step = s.charAt(1) - s.charAt(0);
        if (Math.abs(step) != 1) {
            return false;
        }
        for (int i = 2; i < s.length(); i++) {
            if (s.charAt(i) - s.charAt(i - 1) != step) {
                return false;
            }
        }
        return true;
    }
}
