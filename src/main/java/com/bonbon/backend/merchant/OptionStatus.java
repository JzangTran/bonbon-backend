package com.bonbon.backend.merchant;

/** An option is offered, switched off for now, or retired (hidden, row kept for order snapshots). */
public enum OptionStatus {
    AVAILABLE,
    SOLD_OUT,
    ARCHIVED
}
