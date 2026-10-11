package com.keevo.shared.infrastructure.config;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import java.time.Clock;

/**
 * ClockConfig — shared {@link Clock} bean (UTC) so time-driven components (revocation cache,
 * password-reset rate limiter, token cleanup) can be tested by injecting a controllable clock
 * (Story 12.12).
 */
@Configuration
public class ClockConfig {

    @Bean
    public Clock clock() {
        return Clock.systemUTC();
    }
}
