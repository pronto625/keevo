package com.keevo.subscription.offer.adapter.out.persistence;

import com.keevo.shared.infrastructure.persistence.TenantSchema;
import com.keevo.subscription.offer.domain.model.BillingCycle;
import com.keevo.subscription.offer.domain.model.Discount;
import com.keevo.subscription.offer.domain.model.DiscountScope;
import com.keevo.subscription.offer.domain.model.OfferChange;
import com.keevo.subscription.offer.domain.model.OfferEntity;
import com.keevo.subscription.offer.domain.model.PlanPrice;
import com.keevo.subscription.offer.domain.model.PriceChange;
import com.keevo.subscription.offer.domain.model.TenantPlanRef;
import com.keevo.subscription.offer.domain.port.out.OfferStore;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.stereotype.Repository;

import java.sql.Array;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.sql.Types;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collection;
import java.util.HashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;

/**
 * Story 21.10 — {@link OfferStore} on {@code public.billing_cycles}, {@code plan_prices}, {@code plan_price_history},
 * {@code discounts}, {@code offer_change_log} (V60), plus the set-based reads and writes the catalogue needs on
 * {@code tenants}, {@code plans} and the plan profiles. Every method is one statement (the profile copy three), on the
 * caller's transaction.
 */
@Repository
public class JdbcOfferStore implements OfferStore {

    private static final String SYSTEM_TENANT = "00000000-0000-0000-0000-000000000000";
    private static final String ACTOR_NAME = "NULLIF(btrim(concat_ws(' ', u.first_name, u.last_name)), '')";

    static final String CYCLES_SQL = "SELECT code, label, months, sort_order, archived_at FROM public.billing_cycles "
            + "ORDER BY sort_order, code";
    static final String CYCLE_SQL = "SELECT code, label, months, sort_order, archived_at FROM public.billing_cycles "
            + "WHERE code = ?";
    static final String INSERT_CYCLE_SQL = "INSERT INTO public.billing_cycles (code, label, months, sort_order) "
            + "VALUES (?, ?, ?, ?) ON CONFLICT (code) DO NOTHING";
    static final String UPDATE_CYCLE_SQL = "UPDATE public.billing_cycles SET label = ?, months = ?, sort_order = ?, "
            + "archived_at = ?, updated_at = now() WHERE code = ?";

    static final String PRICES_SQL = "SELECT plan_code, cycle_code, amount_xaf, updated_at FROM public.plan_prices";
    static final String CYCLE_IN_USE_SQL = "SELECT EXISTS (SELECT 1 FROM public.plan_prices WHERE cycle_code = ?) "
            + "OR EXISTS (SELECT 1 FROM public.tenant_payments WHERE cycle_code = ?) "
            // Story 21.2 — an App Store product has a fixed Apple duration (index ix_store_products_cycle)
            + "OR EXISTS (SELECT 1 FROM public.store_products WHERE cycle_code = ?)";
    static final String LOCK_PLAN_SQL = "SELECT code FROM public.plans WHERE code = ? FOR UPDATE";
    static final String INSERT_PLAN_SQL = "INSERT INTO public.plans (code, label, description, sort_order, visible, "
            + "kind, archived_at) VALUES (?, ?, ?, ?, ?, ?, ?) ON CONFLICT (code) DO NOTHING";
    static final String LOCK_PRICE_SQL = "SELECT plan_code, cycle_code, amount_xaf, updated_at FROM public.plan_prices "
            + "WHERE plan_code = ? "
            + "AND cycle_code = ? FOR UPDATE";
    static final String UPSERT_PRICE_SQL = "INSERT INTO public.plan_prices (plan_code, cycle_code, amount_xaf, "
            + "updated_by) VALUES (?, ?, ?, ?) ON CONFLICT (plan_code, cycle_code) DO UPDATE SET "
            + "amount_xaf = EXCLUDED.amount_xaf, updated_by = EXCLUDED.updated_by, updated_at = now()";
    static final String DELETE_PRICE_SQL = "DELETE FROM public.plan_prices WHERE plan_code = ? AND cycle_code = ?";
    static final String INSERT_HISTORY_SQL = "INSERT INTO public.plan_price_history (plan_code, cycle_code, "
            + "old_amount_xaf, new_amount_xaf, changed_by) VALUES (?, ?, ?, ?, ?)";
    static final String HISTORY_SQL = "SELECT h.plan_code, h.cycle_code, h.old_amount_xaf, h.new_amount_xaf, "
            + "h.changed_by, h.changed_at, " + ACTOR_NAME + " AS actor_name FROM public.plan_price_history h "
            + "LEFT JOIN public.users u ON u.id = h.changed_by";

    private static final String DISCOUNT_COLUMNS = "d.id, d.scope, d.label, d.percent_off, d.amount_off_xaf, "
            + "d.plan_codes, d.cycle_codes, d.starts_at, d.ends_at, d.tenant_id, d.promo_code, d.archived_at, "
            + "d.created_at, d.max_redemptions, d.redemption_count";
    static final String DISCOUNTS_SQL = "SELECT " + DISCOUNT_COLUMNS + " FROM public.discounts d "
            + "ORDER BY d.created_at DESC, d.id LIMIT ?";
    static final String LIVE_DISCOUNTS_SQL = "SELECT " + DISCOUNT_COLUMNS + " FROM public.discounts d "
            + "WHERE d.archived_at IS NULL ORDER BY d.created_at DESC, d.id LIMIT ?";
    static final String DISCOUNT_SQL = "SELECT " + DISCOUNT_COLUMNS + " FROM public.discounts d WHERE d.id = ?";
    /** Parameters: now, now, tenant id (nullable), promo code (nullable). */
    static final String CANDIDATES_SQL = "SELECT " + DISCOUNT_COLUMNS + " FROM public.discounts d "
            + "WHERE d.archived_at IS NULL AND (d.starts_at IS NULL OR d.starts_at <= ?) "
            + "AND (d.ends_at IS NULL OR d.ends_at > ?) "
            + "AND (d.max_redemptions IS NULL OR d.redemption_count < d.max_redemptions) "
            + "AND (d.scope = 'PROMOTION' "
            + "OR (d.scope = 'TENANT' AND d.tenant_id = CAST(? AS uuid)) "
            + "OR (d.scope = 'PROMO_CODE' AND upper(d.promo_code) = upper(CAST(? AS text))))";
    static final String PROMO_TAKEN_SQL = "SELECT EXISTS (SELECT 1 FROM public.discounts WHERE promo_code IS NOT NULL "
            + "AND archived_at IS NULL AND upper(promo_code) = upper(?) AND id IS DISTINCT FROM CAST(? AS uuid))";
    static final String INSERT_DISCOUNT_SQL = "INSERT INTO public.discounts (scope, label, percent_off, amount_off_xaf, "
            + "plan_codes, cycle_codes, starts_at, ends_at, tenant_id, promo_code, archived_at, max_redemptions, "
            + "created_by, updated_by) VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?) RETURNING id";
    static final String UPDATE_DISCOUNT_SQL = "UPDATE public.discounts SET scope = ?, label = ?, percent_off = ?, "
            + "amount_off_xaf = ?, plan_codes = ?, cycle_codes = ?, starts_at = ?, ends_at = ?, tenant_id = ?, "
            + "promo_code = ?, archived_at = ?, max_redemptions = ?, updated_by = ?, updated_at = now() WHERE id = ?";
    /** Story 21.1 — one confirmed use (never touched by the CRUD). */
    static final String REDEMPTION_SQL =
            "UPDATE public.discounts SET redemption_count = redemption_count + 1 WHERE id = ?";
    /** Story 21.1 — same, only while the use cap is not reached (atomic check-and-take). */
    static final String REDEMPTION_WITHIN_CAP_SQL = REDEMPTION_SQL
            + " AND (max_redemptions IS NULL OR redemption_count < max_redemptions)";

    static final String JOURNAL_SQL = "INSERT INTO public.offer_change_log (entity, entity_key, summary, actor_id) "
            + "VALUES (?, ?, ?, ?)";
    private static final String CHANGES_SELECT = "SELECT l.id, l.entity, l.entity_key, l.summary, l.actor_id, "
            + "l.occurred_at, " + ACTOR_NAME + " AS actor_name FROM public.offer_change_log l "
            + "LEFT JOIN public.users u ON u.id = l.actor_id ";
    static final String CHANGES_SQL = CHANGES_SELECT + "ORDER BY l.occurred_at DESC, l.id DESC LIMIT ?";
    static final String CHANGES_BEFORE_SQL = CHANGES_SELECT + "WHERE l.occurred_at < ? "
            + "ORDER BY l.occurred_at DESC, l.id DESC LIMIT ?";
    /** Keyset on the full sort key: rows sharing the boundary instant are never skipped. */
    static final String CHANGES_AFTER_CURSOR_SQL = CHANGES_SELECT + "WHERE (l.occurred_at, l.id) < (?, ?) "
            + "ORDER BY l.occurred_at DESC, l.id DESC LIMIT ?";

    private static final String LIVE_TENANT = "status <> 'DELETED' AND id <> '" + SYSTEM_TENANT + "'::uuid";
    static final String TENANT_COUNTS_SQL = "SELECT plan_type, count(*) AS n FROM public.tenants WHERE " + LIVE_TENANT
            + " GROUP BY plan_type";
    static final String TENANTS_ON_PLAN_SQL = "SELECT count(*) FROM public.tenants WHERE plan_type = ? AND "
            + LIVE_TENANT;
    static final String TENANT_SQL = "SELECT id, plan_type FROM public.tenants WHERE id = ? AND " + LIVE_TENANT;
    static final String TENANT_BY_SCHEMA_SQL = "SELECT id, plan_type FROM public.tenants WHERE schema_name = ? AND "
            + LIVE_TENANT;
    static final String TENANT_NAMES_SQL = "SELECT id, name FROM public.tenants WHERE id = ANY (?)";

    static final String COPY_PROFILE_SQL = "INSERT INTO public.plan_profiles (plan_type, max_stores, max_products, "
            + "max_employees, max_employees_per_store) SELECT ?, max_stores, max_products, max_employees, "
            + "max_employees_per_store FROM public.plan_profiles WHERE plan_type = ? ON CONFLICT (plan_type) DO NOTHING";
    static final String COPY_MODULES_SQL = "INSERT INTO public.profile_modules (plan_type, module_id, granted) "
            + "SELECT ?, module_id, granted FROM public.profile_modules WHERE plan_type = ? "
            + "ON CONFLICT (plan_type, module_id) DO NOTHING";
    static final String COPY_QUOTAS_SQL = "INSERT INTO public.plan_quotas (plan_type, metric, period, quota_limit) "
            + "SELECT ?, metric, period, quota_limit FROM public.plan_quotas WHERE plan_type = ? "
            + "ON CONFLICT (plan_type, metric, period) DO NOTHING";
    static final String REORDER_SQL = "UPDATE public.plans p SET sort_order = o.ord * 10, updated_at = now() "
            + "FROM unnest(?) WITH ORDINALITY AS o (code, ord) WHERE p.code = o.code";

    private final JdbcTemplate jdbc;

    public JdbcOfferStore(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    // ── cycles ────────────────────────────────────────────────────────────────

    private static final RowMapper<BillingCycle> CYCLE = (rs, i) -> new BillingCycle(rs.getString("code"),
            rs.getString("label"), rs.getInt("months"), rs.getInt("sort_order"), instant(rs, "archived_at"));

    @Override
    public List<BillingCycle> cycles() {
        return jdbc.query(CYCLES_SQL, CYCLE);
    }

    @Override
    public Optional<BillingCycle> cycle(String code) {
        return jdbc.query(CYCLE_SQL, CYCLE, code).stream().findFirst();
    }

    @Override
    public boolean insertCycle(BillingCycle c) {
        return jdbc.update(INSERT_CYCLE_SQL, c.code(), c.label(), c.months(), c.sortOrder()) == 1;
    }

    @Override
    public void updateCycle(BillingCycle c) {
        jdbc.update(UPDATE_CYCLE_SQL, c.label(), c.months(), c.sortOrder(), ts(c.archivedAt()), c.code());
    }

    @Override
    public boolean cycleInUse(String code) {
        return Boolean.TRUE.equals(jdbc.queryForObject(CYCLE_IN_USE_SQL, Boolean.class, code, code, code));
    }

    // ── prices ────────────────────────────────────────────────────────────────

    private static final RowMapper<PlanPrice> PRICE = (rs, i) -> new PlanPrice(rs.getString("plan_code"),
            rs.getString("cycle_code"), rs.getLong("amount_xaf"), instant(rs, "updated_at"));

    @Override
    public List<PlanPrice> prices() {
        return jdbc.query(PRICES_SQL, PRICE);
    }

    @Override
    public List<PlanPrice> prices(String planCode) {
        return jdbc.query(PRICES_SQL + " WHERE plan_code = ?", PRICE, planCode);
    }

    @Override
    public void lockPlan(String planCode) {
        jdbc.queryForList(LOCK_PLAN_SQL, String.class, planCode);
    }

    @Override
    public Optional<PlanPrice> price(String planCode, String cycleCode) {
        return jdbc.query(PRICES_SQL + " WHERE plan_code = ? AND cycle_code = ?", PRICE, planCode, cycleCode)
                .stream().findFirst();
    }

    @Override
    public Optional<PlanPrice> lockPrice(String planCode, String cycleCode) {
        return jdbc.query(LOCK_PRICE_SQL, PRICE, planCode, cycleCode).stream().findFirst();
    }

    @Override
    public void upsertPrice(String planCode, String cycleCode, long amountXaf, UUID actorId) {
        jdbc.update(UPSERT_PRICE_SQL, planCode, cycleCode, Math.toIntExact(amountXaf), actorId);
    }

    @Override
    public void deletePrice(String planCode, String cycleCode) {
        jdbc.update(DELETE_PRICE_SQL, planCode, cycleCode);
    }

    @Override
    public void recordPriceChange(String planCode, String cycleCode, Long old, Long next, UUID actorId) {
        jdbc.update(INSERT_HISTORY_SQL, ps -> {
            ps.setString(1, planCode);
            ps.setString(2, cycleCode);
            setLong(ps, 3, old);
            setLong(ps, 4, next);
            ps.setObject(5, actorId, Types.OTHER);
        });
    }

    @Override
    public List<PriceChange> priceHistory(String planCode, String cycleCode, int limit) {
        StringBuilder sql = new StringBuilder(HISTORY_SQL);
        List<Object> args = new ArrayList<>();
        if (planCode != null) {
            sql.append(" WHERE h.plan_code = ?");
            args.add(planCode);
        }
        if (cycleCode != null) {
            sql.append(args.isEmpty() ? " WHERE" : " AND").append(" h.cycle_code = ?");
            args.add(cycleCode);
        }
        sql.append(" ORDER BY h.changed_at DESC, h.id DESC LIMIT ?");
        args.add(limit);
        return jdbc.query(sql.toString(), (rs, i) -> new PriceChange(rs.getString("plan_code"),
                rs.getString("cycle_code"), nullableLong(rs, "old_amount_xaf"), nullableLong(rs, "new_amount_xaf"),
                uuid(rs, "changed_by"), rs.getString("actor_name"), instant(rs, "changed_at")), args.toArray());
    }

    // ── discounts ─────────────────────────────────────────────────────────────

    private static final RowMapper<Discount> DISCOUNT = (rs, i) -> {
        long amount = rs.getLong("amount_off_xaf");
        Long amountOff = rs.wasNull() ? null : amount;
        return new Discount(uuid(rs, "id"), DiscountScope.valueOf(rs.getString("scope")), rs.getString("label"),
                rs.getBigDecimal("percent_off"), amountOff, codes(rs.getArray("plan_codes")),
                codes(rs.getArray("cycle_codes")), instant(rs, "starts_at"), instant(rs, "ends_at"),
                uuid(rs, "tenant_id"), rs.getString("promo_code"), instant(rs, "archived_at"),
                instant(rs, "created_at"), (Integer) rs.getObject("max_redemptions"), rs.getInt("redemption_count"));
    };

    @Override
    public List<Discount> discounts(boolean includeArchived, int limit) {
        return jdbc.query(includeArchived ? DISCOUNTS_SQL : LIVE_DISCOUNTS_SQL, DISCOUNT, limit);
    }

    @Override
    public Optional<Discount> discount(UUID id) {
        return jdbc.query(DISCOUNT_SQL, DISCOUNT, id).stream().findFirst();
    }

    @Override
    public List<Discount> liveCandidates(UUID tenantId, String promoCode, Instant now) {
        Timestamp at = Timestamp.from(now);
        return jdbc.query(CANDIDATES_SQL, ps -> {
            ps.setTimestamp(1, at);
            ps.setTimestamp(2, at);
            ps.setObject(3, tenantId, Types.OTHER);
            if (promoCode == null) {
                ps.setNull(4, Types.VARCHAR);
            } else {
                ps.setString(4, promoCode);
            }
        }, DISCOUNT);
    }

    @Override
    public boolean promoCodeTaken(String promoCode, UUID exceptId) {
        return Boolean.TRUE.equals(jdbc.query(PROMO_TAKEN_SQL, ps -> {
            ps.setString(1, promoCode);
            ps.setObject(2, exceptId, Types.OTHER);
        }, rs -> rs.next() && rs.getBoolean(1)));
    }

    @Override
    public UUID insertDiscount(Discount d, UUID actorId) {
        return jdbc.query(con -> {
            var ps = con.prepareStatement(INSERT_DISCOUNT_SQL);
            int i = bindDiscount(ps, d, con);
            ps.setObject(i++, actorId, Types.OTHER);
            ps.setObject(i, actorId, Types.OTHER);
            return ps;
        }, rs -> {
            rs.next();
            return uuid(rs, "id");
        });
    }

    @Override
    public void updateDiscount(Discount d, UUID actorId) {
        jdbc.update(con -> {
            var ps = con.prepareStatement(UPDATE_DISCOUNT_SQL);
            int i = bindDiscount(ps, d, con);
            ps.setObject(i++, actorId, Types.OTHER);
            ps.setObject(i, d.id(), Types.OTHER);
            return ps;
        });
    }

    @Override
    public boolean recordRedemption(UUID discountId, boolean withinCap) {
        return jdbc.update(withinCap ? REDEMPTION_WITHIN_CAP_SQL : REDEMPTION_SQL,
                ps -> ps.setObject(1, discountId, Types.OTHER)) == 1;
    }

    /** Binds the 12 data columns of a discount; returns the next parameter index. */
    private static int bindDiscount(java.sql.PreparedStatement ps, Discount d, java.sql.Connection con)
            throws SQLException {
        int i = 1;
        ps.setString(i++, d.scope().name());
        ps.setString(i++, d.label());
        if (d.percentOff() == null) {
            ps.setNull(i++, Types.NUMERIC);
        } else {
            ps.setBigDecimal(i++, d.percentOff());
        }
        if (d.amountOffXaf() == null) {
            ps.setNull(i++, Types.INTEGER);
        } else {
            ps.setInt(i++, Math.toIntExact(d.amountOffXaf()));
        }
        ps.setArray(i++, con.createArrayOf("varchar", d.planCodes().stream().sorted().toArray()));
        ps.setArray(i++, con.createArrayOf("varchar", d.cycleCodes().stream().sorted().toArray()));
        ps.setTimestamp(i++, ts(d.startsAt()));
        ps.setTimestamp(i++, ts(d.endsAt()));
        ps.setObject(i++, d.tenantId(), Types.OTHER);
        ps.setString(i++, d.promoCode());
        ps.setTimestamp(i++, ts(d.archivedAt()));
        if (d.maxRedemptions() == null) {
            ps.setNull(i++, Types.INTEGER);
        } else {
            ps.setInt(i++, d.maxRedemptions());
        }
        return i;
    }

    // ── journal ───────────────────────────────────────────────────────────────

    @Override
    public void journal(OfferEntity entity, String entityKey, String summary, UUID actorId) {
        jdbc.update(JOURNAL_SQL, ps -> {
            ps.setString(1, entity.name());
            ps.setString(2, entityKey);
            ps.setString(3, summary.length() > 500 ? summary.substring(0, 500) : summary);
            ps.setObject(4, actorId, Types.OTHER);
        });
    }

    @Override
    public List<OfferChange> changes(Instant before, UUID beforeId, int limit) {
        RowMapper<OfferChange> mapper = (rs, i) -> new OfferChange(uuid(rs, "id"),
                OfferEntity.valueOf(rs.getString("entity")), rs.getString("entity_key"), rs.getString("summary"),
                uuid(rs, "actor_id"), rs.getString("actor_name"), instant(rs, "occurred_at"));
        if (before == null) {
            return jdbc.query(CHANGES_SQL, mapper, limit);
        }
        return beforeId == null
                ? jdbc.query(CHANGES_BEFORE_SQL, mapper, Timestamp.from(before), limit)
                : jdbc.query(CHANGES_AFTER_CURSOR_SQL, mapper, Timestamp.from(before), beforeId, limit);
    }

    // ── plans and companies ───────────────────────────────────────────────────

    @Override
    public boolean insertPlan(com.keevo.shared.capability.entitlement.PlanDefinition p) {
        return jdbc.update(INSERT_PLAN_SQL, p.code().code(), p.label(), p.description(), p.sortOrder(), p.visible(),
                p.kind().name(), ts(p.archivedAt())) == 1;
    }

    @Override
    public Map<String, Long> tenantCountsByPlan() {
        Map<String, Long> counts = new HashMap<>();
        jdbc.query(TENANT_COUNTS_SQL, rs -> {
            counts.put(rs.getString("plan_type"), rs.getLong("n"));
        });
        return counts;
    }

    @Override
    public long tenantsOnPlan(String planCode) {
        Long n = jdbc.queryForObject(TENANTS_ON_PLAN_SQL, Long.class, planCode);
        return n == null ? 0 : n;
    }

    @Override
    public void copyProfile(String source, String target) {
        jdbc.update(COPY_PROFILE_SQL, target, source);
        jdbc.update(COPY_MODULES_SQL, target, source);
        jdbc.update(COPY_QUOTAS_SQL, target, source);
    }

    @Override
    public void reorderPlans(List<String> codes) {
        jdbc.update(con -> {
            var ps = con.prepareStatement(REORDER_SQL);
            ps.setArray(1, con.createArrayOf("varchar", codes.toArray()));
            return ps;
        });
    }

    private static final RowMapper<TenantPlanRef> TENANT = (rs, i) -> new TenantPlanRef(uuid(rs, "id"),
            rs.getString("plan_type"));

    @Override
    public Optional<TenantPlanRef> tenant(UUID tenantId) {
        return jdbc.query(TENANT_SQL, TENANT, tenantId).stream().findFirst();
    }

    @Override
    public Optional<TenantPlanRef> tenantBySchema(String schemaName) {
        if (!TenantSchema.isValid(schemaName)) {
            return Optional.empty();
        }
        return jdbc.query(TENANT_BY_SCHEMA_SQL, TENANT, schemaName).stream().findFirst();
    }

    @Override
    public Map<UUID, String> tenantNames(Collection<UUID> ids) {
        Map<UUID, String> names = new HashMap<>();
        if (ids.isEmpty()) {
            return names;
        }
        jdbc.query(con -> {
            var ps = con.prepareStatement(TENANT_NAMES_SQL);
            ps.setArray(1, con.createArrayOf("uuid", ids.toArray()));
            return ps;
        }, rs -> {
            names.put(uuid(rs, "id"), rs.getString("name"));
        });
        return names;
    }

    // ── mapping helpers ───────────────────────────────────────────────────────

    private static Instant instant(ResultSet rs, String column) throws SQLException {
        Timestamp t = rs.getTimestamp(column);
        return t == null ? null : t.toInstant();
    }

    private static Timestamp ts(Instant i) {
        return i == null ? null : Timestamp.from(i);
    }

    private static UUID uuid(ResultSet rs, String column) throws SQLException {
        String v = rs.getString(column);
        return v == null ? null : UUID.fromString(v);
    }

    private static Long nullableLong(ResultSet rs, String column) throws SQLException {
        long v = rs.getLong(column);
        return rs.wasNull() ? null : v;
    }

    private static void setLong(java.sql.PreparedStatement ps, int i, Long v) throws SQLException {
        if (v == null) {
            ps.setNull(i, Types.INTEGER);
        } else {
            ps.setInt(i, Math.toIntExact(v));
        }
    }

    private static Set<String> codes(Array array) throws SQLException {
        if (array == null) {
            return Set.of();
        }
        Object[] values = (Object[]) array.getArray();
        Set<String> codes = new LinkedHashSet<>();
        Arrays.stream(values).map(String::valueOf).forEach(codes::add);
        return codes;
    }
}
