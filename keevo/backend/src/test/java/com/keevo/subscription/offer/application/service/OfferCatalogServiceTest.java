package com.keevo.subscription.offer.application.service;

import com.keevo.identity.api.PlanKind;
import com.keevo.identity.api.PlanType;
import com.keevo.shared.capability.entitlement.PlanCatalog;
import com.keevo.shared.capability.entitlement.PlanDefinition;
import com.keevo.shared.capability.entitlement.PlanSettings;
import com.keevo.shared.domain.exception.DomainException;
import com.keevo.shared.domain.exception.ErrorCode;
import com.keevo.shared.module.application.port.PlanCatalogStore;
import com.keevo.subscription.api.OfferAdministration.CycleInput;
import com.keevo.subscription.api.OfferAdministration.DiscountInput;
import com.keevo.subscription.offer.domain.model.PlanPrice;
import com.keevo.subscription.api.OfferAdministration.PlanInput;
import com.keevo.subscription.api.OfferAdministration.PlanUpdate;
import com.keevo.subscription.api.OfferAdministration.SettingsInput;
import com.keevo.subscription.offer.domain.model.BillingCycle;
import com.keevo.subscription.offer.domain.model.Discount;
import com.keevo.subscription.offer.domain.model.OfferEntity;
import com.keevo.subscription.offer.domain.model.TenantPlanRef;
import com.keevo.subscription.offer.domain.port.out.OfferStore;
import org.assertj.core.api.ThrowableAssert.ThrowingCallable;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;

import java.math.BigDecimal;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.time.temporal.ChronoUnit;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.contains;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
@DisplayName("OfferCatalogService — guards and validation (Story 21.10)")
class OfferCatalogServiceTest {

    private static final Instant NOW = Instant.parse("2026-11-09T10:00:00Z");
    private static final UUID ACTOR = UUID.randomUUID();
    private static final Instant STORED_AT = Instant.parse("2026-11-01T08:00:00Z");

    private static Optional<PlanPrice> price(long amount) {
        return Optional.of(new PlanPrice("PRO", "MONTHLY", amount, STORED_AT));
    }

    @Mock PlanCatalogStore catalogStore;
    @Mock OfferStore store;

    OfferCatalogService service;

    @BeforeEach
    void setUp() {
        service = new OfferCatalogService(PlanCatalog.SEED, catalogStore, store, Clock.fixed(NOW, ZoneOffset.UTC));
        when(store.cycles()).thenReturn(List.of(new BillingCycle("MONTHLY", "Mensuel", 1, 10, null),
                new BillingCycle("ANNUAL", "Annuel", 12, 30, null)));
        when(store.cycle("MONTHLY")).thenReturn(Optional.of(new BillingCycle("MONTHLY", "Mensuel", 1, 10, null)));
        when(store.prices()).thenReturn(List.of());
    }

    private static void assertError(ThrowingCallable call, ErrorCode code, String message, String field) {
        assertThatThrownBy(call).isInstanceOfSatisfying(DomainException.class, e -> {
            assertThat(e.getErrorCode()).isEqualTo(code);
            assertThat(e.getUserMessage()).isEqualTo(message);
            if (field != null) {
                assertThat(e.getDetails()).containsEntry(field, message);
            }
        });
    }

    // ── plans ──

    @Test
    @DisplayName("kind change of a plan companies are on → 409, nothing saved")
    void kindChange_usedPlan() {
        when(store.tenantsOnPlan("PRO")).thenReturn(2L);

        assertError(() -> service.updatePlan("PRO", new PlanUpdate(null, null, "FREE", null), ACTOR),
                ErrorCode.INVALID_STATE, OfferText.PLAN_IN_USE, null);
        verify(catalogStore, never()).save(any());
        verify(store, never()).journal(any(), anyString(), anyString(), any());
    }

    @Test
    @DisplayName("kind change of a plan the settings use → 409")
    void kindChange_settingsPlan() {
        assertError(() -> service.updatePlan("TEAM", new PlanUpdate(null, null, "FREE", null), ACTOR),
                ErrorCode.INVALID_STATE, OfferText.PLAN_IN_SETTINGS, null);
    }

    @Test
    @DisplayName("kind change of an unused plan, label and visibility: saved and journaled in French")
    void update_unusedPlan() {
        when(store.tenantsOnPlan("BUSINESS")).thenReturn(0L);

        service.updatePlan("business", new PlanUpdate("Business+", null, "TRIAL", false), ACTOR);

        ArgumentCaptor<PlanDefinition> saved = ArgumentCaptor.forClass(PlanDefinition.class);
        verify(catalogStore).save(saved.capture());
        assertThat(saved.getValue().kind()).isEqualTo(PlanKind.TRIAL);
        assertThat(saved.getValue().visible()).isFalse();
        assertThat(saved.getValue().label()).isEqualTo("Business+");
        ArgumentCaptor<String> summary = ArgumentCaptor.forClass(String.class);
        verify(store).journal(eq(OfferEntity.PLAN), eq("BUSINESS"), summary.capture(), eq(ACTOR));
        assertThat(summary.getValue()).contains("« Business » → « Business+ »").contains("payant → essai")
                .contains("masqué").doesNotContain("BUSINESS");
    }

    @Test
    @DisplayName("archive the free plan of the settings → 409; archive PRO → archived, journaled")
    void archive() {
        assertError(() -> service.archivePlan("FREE", ACTOR), ErrorCode.INVALID_STATE, OfferText.PLAN_IN_SETTINGS,
                null);

        service.archivePlan("PRO", ACTOR);
        ArgumentCaptor<PlanDefinition> saved = ArgumentCaptor.forClass(PlanDefinition.class);
        verify(catalogStore).save(saved.capture());
        assertThat(saved.getValue().archivedAt()).isEqualTo(NOW);
        verify(store).journal(eq(OfferEntity.PLAN), eq("PRO"), contains("archivé"), eq(ACTOR));
    }

    @Test
    @DisplayName("create: duplicate code → 409, reserved or malformed code → 422, unknown kind → 422")
    void create_validation() {
        assertError(() -> service.createPlan(new PlanInput("pro", "Pro bis", null, "PAID", null, null), ACTOR),
                ErrorCode.INVALID_STATE, OfferText.DUPLICATE_CODE, null);
        assertThatThrownBy(() -> service.createPlan(new PlanInput("PAID", "X", null, "PAID", null, null), ACTOR))
                .isInstanceOfSatisfying(DomainException.class,
                        e -> assertThat(e.getDetails()).containsKey("code"));
        assertThatThrownBy(() -> service.createPlan(new PlanInput("ELITE", "Elite", null, "GOLD", null, null), ACTOR))
                .isInstanceOfSatisfying(DomainException.class,
                        e -> assertThat(e.getDetails()).containsKey("kind"));
        verify(catalogStore, never()).save(any());
    }

    @Test
    @DisplayName("create: plan inserted after the last one (never an upsert), cache dropped, profile copied from the "
            + "free plan by default, journaled")
    void create_ok() {
        when(store.insertPlan(any())).thenReturn(true);

        service.createPlan(new PlanInput("elite", "Elite", "Pour les grands", "paid", null, null), ACTOR);

        ArgumentCaptor<PlanDefinition> saved = ArgumentCaptor.forClass(PlanDefinition.class);
        verify(store).insertPlan(saved.capture());
        verify(catalogStore, never()).save(any());
        verify(catalogStore).invalidate();
        assertThat(saved.getValue().code()).isEqualTo(PlanType.of("ELITE"));
        assertThat(saved.getValue().sortOrder()).isEqualTo(101); // after PREMIUM (91)
        assertThat(saved.getValue().visible()).isTrue();
        verify(store).copyProfile("FREE", "ELITE");
        verify(store).journal(eq(OfferEntity.PLAN), eq("ELITE"), contains("« Gratuit »"), eq(ACTOR));
    }

    @Test
    @DisplayName("reorder: not every plan exactly once → 422 details.codes")
    void reorder_validation() {
        assertThatThrownBy(() -> service.reorderPlans(List.of("FREE", "PRO"), ACTOR))
                .isInstanceOfSatisfying(DomainException.class,
                        e -> assertThat(e.getDetails()).containsKey("codes"));
        verify(store, never()).reorderPlans(any());
    }

    @Test
    @DisplayName("create through a stale cache: the code exists in the database → 409, nothing copied or journaled")
    void create_staleCacheDuplicate() {
        when(store.insertPlan(any())).thenReturn(false);

        assertError(() -> service.createPlan(new PlanInput("ELITE", "Elite", null, "PAID", null, null), ACTOR),
                ErrorCode.INVALID_STATE, OfferText.DUPLICATE_CODE, null);
        verify(store, never()).copyProfile(anyString(), anyString());
        verify(store, never()).journal(any(), anyString(), anyString(), any());
    }

    @Test
    @DisplayName("kind change of an unused paid plan that still has prices → 409, nothing saved")
    void kindChange_planWithPrices() {
        when(store.tenantsOnPlan("BUSINESS")).thenReturn(0L);
        when(store.prices("BUSINESS")).thenReturn(List.of(new PlanPrice("BUSINESS", "MONTHLY", 8_500, STORED_AT)));

        assertError(() -> service.updatePlan("BUSINESS", new PlanUpdate(null, null, "FREE", null), ACTOR),
                ErrorCode.INVALID_STATE, OfferText.PLAN_HAS_PRICES, null);
        verify(catalogStore, never()).save(any());
    }

    // ── cycles ──

    @Test
    @DisplayName("cycles: create (duplicate → 409), months change of a cycle in use → 409, label change saved, "
            + "archive / unarchive (twice → 409)")
    void cycles() {
        when(store.insertCycle(any())).thenReturn(false);
        assertError(() -> service.createCycle(new CycleInput("MONTHLY", "Mensuel", 1, null), ACTOR),
                ErrorCode.INVALID_STATE, OfferText.DUPLICATE_CODE, null);
        when(store.insertCycle(any())).thenReturn(true);
        assertThat(service.createCycle(new CycleInput("semester", "Semestriel", 6, null), ACTOR).sortOrder())
                .isEqualTo(40);
        assertThatThrownBy(() -> service.createCycle(new CycleInput("YEAR2", "Deux ans", 48, null), ACTOR))
                .isInstanceOfSatisfying(DomainException.class, e -> assertThat(e.getDetails()).containsKey("months"));

        when(store.cycleInUse("MONTHLY")).thenReturn(true);
        assertError(() -> service.updateCycle("MONTHLY", new CycleInput(null, null, 2, null), ACTOR),
                ErrorCode.INVALID_STATE, OfferText.CYCLE_IN_USE, null);
        service.updateCycle("MONTHLY", new CycleInput(null, "Mois", null, null), ACTOR);
        verify(store).updateCycle(new BillingCycle("MONTHLY", "Mois", 1, 10, null));

        assertThat(service.archiveCycle("MONTHLY", ACTOR).archived()).isTrue();
        when(store.cycle("OLD")).thenReturn(Optional.of(new BillingCycle("OLD", "Ancien", 1, 50, NOW)));
        assertError(() -> service.archiveCycle("OLD", ACTOR), ErrorCode.INVALID_STATE,
                "Cette durée est déjà archivée", null);
        assertThat(service.unarchiveCycle("OLD", ACTOR).archived()).isFalse();
        assertError(() -> service.unarchiveCycle("MONTHLY", ACTOR), ErrorCode.INVALID_STATE,
                "Cette durée n'est pas archivée", null);
    }

    // ── prices ──

    @Test
    @DisplayName("delete a price that does not exist → 404; existing → deleted, history with new amount null")
    void deletePrice() {
        assertError(() -> service.deletePrice("PRO", "MONTHLY", ACTOR), ErrorCode.NOT_FOUND,
                OfferText.PRICE_NOT_FOUND, null);

        when(store.lockPrice("PRO", "MONTHLY")).thenReturn(price(3_500));
        service.deletePrice("PRO", "MONTHLY", ACTOR);
        verify(store).deletePrice("PRO", "MONTHLY");
        verify(store).recordPriceChange("PRO", "MONTHLY", 3_500L, null, ACTOR);
    }

    @Test
    @DisplayName("price on a non-paid plan → 422 « Seuls les plans payants ont un prix »")
    void price_nonPaid() {
        assertError(() -> service.setPrice("FREE", "MONTHLY", 1_000L, ACTOR), ErrorCode.VALIDATION_ERROR,
                OfferText.PRICE_ONLY_PAID, "planCode");
    }

    @Test
    @DisplayName("price out of range → 422 details.amountXaf; unknown plan or cycle → 404")
    void price_validation() {
        for (Long amount : new Long[] {null, 0L, -5L, 100_000_001L}) {
            assertError(() -> service.setPrice("PRO", "MONTHLY", amount, ACTOR), ErrorCode.VALIDATION_ERROR,
                    OfferText.AMOUNT_RANGE, "amountXaf");
        }
        assertError(() -> service.setPrice("NOPE", "MONTHLY", 1_000L, ACTOR), ErrorCode.NOT_FOUND,
                OfferText.PLAN_NOT_FOUND, null);
        assertError(() -> service.setPrice("PRO", "WEEKLY", 1_000L, ACTOR), ErrorCode.NOT_FOUND,
                OfferText.CYCLE_NOT_FOUND, null);
        verify(store, never()).upsertPrice(anyString(), anyString(), anyLong(), any());
    }

    @Test
    @DisplayName("price change: locked read, upsert, history 3 500 → 4 000 with the actor, French journal line")
    void price_change() {
        when(store.lockPrice("PRO", "MONTHLY")).thenReturn(price(3_500));

        service.setPrice("pro", "monthly", 4_000L, ACTOR);

        org.mockito.InOrder order = org.mockito.Mockito.inOrder(store);
        order.verify(store).lockPlan("PRO"); // a first creation is serialised too
        order.verify(store).lockPrice("PRO", "MONTHLY");
        verify(store).upsertPrice("PRO", "MONTHLY", 4_000, ACTOR);
        verify(store).recordPriceChange("PRO", "MONTHLY", 3_500L, 4_000L, ACTOR);
        verify(store).journal(OfferEntity.PRICE, "PRO:MONTHLY", "Prix « Pro » · Mensuel : 3 500 FCFA → 4 000 FCFA",
                ACTOR);
    }

    @Test
    @DisplayName("same price again: nothing written")
    void price_unchanged() {
        when(store.lockPrice("PRO", "MONTHLY")).thenReturn(price(3_500));
        assertThat(service.setPrice("PRO", "MONTHLY", 3_500L, ACTOR).updatedAt()).isEqualTo(STORED_AT);
        verify(store, never()).upsertPrice(anyString(), anyString(), anyLong(), any());
    }

    // ── settings ──

    @Test
    @DisplayName("trial off: settings saved with trialEnabled false, journaled; a non-free free plan → 422")
    void settings() {
        service.updateSettings(new SettingsInput(null, null, null, false, null), ACTOR);

        ArgumentCaptor<PlanSettings> saved = ArgumentCaptor.forClass(PlanSettings.class);
        verify(catalogStore).saveSettings(saved.capture());
        assertThat(saved.getValue().trialEnabled()).isFalse();
        assertThat(saved.getValue().freePlan()).isEqualTo(PlanType.FREE);
        verify(store).journal(eq(OfferEntity.SETTINGS), anyString(), contains("essai désactivé"), eq(ACTOR));

        assertThatThrownBy(() -> service.updateSettings(new SettingsInput("PRO", null, null, null, null), ACTOR))
                .isInstanceOfSatisfying(DomainException.class,
                        e -> assertThat(e.getDetails()).containsKey("freePlanCode"));
    }

    // ── discounts ──

    private static DiscountInput promotion(BigDecimal percent, Long amount, Instant endsAt) {
        return new DiscountInput("PROMOTION", "Rentrée", percent, amount, List.of("PRO"), List.of(), null, endsAt,
                null, null);
    }

    @Test
    @DisplayName("discount validation: one value only, promotion needs an end in the future, plans exist")
    void discount_validation() {
        Instant later = NOW.plus(10, ChronoUnit.DAYS);
        assertThatThrownBy(() -> service.createDiscount(promotion(BigDecimal.TEN, 500L, later), ACTOR))
                .isInstanceOfSatisfying(DomainException.class, e -> assertThat(e.getDetails()).containsKey("percentOff"));
        assertThatThrownBy(() -> service.createDiscount(promotion(new BigDecimal("101"), null, later), ACTOR))
                .isInstanceOfSatisfying(DomainException.class, e -> assertThat(e.getDetails()).containsKey("percentOff"));
        assertThatThrownBy(() -> service.createDiscount(promotion(BigDecimal.TEN, null, null), ACTOR))
                .isInstanceOfSatisfying(DomainException.class, e -> assertThat(e.getDetails()).containsKey("endsAt"));
        assertThatThrownBy(() -> service.createDiscount(promotion(BigDecimal.TEN, null, NOW), ACTOR))
                .isInstanceOfSatisfying(DomainException.class, e -> assertThat(e.getDetails()).containsKey("endsAt"));
        assertThatThrownBy(() -> service.createDiscount(new DiscountInput("PROMOTION", "X", BigDecimal.TEN, null,
                List.of("NOPE"), List.of(), null, later, null, null), ACTOR))
                .isInstanceOfSatisfying(DomainException.class, e -> assertThat(e.getDetails()).containsKey("planCodes"));
        assertThatThrownBy(() -> service.createDiscount(new DiscountInput("TENANT", "X", BigDecimal.TEN, null,
                null, null, null, null, UUID.randomUUID(), null), ACTOR))
                .isInstanceOfSatisfying(DomainException.class, e -> assertThat(e.getDetails()).containsKey("tenantId"));
        verify(store, never()).insertDiscount(any(), any());
    }

    @Test
    @DisplayName("promo code taken (case-insensitive) → 409 « Ce code existe déjà »; free code stored upper case")
    void discount_promoCode() {
        when(store.promoCodeTaken("BREF10", null)).thenReturn(true);
        DiscountInput input = new DiscountInput("PROMO_CODE", "Bref", BigDecimal.TEN, null, null, null, null, null,
                null, "bref10");
        assertError(() -> service.createDiscount(input, ACTOR), ErrorCode.INVALID_STATE, OfferText.DUPLICATE_CODE,
                null);

        when(store.promoCodeTaken("BREF10", null)).thenReturn(false);
        when(store.insertDiscount(any(), eq(ACTOR))).thenReturn(UUID.randomUUID());
        service.createDiscount(input, ACTOR);
        ArgumentCaptor<Discount> saved = ArgumentCaptor.forClass(Discount.class);
        verify(store).insertDiscount(saved.capture(), eq(ACTOR));
        assertThat(saved.getValue().promoCode()).isEqualTo("BREF10");
        assertThat(saved.getValue().tenantId()).isNull();
    }

    @Test
    @DisplayName("21.1 — use cap: promo codes only, 1..1 000 000, stored; the update keeps the stored use counter")
    void discount_useCap() {
        Instant later = NOW.plus(10, ChronoUnit.DAYS);
        assertThatThrownBy(() -> service.createDiscount(new DiscountInput("PROMOTION", "X", BigDecimal.TEN, null,
                null, null, null, later, null, null, 5), ACTOR))
                .isInstanceOfSatisfying(DomainException.class,
                        e -> assertThat(e.getDetails()).containsKey("maxRedemptions"));
        assertThatThrownBy(() -> service.createDiscount(new DiscountInput("PROMO_CODE", "X", BigDecimal.TEN, null,
                null, null, null, null, null, "CAP0", 0), ACTOR))
                .isInstanceOfSatisfying(DomainException.class,
                        e -> assertThat(e.getDetails()).containsKey("maxRedemptions"));
        assertThatThrownBy(() -> service.createDiscount(new DiscountInput("PROMO_CODE", "X", BigDecimal.TEN, null,
                null, null, null, null, null, "CAPBIG", 1_000_001), ACTOR))
                .isInstanceOfSatisfying(DomainException.class,
                        e -> assertThat(e.getDetails()).containsKey("maxRedemptions"));

        when(store.insertDiscount(any(), eq(ACTOR))).thenReturn(UUID.randomUUID());
        var view = service.createDiscount(new DiscountInput("PROMO_CODE", "Lancement", BigDecimal.TEN, null, null,
                null, null, null, null, "LANCE", 3), ACTOR);
        ArgumentCaptor<Discount> saved = ArgumentCaptor.forClass(Discount.class);
        verify(store).insertDiscount(saved.capture(), eq(ACTOR));
        assertThat(saved.getValue().maxRedemptions()).isEqualTo(3);
        assertThat(view.maxRedemptions()).isEqualTo(3);
        assertThat(view.redemptionCount()).isZero();
        verify(store).journal(eq(OfferEntity.DISCOUNT), anyString(), contains("3 utilisation(s) au plus"), eq(ACTOR));

        UUID id = UUID.randomUUID();
        when(store.discount(id)).thenReturn(Optional.of(new Discount(id, com.keevo.subscription.offer.domain.model
                .DiscountScope.PROMO_CODE, "Lancement", BigDecimal.TEN, null, null, null, null, null, null, "LANCE",
                null, STORED_AT, 3, 2)));
        var updated = service.updateDiscount(id, new DiscountInput("PROMO_CODE", "Lancement", BigDecimal.TEN, null,
                null, null, null, null, null, "LANCE", 5), ACTOR);
        assertThat(updated.redemptionCount()).isEqualTo(2);
        assertThat(updated.maxRedemptions()).isEqualTo(5);
        assertThat(updated.live()).isTrue();
    }

    @Test
    @DisplayName("21.1 — a promo code whose cap is reached is exhausted, hence not live")
    void discount_exhaustedNotLive() {
        Discount used = new Discount(UUID.randomUUID(), com.keevo.subscription.offer.domain.model.DiscountScope
                .PROMO_CODE, "Lancement", BigDecimal.TEN, null, null, null, null, null, null, "LANCE", null,
                STORED_AT, 2, 2);
        assertThat(used.exhausted()).isTrue();
        assertThat(used.liveAt(NOW)).isFalse();
        assertThat(used.withArchivedAt(null).redemptionCount()).isEqualTo(2);
    }

    @Test
    @DisplayName("update of an archived discount → 409; archive twice → 409")
    void discount_archived() {
        UUID id = UUID.randomUUID();
        Discount archived = new Discount(id, com.keevo.subscription.offer.domain.model.DiscountScope.PROMOTION,
                "Rentrée", BigDecimal.TEN, null, java.util.Set.of(), java.util.Set.of(), null,
                NOW.plus(5, ChronoUnit.DAYS), null, null, NOW, NOW);
        when(store.discount(id)).thenReturn(Optional.of(archived));

        assertError(() -> service.updateDiscount(id, promotion(BigDecimal.ONE, null, NOW.plus(9, ChronoUnit.DAYS)),
                ACTOR), ErrorCode.INVALID_STATE, "Cette remise est archivée", null);
        assertError(() -> service.archiveDiscount(id, ACTOR), ErrorCode.INVALID_STATE,
                "Cette remise est déjà archivée", null);
        verify(store, never()).updateDiscount(any(), any());
    }

    @Test
    @DisplayName("tenant discount: journal names the company, never its id")
    void discount_tenant() {
        UUID tenant = UUID.randomUUID();
        UUID id = UUID.randomUUID();
        when(store.tenant(tenant)).thenReturn(Optional.of(new TenantPlanRef(tenant, "FREE")));
        when(store.insertDiscount(any(), eq(ACTOR))).thenReturn(id);
        when(store.tenantNames(any())).thenReturn(java.util.Map.of(tenant, "Boutique Awa"));

        service.createDiscount(new DiscountInput("TENANT", "Fidélité", null, 1_000L, null, null, null, null, tenant,
                null), ACTOR);

        ArgumentCaptor<String> summary = ArgumentCaptor.forClass(String.class);
        verify(store).journal(eq(OfferEntity.DISCOUNT), eq(id.toString()), summary.capture(), eq(ACTOR));
        assertThat(summary.getValue()).contains("« Boutique Awa »").contains("1 000 FCFA")
                .doesNotContain(tenant.toString());
    }
}
