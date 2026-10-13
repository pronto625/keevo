package com.keevo.admin.platform.domain.port.out;

import com.keevo.admin.platform.domain.model.HealthSample;

import java.time.Instant;
import java.util.List;
import java.util.Optional;

/** HealthSampleRepository — {@code public.platform_health_samples} (Story 9-5). */
public interface HealthSampleRepository {

    /** Inserts the sample; a sample already stored at the same instant is kept (no error). */
    void insert(HealthSample sample);

    /** @return number of samples deleted */
    int purgeBefore(Instant cutoff);

    Optional<Instant> firstSampleAt();

    /** Samples with {@code db_ok} taken at or after {@code since}. */
    long countOkSince(Instant since);

    Optional<HealthSample> latest();

    /** Newest first, taken at or after {@code since}, at most {@code limit}. */
    List<HealthSample> recent(Instant since, int limit);
}
