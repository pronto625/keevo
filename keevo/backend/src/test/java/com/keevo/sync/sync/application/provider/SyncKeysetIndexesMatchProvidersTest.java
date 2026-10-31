package com.keevo.sync.sync.application.provider;

import com.keevo.shared.infrastructure.persistence.TenantSchemaProvisioner;
import com.keevo.shared.infrastructure.persistence.TenantSchemaProvisioner.SyncKeysetIndex;
import com.keevo.shared.application.port.sync.DeltaEntityProvider;
import jakarta.persistence.EntityManager;
import jakarta.persistence.Query;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import org.springframework.context.annotation.ClassPathScanningCandidateComponentProvider;
import org.springframework.core.type.filter.AssignableTypeFilter;

import java.lang.reflect.Constructor;
import java.lang.reflect.Field;
import java.lang.reflect.Modifier;
import java.time.Instant;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.function.Function;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Collectors;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.atLeastOnce;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Story 11.5 — guards the coupling between the keyset pull indexes
 * ({@link TenantSchemaProvisioner#SYNC_KEYSET_INDEXES}) and the delta providers, discovered by
 * classpath scanning (a new provider cannot slip through): every table read through
 * {@link DeltaPageQuery} has a provider {@code CURSOR} constant and exactly one index led by it,
 * and the provider really filters and orders by that {@code CURSOR}.
 */
@DisplayName("Keyset indexes match the delta providers' cursors (Story 11.5)")
class SyncKeysetIndexesMatchProvidersTest {

    /** A delta provider discovered on the classpath, with the SQL it sends and its CURSOR constant. */
    private record Discovered(Class<?> type, String sql, String cursor) {}

    private static final Pattern DELTA_TABLE = Pattern.compile(" AS sync_cursor FROM (\\w+) WHERE ");

    /**
     * Every {@link DeltaEntityProvider} implementation under {@code com.keevo.sync}, instantiated
     * with a mocked {@link EntityManager}; keyed by the table it reads through DeltaPageQuery.
     */
    private static Map<String, Discovered> providersUsingDeltaPageQuery() throws Exception {
        var scanner = new ClassPathScanningCandidateComponentProvider(false);
        scanner.addIncludeFilter(new AssignableTypeFilter(DeltaEntityProvider.class));
        Map<String, Discovered> byTable = new HashMap<>();
        int found = 0;
        for (var bd : scanner.findCandidateComponents("com.keevo.sync")) {
            Class<?> type = Class.forName(bd.getBeanClassName());
            if (type.isInterface() || Modifier.isAbstract(type.getModifiers())) continue;
            // production providers are Spring beans; test stubs (e.g. DeltaEntityProviderTest) are not
            if (!type.isAnnotationPresent(org.springframework.stereotype.Component.class)) continue;
            found++;
            String sql = firstSql(type);
            Matcher m = DELTA_TABLE.matcher(sql);
            if (!m.find()) continue; // provider not built on DeltaPageQuery
            Field cursor = type.getDeclaredField("CURSOR"); // NoSuchFieldException = missing constant
            assertThat(Modifier.isStatic(cursor.getModifiers())).as("%s.CURSOR is static", type.getSimpleName()).isTrue();
            cursor.setAccessible(true);
            Discovered previous = byTable.put(m.group(1), new Discovered(type, sql, (String) cursor.get(null)));
            assertThat(previous).as("two providers read %s", m.group(1)).isNull();
        }
        assertThat(found).as("delta providers found by classpath scanning").isGreaterThanOrEqualTo(15);
        return byTable;
    }

    /** First native SQL a provider sends for a page with no position. */
    private static String firstSql(Class<?> type) throws Exception {
        EntityManager em = mock(EntityManager.class);
        Query query = mock(Query.class);
        when(em.createNativeQuery(anyString())).thenReturn(query);
        when(query.setParameter(anyString(), any())).thenReturn(query);
        when(query.setMaxResults(anyInt())).thenReturn(query);
        when(query.getResultList()).thenReturn(List.of());
        Constructor<?> ctor = type.getDeclaredConstructor(EntityManager.class);
        ctor.setAccessible(true);
        ((DeltaEntityProvider) ctor.newInstance(em)).queryDeltaPage(Instant.parse("2026-10-01T00:00:00Z"), null, 50);
        ArgumentCaptor<String> sql = ArgumentCaptor.forClass(String.class);
        verify(em, atLeastOnce()).createNativeQuery(sql.capture());
        return sql.getAllValues().get(0);
    }

    @Test
    @DisplayName("every DeltaPageQuery provider: CURSOR constant, one index on its table led by CURSOR, SQL ordered by CURSOR")
    void everyProviderCursorLeadsItsIndex() throws Exception {
        Map<String, Discovered> providers = providersUsingDeltaPageQuery();
        // Story 15.5b — a module table's index (e.g. invoices) belongs to the module's own delta provider, checked
        // by the module's tests (InvoiceDeltaProviderTest); the providers of com.keevo.sync read core tables only.
        List<SyncKeysetIndex> core = TenantSchemaProvisioner.SYNC_KEYSET_INDEXES.stream()
                .filter(i -> !i.moduleTable()).toList();
        assertThat(TenantSchemaProvisioner.SYNC_KEYSET_INDEXES.stream().map(SyncKeysetIndex::table).distinct())
                .as("one index per table").hasSameSizeAs(TenantSchemaProvisioner.SYNC_KEYSET_INDEXES);
        Map<String, SyncKeysetIndex> byTable = core.stream()
                .collect(Collectors.toMap(SyncKeysetIndex::table, Function.identity()));
        assertThat(byTable.keySet()).containsExactlyInAnyOrderElementsOf(providers.keySet());

        providers.forEach((table, p) -> {
            SyncKeysetIndex index = byTable.get(table);
            assertThat(index.cursor()).as("index cursor of %s (%s)", table, p.type().getSimpleName())
                    .isEqualTo(p.cursor());
            String lead = p.cursor().matches("[a-z_]+") ? p.cursor() : "(" + p.cursor() + ")";
            assertThat(index.createSql()).as("DDL of %s", table)
                    .endsWith(" ON " + table + " (" + lead + ", (CAST(id AS text)))");
            assertThat(p.sql()).as("delta SQL of %s", table)
                    .contains(" FROM " + table + " WHERE " + p.cursor() + " >= :since")
                    .endsWith(" ORDER BY " + p.cursor() + ", CAST(id AS text)");
        });
    }
}
