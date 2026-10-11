package com.bonbon.backend.support.service;

/** What an administrator sees before asking to reveal: enough to recognise a record, not enough to contact the person. */
final class Masking {

    private Masking() {
    }

    /** 0901234678 becomes 09******78. */
    static String phone(String phone) {
        if (phone == null || phone.isBlank()) {
            return null;
        }
        if (phone.length() <= 4) {
            return "*".repeat(phone.length());
        }
        return phone.substring(0, 2) + "*".repeat(phone.length() - 4) + phone.substring(phone.length() - 2);
    }

    /** jane@gmail.com becomes j***@gmail.com. */
    static String email(String email) {
        if (email == null || email.isBlank()) {
            return null;
        }
        int at = email.indexOf('@');
        if (at <= 0) {
            return "***";
        }
        return email.charAt(0) + "***" + email.substring(at);
    }

    /** Keeps the last three comma-separated parts (ward, district, province); the street and house number go. */
    static String address(String address) {
        if (address == null || address.isBlank()) {
            return null;
        }
        String[] parts = address.split(",");
        if (parts.length <= 1) {
            return "***";
        }
        int keep = Math.min(3, parts.length - 1);
        StringBuilder out = new StringBuilder("***");
        for (int i = parts.length - keep; i < parts.length; i++) {
            out.append(',').append(parts[i]);
        }
        return out.toString();
    }
}
