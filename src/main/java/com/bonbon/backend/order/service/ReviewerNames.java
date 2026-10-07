package com.bonbon.backend.order.service;

/** The short name shown next to a review: the given name and the initial of the family name. */
final class ReviewerNames {

    private ReviewerNames() {
    }

    /** "Trần Văn An" becomes "An T."; a single word stays as it is. */
    static String shorten(String fullName) {
        if (fullName == null || fullName.isBlank()) {
            return "Khách";
        }
        String[] parts = fullName.strip().split("\\s+");
        if (parts.length == 1) {
            return parts[0];
        }
        return parts[parts.length - 1] + " " + parts[0].substring(0, 1).toUpperCase() + ".";
    }
}
