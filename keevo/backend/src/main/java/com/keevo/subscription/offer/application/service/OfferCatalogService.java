package com.keevo.subscription.offer.application.service;

import com.keevo.identity.api.PlanKind;
import com.keevo.identity.api.PlanType;
import com.keevo.shared.capability.entitlement.PlanCatalog;
import com.keevo.shared.capability.entitlement.PlanDefinition;
import com.keevo.shared.capability.entitlement.PlanSettings;
import com.keevo.shared.module.application.port.PlanCatalogStore;
import com.keevo.subscription.api.OfferAdministration;
import com.keevo.subscription.offer.domain.model.BillingCycle;
import com.keevo.subscription.offer.domain.model.Discount;
import com.keevo.subscription.offer.domain.model.DiscountScope;
import com.keevo.subscription.offer.domain.model.OfferChange;
import com.keevo.subscription.offer.domain.model.OfferEntity;
import com.keevo.subscription.offer.domain.model.PlanPrice;
import com.keevo.subscription.offer.domain.model.PriceChange;
import com.keevo.subscription.offer.domain.port.out.OfferStore;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;

import java.math.BigDecimal;
import java.time.Clock;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;
import java.util.regex.Pattern;
import java.util.stream.Collectors;

import static com.keevo.subscription.offer.application.service.OfferText.*;

/**
 * Story 21.10 — super-admin CRUD of the commercial offer ({@link OfferAdministration}): plans (through
 * {@link PlanCatalogStore}, which invalidates the catalogue cache), billing cycles, prices with their history, trial
 * policy and discounts. Every write validates (French messages), applies its guards and appends a French line to the
 * change journal in the same transaction.
 *
 * <p>Guards (19.7a review items): the kind of a plan cannot change while a non-deleted company is on it or the
 * settings point at it (a resync of every company's modules and expiry on a kind flip is riskier than a new plan); a
 * plan used by the settings cannot be archived; archiving never touches the companies already on a plan; only paid
 * plans have prices; codes are unique and never deleted.
 *
 * <p>Pattern: application service over two repositories behind ports ({@link OfferStore}, {@link PlanCatalogStore});
 * plans read from the cached {@link PlanCatalog}. Cost: catalogue view = cycles + prices + one tenant-count GROUP BY;
 * price write = one locked read + one upsert + two inserts; reorder = one UPDATE.
 */
@Service
public class OfferCatalogService implements OfferAdministration {

    static final int DEFAULT_LIMIT = 50;
    static final int MAX_LIMIT = 200;
    static final int LABEL_MAX = 80;
    static final int DESCRIPTION_MAX = 1000;
    static final int DISCOUNT_LABEL_MAX = 120;
    static final int MAX_MONTHS = 36;
    /** Discounts listed at most (archived ones accumulate). */
    static final int MAX_DISCOUNTS = 500;
    private static final Set<String> RESERVED_CODES = Set.of("ALL", "PAID");
    private static final Pattern PROMO_CODE = Pattern.compile("^[A-Z0-9_-]{3,40}$");
    private static final BigDecimal HUNDRED = BigDecimal.valueOf(100);

    private final PlanCatalog catalog;
    private final PlanCatalogStore catalogStore;
    private final OfferStore store;
    private final Clock clock;

    public OfferCatalogService(PlanCatalog catalog, PlanCatalogStore catalogStore, OfferStore store, Clock clock) {
        this.catalog = catalog;
        this.catalogStore = catalogStore;
        this.store = store;
        this.clock = clock;
    }

    // ── catalogue ─────────────────────────────────────────────────────────────

    @Override
    @Transactional(readOnly = true)
    public CatalogueView catalogue() {
        List<BillingCycle> cycles = store.cycles();
        Map<String, BillingCycle> cyclesByCode = byCode(cycles);
        Map<String, List<PlanPrice>> pricesByPlan = store.prices().stream()
                .collect(Collectors.groupingBy(PlanPrice::planCode));
        Map<String, Long> counts = store.tenantCountsByPlan();
        List<PlanItem> plans = catalog.all().stream()
                .map(p -> planItem(p, counts.getOrDefault(p.code().code(), 0L),
                        pricesByPlan.getOrDefault(p.code().code(), List.of()), cyclesByCode))
                .toList();
        return new CatalogueView(plans, cycles.stream().map(OfferCatalogService::cycleItem).toList(),
                settingsView(catalog.settings()));
    }

    @Override
    @Transactional(readOnly = true)
    public List<CycleItem> cycles() {
        return store.cycles().stream().map(OfferCatalogService::cycleItem).toList();
    }

    @Override
    @Transactional(readOnly = true)
    public java.util.Optional<CycleItem> cycle(String code) {
        String c = code(code);
        return c == null ? java.util.Optional.empty() : store.cycle(c).map(OfferCatalogService::cycleItem);
    }

    // ── plans ─────────────────────────────────────────────────────────────────

    @Override
    @Transactional
    public PlanItem createPlan(PlanInput in, UUID actorId) {
        if (in == null) {
            throw invalid("code", "Indiquez le code du plan");
        }
        String code = newCode(in.code());
        String label = label(in.label(), "label", LABEL_MAX);
        String description = description(in.description());
        PlanKind kind = kind(in.kind());
        if (catalog.find(code).isPresent()) {
            throw conflict(DUPLICATE_CODE);
        }
        String sourceCode = in.copyProfileFrom() == null ? catalog.freePlan().code() : code(in.copyProfileFrom());
        PlanDefinition source = catalog.find(sourceCode)
                .orElseThrow(() -> invalid("copyProfileFrom", PLAN_NOT_FOUND));
        int sortOrder = catalog.all().stream().mapToInt(PlanDefinition::sortOrder).max().orElse(0) + 10;
        PlanDefinition plan = new PlanDefinition(PlanType.of(code), label, description, sortOrder,
                in.visible() == null || in.visible(), kind, null);
        if (!store.insertPlan(plan)) { // the cache may be stale: the database decides, never an overwrite
            throw conflict(DUPLICATE_CODE);
        }
        invalidateCatalogueNowAndAfterCommit();
        store.copyProfile(source.code().code(), code);
        store.journal(OfferEntity.PLAN, code, "Plan " + quoted(label) + " créé (" + kindLabel(kind).toLowerCase()
                + (plan.visible() ? "" : ", masqué") + ", limites et modules repris de " + quoted(source.label()) + ")",
                actorId);
        return planItem(plan, 0, List.of(), Map.of());
    }

    @Override
    @Transactional
    public PlanItem updatePlan(String code, PlanUpdate in, UUID actorId) {
        PlanDefinition current = requirePlan(code);
        if (in == null) {
            throw invalid("label", "Indiquez les changements du plan");
        }
        String label = in.label() == null ? current.label() : label(in.label(), "label", LABEL_MAX);
        String description = in.description() == null ? current.description() : description(in.description());
        PlanKind kind = in.kind() == null ? current.kind() : kind(in.kind());
        boolean visible = in.visible() == null ? current.visible() : in.visible();
        String key = current.code().code();
        if (kind != current.kind()) {
            if (usedBySettings(key)) {
                throw conflict(PLAN_IN_SETTINGS);
            }
            if (store.tenantsOnPlan(key) > 0) {
                throw conflict(PLAN_IN_USE);
            }
            if (current.kind() == PlanKind.PAID && !store.prices(key).isEmpty()) {
                throw conflict(PLAN_HAS_PRICES);
            }
        }
        List<String> changes = new ArrayList<>();
        if (!label.equals(current.label())) {
            changes.add("libellé " + quoted(current.label()) + " → " + quoted(label));
        }
        if (!Objects.equals(description, current.description())) {
            changes.add("description modifiée");
        }
        if (kind != current.kind()) {
            changes.add("nature " + kindLabel(current.kind()).toLowerCase() + " → " + kindLabel(kind).toLowerCase());
        }
        if (visible != current.visible()) {
            changes.add(visible ? "proposé aux clients" : "masqué aux clients");
        }
        PlanDefinition plan = new PlanDefinition(current.code(), label, description, current.sortOrder(), visible,
                kind, current.archivedAt());
        if (!changes.isEmpty()) {
            catalogStore.save(plan);
            store.journal(OfferEntity.PLAN, key, "Plan " + quoted(label) + " modifié : " + String.join(", ", changes),
                    actorId);
        }
        return planItem(plan);
    }

    @Override
    @Transactional
    public PlanItem archivePlan(String code, UUID actorId) {
        PlanDefinition current = requirePlan(code);
        if (current.archived()) {
            throw conflict("Ce plan est déjà archivé");
        }
        if (usedBySettings(current.code().code())) {
            throw conflict(PLAN_IN_SETTINGS);
        }
        PlanDefinition plan = withArchive(current, clock.instant());
        catalogStore.save(plan);
        store.journal(OfferEntity.PLAN, current.code().code(), "Plan " + quoted(current.label())
                + " archivé (les entreprises qui l'ont le gardent)", actorId);
        return planItem(plan);
    }

    @Override
    @Transactional
    public PlanItem unarchivePlan(String code, UUID actorId) {
        PlanDefinition current = requirePlan(code);
        if (!current.archived()) {
            throw conflict("Ce plan n'est pas archivé");
        }
        PlanDefinition plan = withArchive(current, null);
        catalogStore.save(plan);
        store.journal(OfferEntity.PLAN, current.code().code(), "Plan " + quoted(current.label()) + " désarchivé",
                actorId);
        return planItem(plan);
    }

    @Override
    @Transactional
    public CatalogueView reorderPlans(List<String> codes, UUID actorId) {
        List<PlanDefinition> plans = catalog.all();
        Set<String> expected = plans.stream().map(p -> p.code().code()).collect(Collectors.toSet());
        List<String> ordered = codes == null ? List.of() : codes.stream().map(OfferText::code).toList();
        if (ordered.size() != expected.size() || !new HashSet<>(ordered).equals(expected)) {
            throw invalid("codes", "Indiquez chaque plan une seule fois");
        }
        store.reorderPlans(ordered);
        invalidateCatalogueNowAndAfterCommit();
        Map<String, String> labels = plans.stream()
                .collect(Collectors.toMap(p -> p.code().code(), PlanDefinition::label));
        store.journal(OfferEntity.PLAN, "ORDER", "Ordre des plans modifié : "
                + ordered.stream().map(labels::get).collect(Collectors.joining(", ")), actorId);
        return catalogue();
    }

    // ── cycles ────────────────────────────────────────────────────────────────

    @Override
    @Transactional
    public CycleItem createCycle(CycleInput in, UUID actorId) {
        if (in == null) {
            throw invalid("code", "Indiquez le code de la durée");
        }
        String code = newCode(in.code());
        String label = label(in.label(), "label", LABEL_MAX);
        int months = months(in.months());
        List<BillingCycle> cycles = store.cycles();
        int sortOrder = in.sortOrder() != null ? in.sortOrder()
                : cycles.stream().mapToInt(BillingCycle::sortOrder).max().orElse(0) + 10;
        BillingCycle cycle = new BillingCycle(code, label, months, sortOrder, null);
        if (!store.insertCycle(cycle)) {
            throw conflict(DUPLICATE_CODE);
        }
        store.journal(OfferEntity.CYCLE, code, "Durée " + quoted(label) + " créée (" + months + " mois)", actorId);
        return cycleItem(cycle);
    }

    @Override
    @Transactional
    public CycleItem updateCycle(String code, CycleInput in, UUID actorId) {
        BillingCycle current = requireCycle(code);
        if (in == null) {
            throw invalid("label", "Indiquez les changements de la durée");
        }
        String label = in.label() == null ? current.label() : label(in.label(), "label", LABEL_MAX);
        int months = in.months() == null ? current.months() : months(in.months());
        int sortOrder = in.sortOrder() == null ? current.sortOrder() : in.sortOrder();
        if (months != current.months() && store.cycleInUse(current.code())) {
            throw conflict(CYCLE_IN_USE);
        }
        List<String> changes = new ArrayList<>();
        if (!label.equals(current.label())) {
            changes.add("libellé " + quoted(current.label()) + " → " + quoted(label));
        }
        if (months != current.months()) {
            changes.add(current.months() + " → " + months + " mois");
        }
        if (sortOrder != current.sortOrder()) {
            changes.add("ordre modifié");
        }
        BillingCycle cycle = new BillingCycle(current.code(), label, months, sortOrder, current.archivedAt());
        if (!changes.isEmpty()) {
            store.updateCycle(cycle);
            store.journal(OfferEntity.CYCLE, cycle.code(), "Durée " + quoted(label) + " modifiée : "
                    + String.join(", ", changes), actorId);
        }
        return cycleItem(cycle);
    }

    @Override
    @Transactional
    public CycleItem archiveCycle(String code, UUID actorId) {
        BillingCycle current = requireCycle(code);
        if (current.archived()) {
            throw conflict("Cette durée est déjà archivée");
        }
        BillingCycle cycle = current.withArchivedAt(clock.instant());
        store.updateCycle(cycle);
        store.journal(OfferEntity.CYCLE, cycle.code(), "Durée " + quoted(cycle.label()) + " archivée", actorId);
        return cycleItem(cycle);
    }

    @Override
    @Transactional
    public CycleItem unarchiveCycle(String code, UUID actorId) {
        BillingCycle current = requireCycle(code);
        if (!current.archived()) {
            throw conflict("Cette durée n'est pas archivée");
        }
        BillingCycle cycle = current.withArchivedAt(null);
        store.updateCycle(cycle);
        store.journal(OfferEntity.CYCLE, cycle.code(), "Durée " + quoted(cycle.label()) + " désarchivée", actorId);
        return cycleItem(cycle);
    }

    // ── prices ────────────────────────────────────────────────────────────────

    @Override
    @Transactional
    public PriceItem setPrice(String planCode, String cycleCode, Long amountXaf, UUID actorId) {
        PlanDefinition plan = requirePlan(planCode);
        BillingCycle cycle = requireCycle(cycleCode);
        if (plan.kind() != PlanKind.PAID) {
            throw invalid("planCode", PRICE_ONLY_PAID);
        }
        if (amountXaf == null || amountXaf <= 0 || amountXaf > MAX_AMOUNT_XAF) {
            throw invalid("amountXaf", AMOUNT_RANGE);
        }
        String plan_ = plan.code().code();
        store.lockPlan(plan_); // serialises the price writes of the plan, a first creation included
        java.util.Optional<PlanPrice> old = store.lockPrice(plan_, cycle.code());
        if (old.isPresent() && old.get().amountXaf() == amountXaf) {
            return new PriceItem(cycle.code(), cycle.label(), cycle.months(), amountXaf, old.get().updatedAt());
        }
        Long previous = old.map(PlanPrice::amountXaf).orElse(null);
        store.upsertPrice(plan_, cycle.code(), amountXaf, actorId);
        store.recordPriceChange(plan_, cycle.code(), previous, amountXaf, actorId);
        store.journal(OfferEntity.PRICE, plan_ + ":" + cycle.code(), "Prix " + quoted(plan.label()) + " · "
                + cycle.label() + " : " + (previous != null ? xaf(previous) + " → " : "") + xaf(amountXaf), actorId);
        return new PriceItem(cycle.code(), cycle.label(), cycle.months(), amountXaf, clock.instant());
    }

    @Override
    @Transactional
    public void deletePrice(String planCode, String cycleCode, UUID actorId) {
        PlanDefinition plan = requirePlan(planCode);
        BillingCycle cycle = requireCycle(cycleCode);
        String plan_ = plan.code().code();
        store.lockPlan(plan_);
        long old = store.lockPrice(plan_, cycle.code()).orElseThrow(() -> notFound(PRICE_NOT_FOUND)).amountXaf();
        store.deletePrice(plan_, cycle.code());
        store.recordPriceChange(plan_, cycle.code(), old, null, actorId);
        store.journal(OfferEntity.PRICE, plan_ + ":" + cycle.code(), "Prix " + quoted(plan.label()) + " · "
                + cycle.label() + " supprimé (" + xaf(old) + ")", actorId);
    }

    @Override
    @Transactional(readOnly = true)
    public List<PriceChangeView> priceHistory(String planCode, String cycleCode, Integer limit) {
        String plan = code(planCode);
        String cycle = code(cycleCode);
        Map<String, BillingCycle> cycles = byCode(store.cycles());
        List<PriceChange> rows = store.priceHistory(plan, cycle, limit(limit));
        return rows.stream().map(r -> new PriceChangeView(r.planCode(), catalog.label(r.planCode(), PLAN_NOT_FOUND),
                r.cycleCode(), cycles.containsKey(r.cycleCode()) ? cycles.get(r.cycleCode()).label() : CYCLE_NOT_FOUND,
                r.oldAmountXaf(), r.newAmountXaf(), author(r.changedBy(), r.changedByName(), SEED_AUTHOR),
                r.changedAt())).toList();
    }

    // ── settings ──────────────────────────────────────────────────────────────

    @Override
    @Transactional
    public SettingsView updateSettings(SettingsInput in, UUID actorId) {
        PlanSettings current = catalog.settings();
        if (in == null) {
            throw invalid("trialEnabled", "Indiquez les réglages de l'offre");
        }
        PlanDefinition free = in.freePlanCode() == null ? requireDefinition(current.freePlan())
                : livePlanOfKind(in.freePlanCode(), PlanKind.FREE, "freePlanCode", "Choisissez un plan gratuit actif");
        PlanDefinition trial = in.trialPlanCode() == null ? requireDefinition(current.trialPlan())
                : livePlanOfKind(in.trialPlanCode(), PlanKind.TRIAL, "trialPlanCode",
                        "Choisissez un plan d'essai actif");
        PlanDefinition paid = in.defaultPaidPlanCode() == null ? requireDefinition(current.defaultPaidPlan())
                : livePlanOfKind(in.defaultPaidPlanCode(), PlanKind.PAID, "defaultPaidPlanCode",
                        "Choisissez un plan payant actif");
        int months = in.trialMonths() == null ? current.trialMonths() : trialMonths(in.trialMonths());
        boolean enabled = in.trialEnabled() == null ? current.trialEnabled() : in.trialEnabled();
        PlanSettings next = new PlanSettings(free.code(), trial.code(), months, paid.code(), enabled);

        List<String> changes = new ArrayList<>();
        if (enabled != current.trialEnabled()) {
            changes.add(enabled ? "essai activé" : "essai désactivé (les nouvelles entreprises démarrent sur "
                    + quoted(free.label()) + ")");
        }
        if (!next.trialPlan().equals(current.trialPlan())) {
            changes.add("plan d'essai " + quoted(catalog.label(current.trialPlan().code(), "?")) + " → "
                    + quoted(trial.label()));
        }
        if (months != current.trialMonths()) {
            changes.add("durée d'essai " + current.trialMonths() + " → " + months + " mois");
        }
        if (!next.freePlan().equals(current.freePlan())) {
            changes.add("plan gratuit " + quoted(catalog.label(current.freePlan().code(), "?")) + " → "
                    + quoted(free.label()));
        }
        if (!next.defaultPaidPlan().equals(current.defaultPaidPlan())) {
            changes.add("plan payant par défaut " + quoted(catalog.label(current.defaultPaidPlan().code(), "?"))
                    + " → " + quoted(paid.label()));
        }
        if (!changes.isEmpty()) {
            try {
                catalogStore.saveSettings(next);
            } catch (IllegalArgumentException e) { // re-checked by the store (live plan of the right kind)
                throw invalid("trialPlanCode", "Ces réglages ne sont pas valides");
            }
            store.journal(OfferEntity.SETTINGS, "SETTINGS", "Réglages de l'offre modifiés : "
                    + String.join(" ; ", changes), actorId);
        }
        return settingsView(next);
    }

    // ── discounts ─────────────────────────────────────────────────────────────

    @Override
    @Transactional(readOnly = true)
    public List<DiscountView> discounts(boolean includeArchived) {
        return views(store.discounts(includeArchived, MAX_DISCOUNTS));
    }

    @Override
    @Transactional
    public DiscountView createDiscount(DiscountInput in, UUID actorId) {
        Discount draft = validDiscount(in, null, null);
        UUID id;
        try {
            id = store.insertDiscount(draft, actorId);
        } catch (DuplicateKeyException e) { // a concurrent creation took the same promo code
            throw conflict(DUPLICATE_CODE);
        }
        Discount saved = new Discount(id, draft.scope(), draft.label(), draft.percentOff(), draft.amountOffXaf(),
                draft.planCodes(), draft.cycleCodes(), draft.startsAt(), draft.endsAt(), draft.tenantId(),
                draft.promoCode(), null, clock.instant());
        store.journal(OfferEntity.DISCOUNT, id.toString(), discountSummary(saved, "créée"), actorId);
        return views(List.of(saved)).get(0);
    }

    @Override
    @Transactional
    public DiscountView updateDiscount(UUID id, DiscountInput in, UUID actorId) {
        Discount current = requireDiscount(id);
        if (current.archived()) {
            throw conflict("Cette remise est archivée");
        }
        Discount updated = validDiscount(in, current.id(), current.createdAt());
        try {
            store.updateDiscount(updated, actorId);
        } catch (DuplicateKeyException e) {
            throw conflict(DUPLICATE_CODE);
        }
        store.journal(OfferEntity.DISCOUNT, id.toString(), discountSummary(updated, "modifiée"), actorId);
        return views(List.of(updated)).get(0);
    }

    @Override
    @Transactional
    public DiscountView archiveDiscount(UUID id, UUID actorId) {
        Discount current = requireDiscount(id);
        if (current.archived()) {
            throw conflict("Cette remise est déjà archivée");
        }
        Discount archived = current.withArchivedAt(clock.instant());
        store.updateDiscount(archived, actorId);
        store.journal(OfferEntity.DISCOUNT, id.toString(), "Remise " + quoted(current.label()) + " archivée",
                actorId);
        return views(List.of(archived)).get(0);
    }

    // ── journal ───────────────────────────────────────────────────────────────

    @Override
    @Transactional(readOnly = true)
    public List<ChangeView> changes(Instant before, UUID beforeId, Integer limit) {
        List<OfferChange> rows = store.changes(before, beforeId, limit(limit));
        return rows.stream().map(c -> new ChangeView(c.id(), c.entity().name(), c.entity().label(), c.summary(),
                author(c.actorId(), c.actorName(), SYSTEM), c.occurredAt())).toList();
    }

    // ── validation ────────────────────────────────────────────────────────────

    private Discount validDiscount(DiscountInput in, UUID id, Instant createdAt) {
        if (in == null) {
            throw invalid("scope", "Indiquez le type de remise");
        }
        DiscountScope scope;
        try {
            scope = DiscountScope.valueOf(code(in.scope()) == null ? "" : code(in.scope()));
        } catch (IllegalArgumentException e) {
            throw invalid("scope", "Type de remise inconnu");
        }
        String label = label(in.label(), "label", DISCOUNT_LABEL_MAX);
        BigDecimal percent = in.percentOff();
        Long amount = in.amountOffXaf();
        if ((percent == null) == (amount == null)) {
            throw invalid("percentOff", "Indiquez soit un pourcentage, soit un montant");
        }
        if (percent != null && (percent.signum() <= 0 || percent.compareTo(HUNDRED) > 0
                || percent.stripTrailingZeros().scale() > 2)) {
            throw invalid("percentOff", "Le pourcentage doit être compris entre 0 et 100 (deux décimales au plus)");
        }
        if (amount != null && (amount <= 0 || amount > MAX_AMOUNT_XAF)) {
            throw invalid("amountOffXaf", AMOUNT_RANGE);
        }
        Set<String> plans = codes(in.planCodes());
        if (plans.stream().anyMatch(c -> catalog.find(c).isEmpty())) {
            throw invalid("planCodes", "Un des plans choisis n'existe pas");
        }
        Set<String> cycleCodes = codes(in.cycleCodes());
        if (!cycleCodes.isEmpty()) {
            Set<String> known = byCode(store.cycles()).keySet();
            if (!known.containsAll(cycleCodes)) {
                throw invalid("cycleCodes", "Une des durées choisies n'existe pas");
            }
        }
        Instant now = clock.instant();
        if (in.startsAt() != null && in.endsAt() != null && !in.endsAt().isAfter(in.startsAt())) {
            throw invalid("endsAt", "La fin doit suivre le début");
        }
        if (in.endsAt() != null && !in.endsAt().isAfter(now)) {
            throw invalid("endsAt", "La date de fin doit être dans le futur");
        }
        if (scope == DiscountScope.PROMOTION && in.endsAt() == null) {
            throw invalid("endsAt", "Une promotion doit avoir une date de fin");
        }
        UUID tenantId = null;
        if (scope == DiscountScope.TENANT) {
            if (in.tenantId() == null) {
                throw invalid("tenantId", "Choisissez une entreprise");
            }
            tenantId = store.tenant(in.tenantId()).orElseThrow(() -> invalid("tenantId", TENANT_NOT_FOUND))
                    .tenantId();
        }
        String promo = null;
        if (scope == DiscountScope.PROMO_CODE) {
            promo = code(in.promoCode());
            if (promo == null || !PROMO_CODE.matcher(promo).matches()) {
                throw invalid("promoCode", "Code promo invalide (3 à 40 lettres, chiffres, - ou _)");
            }
            if (store.promoCodeTaken(promo, id)) {
                throw conflict(DUPLICATE_CODE);
            }
        }
        return new Discount(id, scope, label, percent == null ? null : percent.stripTrailingZeros(), amount, plans,
                cycleCodes, in.startsAt(), in.endsAt(), tenantId, promo, null, createdAt);
    }

    private String discountSummary(Discount d, String verb) {
        StringBuilder s = new StringBuilder("Remise ").append(quoted(d.label())).append(" (")
                .append(d.scope().label().toLowerCase()).append(") ").append(verb).append(" : ").append(value(d));
        if (d.planCodes().isEmpty()) {
            s.append(" sur tous les plans");
        } else {
            s.append(" sur ").append(d.planCodes().stream().sorted()
                    .map(c -> quoted(catalog.label(c, PLAN_NOT_FOUND))).collect(Collectors.joining(", ")));
        }
        if (d.tenantId() != null) {
            String name = store.tenantNames(List.of(d.tenantId())).get(d.tenantId());
            s.append(", pour ").append(name == null ? "une entreprise" : quoted(name));
        }
        if (d.endsAt() != null) {
            s.append(", jusqu'au ").append(date(d.endsAt()));
        }
        return s.toString();
    }

    private static String newCode(String raw) {
        String code = code(raw);
        if (code == null || !PlanType.CODE.matcher(code).matches() || RESERVED_CODES.contains(code)) {
            throw invalid("code", "Code invalide : 2 à 30 caractères, lettres majuscules, chiffres ou _");
        }
        return code;
    }

    private static String label(String raw, String field, int max) {
        String label = blankToNull(raw);
        if (label == null) {
            throw invalid(field, "Libellé requis");
        }
        if (label.length() > max) {
            throw invalid(field, max + " caractères maximum");
        }
        return label;
    }

    private static String description(String raw) {
        String description = blankToNull(raw);
        if (description != null && description.length() > DESCRIPTION_MAX) {
            throw invalid("description", DESCRIPTION_MAX + " caractères maximum");
        }
        return description;
    }

    private static PlanKind kind(String raw) {
        try {
            return PlanKind.valueOf(code(raw) == null ? "" : code(raw));
        } catch (IllegalArgumentException e) {
            throw invalid("kind", "Nature du plan inconnue (gratuit, essai ou payant)");
        }
    }

    private static int months(Integer months) {
        if (months == null || months < 1 || months > MAX_MONTHS) {
            throw invalid("months", "La durée doit être comprise entre 1 et " + MAX_MONTHS + " mois");
        }
        return months;
    }

    private static int trialMonths(int months) {
        if (months < 1 || months > MAX_MONTHS) {
            throw invalid("trialMonths", "La durée d'essai doit être comprise entre 1 et " + MAX_MONTHS + " mois");
        }
        return months;
    }

    private static int limit(Integer limit) {
        if (limit == null) {
            return DEFAULT_LIMIT;
        }
        if (limit < 1 || limit > MAX_LIMIT) {
            throw invalid("limit", "Entre 1 et " + MAX_LIMIT + " lignes");
        }
        return limit;
    }

    private static Set<String> codes(Collection<String> raw) {
        Set<String> codes = new LinkedHashSet<>();
        if (raw != null) {
            raw.stream().map(OfferText::code).filter(Objects::nonNull).forEach(codes::add);
        }
        return codes;
    }

    // ── lookups ───────────────────────────────────────────────────────────────

    private PlanDefinition requirePlan(String code) {
        String c = code(code);
        return (c == null ? java.util.Optional.<PlanDefinition>empty() : catalog.find(c))
                .orElseThrow(() -> notFound(PLAN_NOT_FOUND));
    }

    private PlanDefinition requireDefinition(PlanType code) {
        return catalog.find(code).orElseThrow(() -> notFound(PLAN_NOT_FOUND));
    }

    private PlanDefinition livePlanOfKind(String code, PlanKind kind, String field, String message) {
        String c = code(code);
        return (c == null ? java.util.Optional.<PlanDefinition>empty() : catalog.find(c))
                .filter(p -> p.kind() == kind && !p.archived())
                .orElseThrow(() -> invalid(field, message));
    }

    private BillingCycle requireCycle(String code) {
        String c = code(code);
        return (c == null ? java.util.Optional.<BillingCycle>empty() : store.cycle(c))
                .orElseThrow(() -> notFound(CYCLE_NOT_FOUND));
    }

    private Discount requireDiscount(UUID id) {
        return (id == null ? java.util.Optional.<Discount>empty() : store.discount(id))
                .orElseThrow(() -> notFound(DISCOUNT_NOT_FOUND));
    }

    private boolean usedBySettings(String code) {
        PlanSettings s = catalog.settings();
        return s.freePlan().code().equals(code) || s.trialPlan().code().equals(code)
                || s.defaultPaidPlan().code().equals(code);
    }

    // ── views ─────────────────────────────────────────────────────────────────

    private PlanItem planItem(PlanDefinition p) {
        String code = p.code().code();
        Map<String, BillingCycle> cycles = byCode(store.cycles());
        List<PlanPrice> prices = store.prices(code);
        return planItem(p, store.tenantsOnPlan(code), prices, cycles);
    }

    private static PlanItem planItem(PlanDefinition p, long tenants, List<PlanPrice> prices,
                                     Map<String, BillingCycle> cycles) {
        List<PriceItem> items = prices.stream()
                .filter(x -> cycles.containsKey(x.cycleCode()))
                .sorted(Comparator.comparingInt((PlanPrice x) -> cycles.get(x.cycleCode()).sortOrder())
                        .thenComparing(PlanPrice::cycleCode))
                .map(x -> {
                    BillingCycle c = cycles.get(x.cycleCode());
                    return new PriceItem(c.code(), c.label(), c.months(), x.amountXaf(), x.updatedAt());
                }).toList();
        return new PlanItem(p.code().code(), p.label(), p.description(), p.kind().name(), kindLabel(p.kind()),
                p.sortOrder(), p.visible(), p.archived(), p.archivedAt(), tenants, items);
    }

    private static CycleItem cycleItem(BillingCycle c) {
        return new CycleItem(c.code(), c.label(), c.months(), c.sortOrder(), c.archived(), c.archivedAt());
    }

    private SettingsView settingsView(PlanSettings s) {
        return new SettingsView(s.freePlan().code(), catalog.label(s.freePlan().code(), PLAN_NOT_FOUND),
                s.trialPlan().code(), catalog.label(s.trialPlan().code(), PLAN_NOT_FOUND), s.trialMonths(),
                s.trialEnabled(), s.defaultPaidPlan().code(),
                catalog.label(s.defaultPaidPlan().code(), PLAN_NOT_FOUND));
    }

    private List<DiscountView> views(List<Discount> discounts) {
        if (discounts.isEmpty()) {
            return List.of();
        }
        Map<String, BillingCycle> cycles = byCode(store.cycles());
        Set<UUID> tenantIds = discounts.stream().map(Discount::tenantId).filter(Objects::nonNull)
                .collect(Collectors.toSet());
        Map<UUID, String> names = tenantIds.isEmpty() ? Map.of() : store.tenantNames(tenantIds);
        Instant now = clock.instant();
        return discounts.stream().map(d -> {
            List<String> plans = d.planCodes().stream().sorted().toList();
            List<String> cycleCodes = d.cycleCodes().stream()
                    .sorted(Comparator.comparingInt(c -> cycles.containsKey(c) ? cycles.get(c).sortOrder() : 0))
                    .toList();
            return new DiscountView(d.id(), d.scope().name(), d.scope().label(), d.label(), d.percentOff(),
                    d.amountOffXaf(), plans, plans.stream().map(c -> catalog.label(c, PLAN_NOT_FOUND)).toList(),
                    cycleCodes, cycleCodes.stream()
                            .map(c -> cycles.containsKey(c) ? cycles.get(c).label() : CYCLE_NOT_FOUND).toList(),
                    d.startsAt(), d.endsAt(), d.tenantId(), d.tenantId() == null ? null : names.get(d.tenantId()),
                    d.promoCode(), d.archived(), d.archivedAt(), d.liveAt(now), d.createdAt());
        }).toList();
    }

    private static String author(UUID actorId, String name, String whenNoActor) {
        if (actorId == null) {
            return whenNoActor;
        }
        return name != null ? name : SUPER_ADMIN;
    }

    private static Map<String, BillingCycle> byCode(List<BillingCycle> cycles) {
        Map<String, BillingCycle> map = new HashMap<>();
        cycles.forEach(c -> map.put(c.code(), c));
        return map;
    }

    private static PlanDefinition withArchive(PlanDefinition p, Instant at) {
        return new PlanDefinition(p.code(), p.label(), p.description(), p.sortOrder(), p.visible(), p.kind(), at);
    }

    /** The reorder writes {@code public.plans} outside {@link PlanCatalogStore#save}: drop the cache now and after. */
    private void invalidateCatalogueNowAndAfterCommit() {
        catalogStore.invalidate();
        if (TransactionSynchronizationManager.isSynchronizationActive()) {
            TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization() {
                @Override
                public void afterCompletion(int status) {
                    catalogStore.invalidate();
                }
            });
        }
    }
}
