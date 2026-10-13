package com.keevo.reporting.export;

import com.keevo.shared.application.port.AuditPort;
import com.keevo.shared.application.port.export.ExportColumn;
import com.keevo.shared.application.port.export.ExportDatasetProvider;
import jakarta.persistence.EntityManager;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.config.BeanDefinition;
import org.springframework.context.annotation.ClassPathScanningCandidateComponentProvider;
import org.springframework.core.type.filter.AssignableTypeFilter;
import org.springframework.stereotype.Component;

import java.lang.reflect.Constructor;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;
import java.util.stream.Collectors;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;

/**
 * Story v1s-18-2 — golden schema of the export datasets. This table IS the documented, stable
 * column contract shared with the on-device export (18-5): keys, French labels, types and filter
 * metadata. Changing a column means changing this test on purpose (and the app's CSV schema).
 *
 * <p>Every {@code @Component} {@link ExportDatasetProvider} of {@code com.keevo} is discovered by
 * classpath scanning, so a new provider without a golden entry fails here.
 */
@DisplayName("Export dataset catalogue — golden schema (v1s-18-2)")
class ExportDatasetCatalogTest {

    /** key → « label | store | period | criteria » then one « key:Label:TYPE » per column. */
    private static final Map<String, String> GOLDEN = new LinkedHashMap<>();

    static {
        GOLDEN.put("products", """
                Produits | store=true | period=false | criteria=
                sku:Référence:TEXT
                name:Nom:TEXT
                category:Catégorie:TEXT
                status:Statut:TEXT
                salePrice:Prix de vente:AMOUNT_XAF
                buyPrice:Prix d'achat:AMOUNT_XAF
                transportCost:Frais de transport:AMOUNT_XAF
                minimumThreshold:Seuil minimum:INTEGER
                stockQuantity:Stock:INTEGER
                suppliers:Fournisseurs:TEXT
                archived:Archivé:BOOLEAN
                createdAt:Créé le:DATETIME
                updatedAt:Modifié le:DATETIME""");
        GOLDEN.put("categories", """
                Catégories | store=false | period=false | criteria=
                name:Nom:TEXT
                parent:Catégorie parente:TEXT
                active:Active:BOOLEAN
                custom:Personnalisée:BOOLEAN
                createdAt:Créée le:DATETIME""");
        GOLDEN.put("stock_levels", """
                Stocks | store=true | period=false | criteria=
                store:Boutique:TEXT
                sku:Référence:TEXT
                product:Produit:TEXT
                category:Catégorie:TEXT
                quantity:Quantité:INTEGER
                minimumThreshold:Seuil minimum:INTEGER
                stockStatus:État:TEXT
                buyPrice:Prix d'achat:AMOUNT_XAF
                stockValue:Valeur du stock:AMOUNT_XAF
                productArchived:Produit archivé:BOOLEAN
                updatedAt:Mis à jour le:DATETIME""");
        GOLDEN.put("stock_movements", """
                Mouvements de stock | store=true | period=true | criteria=
                occurredAt:Date:DATETIME
                store:Boutique:TEXT
                sku:Référence:TEXT
                product:Produit:TEXT
                type:Type:TEXT
                quantityBefore:Quantité avant:INTEGER
                quantityChange:Variation:INTEGER
                quantityAfter:Quantité après:INTEGER
                actor:Auteur:TEXT
                notes:Notes:TEXT""");
        GOLDEN.put("stock_transfers", """
                Transferts | store=true | period=true | criteria=
                occurredAt:Date:DATETIME
                sourceStore:Boutique d'origine:TEXT
                destinationStore:Boutique de destination:TEXT
                sku:Référence:TEXT
                product:Produit:TEXT
                quantity:Quantité:INTEGER
                status:Statut:TEXT
                actor:Auteur:TEXT
                notes:Notes:TEXT""");
        GOLDEN.put("sales", """
                Ventes | store=true | period=true | criteria=
                reference:Référence de la vente:TEXT
                occurredAt:Date:DATETIME
                store:Boutique:TEXT
                employee:Vendeur:TEXT
                client:Client:TEXT
                status:Statut:TEXT
                paymentMode:Mode de paiement:TEXT
                itemCount:Articles:INTEGER
                discount:Remise:AMOUNT_XAF
                total:Montant total:AMOUNT_XAF""");
        GOLDEN.put("sale_items", """
                Lignes de vente | store=true | period=true | criteria=
                sale:Vente:TEXT
                occurredAt:Date:DATETIME
                store:Boutique:TEXT
                employee:Vendeur:TEXT
                saleStatus:Statut de la vente:TEXT
                sku:Référence:TEXT
                product:Produit:TEXT
                quantity:Quantité:INTEGER
                cataloguePrice:Prix catalogue:AMOUNT_XAF
                unitPrice:Prix appliqué:AMOUNT_XAF
                subtotal:Sous-total:AMOUNT_XAF""");
        GOLDEN.put("day_closures", """
                Clôtures | store=true | period=true | criteria=
                closureDate:Jour:DATE
                closedAt:Clôturée le:DATETIME
                store:Boutique:TEXT
                automatic:Automatique:BOOLEAN
                actor:Clôturée par:TEXT
                salesCount:Nombre de ventes:INTEGER
                revenue:Chiffre d'affaires:AMOUNT_XAF
                cashAmount:Espèces:AMOUNT_XAF
                momoAmount:Mobile Money:AMOUNT_XAF
                pendingSalesCount:Ventes en attente:INTEGER
                pendingSalesTotal:Montant en attente:AMOUNT_XAF
                topProduct:Meilleur produit:TEXT
                topProductQuantity:Quantité meilleur produit:INTEGER""");
        GOLDEN.put("inventory_sessions", """
                Inventaires | store=true | period=true | criteria=
                startedAt:Commencé le:DATETIME
                store:Boutique:TEXT
                scope:Périmètre:TEXT
                status:Statut:TEXT
                startedBy:Commencé par:TEXT
                completedAt:Validé le:DATETIME
                cancelledAt:Annulé le:DATETIME
                countedProducts:Produits comptés:INTEGER
                gapProducts:Produits en écart:INTEGER
                totalGap:Écart total:INTEGER""");
        GOLDEN.put("inventory_counts", """
                Comptages d'inventaire | store=true | period=true | criteria=
                sessionStartedAt:Date de l'inventaire:DATETIME
                store:Boutique:TEXT
                sessionStatus:Statut de l'inventaire:TEXT
                product:Produit:TEXT
                variant:Variante:TEXT
                theoretical:Stock théorique:INTEGER
                physical:Stock compté:INTEGER
                gap:Écart:INTEGER
                countedAt:Compté le:DATETIME
                countedBy:Compté par:TEXT""");
        GOLDEN.put("clients", """
                Clients | store=false | period=false | criteria=
                name:Nom:TEXT
                phone:Téléphone:TEXT
                email:E-mail:TEXT
                notes:Notes:TEXT
                salesCount:Nombre d'achats:INTEGER
                totalSpent:Total des achats:AMOUNT_XAF
                lastPurchaseAt:Dernier achat:DATETIME
                archived:Archivé:BOOLEAN
                createdAt:Créé le:DATETIME""");
        GOLDEN.put("suppliers", """
                Fournisseurs | store=false | period=false | criteria=
                name:Nom:TEXT
                phone:Téléphone:TEXT
                email:E-mail:TEXT
                productCount:Produits fournis:INTEGER
                archived:Archivé:BOOLEAN
                createdAt:Créé le:DATETIME""");
        GOLDEN.put("employees", """
                Équipe | store=true | period=false | criteria=
                firstName:Prénom:TEXT
                lastName:Nom:TEXT
                phone:Téléphone:TEXT
                store:Boutique:TEXT
                status:Statut:TEXT
                createdAt:Ajouté le:DATETIME""");
        GOLDEN.put("stores", """
                Boutiques | store=true | period=false | criteria=
                name:Nom:TEXT
                type:Type:TEXT
                address:Adresse:TEXT
                phone:Téléphone:TEXT
                active:Active:BOOLEAN
                createdAt:Créée le:DATETIME""");
        GOLDEN.put("reports", """
                Rapports | store=true | period=true | criteria=
                reportDate:Date du rapport:DATE
                type:Type:TEXT
                store:Boutique:TEXT
                revenue:Chiffre d'affaires:AMOUNT_XAF
                salesCount:Nombre de ventes:INTEGER
                automatic:Automatique:BOOLEAN
                actor:Généré par:TEXT
                deliveryStatus:Envoi:TEXT
                createdAt:Généré le:DATETIME
                content:Contenu:TEXT""");
        GOLDEN.put("audit_log", """
                Journal d'audit | store=true | period=true | criteria=action,entityId,entityType,q,userId
                occurredAt:Date:DATETIME
                actor:Auteur:TEXT
                actorRole:Rôle:TEXT
                action:Action:TEXT
                entityType:Type d'objet:TEXT
                entityLabel:Objet:TEXT
                store:Boutique:TEXT
                source:Source:TEXT
                changes:Changements:TEXT
                details:Détails:TEXT""");
    }

    /** Dataset keys in the documented order (also the order of the 18-5 contract file). */
    static List<String> goldenKeys() {
        return List.copyOf(GOLDEN.keySet());
    }

    /** Instantiates every {@code @Component} provider of the main code with mocked collaborators. */
    public static List<ExportDatasetProvider> allProviders() throws Exception {
        ClassPathScanningCandidateComponentProvider scanner = new ClassPathScanningCandidateComponentProvider(false);
        scanner.addIncludeFilter(new AssignableTypeFilter(ExportDatasetProvider.class));
        List<ExportDatasetProvider> providers = new ArrayList<>();
        for (BeanDefinition bd : scanner.findCandidateComponents("com.keevo")) {
            Class<?> type = Class.forName(bd.getBeanClassName());
            if (!type.isAnnotationPresent(Component.class)) continue; // test doubles
            Constructor<?> ctor = type.getConstructors()[0];
            Object[] args = new Object[ctor.getParameterCount()];
            for (int i = 0; i < args.length; i++) {
                Class<?> p = ctor.getParameterTypes()[i];
                if (p != EntityManager.class && p != AuditPort.class) {
                    throw new IllegalStateException(type + " has an unexpected dependency " + p);
                }
                args[i] = mock(p);
            }
            providers.add((ExportDatasetProvider) ctor.newInstance(args));
        }
        return providers;
    }

    static String describe(ExportDatasetProvider p) {
        String head = p.label() + " | store=" + p.supportsStoreFilter() + " | period=" + p.supportsPeriodFilter()
                + " | criteria=" + p.criteriaKeys().stream().sorted().collect(Collectors.joining(","));
        StringBuilder sb = new StringBuilder(head);
        for (ExportColumn c : p.columns()) {
            sb.append('\n').append(c.key()).append(':').append(c.label()).append(':').append(c.type());
        }
        return sb.toString();
    }

    @Test
    @DisplayName("every provider bean matches the documented schema (keys, French labels, types, filters)")
    void golden() throws Exception {
        Map<String, String> actual = new TreeMap<>();
        for (ExportDatasetProvider p : allProviders()) {
            actual.put(p.key(), describe(p));
        }
        assertThat(actual.keySet()).containsExactlyInAnyOrderElementsOf(GOLDEN.keySet());
        GOLDEN.forEach((key, expected) -> assertThat(actual.get(key)).as(key).isEqualTo(expected));
    }

    @Test
    @DisplayName("column keys are stable camelCase and unique per dataset")
    void columnKeys() throws Exception {
        for (ExportDatasetProvider p : allProviders()) {
            List<String> keys = p.columns().stream().map(ExportColumn::key).toList();
            assertThat(keys).as(p.key()).doesNotHaveDuplicates()
                    .allSatisfy(k -> assertThat(k).matches("[a-z][a-zA-Z0-9]*"));
        }
    }
}
