package com.keevo.invoicing.invoice.adapter.out.storage;

import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Configuration;

/** Registers {@link InvoiceSignatureProperties} (Story 20.7). */
@Configuration
@EnableConfigurationProperties(InvoiceSignatureProperties.class)
public class InvoiceSignatureConfig {
}
