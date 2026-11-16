package com.bonbon.backend.identity.sellerteam;

import java.util.Collection;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

/** The shops a seller team can link and work on, implemented by the shop module (identity knows no shop tables). */
public interface TeamShops {

    /** The shop a shop account owns, whatever its status. */
    Optional<TeamShop> ofOwner(UUID ownerUserId);

    Map<UUID, TeamShop> byIds(Collection<UUID> vendorIds);

    /** {@code status} is the shop's own status (APPROVED, SUSPENDED…); only an APPROVED shop can be linked. */
    record TeamShop(UUID vendorId, UUID ownerUserId, String name, String status) {
    }
}
