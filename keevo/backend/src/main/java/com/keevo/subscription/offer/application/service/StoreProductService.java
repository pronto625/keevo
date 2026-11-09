package com.keevo.subscription.offer.application.service;

import com.keevo.identity.api.PlanKind;
import com.keevo.shared.capability.entitlement.PlanCatalog;
import com.keevo.shared.capability.entitlement.PlanDefinition;
import com.keevo.subscription.api.OfferAdministration.StoreProductInput;
import com.keevo.subscription.api.OfferAdministration.StoreProductView;
import com.keevo.subscription.api.StoreProducts;
import com.keevo.subscription.offer.domain.model.BillingCycle;
import com.keevo.subscription.offer.domain.model.OfferEntity;
import com.keevo.subscription.offer.domain.model.StoreProductRow;
import com.keevo.subscription.offer.domain.port.out.OfferStore;
import com.keevo.subscription.offer.domain.port.out.StoreProductStore;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;
import java.util.regex.Pattern;

import static com.keevo.subscription.offer.application.service.OfferText.*;

/**
 * Story 21.2 — the App Store products of the offer catalogue: which plan × cycle an Apple product grants (offer data,
 * no product id in the code), the Apple price entered by the super-admin or observed on a purchase, and its gap with
 * the configurable grid (Apple prices are set in App Store Connect, the grid covers Mobile Money and granted plans).
 *
 * <p>Pattern: application service over a port ({@link StoreProductStore}); the plan labels come from the cached
 * {@link PlanCatalog}. Cost: every read is one statement (cycle and grid price joined); a save = one locked read + one
 * upsert + one journal insert.
 */
@Service
public class StoreProductService implements StoreProducts {

    static final String STORE_LABEL = "App Store";
    static final String PRODUCT_INVALID = "Identifiant de produit App Store invalide";
    static final String PLAN_PAID_REQUIRED = "Choisissez un plan payant actif";
    static final String DECLARED_RANGE = "Le prix App Store doit être compris entre 1 et 100 000 000 FCFA";
    static final String SOURCE_DECLARED = "Saisi";
    static final String SOURCE_OBSERVED = "Constaté sur un achat";
    static final String SOURCE_NONE = "Prix App Store non renseigné";
    static final String CYCLE_ARCHIVED = "Cette durée est archivée : choisissez une durée active";
    private static final Pattern PRODUCT_ID = Pattern.compile("^[A-Za-z0-9._-]{1,100}$");

    private final StoreProductStore store;
    private final OfferStore offers;
    private final PlanCatalog catalog;

    public StoreProductService(StoreProductStore store, OfferStore offers, PlanCatalog catalog) {
        this.store = store;
        this.offers = offers;
        this.catalog = catalog;
    }

    // ── port used by payments ─────────────────────────────────────────────────

    @Override
    @Transactional(readOnly = true)
    public Optional<StoreProduct> find(String storeCode, String productId) {
        if (storeCode == null || productId == null || !PRODUCT_ID.matcher(productId).matches()) {
            return Optional.empty();
        }
        return store.find(storeCode, productId).map(this::product);
    }

    @Override
    @Transactional(readOnly = true)
    public List<StoreProduct> active(String storeCode) {
        List<StoreProduct> out = new ArrayList<>();
        for (StoreProductRow row : store.all()) {
            if (!row.active() || row.cycleArchived() || !row.store().equals(storeCode)) {
                continue;
            }
            Optional<PlanDefinition> plan = catalog.find(row.planCode());
            if (plan.isPresent() && plan.get().kind() == PlanKind.PAID && !plan.get().archived()) {
                out.add(product(row));
            }
        }
        return out;
    }

    @Override
    @Transactional
    public void recordObservedPrice(String storeCode, String productId, BigDecimal amount, String currency,
                                    Long amountXaf, Instant at) {
        if (amount == null || amount.signum() <= 0 || currency == null || at == null) {
            return;
        }
        store.recordObservedPrice(storeCode, productId, amount, currency, amountXaf, at);
    }

    // ── super-admin ───────────────────────────────────────────────────────────

    /** Every product (inactive included) with its gap. One query. */
    List<StoreProductView> views() {
        return store.all().stream().map(this::view).toList();
    }

    /** Creates or replaces a product mapping, journaled. */
    StoreProductView save(String rawProductId, StoreProductInput in, UUID actorId) {
        String productId = rawProductId == null ? null : rawProductId.trim();
        if (productId == null || !PRODUCT_ID.matcher(productId).matches()) {
            throw invalid("productId", PRODUCT_INVALID);
        }
        if (in == null) {
            throw invalid("planCode", PLAN_PAID_REQUIRED);
        }
        Optional<StoreProductRow> current = store.lock(APP_STORE, productId);
        String planCode = code(in.planCode());
        PlanDefinition plan = planCode == null ? null : catalog.find(planCode).orElse(null);
        boolean keepsArchivedPlan = plan != null && current.isPresent()
                && current.get().planCode().equals(plan.code().code());
        if (plan == null || plan.kind() != PlanKind.PAID || (plan.archived() && !keepsArchivedPlan)) {
            throw invalid("planCode", PLAN_PAID_REQUIRED);
        }
        String cycleCode = code(in.cycleCode());
        BillingCycle cycle = (cycleCode == null ? Optional.<BillingCycle>empty() : offers.cycle(cycleCode))
                .orElseThrow(() -> invalid("cycleCode", CYCLE_NOT_FOUND));
        boolean keepsArchivedCycle = current.isPresent() && current.get().cycleCode().equals(cycle.code());
        if (cycle.archivedAt() != null && !keepsArchivedCycle) {
            throw invalid("cycleCode", CYCLE_ARCHIVED);
        }
        Long declared = in.declaredPriceXaf();
        if (declared != null && (declared <= 0 || declared > MAX_AMOUNT_XAF)) {
            throw invalid("declaredPriceXaf", DECLARED_RANGE);
        }
        boolean active = in.active() == null || in.active();
        StoreProductRow before = current.orElse(null);
        if (before != null && before.planCode().equals(plan.code().code()) && before.cycleCode().equals(cycle.code())
                && Objects.equals(before.declaredPriceXaf(), declared) && before.active() == active) {
            return view(before);
        }
        store.upsert(APP_STORE, productId, plan.code().code(), cycle.code(), declared, active);
        offers.journal(OfferEntity.STORE_PRODUCT, productId, summary(before, productId, plan.label(), cycle.label(),
                declared, active), actorId);
        return view(store.find(APP_STORE, productId).orElseThrow());
    }

    // ── mapping ───────────────────────────────────────────────────────────────

    private StoreProduct product(StoreProductRow r) {
        return new StoreProduct(r.store(), r.productId(), r.planCode(), planLabel(r.planCode()), r.cycleCode(),
                r.cycleLabel(), r.months(), r.active(), r.declaredPriceXaf(), r.gridPriceXaf());
    }

    private StoreProductView view(StoreProductRow r) {
        Long apple;
        String source;
        String sourceLabel;
        if (r.declaredPriceXaf() != null) {
            apple = r.declaredPriceXaf();
            source = "DECLARED";
            sourceLabel = SOURCE_DECLARED;
        } else if (r.observedPriceXaf() != null) {
            apple = r.observedPriceXaf();
            source = "OBSERVED";
            sourceLabel = SOURCE_OBSERVED;
        } else {
            apple = null;
            source = "NONE";
            sourceLabel = SOURCE_NONE;
        }
        Long gap = apple == null || r.gridPriceXaf() == null ? null : apple - r.gridPriceXaf();
        return new StoreProductView(r.store(), STORE_LABEL, r.productId(), r.planCode(), planLabel(r.planCode()),
                r.cycleCode(), r.cycleLabel(), r.active(), r.declaredPriceXaf(), r.observedPrice(),
                r.observedCurrency(), r.observedPriceXaf(), r.observedAt(), apple, source, sourceLabel,
                r.gridPriceXaf(), gap, gapLabel(apple, r.gridPriceXaf(), gap), r.updatedAt());
    }

    static String gapLabel(Long apple, Long grid, Long gap) {
        if (grid == null) {
            return "Aucun prix dans la grille pour cette durée";
        }
        if (apple == null) {
            return SOURCE_NONE;
        }
        if (gap == 0) {
            return "Identique à la grille";
        }
        return xaf(Math.abs(gap)) + (gap > 0 ? " de plus que la grille" : " de moins que la grille");
    }

    private String planLabel(String planCode) {
        return catalog.find(planCode).map(PlanDefinition::label).orElse(planCode);
    }

    private String summary(StoreProductRow before, String productId, String planLabel, String cycleLabel,
                                  Long declared, boolean active) {
        String what = "Produit App Store " + productId + " : " + planLabel + " · " + cycleLabel
                + (declared == null ? "" : ", prix Apple " + xaf(declared)) + (active ? "" : ", non proposé");
        if (before == null) {
            return what + " (ajouté)";
        }
        String was = planLabel(before.planCode()) + " · " + before.cycleLabel()
                + (before.declaredPriceXaf() == null ? "" : ", prix Apple " + xaf(before.declaredPriceXaf()))
                + (before.active() ? "" : ", non proposé");
        return what + " (modifié ; avant : " + was + ")";
    }
}
