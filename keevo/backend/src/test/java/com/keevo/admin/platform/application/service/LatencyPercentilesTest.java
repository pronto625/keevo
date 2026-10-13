package com.keevo.admin.platform.application.service;

import com.keevo.admin.platform.application.service.LatencyPercentiles.Weighted;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.within;

@DisplayName("LatencyPercentiles (Story 9-5)")
class LatencyPercentilesTest {

    @Test
    void emptyOrWeightless() {
        assertThat(LatencyPercentiles.merge(List.of(), 0.99)).isNull();
        assertThat(LatencyPercentiles.merge(List.of(new Weighted(0, 10.0, 20.0, 30.0, 40.0)), 0.5)).isNull();
        assertThat(LatencyPercentiles.merge(List.of(new Weighted(5, null, null, null, null)), 0.5)).isNull();
    }

    @Test
    void singleTimerKeepsItsPercentiles() {
        List<Weighted> one = List.of(new Weighted(10, 12.0, 80.0, 300.0, 900.0));
        assertThat(LatencyPercentiles.merge(one, 0.5)).isCloseTo(12.0, within(1e-6));
        assertThat(LatencyPercentiles.merge(one, 0.95)).isCloseTo(80.0, within(1e-6));
        assertThat(LatencyPercentiles.merge(one, 0.99)).isCloseTo(300.0, within(1e-6));
    }

    @Test
    void identicalTimersMergeToTheSame() {
        List<Weighted> two = List.of(new Weighted(10, 12.0, 80.0, 300.0, 900.0),
                new Weighted(30, 12.0, 80.0, 300.0, 900.0));
        assertThat(LatencyPercentiles.merge(two, 0.99)).isCloseTo(300.0, within(1e-6));
    }

    @Test
    void slowTimerWithHalfTheTrafficPullsTheHighPercentiles() {
        List<Weighted> mix = List.of(new Weighted(50, 10.0, 20.0, 30.0, 40.0),
                new Weighted(50, 2000.0, 3000.0, 4000.0, 5000.0));
        assertThat(LatencyPercentiles.merge(mix, 0.99)).isGreaterThan(2000.0);
        assertThat(LatencyPercentiles.merge(mix, 0.5)).isBetween(10.0, 2000.0);
    }

    @Test
    void rareSlowTimerDoesNotDominateTheMedian() {
        List<Weighted> mix = List.of(new Weighted(990, 10.0, 20.0, 30.0, 40.0),
                new Weighted(10, 5000.0, 6000.0, 7000.0, 8000.0));
        assertThat(LatencyPercentiles.merge(mix, 0.5)).isLessThan(20.0);
    }
}
