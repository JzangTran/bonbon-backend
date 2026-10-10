package com.bonbon.backend.authentication;

import java.util.Collection;
import java.util.Map;
import java.util.UUID;

/** Display names of accounts for screens in other modules (the customer's name in a shop's chat list). */
public interface UserNames {

    /** The name of each account that exists and has one; an unknown or unnamed id is simply absent. */
    Map<UUID, String> names(Collection<UUID> userIds);
}
