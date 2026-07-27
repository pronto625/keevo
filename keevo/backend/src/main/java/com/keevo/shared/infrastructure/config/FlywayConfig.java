package com.keevo.shared.infrastructure.config;

import org.flywaydb.core.Flyway;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.flyway.FlywayMigrationStrategy;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * FlywayConfig — stratégie de migration avec auto-réparation.
 *
 * <p>Avant chaque {@code migrate()}, un {@code repair()} est exécuté pour nettoyer
 * automatiquement les migrations orphelines (fichier .sql supprimé mais déjà appliqué en base),
 * les checksums divergents ou les migrations en échec. Ceci évite tout blocage au démarrage
 * nécessitant une intervention manuelle, que ce soit en dev ou en production.
 *
 * <p>Le {@code repair()} est idempotent : s'il n'y a rien à réparer, c'est un no-op.
 */
@Configuration
public class FlywayConfig {

    private static final Logger log = LoggerFactory.getLogger(FlywayConfig.class);

    @Bean
    public FlywayMigrationStrategy flywayMigrationStrategy() {
        return (Flyway flyway) -> {
            log.info("Flyway: running repair before migrate (auto-clean orphaned/divergent migrations)");
            flyway.repair();
            log.info("Flyway: repair complete — running migrate");
            flyway.migrate();
        };
    }
}
