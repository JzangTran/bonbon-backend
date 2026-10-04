package com.bonbon.backend.merchant;

/** Whether the seller currently offers a dish; a dish with no stock left also counts as sold out. */
public enum MenuItemStatus {
    AVAILABLE,
    SOLD_OUT
}
