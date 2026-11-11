package com.keevo.migration.adapter.out.source;

import com.keevo.migration.domain.model.BrefMonetisation;
import com.keevo.migration.domain.model.BrefMonetisation.AccountCredit;
import com.keevo.migration.domain.model.BrefMonetisation.AppleTransaction;
import com.keevo.migration.domain.model.BrefMonetisation.PlanPaymentRequest;
import com.keevo.migration.domain.model.BrefMonetisation.RevenueCatEvent;
import com.keevo.migration.domain.model.BrefMonetisation.SmsRecharge;
import com.keevo.migration.domain.model.BrefMonetisation.Subscription;
import com.keevo.migration.domain.model.BrefMonetisation.UsageCounter;
import com.keevo.migration.domain.model.SourceKind;
import com.keevo.migration.domain.port.out.BrefMonetisationSourcePort;
import org.springframework.stereotype.Component;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.Collection;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.UUID;
import java.util.function.Function;

/**
 * Story 24.5 — {@link BrefMonetisationSourcePort} over the restored Bref database, through the read-only pool and
 * transaction of {@link JdbcBrefSource} (one {@code REPEATABLE READ} read-only transaction for the whole section).
 *
 * <p>Cost per owner: one query telling which tables the Bref schema has (a table an older schema lacks — e.g.
 * {@code revenuecat_webhook_event} before develop — reads as empty), then one query per table. App Store transactions
 * and RevenueCat events use Bref's indexes ({@code apple_iap_transaction.ownerId}, {@code (subjectKey, …)}), the
 * counters the unique {@code (ownerId, metric, periodMonth)}; subscriptions, requests and recharges have no owner index
 * in Bref (a scan per owner, acceptable on a restored copy — the cutover over every owner is 24.9's). Personal columns
 * the import does not use are removed in SQL ({@code providerMetadata}, recharge {@code phone} / {@code cardNumber}).
 */
@Component
class JdbcBrefMonetisationSource implements BrefMonetisationSourcePort {

    static final String TABLES_SQL = "SELECT to_regclass('public.subscription') IS NOT NULL, "
            + "to_regclass('public.plan_payment_request') IS NOT NULL, "
            + "to_regclass('public.apple_iap_transaction') IS NOT NULL, "
            + "to_regclass('public.revenuecat_webhook_event') IS NOT NULL, "
            + "to_regclass('public.account_credit') IS NOT NULL, "
            + "to_regclass('public.sms_recharge_request') IS NOT NULL, "
            + "to_regclass('public.usage_counter') IS NOT NULL";
    static final String SUBSCRIPTIONS_SQL = "SELECT to_jsonb(s)::text FROM subscription s WHERE s.\"ownerId\" = ?::uuid "
            + "ORDER BY s.\"createdAt\", s.id";
    static final String REQUESTS_SQL = "SELECT (to_jsonb(r) - 'providerMetadata')::text FROM plan_payment_request r "
            + "WHERE r.\"ownerId\" = ?::uuid ORDER BY r.\"createdAt\", r.id";
    /** {@code ownerId} is text in Bref: the lower- and upper-case ids keep its index usable (as 24.3 for products). */
    static final String APPLE_SQL = "SELECT to_jsonb(a)::text FROM apple_iap_transaction a "
            + "WHERE a.\"ownerId\" = ANY (?::text[]) ORDER BY a.\"purchaseDate\", a.id";
    static final String EVENTS_SQL = "SELECT to_jsonb(e)::text FROM revenuecat_webhook_event e "
            + "WHERE e.\"subjectKey\" = ANY (?::text[]) ORDER BY e.\"eventTimestampMs\", e.id";
    static final String CREDIT_SQL = "SELECT to_jsonb(c)::text FROM account_credit c WHERE c.\"ownerId\" = ?::uuid";
    static final String RECHARGES_SQL = "SELECT (to_jsonb(r) - 'phone' - 'cardNumber')::text FROM sms_recharge_request r "
            + "WHERE r.\"userId\" = ANY (?::uuid[]) ORDER BY r.\"createdAt\", r.id";
    static final String COUNTERS_SQL = "SELECT to_jsonb(u)::text FROM usage_counter u WHERE u.\"ownerId\" = ?::uuid "
            + "ORDER BY u.metric, u.\"periodMonth\"";

    private final JdbcBrefSource source;

    JdbcBrefMonetisationSource(JdbcBrefSource source) {
        this.source = source;
    }

    @Override
    public SourceKind kind() {
        return SourceKind.DATABASE;
    }

    @Override
    public BrefMonetisation load(UUID ownerId, Collection<UUID> accountIds) {
        return source.readOnly(c -> read(c, ownerId, accountIds));
    }

    private BrefMonetisation read(Connection c, UUID ownerId, Collection<UUID> accountIds) throws SQLException {
        boolean[] has = tables(c);
        String owner = ownerId.toString();
        List<Subscription> subscriptions = has[0]
                ? rows(c, SUBSCRIPTIONS_SQL, ps -> ps.setString(1, owner), BrefMonetisationRows::subscription)
                : List.of();
        List<PlanPaymentRequest> requests = has[1]
                ? rows(c, REQUESTS_SQL, ps -> ps.setString(1, owner), BrefMonetisationRows::paymentRequest)
                : List.of();
        Object[] ownerKeys = {owner.toLowerCase(Locale.ROOT), owner.toUpperCase(Locale.ROOT)};
        List<AppleTransaction> apple = has[2]
                ? rows(c, APPLE_SQL, ps -> ps.setArray(1, c.createArrayOf("text", ownerKeys)),
                        BrefMonetisationRows::appleTransaction)
                : List.of();
        List<RevenueCatEvent> events = List.of();
        if (has[3]) {
            Object[] subjects = subjectKeys(owner, subscriptions, apple).toArray();
            events = rows(c, EVENTS_SQL, ps -> ps.setArray(1, c.createArrayOf("text", subjects)),
                    BrefMonetisationRows::revenueCatEvent);
        }
        AccountCredit credit = null;
        if (has[4]) {
            List<AccountCredit> credits = rows(c, CREDIT_SQL, ps -> ps.setString(1, owner),
                    BrefMonetisationRows::accountCredit);
            credit = credits.isEmpty() ? null : credits.get(0); // unique (ownerId)
        }
        List<SmsRecharge> recharges = List.of();
        if (has[5] && !accountIds.isEmpty()) {
            Object[] users = accountIds.toArray();
            recharges = rows(c, RECHARGES_SQL, ps -> ps.setArray(1, c.createArrayOf("uuid", users)),
                    BrefMonetisationRows::smsRecharge);
        }
        List<UsageCounter> counters = has[6]
                ? rows(c, COUNTERS_SQL, ps -> ps.setString(1, owner), BrefMonetisationRows::usageCounter)
                : List.of();
        return new BrefMonetisation(subscriptions, requests, apple, events, credit, recharges, counters);
    }

    /**
     * The RevenueCat subject keys of the owner (Bref: the original transaction id, else the app user id = the owner
     * id): the owner id and every original transaction id known for the owner.
     */
    static Set<String> subjectKeys(String owner, List<Subscription> subscriptions, List<AppleTransaction> apple) {
        Set<String> keys = new LinkedHashSet<>();
        keys.add(owner.toLowerCase(Locale.ROOT));
        keys.add(owner.toUpperCase(Locale.ROOT));
        subscriptions.stream().map(Subscription::appleOriginalTransactionId).filter(k -> k != null && !k.isBlank())
                .forEach(keys::add);
        apple.stream().map(AppleTransaction::originalTransactionId).filter(k -> k != null && !k.isBlank())
                .forEach(keys::add);
        return keys;
    }

    private static boolean[] tables(Connection c) throws SQLException {
        try (PreparedStatement ps = c.prepareStatement(TABLES_SQL); ResultSet rs = ps.executeQuery()) {
            rs.next();
            boolean[] has = new boolean[7];
            for (int i = 0; i < has.length; i++) {
                has[i] = rs.getBoolean(i + 1);
            }
            return has;
        }
    }

    private interface Binder {
        void bind(PreparedStatement ps) throws SQLException;
    }

    private <T> List<T> rows(Connection c, String sql, Binder binder,
                             Function<com.fasterxml.jackson.databind.JsonNode, T> mapper) throws SQLException {
        try (PreparedStatement ps = c.prepareStatement(sql)) {
            binder.bind(ps);
            ps.setFetchSize(JdbcBrefSource.FETCH_SIZE); // Story 24.9 — cursor reads
            try (ResultSet rs = ps.executeQuery()) {
                List<T> rows = new ArrayList<>();
                while (rs.next()) {
                    rows.add(mapper.apply(source.jsonRow(rs.getString(1))));
                }
                return rows;
            }
        }
    }
}
