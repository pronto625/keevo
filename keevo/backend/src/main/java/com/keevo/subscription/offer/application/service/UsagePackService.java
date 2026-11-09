package com.keevo.subscription.offer.application.service;

import com.keevo.shared.capability.entitlement.UsageMetric;
import com.keevo.subscription.api.OfferAdministration.UsagePackInput;
import com.keevo.subscription.api.OfferAdministration.UsagePackView;
import com.keevo.subscription.api.UsagePacks;
import com.keevo.subscription.offer.domain.model.OfferEntity;
import com.keevo.subscription.offer.domain.model.UsagePack;
import com.keevo.subscription.offer.domain.port.out.OfferStore;
import com.keevo.subscription.offer.domain.port.out.UsagePackStore;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Optional;
import java.util.UUID;
import java.util.regex.Pattern;

import static com.keevo.subscription.offer.application.service.OfferText.*;

/**
 * Story 21.3 — the usage packs of the offer catalogue (SMS packs first): what an owner can buy beyond the plan's
 * quotas, at which price. Commercial data edited by the super-admin (ADR-05): no pack, quantity or price in the code
 * (V63 seeds them from Bref's 20 FCFA per SMS). A change is journaled and applies to the next checkout; a payment
 * already started keeps the price frozen on its request.
 *
 * <p>Pattern: application service over a port ({@link UsagePackStore}), mirror of {@link StoreProductService}. Cost:
 * every read is one statement on a small table (primary key or full scan in display order); a save = one locked
 * read + one upsert + one journal insert.
 */
@Service
public class UsagePackService implements UsagePacks {

    static final String CODE_INVALID = "Code de pack invalide (majuscules, chiffres et _ ; 2 à 30 caractères)";
    static final String LABEL_REQUIRED = "Le nom du pack est obligatoire (80 caractères au plus)";
    static final String METRIC_INVALID = "Choisissez ce que contient le pack : SMS, documents, emails ou crédits IA";
    static final String QUANTITY_RANGE = "La quantité doit être comprise entre 1 et 1 000 000";
    static final String SORT_RANGE = "L'ordre d'affichage doit être compris entre 0 et 100 000";
    static final String METRIC_FIXED =
            "Le contenu d'un pack ne peut pas changer : créez un nouveau pack avec un autre code";
    static final String INPUT_REQUIRED = "Renseignez le nom, le contenu, la quantité et le prix du pack";
    static final int MAX_QUANTITY = 1_000_000;
    static final int MAX_SORT = 100_000;
    private static final Pattern CODE = Pattern.compile("^[A-Z][A-Z0-9_]{1,29}$");

    private final UsagePackStore store;
    private final OfferStore offers;

    public UsagePackService(UsagePackStore store, OfferStore offers) {
        this.store = store;
        this.offers = offers;
    }

    // ── port used by payments ─────────────────────────────────────────────────

    @Override
    @Transactional(readOnly = true)
    public List<UsagePackOffer> offered() {
        List<UsagePackOffer> out = new ArrayList<>();
        for (UsagePack p : store.all()) {
            if (p.active()) {
                out.add(offer(p));
            }
        }
        return out;
    }

    @Override
    @Transactional(readOnly = true)
    public Optional<UsagePackOffer> offered(String rawCode) {
        String code = code(rawCode);
        if (code == null || !CODE.matcher(code).matches()) {
            return Optional.empty();
        }
        return store.find(code).filter(UsagePack::active).map(UsagePackService::offer);
    }

    // ── super-admin ───────────────────────────────────────────────────────────

    /** Every pack (inactive included). One query. */
    List<UsagePackView> views() {
        return store.all().stream().map(UsagePackService::view).toList();
    }

    /** Creates or updates a pack, journaled. Runs in the caller's transaction (the catalogue service's). */
    UsagePackView save(String rawCode, UsagePackInput in, UUID actorId) {
        String code = code(rawCode);
        if (code == null || !CODE.matcher(code).matches()) {
            throw invalid("code", CODE_INVALID);
        }
        Optional<UsagePack> current = store.lock(code);
        UsagePack before = current.orElse(null);
        if (in == null || (before == null && (in.label() == null || in.metric() == null || in.quantity() == null
                || in.priceXaf() == null))) {
            throw invalid("label", INPUT_REQUIRED);
        }
        String label = in.label() == null ? before.label() : in.label().trim();
        if (label.isEmpty() || label.length() > 80) {
            throw invalid("label", LABEL_REQUIRED);
        }
        UsageMetric metric = in.metric() == null ? before.metric() : metric(in.metric());
        if (before != null && metric != before.metric()) {
            // a code always sells the same thing (history, reports, payments link by code): a new content = new code
            throw invalid("metric", METRIC_FIXED);
        }
        int quantity = in.quantity() == null ? before.quantity() : in.quantity();
        if (quantity < 1 || quantity > MAX_QUANTITY) {
            throw invalid("quantity", QUANTITY_RANGE);
        }
        long price = in.priceXaf() == null ? before.priceXaf() : in.priceXaf();
        if (price < 1 || price > MAX_AMOUNT_XAF) {
            throw invalid("priceXaf", AMOUNT_RANGE);
        }
        boolean active = in.active() != null ? in.active() : before == null || before.active();
        int sortOrder = in.sortOrder() != null ? in.sortOrder() : before == null ? 0 : before.sortOrder();
        if (sortOrder < 0 || sortOrder > MAX_SORT) {
            throw invalid("sortOrder", SORT_RANGE);
        }
        UsagePack after = new UsagePack(code, label, metric, quantity, price, active, sortOrder, null);
        if (before != null && before.label().equals(label) && before.metric() == metric
                && before.quantity() == quantity && before.priceXaf() == price && before.active() == active
                && before.sortOrder() == sortOrder) {
            return view(before);
        }
        store.upsert(after);
        offers.journal(OfferEntity.USAGE_PACK, code, summary(before, after), actorId);
        return view(store.find(code).orElseThrow());
    }

    // ── mapping ───────────────────────────────────────────────────────────────

    private static UsagePackOffer offer(UsagePack p) {
        return new UsagePackOffer(p.code(), p.label(), p.metric().name(), p.metric().label(), p.quantity(),
                p.priceXaf());
    }

    static UsagePackView view(UsagePack p) {
        return new UsagePackView(p.code(), p.label(), p.metric().name(), p.metric().label(), p.quantity(),
                p.priceXaf(), unitPrice(p.priceXaf(), p.quantity()), p.active(), p.sortOrder(), p.updatedAt());
    }

    /** Price per unit, rounded half-up. */
    static long unitPrice(long price, int quantity) {
        return (price * 2 + quantity) / (2L * quantity);
    }

    private static UsageMetric metric(String raw) {
        try {
            return UsageMetric.valueOf(raw.trim().toUpperCase(Locale.ROOT));
        } catch (IllegalArgumentException e) {
            throw invalid("metric", METRIC_INVALID);
        }
    }

    static String summary(UsagePack before, UsagePack after) {
        String what = describe(after);
        if (before == null) {
            return what + " (ajouté)";
        }
        return what + " (modifié ; avant : " + describe(before) + ")";
    }

    private static String describe(UsagePack p) {
        return "Pack " + quoted(p.label()) + " : " + p.quantity() + " " + p.metric().label() + ", " + xaf(p.priceXaf())
                + (p.active() ? "" : ", non proposé");
    }
}
