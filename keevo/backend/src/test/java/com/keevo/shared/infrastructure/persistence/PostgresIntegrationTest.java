package com.keevo.shared.infrastructure.persistence;

import org.springframework.boot.test.autoconfigure.jdbc.AutoConfigureTestDatabase;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.test.context.ActiveProfiles;
import org.testcontainers.containers.PostgreSQLContainer;

/**
 * PostgresIntegrationTest — base class of every Spring test that needs a real PostgreSQL
 * (Story v1s-15-11).
 *
 * <p>Binds the context's DataSource to {@link SharedPostgresContainer} through
 * {@code @ServiceConnection}: all subclasses hit the same container (one per JVM), and two
 * subclasses with the same slice configuration share one cached Spring context. Flyway migrates
 * the shared {@code test} database the first time a context starts; later contexts find it
 * up to date.
 *
 * <p>Isolation contract: {@code @DataJpaTest} subclasses roll back each test; tests that commit
 * (concurrency, full context) must use unique ids/phones/schemas and never assert global counts.
 */
@AutoConfigureTestDatabase(replace = AutoConfigureTestDatabase.Replace.NONE)
@ActiveProfiles("test")
public abstract class PostgresIntegrationTest {

    @ServiceConnection
    protected static final PostgreSQLContainer<?> POSTGRES = SharedPostgresContainer.POSTGRES;
}
