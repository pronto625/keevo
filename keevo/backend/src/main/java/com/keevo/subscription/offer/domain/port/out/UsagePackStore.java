package com.keevo.subscription.offer.domain.port.out;

import com.keevo.subscription.offer.domain.model.UsagePack;

import java.util.List;
import java.util.Optional;

/** Story 21.3 — persistence of the usage packs ({@code public.usage_packs}, primary key {@code code}). */
public interface UsagePackStore {

    /** Every pack (inactive included), in display order. One query. */
    List<UsagePack> all();

    Optional<UsagePack> find(String code);

    /** The pack locked {@code FOR UPDATE} (serialises the writes of one pack), empty when absent. */
    Optional<UsagePack> lock(String code);

    /** Inserts or replaces a pack. */
    void upsert(UsagePack pack);
}
