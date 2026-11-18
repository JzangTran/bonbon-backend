package com.bonbon.backend.marketplace.merchant;

import java.util.UUID;

/** The shop's receiving account was changed by the shop account; the owner is told, so a change they did not make is seen at once. */
public record PayoutAccountChanged(UUID vendorId, String bankName, String accountLast4) {
}
