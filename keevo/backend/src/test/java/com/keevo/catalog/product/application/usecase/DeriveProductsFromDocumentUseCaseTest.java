package com.keevo.catalog.product.application.usecase;

import com.keevo.catalog.api.ProductLookupPort;
import com.keevo.catalog.api.ProductLookupPort.DocumentLine;
import com.keevo.catalog.product.domain.port.out.ProductDerivationRepository;
import com.keevo.catalog.product.domain.port.out.ProductDerivationRepository.Candidate;
import com.keevo.catalog.product.domain.port.out.ProductDerivationRepository.NewProduct;
import com.keevo.catalog.product.domain.port.out.ProductDerivationRepository.Target;
import com.keevo.shared.application.port.TransactionLockPort;
import com.keevo.shared.capability.entitlement.PlanLimitCheck;
import com.keevo.shared.capability.entitlement.PlanView;
import com.keevo.shared.capability.entitlement.TenantPlanQuery;
import com.keevo.shared.event.catalog.product.ProductCreatedEvent;
import com.keevo.subscription.api.ProductCountPort;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.context.ApplicationEventPublisher;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.Collection;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyCollection;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

/** Story 20.6 — catalogue products of a document's lines: key, name, merge hop, dedupe, prices, plan limit. */
@DisplayName("DeriveProductsFromDocumentUseCase")
class DeriveProductsFromDocumentUseCaseTest {

    private static final String TENANT = "kv_abcdef";
    private static final Instant NOW = Instant.parse("2026-11-07T10:00:00Z");
    private static final UUID ACTOR = UUID.randomUUID();

    private final ProductDerivationRepository repo = mock(ProductDerivationRepository.class);
    private final TransactionLockPort lock = mock(TransactionLockPort.class);
    private final TenantPlanQuery plans = mock(TenantPlanQuery.class);
    private final PlanLimitCheck limits = mock(PlanLimitCheck.class);
    private final ProductCountPort count = mock(ProductCountPort.class);
    private final ApplicationEventPublisher events = mock(ApplicationEventPublisher.class);
    private DeriveProductsFromDocumentUseCase useCase;

    @BeforeEach
    void setUp() {
        useCase = new DeriveProductsFromDocumentUseCase(repo, lock, plans, limits, count, events,
                Clock.fixed(NOW, ZoneOffset.UTC));
        when(plans.findCurrentPlan(TENANT)).thenReturn(Optional.of(mock(PlanView.class)));
        when(limits.remainingProducts(any(), any())).thenReturn(Integer.MAX_VALUE);
        when(repo.findTargets(anyCollection())).thenReturn(Map.of());
        when(repo.existingSkus(anyCollection())).thenReturn(List.of());
        when(repo.insertAll(anyList(), eq(NOW))).thenAnswer(i -> ((List<NewProduct>) i.getArgument(0)).stream()
                .map(NewProduct::id).toList());
    }

    private static Candidate byKey(String key, UUID id, int price, boolean manual) {
        return new Candidate(true, key, id, false, price, manual, null, NOW.minusSeconds(60));
    }

    @Test
    @DisplayName("unknown lines → created untracked at the line price with key / barcode; one SELECT for all lines, "
            + "repeated once under the catalogue lock")
    void createsMissing() {
        when(repo.findCandidates(anyCollection(), anyCollection())).thenReturn(List.of());

        Map<String, UUID> ids = useCase.execute(List.of(new DocumentLine("Riz 25kg", null, 15_000),
                new DocumentLine("Huile", "6001234567890", 2_500)), ACTOR, TENANT);

        assertThat(ids).containsOnlyKeys("nm:riz 25kg", "bc:6001234567890");
        verify(lock).lock("product-limit:" + TENANT);
        // first pass without the lock, second pass (missing products only) under it — never per line
        verify(repo, times(2)).findCandidates(anyCollection(), anyCollection());
        verify(repo, never()).findTargets(anyCollection()); // no merged candidate → no merge-target query
        ArgumentCaptor<List<NewProduct>> created = ArgumentCaptor.forClass(List.class);
        verify(repo).insertAll(created.capture(), eq(NOW));
        assertThat(created.getValue()).extracting(NewProduct::name, NewProduct::price, NewProduct::barcode,
                NewProduct::productKey).containsExactly(
                org.assertj.core.groups.Tuple.tuple("Riz 25kg", 15_000, null, "nm:riz 25kg"),
                org.assertj.core.groups.Tuple.tuple("Huile", 2_500, "6001234567890", "bc:6001234567890"));
        assertThat(created.getValue()).allSatisfy(p -> assertThat(p.sku()).startsWith("KEV-").hasSize(10));
        verify(events, times(2)).publishEvent(any(ProductCreatedEvent.class));
        verify(repo).updatePrices(Map.of(), NOW);
    }

    @Test
    @DisplayName("found by key → linked; automatic price updated to the line price, a manual price kept")
    void linksAndUpdatesPrices() {
        UUID auto = UUID.randomUUID();
        UUID manual = UUID.randomUUID();
        when(repo.findCandidates(anyCollection(), anyCollection())).thenReturn(List.of(
                byKey("nm:riz", auto, 10_000, false), byKey("nm:sucre", manual, 900, true)));

        Map<String, UUID> ids = useCase.execute(List.of(new DocumentLine("Riz", null, 12_000),
                new DocumentLine("Sucre", "REF-1", 1_000)), ACTOR, TENANT);

        assertThat(ids).containsEntry("nm:riz", auto).containsEntry("nm:sucre", manual);
        verify(repo).updatePrices(Map.of(auto, 12_000), NOW);
        verify(repo, never()).insertAll(anyList(), any());
        verifyNoInteractions(events);
        // everything found on the first pass: no catalogue lock, one SELECT, no merge-target query
        verifyNoInteractions(lock);
        verify(repo, times(1)).findCandidates(anyCollection(), anyCollection());
        verify(repo, never()).findTargets(anyCollection());
    }

    @Test
    @DisplayName("merged product → its merge target; found by name (case-insensitive) when the key differs")
    void mergeHopAndName() {
        UUID merged = UUID.randomUUID();
        UUID target = UUID.randomUUID();
        UUID byName = UUID.randomUUID();
        when(repo.findCandidates(anyCollection(), anyCollection())).thenReturn(List.of(
                new Candidate(true, "nm:riz", merged, true, 1, false, target, NOW),
                new Candidate(false, "Huile", byName, false, 2_500, false, null, NOW)));
        when(repo.findTargets(List.of(target))).thenReturn(Map.of(target, new Target(target, false, 9_000, true)));

        Map<String, UUID> ids = useCase.execute(List.of(new DocumentLine("riz", null, 10_000),
                new DocumentLine("Huile", "6001234567890", 2_500)), ACTOR, TENANT);

        assertThat(ids).containsEntry("nm:riz", target).containsEntry("bc:6001234567890", byName);
        verify(repo).updatePrices(Map.of(), NOW); // target manual, by-name price equal
    }

    @Test
    @DisplayName("identical keys → one product at the last line's price; two keys with one name → one product")
    void dedupe() {
        when(repo.findCandidates(anyCollection(), anyCollection())).thenReturn(List.of());

        Map<String, UUID> ids = useCase.execute(List.of(new DocumentLine("Riz", null, 10_000),
                new DocumentLine("RIZ ", null, 11_000), new DocumentLine("Huile", "6001234567890", 2_500),
                new DocumentLine("Huile", null, 2_400), new DocumentLine("  ", null, 5)), ACTOR, TENANT);

        ArgumentCaptor<List<NewProduct>> created = ArgumentCaptor.forClass(List.class);
        verify(repo).insertAll(created.capture(), eq(NOW));
        assertThat(created.getValue()).extracting(NewProduct::name, NewProduct::price).containsExactly(
                org.assertj.core.groups.Tuple.tuple("Riz", 11_000),
                org.assertj.core.groups.Tuple.tuple("Huile", 2_500));
        assertThat(ids).hasSize(3);
        assertThat(ids.get("bc:6001234567890")).isEqualTo(ids.get("nm:huile"));
        ArgumentCaptor<Collection<String>> keys = ArgumentCaptor.forClass(Collection.class);
        verify(repo, times(2)).findCandidates(keys.capture(), anyCollection()); // first pass, then under the lock
        assertThat(keys.getAllValues().get(0)).containsExactly("nm:riz", "bc:6001234567890", "nm:huile");
    }

    @Test
    @DisplayName("plan limit: only the remaining products are created, the others stay unlinked; no plan → none")
    void planLimit() {
        UUID known = UUID.randomUUID();
        when(repo.findCandidates(anyCollection(), anyCollection())).thenReturn(List.of(
                byKey("nm:riz", known, 10_000, false)));
        when(limits.remainingProducts(any(), any())).thenReturn(1);

        Map<String, UUID> ids = useCase.execute(List.of(new DocumentLine("Riz", null, 10_000),
                new DocumentLine("Huile", null, 2_500), new DocumentLine("Sel", null, 200)), ACTOR, TENANT);

        assertThat(ids).containsKeys("nm:riz", "nm:huile").doesNotContainKey("nm:sel");

        when(plans.findCurrentPlan(TENANT)).thenReturn(Optional.empty());
        assertThat(useCase.execute(List.of(new DocumentLine("Poivre", null, 100)), ACTOR, TENANT)).isEmpty();
        when(plans.findCurrentPlan(TENANT)).thenThrow(new IllegalStateException("quota indisponible"));
        assertThat(useCase.execute(List.of(new DocumentLine("Poivre", null, 100)), ACTOR, TENANT)).isEmpty();
    }

    @Test
    @DisplayName("a row skipped by the unique rule stays unlinked; nothing usable → no lock, no query")
    void skippedAndEmpty() {
        when(repo.findCandidates(anyCollection(), anyCollection())).thenReturn(List.of());
        when(repo.insertAll(anyList(), eq(NOW))).thenReturn(List.of());
        assertThat(useCase.execute(List.of(new DocumentLine("Riz", null, 1)), ACTOR, TENANT)).isEmpty();
        verifyNoInteractions(events);

        DeriveProductsFromDocumentUseCase fresh = new DeriveProductsFromDocumentUseCase(mock(
                ProductDerivationRepository.class), mock(TransactionLockPort.class), plans, limits, count, events,
                Clock.fixed(NOW, ZoneOffset.UTC));
        assertThat(fresh.execute(List.of(new DocumentLine(" ", "6001234567890", 1)), ACTOR, TENANT)).isEmpty();
        assertThat(ProductLookupPort.keyOf("x".repeat(250), null)).isEqualTo("nm:" + "x".repeat(200));
    }

    @Test
    @DisplayName("archived automatic-price product matched → linked, its price kept (only live products follow sales)")
    void archivedKeepsPrice() {
        UUID archived = UUID.randomUUID();
        when(repo.findCandidates(anyCollection(), anyCollection())).thenReturn(List.of(
                new Candidate(true, "nm:riz", archived, true, 10_000, false, null, NOW.minusSeconds(60))));

        Map<String, UUID> ids = useCase.execute(List.of(new DocumentLine("Riz", null, 12_000)), ACTOR, TENANT);

        assertThat(ids).containsEntry("nm:riz", archived);
        verify(repo).updatePrices(Map.of(), NOW);
        verify(repo, never()).insertAll(anyList(), any());
    }

    @Test
    @DisplayName("a line priced 0 (gift) is linked but never resets the automatic catalogue price")
    void freeLineKeepsPrice() {
        UUID auto = UUID.randomUUID();
        when(repo.findCandidates(anyCollection(), anyCollection())).thenReturn(List.of(
                byKey("nm:riz", auto, 10_000, false)));

        Map<String, UUID> ids = useCase.execute(List.of(new DocumentLine("Riz", null, 0)), ACTOR, TENANT);

        assertThat(ids).containsEntry("nm:riz", auto);
        verify(repo).updatePrices(Map.of(), NOW);
    }

    @Test
    @DisplayName("a product created concurrently before the lock is found on the second pass: linked, not created")
    void foundUnderTheLock() {
        UUID concurrent = UUID.randomUUID();
        when(repo.findCandidates(anyCollection(), anyCollection())).thenReturn(List.of())
                .thenReturn(List.of(byKey("nm:riz", concurrent, 10_000, false)));

        Map<String, UUID> ids = useCase.execute(List.of(new DocumentLine("Riz", null, 10_000)), ACTOR, TENANT);

        assertThat(ids).containsEntry("nm:riz", concurrent);
        verify(lock).lock("product-limit:" + TENANT);
        verify(repo, never()).insertAll(anyList(), any());
        verifyNoInteractions(events);
    }
}
