package com.keevo.migration.application.step;

import com.keevo.catalog.api.CatalogImportPort;
import com.keevo.catalog.api.CatalogImportPort.ImportedCategories;
import com.keevo.identity.api.SectorTemplatePort;
import com.keevo.migration.application.ImportRun;
import com.keevo.migration.application.MigrationUnitOfWork;
import com.keevo.migration.domain.model.BrefAccount;
import com.keevo.migration.domain.model.BrefShop;
import com.keevo.migration.domain.model.BrefSnapshot;
import com.keevo.migration.domain.model.SourceKind;
import com.keevo.migration.domain.port.out.MigrationRecordStore;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.HashMap;
import java.util.List;
import java.util.UUID;
import java.util.function.Supplier;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/** Story 24.3 — the categories step: sector template or none, once per tenant. */
@DisplayName("24.3 — CategoriesStep")
class CategoriesStepTest {

    private static final Instant T = Instant.parse("2026-03-01T08:00:00Z");
    private final UUID owner = UUID.randomUUID();
    private final SectorTemplatePort templates = mock(SectorTemplatePort.class);
    private final CatalogImportPort catalog = mock(CatalogImportPort.class);
    private final MigrationRecordStore records = mock(MigrationRecordStore.class);
    private final MigrationUnitOfWork uow = mock(MigrationUnitOfWork.class);
    private final CategoriesStep step = new CategoriesStep(templates, catalog, records, uow);

    @SuppressWarnings("unchecked")
    private ImportRun run(String category) {
        when(uow.tenant(anyString(), any())).thenAnswer(i -> ((Supplier<Object>) i.getArgument(1)).get());
        BrefAccount a = new BrefAccount(owner, "owner", null, false, "+237600000001", true, null, null, "A", "B", null,
                true, false, "email", null, T, T, null);
        BrefShop shop = new BrefShop(UUID.randomUUID(), owner, "Boutique", "A", category, null, null, null, null,
                null, "CM", true, T, T, null, null);
        ImportRun run = new ImportRun(new BrefSnapshot(a, List.of(shop), List.of(), SourceKind.DATABASE),
                UUID.randomUUID(), UUID.randomUUID(), new HashMap<>());
        run.tenant(UUID.randomUUID(), "kv_test243");
        return run;
    }

    @Test
    @DisplayName("no sector (blank shop category): only « Produits importés »; a re-run writes nothing")
    void noSectorThenOnce() {
        UUID imported = UUID.randomUUID();
        when(catalog.seedImportCategories(eq("kv_test243"), anyList(), eq(CategoriesStep.IMPORTED_CATEGORY)))
                .thenReturn(new ImportedCategories(imported, 1));
        ImportRun run = run(" ");
        step.run(run);
        verify(templates, never()).templateCategories(anyString());
        verify(catalog).seedImportCategories("kv_test243", List.of(), CategoriesStep.IMPORTED_CATEGORY);
        assertThat(run.counts().get(ImportRun.CATEGORIES_CREATED)).isEqualTo(1);
        assertThat(run.target("user", owner, "categories")).isEqualTo(imported);

        step.run(run); // the record is known: nothing written
        verify(catalog, times(1)).seedImportCategories(anyString(), anyList(), anyString());
        assertThat(run.counts().get(ImportRun.ALREADY_IMPORTED)).isEqualTo(1);
    }

    @Test
    @DisplayName("a sector: its template names are seeded with « Produits importés »")
    void sector() {
        when(templates.templateCategories("FOOD_GROCERY")).thenReturn(List.of("Riz", "Huile"));
        when(catalog.seedImportCategories(anyString(), anyList(), anyString()))
                .thenReturn(new ImportedCategories(UUID.randomUUID(), 3));
        step.run(run("food"));
        verify(catalog).seedImportCategories("kv_test243", List.of("Riz", "Huile"), "Produits importés");
    }
}
