package com.keevo.admin.campaign.domain.port.out;

import com.keevo.admin.campaign.domain.model.AudienceMember;
import com.keevo.admin.campaign.domain.model.Segment;

import java.util.List;

/**
 * Story 21.6 — the owners a platform campaign reaches: owners (active membership, active account) of the live
 * companies of the segment (never suspended / deleted ones, never the platform's own account), one per person (his
 * oldest company), at most {@code limit}. Plans / explicit companies are filtered in the public schema; store country
 * / activity through the companies' stores (chunked {@code UNION ALL}, never one query per company).
 */
public interface PlatformAudiencePort {

    List<AudienceMember> audience(Segment segment, int limit);

    /** The same audience counted in SQL, up to {@code cap}: owners, with a usable phone (8–15 digits), with an email. */
    Counts count(Segment segment, int cap);

    record Counts(int owners, int withPhone, int withEmail) {
    }
}
