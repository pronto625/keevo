package com.keevo.catalog.category.application.listener;

import com.keevo.catalog.category.domain.model.Category;
import com.keevo.catalog.category.domain.port.out.CategoryRepository;
import com.keevo.shared.event.catalog.onboarding.TenantOnboardedEvent;
import com.keevo.shared.infrastructure.persistence.TenantContext;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;
import org.springframework.transaction.event.TransactionPhase;
import org.springframework.transaction.event.TransactionalEventListener;

import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

/**
 * Seeds the onboarding sector template's root categories (Story 12.6a — formerly
 * {@code CategoryCommandPort.seedTemplateCategories}, called by identity's onboarding).
 *
 * <p>Synchronous {@code BEFORE_COMMIT} ({@code fallbackExecution}: published without a transaction it runs
 * immediately, never silently skipped): runs in the onboarding transaction, on the request thread (tenant
 * context bound), so the categories commit or roll back with the onboarding and the app finds them on its
 * first catalogue load right after the onboarding response. A failure here rolls the onboarding back, as
 * before. Not recorded by the publication registry (only after-commit listeners are).
 *
 * <p>Categories are created active and non-custom, without {@code CategoryCreatedEvent} (unchanged).
 * Idempotent: the template is de-duplicated case-insensitively (first spelling kept), then one query finds
 * the names already present as root categories (trimmed, case-insensitive), which are skipped — a replayed
 * event never duplicates the template.
 */
@Component
public class TenantOnboardedCategorySeeder {

    private static final Logger log = LoggerFactory.getLogger(TenantOnboardedCategorySeeder.class);

    private final CategoryRepository categoryRepository;

    public TenantOnboardedCategorySeeder(CategoryRepository categoryRepository) {
        this.categoryRepository = categoryRepository;
    }

    @TransactionalEventListener(phase = TransactionPhase.BEFORE_COMMIT, fallbackExecution = true)
    public void onTenantOnboarded(TenantOnboardedEvent event) {
        TenantContext.requireCurrent(event.tenantId());
        // Names arrive trimmed, non-blank (TenantOnboardedEvent); keep the first of case variants.
        Map<String, String> byLowerName = new LinkedHashMap<>();
        event.templateCategories().forEach(name -> byLowerName.putIfAbsent(name.toLowerCase(Locale.ROOT), name));
        if (byLowerName.isEmpty()) {
            return;
        }
        Set<String> existing = categoryRepository.findExistingRootNamesIgnoreCase(byLowerName.values());
        Instant now = Instant.now();
        // id = null so Hibernate uses persist() (not merge()) and generates the UUIDs.
        List<Category> categories = byLowerName.entrySet().stream()
                .filter(e -> !existing.contains(e.getKey()))
                .map(e -> new Category(null, e.getValue(), null, true, false, now, now))
                .toList();
        int created = categories.isEmpty() ? 0 : categoryRepository.saveAll(categories).size();
        log.info("[ONBOARDING] {} template categorie(s) seeded for tenant {} ({} in template)",
                created, event.tenantId(), event.templateCategories().size());
    }
}
