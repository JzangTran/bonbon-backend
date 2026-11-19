package com.bonbon.backend.identity.legal;

import java.util.UUID;

/**
 * One module's share of a person's data for the "download my data" export (data-subject-requests.md). Every module that keeps personal
 * data about a person implements this (profile and addresses, orders and reviews, chat, a shop), so the legal module that builds the file
 * depends on none of them.
 */
public interface PersonalDataSource {

    /** The name of this part of the file, e.g. {@code orders}. */
    String section();

    /** Plain maps and lists the JSON writer can serialize; null or empty when the module holds nothing about the person. */
    Object dataOf(UUID userId);
}
