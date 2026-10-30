package com.keevo.identity.company.adapter.out.config;

import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Configuration;

/** Registers {@link CompanyLogoProperties} (Story 14.2). */
@Configuration
@EnableConfigurationProperties(CompanyLogoProperties.class)
public class CompanyLogoConfig {
}
