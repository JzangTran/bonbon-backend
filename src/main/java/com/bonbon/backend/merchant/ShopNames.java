package com.bonbon.backend.merchant;

import java.util.Collection;
import java.util.Map;
import java.util.UUID;

/** Shop names for screens that list shops of other modules (settlement tables). */
public interface ShopNames {

    /** The name of each shop that exists; an unknown id is simply absent. */
    Map<UUID, String> names(Collection<UUID> vendorIds);
}
