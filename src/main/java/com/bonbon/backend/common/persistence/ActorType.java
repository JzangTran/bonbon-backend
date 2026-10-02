package com.bonbon.backend.common.persistence;

/**
 * Who performed a change, stored with {@code acted_by_id} on audited records.
 */
public enum ActorType {
    CUSTOMER,
    SHOP_ACCOUNT,
    MAIN_ACCOUNT,
    MEMBER,
    ADMIN,
    SYSTEM
}
