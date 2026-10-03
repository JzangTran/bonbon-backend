package com.bonbon.backend.merchant.service;

import java.text.Normalizer;
import java.util.Locale;
import java.util.Objects;

/**
 * Compares a bank account holder name with the name on an identity document the way Vietnamese banks print
 * it: without diacritics, case or extra spaces, so Nguyễn Văn  An equals NGUYEN VAN AN.
 */
final class PersonNames {

    private PersonNames() {
    }

    static String normalize(String name) {
        if (name == null) {
            return null;
        }
        String noMarks = Normalizer.normalize(name.replace('đ', 'd').replace('Đ', 'D'), Normalizer.Form.NFD)
                .replaceAll("\\p{M}+", "");
        return noMarks.toUpperCase(Locale.ROOT).replaceAll("[^A-Z ]", " ").trim().replaceAll("\\s+", " ");
    }

    /** Null when either name is missing. */
    static Boolean sameName(String a, String b) {
        if (a == null || b == null || a.isBlank() || b.isBlank()) {
            return null;
        }
        return Objects.equals(normalize(a), normalize(b));
    }
}
