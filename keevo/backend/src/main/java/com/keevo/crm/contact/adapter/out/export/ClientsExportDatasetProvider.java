package com.keevo.crm.contact.adapter.out.export;

import com.keevo.crm.contact.adapter.out.persistence.ClientPurchaseAggregate;
import com.keevo.shared.application.port.export.ExportColumn;
import com.keevo.shared.capability.revenue.RevenueQueryPort;
import com.keevo.shared.application.port.export.ExportColumnType;
import com.keevo.shared.application.port.export.ExportFilter;
import com.keevo.shared.infrastructure.export.KeysetCursor.KeyType;
import com.keevo.shared.infrastructure.export.NativeKeysetExportDataset;
import jakarta.persistence.EntityManager;
import org.springframework.stereotype.Component;

import java.util.Arrays;
import java.util.List;
import java.util.Map;

import static com.keevo.shared.infrastructure.export.ExportValues.frLabel;
import static com.keevo.shared.infrastructure.export.ExportValues.str;
import static com.keevo.shared.infrastructure.export.ExportValues.toBool;
import static com.keevo.shared.infrastructure.export.ExportValues.toInstant;
import static com.keevo.shared.infrastructure.export.ExportValues.toLocalDate;
import static com.keevo.shared.infrastructure.export.ExportValues.toLong;

/**
 * Export dataset {@code clients} (« Clients ») — Story v1s-18-2. Reference dataset, not
 * store-scoped (no store or period filter); archived clients included. Keyset {@code (created_at, id)}.
 *
 * <p>Story 20.14 — purchases read the single revenue source ({@link RevenueQueryPort}): the client's COMPLETED sales
 * and issued / paid invoices (a sale bridged to an automatic invoice counted once), credit notes deducted from the
 * total (they are not purchases: neither counted nor « dernier achat »).
 *
 * <p>Story 20.2 — Bref fields (type as its French label), the store by name (never its id, « Toutes les boutiques »
 * is never written: a legacy client without store has an empty cell), the marketing consent as Oui/Non + date and the
 * client's own opt-out date.
 */
@Component
public class ClientsExportDatasetProvider extends NativeKeysetExportDataset {

    static final String KEY = "clients";

    /** Story 20.2 — French label of {@code clients.client_type}. */
    static final Map<String, String> TYPES = Map.of("PERSON", "Particulier", "COMPANY", "Entreprise");

    private static final List<ExportColumn> COLUMNS = List.of(
            new ExportColumn("name", "Nom", ExportColumnType.TEXT),
            new ExportColumn("phone", "Téléphone", ExportColumnType.TEXT),
            new ExportColumn("email", "E-mail", ExportColumnType.TEXT),
            new ExportColumn("notes", "Notes", ExportColumnType.TEXT),
            new ExportColumn("salesCount", "Nombre d'achats", ExportColumnType.INTEGER),
            new ExportColumn("totalSpent", "Total des achats", ExportColumnType.AMOUNT_XAF),
            new ExportColumn("lastPurchaseAt", "Dernier achat", ExportColumnType.DATETIME),
            new ExportColumn("archived", "Archivé", ExportColumnType.BOOLEAN),
            new ExportColumn("createdAt", "Créé le", ExportColumnType.DATETIME),
            // Story 20.2
            new ExportColumn("clientType", "Type de client", ExportColumnType.TEXT),
            new ExportColumn("niu", "NIU", ExportColumnType.TEXT),
            new ExportColumn("rccm", "RCCM", ExportColumnType.TEXT),
            new ExportColumn("address", "Adresse", ExportColumnType.TEXT),
            new ExportColumn("contactName", "Personne à contacter", ExportColumnType.TEXT),
            new ExportColumn("website", "Site web", ExportColumnType.TEXT),
            new ExportColumn("birthDate", "Date de naissance", ExportColumnType.DATE),
            new ExportColumn("store", "Boutique", ExportColumnType.TEXT),
            new ExportColumn("marketingConsent", "Accord marketing", ExportColumnType.BOOLEAN),
            new ExportColumn("marketingConsentAt", "Accord marketing le", ExportColumnType.DATETIME),
            new ExportColumn("marketingOptOutAt", "Désinscrit le", ExportColumnType.DATETIME));

    private static final List<Key> KEYSET = List.of(
            new Key("c.created_at", KeyType.TIMESTAMP),
            new Key("c.id", KeyType.UUID));

    private final RevenueQueryPort revenue;

    public ClientsExportDatasetProvider(EntityManager em, RevenueQueryPort revenue) {
        super(em);
        this.revenue = revenue;
    }

    @Override public String moduleId() { return "crm"; } // Story 13.10a review: owning module
    @Override public String key() { return KEY; }
    @Override public String label() { return "Clients"; }
    @Override public List<ExportColumn> columns() { return COLUMNS; }

    @Override
    protected String selectList(ExportFilter filter) {
        return "c.name, c.phone, c.email, c.notes, agg." + ClientPurchaseAggregate.COUNT + ", agg."
                + ClientPurchaseAggregate.TOTAL + ", agg." + ClientPurchaseAggregate.LAST + ", c.archived, c.created_at,"
                + " c.client_type, c.niu, c.rccm, c.address, c.contact_name, c.website, c.birth_date, st.name,"
                + " c.marketing_consent_at, c.marketing_opt_out_at";
    }

    @Override
    protected String fromClause() {
        // Story 20.14 — the single revenue source (sales + invoices − credit notes, a bridged sale counted once),
        // resolved once per page inside the page's transaction; client_id = c.id is pushed into every branch
        // (idx_sales_client_id, idx_invoices_client, then idx_credit_notes_invoice)
        // Story 20.2 reprise — the aggregate shared with the client profile and the marketing audience
        return "clients c LEFT JOIN LATERAL (SELECT " + ClientPurchaseAggregate.select("r")
                + " FROM " + revenue.current().documents() + " r WHERE r.client_id = c.id) agg ON TRUE"
                + " LEFT JOIN stores st ON st.id = c.store_id";
    }

    /** Story 20.14 — counting needs no purchase totals: the plain clients join (same rows). */
    @Override
    protected String countFromClause() {
        return "clients c LEFT JOIN stores st ON st.id = c.store_id";
    }

    @Override
    protected List<Key> keyset() {
        return KEYSET;
    }

    @Override
    protected List<Object> mapRow(Object[] r) {
        return Arrays.asList(str(r[0]), str(r[1]), str(r[2]), str(r[3]), toLong(r[4]), toLong(r[5]),
                toInstant(r[6]), toBool(r[7]), toInstant(r[8]),
                frLabel(TYPES, r[9]), str(r[10]), str(r[11]), str(r[12]), str(r[13]), str(r[14]),
                toLocalDate(r[15]), str(r[16]), r[17] != null, toInstant(r[17]), toInstant(r[18]));
    }
}
