package com.bonbon.backend.account;

import java.util.List;
import java.util.UUID;

/**
 * Something that stops a person from deleting their account (flows/account/delete-account.md "Eligibility"). Every module that
 * keeps a commitment to the person implements this (orders in progress, open cases, open tickets, a shop that is not closed), so
 * the account module needs to know none of them.
 */
public interface DeletionBlocker {

    /** {@code code} names the kind of blocker; {@code link} says where the person resolves it (ORDERS, ORDER_CASES, SUPPORT_TICKETS, CLOSE_SHOP). */
    record Blocker(String code, String message, String link, long count) {
    }

    /** Empty when this module has nothing in the way. */
    List<Blocker> blockersOf(UUID userId);
}
