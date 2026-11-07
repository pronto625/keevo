package com.keevo.catalog.product.application.usecase;

import com.keevo.catalog.api.ProductLookupPort;
import com.keevo.catalog.api.ProductLookupPort.DocumentLine;
import com.keevo.catalog.product.domain.port.out.ProductDerivationRepository;
import com.keevo.catalog.product.domain.port.out.ProductDerivationRepository.Candidate;
import com.keevo.catalog.product.domain.port.out.ProductDerivationRepository.NewProduct;
import com.keevo.catalog.product.domain.port.out.ProductDerivationRepository.Target;
import com.keevo.catalog.product.domain.service.ProductKeys;
import com.keevo.shared.application.port.TransactionLockPort;
import com.keevo.shared.capability.entitlement.PlanLimitCheck;
import com.keevo.shared.capability.entitlement.PlanView;
import com.keevo.shared.capability.entitlement.TenantPlanQuery;
import com.keevo.shared.event.catalog.product.ProductCreatedEvent;
import com.keevo.subscription.api.ProductCountPort;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Clock;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ThreadLocalRandom;

/**
 * Story 20.6 — the catalogue products of an issued document's lines, found or created (Bref
 * {@code product.repository} « upsert by key », FUS-FR16), behind {@link ProductLookupPort#findOrCreateForDocument}.
 *
 * <p>Per distinct key ({@link ProductLookupPort#keyOf}; identical keys of one document are one product, the last
 * line's price wins): a product whose {@code product_key} is the key, else whose name is the line's product name
 * (case-insensitive, the {@code uq_products_name} rule), is the match — a merged one answers with its merge target
 * (one hop, as {@code LookupProductByBarcodeUseCase}); among several, a live product first, then the oldest. A
 * matched product, not archived, whose price was not typed by a person ({@code price_manual = false}) takes the
 * line's price (Bref: last sale price). No match → a new product: name = the description cut to the column, the key,
 * the reference as barcode when it looks like one, price = the line's unit price, {@code track_stock = false},
 * {@code price_manual = false}, no category, auto SKU, as many as the plan's product limit still allows (the others
 * stay unlinked); one {@link ProductCreatedEvent} per product created (audit, sync).
 *
 * <p>Cost: 1 advisory lock (the per-tenant catalogue lock of product creations) + 1 SELECT for every line
 * ({@code idx_products_product_key}, {@code uq_products_name}) + 1 SELECT of merge targets when needed + 1 batch
 * UPDATE of prices + 1 product count (finite plan only) + 1 SKU check + 1 batch INSERT — constant, never per line.
 *
 * <p>Never throws for a business reason (the caller's transaction must stay committable): every refusal leaves the
 * line out of the answer, logged at debug.
 */
@Service
public class DeriveProductsFromDocumentUseCase {

    private static final Logger log = LoggerFactory.getLogger(DeriveProductsFromDocumentUseCase.class);

    private static final String SKU_CHARS = "ABCDEFGHIJKLMNOPQRSTUVWXYZ0123456789";

    private final ProductDerivationRepository products;
    private final TransactionLockPort transactionLock;
    private final TenantPlanQuery tenantPlanQuery;
    private final PlanLimitCheck planLimit;
    private final ProductCountPort productCount;
    private final ApplicationEventPublisher events;
    private final Clock clock;

    public DeriveProductsFromDocumentUseCase(ProductDerivationRepository products, TransactionLockPort transactionLock,
                                             TenantPlanQuery tenantPlanQuery, PlanLimitCheck planLimit,
                                             ProductCountPort productCount, ApplicationEventPublisher events,
                                             Clock clock) {
        this.products = products;
        this.transactionLock = transactionLock;
        this.tenantPlanQuery = tenantPlanQuery;
        this.planLimit = planLimit;
        this.productCount = productCount;
        this.events = events;
        this.clock = clock;
    }

    /** One product to resolve: its key, the name and barcode it would be created with, the last line price. */
    private record Wanted(String key, String name, String barcode, long price) {
    }

    @Transactional(propagation = org.springframework.transaction.annotation.Propagation.MANDATORY) // joins the issue
    public Map<String, UUID> execute(List<DocumentLine> lines, UUID actorId, String tenantSchema) {
        Map<String, Wanted> wanted = new LinkedHashMap<>();
        for (DocumentLine line : lines == null ? List.<DocumentLine>of() : lines) {
            String name = ProductLookupPort.productName(line.description());
            String key = ProductLookupPort.keyOf(line.description(), line.reference());
            if (name == null || key == null || line.unitPrice() < 0) continue;
            String barcode = ProductKeys.isBarcodeLike(line.reference()) ? line.reference().trim() : null;
            Wanted previous = wanted.get(key);
            // identical keys: one product; the first line names it, the last line's price is the sale price
            wanted.put(key, previous == null ? new Wanted(key, name, barcode, line.unitPrice())
                    : new Wanted(key, previous.name(), previous.barcode(), line.unitPrice()));
        }
        if (wanted.isEmpty()) return Map.of();

        Instant now = clock.instant();
        Map<String, UUID> answer = new LinkedHashMap<>();
        Map<UUID, Integer> priceUpdates = new LinkedHashMap<>();
        List<Wanted> missing = match(wanted.values(), answer, priceUpdates);
        if (!missing.isEmpty()) {
            // the catalogue's per-tenant creation lock, only when something may be created (plan limit, SKU and name
            // uniqueness); a product created by a concurrent issue before the lock is found again under it
            transactionLock.lock("product-limit:" + tenantSchema);
            missing = match(missing, answer, priceUpdates);
        }
        products.updatePrices(priceUpdates, now);
        create(missing, answer, actorId, tenantSchema, now);
        return answer;
    }

    /**
     * Links the wanted products that exist (by key, then by name, following a merge) into {@code answer}, records
     * their automatic price updates and returns the ones still missing — one SELECT (+ one for merge targets).
     */
    private List<Wanted> match(java.util.Collection<Wanted> wanted, Map<String, UUID> answer,
                               Map<UUID, Integer> priceUpdates) {
        List<Candidate> candidates = products.findCandidates(wanted.stream().map(Wanted::key).toList(),
                wanted.stream().map(Wanted::name).distinct().toList());
        Map<String, Candidate> byKey = best(candidates, true);
        Map<String, Candidate> byName = best(candidates, false);
        List<UUID> mergeTargets = candidates.stream()
                .map(Candidate::mergedIntoId).filter(java.util.Objects::nonNull).distinct().toList();
        Map<UUID, Target> targets = mergeTargets.isEmpty() ? Map.of() : products.findTargets(mergeTargets);

        List<Wanted> missing = new ArrayList<>();
        for (Wanted w : wanted) {
            Candidate c = byKey.get(w.key());
            if (c == null) c = byName.get(lower(w.name()));
            if (c == null) {
                missing.add(w);
                continue;
            }
            Target resolved = c.mergedIntoId() == null
                    ? new Target(c.id(), c.archived(), c.price(), c.priceManual())
                    : targets.get(c.mergedIntoId());
            if (resolved == null) { // a merge target that vanished: leave the line free
                log.debug("[catalog] document line left free: merge target of {} not found", c.id());
                continue;
            }
            answer.put(w.key(), resolved.id());
            // a free line (price 0, gift) never resets a catalogue price
            if (!resolved.archived() && !resolved.priceManual() && w.price() > 0 && fitsPrice(w.price())
                    && resolved.price() != (int) w.price()) {
                priceUpdates.put(resolved.id(), (int) w.price());
            }
        }
        return missing;
    }

    /** Creates the missing products the plan allows, in one statement, and adds them to {@code answer}. */
    private void create(List<Wanted> missing, Map<String, UUID> answer, UUID actorId, String tenantSchema,
                        Instant now) {
        // two keys, one name (a barcode line and a plain line « Huile »): one product — the name is unique
        Map<String, UUID> plannedByName = new HashMap<>();
        List<NewProduct> toCreate = new ArrayList<>();
        Map<UUID, List<String>> keysOf = new HashMap<>();
        int remaining = missing.isEmpty() ? 0 : remainingProducts(tenantSchema);
        for (Wanted w : missing) {
            if (!fitsPrice(w.price())) continue;
            UUID planned = plannedByName.get(lower(w.name()));
            if (planned != null) {
                keysOf.get(planned).add(w.key());
                continue;
            }
            if (toCreate.size() >= remaining) {
                log.debug("[catalog] document product not created: plan product limit reached");
                continue;
            }
            UUID id = UUID.randomUUID();
            toCreate.add(new NewProduct(id, w.name(), null, (int) w.price(), w.barcode(),
                    ProductKeys.key(w.name(), w.barcode())));
            plannedByName.put(lower(w.name()), id);
            keysOf.put(id, new ArrayList<>(List.of(w.key())));
        }
        if (toCreate.isEmpty()) return;
        List<NewProduct> withSkus = withUniqueSkus(toCreate);
        Set<UUID> created = new HashSet<>(products.insertAll(withSkus, now));
        for (NewProduct p : withSkus) {
            if (!created.contains(p.id())) { // a unique rule hit meanwhile (name, key or SKU): line left free
                log.debug("[catalog] document product {} not created: a unique rule refused it", p.name());
                continue;
            }
            keysOf.get(p.id()).forEach(k -> answer.put(k, p.id()));
            events.publishEvent(new ProductCreatedEvent(p.id(), p.name(), p.sku(), tenantSchema, actorId, null, null,
                    null, now));
        }
    }

    /**
     * Products the plan still allows (the count runs only for a finite limit). No plan, or a count that cannot be
     * read → none (fails closed like a manual creation, without failing the document).
     */
    private int remainingProducts(String tenantSchema) {
        try {
            Optional<PlanView> plan = tenantPlanQuery.findCurrentPlan(tenantSchema);
            if (plan.isEmpty()) return 0;
            return Math.max(0, planLimit.remainingProducts(plan.get(),
                    () -> productCount.countActiveProducts(tenantSchema)));
        } catch (RuntimeException e) { // no transactional proxy crossed: the caller's transaction is untouched
            log.debug("[catalog] document products not created: product quota unavailable ({})", e.getMessage());
            return 0;
        }
    }

    /** Auto SKUs ({@code KEV-} + 6), unique among themselves and against the catalogue (one query). */
    private List<NewProduct> withUniqueSkus(List<NewProduct> toCreate) {
        Set<String> skus = new HashSet<>();
        for (int i = 0; i < toCreate.size(); i++) {
            String sku;
            do {
                sku = generateSku();
            } while (!skus.add(sku));
        }
        Set<String> taken = new HashSet<>(products.existingSkus(skus));
        List<String> free = new ArrayList<>(skus);
        for (int i = 0; i < free.size(); i++) {
            while (taken.contains(free.get(i))) { // ~1 in 2 billion: regenerate (unchecked twice, still not unique)
                String sku = generateSku();
                if (skus.add(sku)) free.set(i, sku);
            }
        }
        List<NewProduct> result = new ArrayList<>(toCreate.size());
        for (int i = 0; i < toCreate.size(); i++) {
            NewProduct p = toCreate.get(i);
            result.add(new NewProduct(p.id(), p.name(), free.get(i), p.price(), p.barcode(), p.productKey()));
        }
        return result;
    }

    private static String generateSku() {
        ThreadLocalRandom random = ThreadLocalRandom.current();
        StringBuilder sb = new StringBuilder("KEV-");
        for (int i = 0; i < 6; i++) {
            sb.append(SKU_CHARS.charAt(random.nextInt(SKU_CHARS.length())));
        }
        return sb.toString();
    }

    /**
     * The best candidate per match value ({@code byKey}: the key; else the lower-cased name): a live (not merged, not
     * archived) product first, then a merged one, then the oldest, then the smallest id.
     */
    private static Map<String, Candidate> best(List<Candidate> candidates, boolean byKey) {
        Comparator<Candidate> order = Comparator.<Candidate>comparingInt(c -> c.mergedIntoId() == null
                        ? (c.archived() ? 2 : 0) : 1)
                .thenComparing(Candidate::createdAt)
                .thenComparing(Candidate::id);
        Map<String, Candidate> best = new HashMap<>();
        for (Candidate c : candidates) {
            if (c.byKey() != byKey || c.match() == null) continue;
            String match = byKey ? c.match() : lower(c.match());
            best.merge(match, c, (a, b) -> order.compare(a, b) <= 0 ? a : b);
        }
        return best;
    }

    private static boolean fitsPrice(long price) {
        return price >= 0 && price <= Integer.MAX_VALUE;
    }

    private static String lower(String name) {
        return name.toLowerCase(Locale.ROOT);
    }
}
