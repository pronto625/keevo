package com.keevo.catalog.product.adapter.out.config;

import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Configuration;

/** Registers {@link ProductPhotoProperties}. */
@Configuration
@EnableConfigurationProperties(ProductPhotoProperties.class)
public class ProductPhotoConfig {
}
