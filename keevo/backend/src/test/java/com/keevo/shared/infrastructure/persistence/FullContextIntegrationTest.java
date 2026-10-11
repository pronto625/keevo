package com.keevo.shared.infrastructure.persistence;

import org.springframework.boot.test.context.SpringBootTest;

/**
 * FullContextIntegrationTest — base class for tests that boot the complete application context
 * on the shared Testcontainers PostgreSQL (Story v1s-15-11).
 *
 * <p>Every subclass with no further configuration shares one cached context: public Flyway
 * migrations, the tenant-schema lifecycle runner, ShedLock, schedulers and all adapters are the
 * production beans. Tenant-scoped tests provision their own {@code kv_xxxxxx} schema with unique
 * ids and must clear {@link TenantContext} in every thread they use.
 *
 * <p>{@code keevo.whatsapp.provider=noop} is forced as a test property (it beats the OS environment
 * and the developer's {@code .env}, which {@code application.yml} imports): a full-context test
 * must never send a real WhatsApp message (schedulers, account deletion, OTP).
 */
@SpringBootTest(properties = "keevo.whatsapp.provider=noop")
public abstract class FullContextIntegrationTest extends PostgresIntegrationTest {
}
