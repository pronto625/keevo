# Story 4.4: Clôture Journalière & Historique des Ventes

Status: in-progress

## Story

As an employee (Loïc),
I want to close my day in 1 tap and see my own sales history,
So that I can end my shift properly without any manual accounting and track my own performance.

---

## Acceptance Criteria

### AC1 — DayCloseButton prominent sur la PosPage

- **Given** Loïc est sur la PosPage en cours de journée
- **When** il regarde l'écran POS
- **Then** un bouton `DayCloseButton` "🌙 Clôturer la journée" est visible en bas à droite (position `Positioned` bottom-right dans la `Stack`)
- **And** le bouton affiche un badge avec le nombre de ventes `COMPLETED` effectuées depuis la dernière clôture de la journée
- **And** si aucune vente n'a été effectuée aujourd'hui, le badge n'est pas visible (count = 0)
- **And** le bouton est de couleur indigo (`Color(0xFF3B5BDB)`) avec icône lune

### AC2 — Flash summary bottom sheet (non-blocking, auto-dismiss 5s)

- **Given** Loïc tape sur `DayCloseButton`
- **When** le bottom sheet `DaySummaryBottomSheet` s'affiche
- **Then** il voit :
  - Total des ventes du jour (count, exclut `PENDING_VALIDATION` et `CANCELLED`)
  - Chiffre d'affaires total XAF (somme `totalAmount` des ventes `COMPLETED`)
  - Top produit vendu (nom + quantité totale)
  - Répartition par mode de paiement : Cash vs MoMo (XAF)
  - Ligne séparée : "🔶 {count} vente(s) en attente — {total} FCFA (non comptabilisé)" si applicable (AC12 de la story 4.3)
  - Bouton "Confirmer la clôture" (CTA principal)
  - Bouton "Annuler" (dismiss)
- **And** le bottom sheet se ferme automatiquement après 5 secondes si aucune action n'est effectuée
- **And** un `LinearProgressIndicator` affiche le décompte visuel des 5 secondes
- **And** le calcul est effectué localement depuis Drift (offline-first)

### AC3 — Confirmation de clôture & DayClosedEvent

- **Given** Loïc tape "Confirmer la clôture"
- **When** la clôture est confirmée
- **Then** un enregistrement est inséré dans la table Drift `day_closures` : `id`, `storeId`, `actorId`, `closedAt`, `totalSales`, `totalRevenue`, `topProductId`, `topProductName`, `topProductQty`, `cashAmount`, `momoAmount`, `pendingSalesCount`, `pendingSalesTotal`, `isAutomatic` (false), `synced` (false)
- **And** un `DayClosedEvent` Spring (backend) est émis avec : `storeId`, `actorId`, `closedAt`, `totalSales`, `totalRevenue`, `paymentBreakdown`
- **And** l'opération est enregistrée dans `sync_queue` avec `operation: 'CREATE_DAY_CLOSURE'`
- **And** l'affichage passe à "Bonne soirée Loïc ! 🌙" (page `DayCloseSuccessOverlay` pendant 1.5s) puis retour automatique à la PosPage
- **And** le `DayCloseButton` passe au state "Journée clôturée ✅" (désactivé, grisé) jusqu'au prochain jour calendaire

### AC4 — State persisté entre sessions (bouton désactivé jusqu'au prochain jour)

- **Given** Loïc a clôturé sa journée
- **When** il ferme et rouvre l'application ou navigue ailleurs puis revient sur la PosPage
- **Then** le bouton reste désactivé ("Journée clôturée ✅") jusqu'au lendemain
- **And** la persistance utilise `SharedPreferences` avec la clé `kLastClosureDate` (format `yyyy-MM-dd`) et `kLastClosureStoreId`
- **And** si la date stockée est antérieure à `DateFormat('yyyy-MM-dd').format(DateTime.now())`, le bouton redevient actif

### AC5 — Rapport WhatsApp envoyé au propriétaire (backend)

- **Given** la clôture est confirmée (manuelle ou automatique)
- **When** le backend reçoit l'entrée `day_closures` via sync
- **Then** le backend publie un `DayClosedEvent` Spring qui est écouté par un `DayClosureWhatsAppListener`
- **And** le listener appelle `WhatsAppPort.sendReport(ownerPhone, reportText)` avec le format :
  ```
  📊 Clôture {nomBoutique} — {date FR ex: "18 mars 2026"}
  👤 Vendeur : {employeeName}
  💰 CA : {totalRevenue} FCFA
  🛍 Ventes : {totalSales}
  📦 Top produit : {topProductName} (×{topProductQty})
  💵 Cash : {cashAmount} | 📱 MoMo : {momoAmount}
  ✅ Clôture {manuelle|auto} à {HH}h{mm}
  🔶 En attente : {pendingCount} vente(s) — {pendingTotal} FCFA (non comptabilisé)
  ```
  (la ligne 🔶 est omise si `pendingSalesCount = 0`)
- **And** `WhatsAppPort` est une interface hexagonale (stub `NoOpWhatsAppAdapter` pour MVP — log uniquement, pas d'envoi réel)
- **And** l'endpoint `POST /api/v1/day-closures` accepte la payload de sync

### AC6 — Clôture automatique à 20h00 heure locale (backend scheduler)

- **Given** il est 20h00 heure locale du tenant sans `DayClosedEvent` émis pour ce `storeId` + `actorId` ce jour
- **When** le `DayClosureAutoScheduler` s'exécute (cron `0 0 19 * * *` UTC = ~20h00 WAT)
- **Then** pour chaque store actif du tenant sans clôture du jour : une clôture automatique est générée
- **And** le rapport WhatsApp inclut "⏰ Rapport auto-généré (clôture oubliée)" au lieu de "✅ Clôture manuelle"
- **And** `isAutomatic = true` dans `day_closures`
- **And** Loïc voit au prochain démarrage de l'app un snackbar : "Votre journée du {date} a été clôturée automatiquement."
  - Ce snackbar est affiché une seule fois (flag `kAutoClosureNotifiedDate` dans SharedPreferences)

### AC7 — Historique des ventes (Mes Ventes)

- **Given** Loïc navigue POS > "Mes Ventes" (route `/pos/sales-history`)
- **When** la page `SalesHistoryPage` s'affiche
- **Then** il voit ses propres ventes filtrées par défaut sur "Aujourd'hui" (`occurredAt >= startOfDay`)
  *(Note : la colonne de filtrage est `occurred_at` — date de la vente — pas `created_at` — date du record DB)*
- **And** chaque `SaleHistoryCard` affiche : heure, total FCFA, mode de paiement (icône), nom du client (si lié), nombre d'articles
- **And** les filtres disponibles : Aujourd'hui | Cette semaine | Ce mois | Personnalisé (DateRangePicker)
- **And** tapper une carte ouvre `SaleDetailPage` : tous les articles avec quantités, prix appliqués, remise, client
- **And** Loïc voit UNIQUEMENT ses propres ventes (filtré sur `employeeId = currentUserId`)
- **And** la liste charge depuis Drift en offline-first (`employeeId + storeId + dateRange`)
- **And** les ventes `PENDING_VALIDATION` et `CANCELLED` apparaissent avec un badge coloré (amber/red) distinct des ventes `COMPLETED`

### AC8 — Bouton "Mes Ventes" sur la PosPage (navigation)

- **Given** Loïc est sur la PosPage
- **When** il regarde le SliverAppBar
- **Then** une icône "Historique" (`Icons.history_rounded`) est visible dans la barre près du `SyncIndicator`
- **And** la tapper navigue vers `/pos/sales-history`
- **And** pour les OWNER : la même icône est visible mais avec un label "Ventes du jour" (affiche le total du jour toutes boutiques confondues)

### AC9 — Backend : endpoint historique ventes avec filtres

- **Given** le backend reçoit `GET /api/v1/sales/history?storeId={storeId}&employeeId={employeeId}&from={ISO}&to={ISO}&page=0&size=50`
  *(Note : `/history` évite tout conflit avec `POST /api/v1/sales` géré par `SaleController`)*
- **When** la requête est authentifiée
- **Then** le backend retourne la liste paginée des ventes filtrées, avec leurs items, dans `ApiResponseWrapper<Page<SaleResponseDto>>`
- **And** les EMPLOYEE voient uniquement leurs propres ventes (leur `employeeId` extrait du JWT, ignorant le paramètre `employeeId` de la requête)
- **And** les OWNER voient toutes les ventes du store (paramètre `employeeId` optionnel pour filtrer)
- **And** `SaleResponseDto` contient : `id`, `occurredAt`, `totalAmount`, `discountAmount`, `paymentMode`, `status`, `clientId`, `employeeId`, liste `items[]` (id, productName, qty, unitPrice, subtotal)

### AC10 — Backend : endpoint clôture journalière

- **Given** le backend reçoit `POST /api/v1/day-closures`
- **When** la sync envoie la payload de clôture
- **Then** l'entrée est persistée dans la table `day_closures` (tenant schema)
- **And** le `DayClosedEvent` est publié (Spring `ApplicationEventPublisher`)
- **And** `GET /api/v1/day-closures?storeId={storeId}&date={yyyy-MM-dd}` retourne les clôtures du jour (OWNER-only)
- **And** la table DDL `day_closures` est créée via `TenantSchemaProvisioner` et migrée via `TenantSchemaSyncService`

### AC11 — Tests TDD obligatoires : RED → GREEN

#### Backend (JUnit 5)
- `DayClosureTest.java` — domain model, calcul summary
- `DayClosureServiceTest.java` — récupération sales, calcul aggregats
- `DayClosureControllerTest.java` — POST/GET endpoints, RBAC
- `DayClosureAutoSchedulerTest.java` — logique auto à 20h
- `SaleRepositoryAdapterTest.java` — findByEmployeeIdAndDateRange, findByStoreIdAndDate

#### Flutter (flutter_test)
- `day_closure_service_test.dart` — calcul aggregats depuis Drift
- `day_summary_bottom_sheet_test.dart` — affichage CA, top produit, breakdown
- `sales_history_page_test.dart` — liste, filtres, navigation
- `day_close_button_test.dart` — états: actif / désactivé / badge count
- `sale_detail_page_test.dart` — affichage items, montants, statuts

### AC12 — GoF patterns requis

| Pattern | Location | Application |
|---------|----------|-------------|
| **Observer** | `DayClosedEvent` + `DayClosureWhatsAppListener` / `AuditEventListener` | Événement Spring publié à chaque clôture → listener WhatsApp + audit |
| **Command** | `CloseDayCommand(storeId, actorId, closedAt, summary)` | Commande immuable traversant la frontière hexagonale |
| **Builder** | `DayClosureSummaryBuilder` | Construction progressive du rapport (total, top produit, breakdown) à partir de la liste des ventes |
| **Strategy** | `ClosureReportStrategy` (interface) + `ManualReportStrategy` / `AutoReportStrategy` | Format du rapport WhatsApp selon type de clôture (manuelle vs auto) |
| **State** | `DayCloseButtonState` enum : `available`, `closed`, `noSales` | Contrôle l'état visuel et l'interactivité du bouton |
| **Template Method** | `DayClosureAggregateService.computeSummary()` | Étapes fixes: load sales → filter COMPLETED → aggregate → build report (sous-classes: manual vs scheduler) |

---

## Tasks / Subtasks

> **LOI TDD : Tous les tests RED sont écrits AVANT tout code de production. Le test doit échouer en premier.**

---

### Task 1 — TDD RED : Tests backend domain model (Java)

- [ ] **1.1** Créer `DayClosureTest.java`
  ```java
  // commerce/sale/domain/model/DayClosureTest.java
  // Tests:
  // - DayClosure_create_setsAllFields()
  // - DayClosure_totalRevenue_excludesCancelledAndPending()
  ```
- [ ] **1.2** Créer `DayClosureSummaryTest.java`
  ```java
  // commerce/sale/domain/model/DayClosureSummaryTest.java
  // DayClosureSummary_topProduct_returnsProductWithMaxQty()
  // DayClosureSummary_paymentBreakdown_sumsCashAndMomo()
  // DayClosureSummary_withNoPendingSales_omitsPendingLine()
  ```
- [ ] **1.3** Créer `DayClosedEventTest.java`
  ```java
  // DayClosedEvent_setsAllRequiredFields()
  // DayClosedEvent_isAutomatic_falseForManual()
  ```
- [ ] **1.4** Ajouter à `SaleSpringRepository.java` les méthodes de requête (écrire tests d'abord — `SaleSpringRepositoryTest`)
  - `findByStoreIdAndEmployeeIdAndOccurredAtBetween(UUID, UUID, Instant, Instant, Pageable)`
  - `findByStoreIdAndOccurredAtBetween(UUID, Instant, Instant, Pageable)` (OWNER)
  - `findByStoreIdAndOccurredAtBetweenAndStatus(UUID, Instant, Instant, String, Pageable)`
  - `existsByStoreIdAndOccurredAtBetweenAndStatus(UUID, Instant, Instant, String)` (check — y a-t-il déjà des ventes aujourd'hui)
  *(La colonne `occurred_at` de `SaleJpaEntity` est la date de la vente, `created_at` est la date du record DB. Utiliser `occurredAt` pour les filtres métier.)*

### Task 2 — TDD RED : Tests backend application layer

- [ ] **2.1** Créer `CloseDayServiceTest.java`
  ```
  // closeDayService_givenSalesForDay_computesCorrectSummary()
  // closeDayService_excludesPendingValidationFromRevenue()
  // closeDayService_publishesDayClosedEvent()
  // closeDayService_alreadyClosed_throwsDomainException()
  ```
- [ ] **2.2** Créer `GetSalesHistoryServiceTest.java`
  ```
  // getSalesHistory_asEmployee_filtersOnEmployeeId()
  // getSalesHistory_asOwner_returnsAllStoresSales()
  // getSalesHistory_dateRange_filtersCorrectly()
  ```
- [ ] **2.3** Créer `DayClosureAutoSchedulerTest.java`
  ```
  // scheduler_whenNoClosureForDay_triggersAutoClose()
  // scheduler_whenClosureExists_skipsTrigger()
  // scheduler_autoClose_setsIsAutomaticTrue()
  ```
- [ ] **2.4** Créer `DayClosureSummaryBuilderTest.java`
  ```java
  // commerce/sale/application/service/DayClosureSummaryBuilderTest.java
  // builder_withMultipleSales_computesTopProduct()
  // builder_withMixedPaymentModes_splitsCashAndMomo()
  // builder_withPendingSales_excludesFromRevenue()
  // builder_withNoCompletedSales_returnsZeroRevenue()
  ```

### Task 3 — TDD RED : Tests backend controller layer

- [ ] **3.1** Créer `DayClosureControllerTest.java`
  ```
  // POST /api/v1/day-closures — 201 Created (OWNER + EMPLOYEE authorized)
  // POST /api/v1/day-closures — 409 CONFLICT si déjà clôturé aujourd'hui
  // GET /api/v1/day-closures?storeId=X&date=Y — 200 (OWNER)
  // GET /api/v1/day-closures?storeId=X&date=Y — 403 (EMPLOYEE)
  ```
- [ ] **3.2** Créer `SaleHistoryControllerTest.java`
  ```
  // GET /api/v1/sales/history?from=&to= — 200 (OWNER — all store sales)
  // GET /api/v1/sales/history?from=&to= — 200 (EMPLOYEE — only own sales)
  // GET /api/v1/sales/history?from=&to= — 401 (no auth)
  // Pagination: page, size params correct
  ```

### Task 4 — Implémentation backend : domain model

- [x] **4.1** Créer `DayClosureSummary.java` (pure Java record)
  ```java
  // commerce/sale/domain/model/DayClosureSummary.java
  public record DayClosureSummary(
      int totalSales,
      int totalRevenue,
      String topProductId,   // nullable
      String topProductName, // nullable
      int topProductQty,
      int cashAmount,
      int momoAmount,
      int pendingSalesCount,
      int pendingSalesTotal
  ) {}
  ```
- [x] **4.2** Créer `DayClosure.java` (aggregate)
  ```java
  // commerce/sale/domain/model/DayClosure.java
  // Champs: id UUID, storeId UUID, actorId UUID, closedAt Instant,
  //         summary DayClosureSummary, isAutomatic boolean, tenantId String
  // Validation: closedAt non-null, storeId non-null
  ```
- [x] **4.3** Créer `DayClosedEvent.java` (domain event record)
  ```java
  // commerce/sale/domain/model/DayClosedEvent.java
  public record DayClosedEvent(
      UUID closureId, UUID storeId, UUID actorId,
      DayClosureSummary summary, boolean isAutomatic,
      String tenantId, Instant occurredAt
  ) {}
  ```
- [x] **4.4** Créer `DayClosureAlreadyExistsException.java` (extends `DomainException`)
  - `ErrorCode.DAY_ALREADY_CLOSED` à ajouter dans `ErrorCode.java`
  - Message FR : "La journée a déjà été clôturée pour cette boutique"

### Task 5 — Implémentation backend : ports

- [x] **5.1** Créer `CloseDayUseCase.java` (port in)
  ```java
  // commerce/sale/domain/port/in/CloseDayUseCase.java
  public interface CloseDayUseCase {
      record CloseDayCommand(UUID storeId, UUID actorId, String tenantId,
                             boolean isAutomatic) {}
      DayClosureSummary closeDay(CloseDayCommand command);
  }
  ```
- [x] **5.2** Créer `GetSalesHistoryUseCase.java` (port in)
  ```java
  // commerce/sale/domain/port/in/GetSalesHistoryUseCase.java
  public interface GetSalesHistoryUseCase {
      record SalesHistoryQuery(UUID storeId, UUID employeeId, // null = all
                               Instant from, Instant to,
                               String role, int page, int size) {}
      org.springframework.data.domain.Page<Sale> getSalesHistory(SalesHistoryQuery query);
  }
  ```
- [x] **5.3** Créer `DayClosureRepository.java` (port out)
  ```java
  // commerce/sale/domain/port/out/DayClosureRepository.java
  public interface DayClosureRepository {
      void save(DayClosure closure);
      boolean existsByStoreIdAndDate(UUID storeId, LocalDate date);
      List<DayClosure> findByStoreIdAndDate(UUID storeId, LocalDate date);
  }
  ```
- [x] **5.4** Créer `WhatsAppPort.java` (port out)
  ```java
  // commerce/sale/domain/port/out/WhatsAppPort.java
  public interface WhatsAppPort {
      void sendReport(String phoneNumber, String reportText);
  }
  ```

### Task 6 — Implémentation backend : application services

- [x] **6.1** Créer `CloseDayService.java` avec pattern Template Method + Builder
  ```java
  // commerce/sale/application/service/CloseDayService.java
  @Service @Transactional
  public class CloseDayService implements CloseDayUseCase {
      // 1. Vérifier qu'aucune clôture n'existe pour storeId + today
      // 2. Charger toutes les ventes du jour (storeId, TODAY 00:00—23:59)
      // 3. DayClosureSummaryBuilder.build(sales) — GoF Builder
      // 4. Persister DayClosure
      // 5. ApplicationEventPublisher.publishEvent(DayClosedEvent)
  }
  ```
- [x] **6.2** Créer `DayClosureSummaryBuilder.java` (GoF Builder)
  ```java
  // Chaîne: addSale(Sale) → build() → DayClosureSummary
  // Calcule: count COMPLETED, totalRevenue, topProduct (Map productId→qty),
  //          cashAmount, momoAmount, pending count/total
  ```
- [x] **6.3** Créer `GetSalesHistoryService.java`
  ```java
  // EMPLOYEE: force employeeId = JWT actorId (ignore query param)
  // OWNER: applique le filtre optionnel employeeId
  // Délègue à SaleRepository.findSalesHistory(query)
  ```
- [x] **6.4** Créer `DayClosureAutoScheduler.java` (GoF Strategy: AutoReportStrategy)
  ```java
  // @Scheduled(cron = "0 0 19 * * *") — 19:00 UTC = ~20:00 WAT
  // Note: @EnableScheduling déjà présent sur KeevoApplication.java — aucune modification requise
  //
  // Itération multi-tenant (pattern identique à SubscriptionExpiryScheduler):
  //   List<Tenant> tenants = tenantRepository.findAll(); // ⚠ REQUIERT L'AJOUT DE findAll() au port out
  //   for (Tenant tenant : tenants) {
  //     if (tenant.getStatus() != TenantStatus.ACTIVE) continue;
  //     TenantContext.setTenantId(tenant.getSchemaName()); // isolation schema
  //     try {
  //       storeRepository.findAllActive()   // StoreRepository: com.keevo.store.store.domain.port.out
  //         .forEach(store -> {
  //           if (!dayClosureRepository.existsByStoreIdAndDate(store.getId(), LocalDate.now())) {
  //             closeDayUseCase.closeDay(new CloseDayCommand(store.getId(), SYSTEM_UUID, tenant.getSchemaName(), true));
  //           }
  //         });
  //     } finally {
  //       TenantContext.clear(); // toujours nettoyer — évite les leaks
  //     }
  //   }
  // Note CROSSāDOMAIN: StoreRepository (com.keevo.store.store.domain.port.out.StoreRepository)
  //   est injecté via @Autowired — cross-domain permis au niveau infrastructure (scheduler)
  ```
- [x] **6.5** Créer `DayClosureWhatsAppListener.java` (GoF Observer)
  ```java
  // @EventListener(DayClosedEvent.class)
  // Construit le rapport texte via ClosureReportStrategy (Manual ou Auto)
  // Appelle WhatsAppPort.sendReport(ownerPhone, reportText)
  //
  // ⚠ Dépendances cross-domain (injections nécessaires):
  //
  // 1. {nomBoutique} → StoreRepository (com.keevo.store.store.domain.port.out.StoreRepository)
  //    store = storeRepository.findById(event.storeId())
  //    storeName = store.map(Store::getName).orElse("Boutique #{storeId.toString().substring(0,8)}")
  //
  // 2. {employeeName} → UserRepository (com.keevo.identity.auth.domain.port.out.UserRepository)
  //    User.java n'a PAS de fullName en Story 4.4 (aucun champ name sur User)
  //    Pour MVP: employeeName = userRepository.findById(event.actorId())
  //                               .map(User::getPhoneNumber).orElse("Employé inconnu")
  //    (fullName sera ajouté en story profil utilisateur)
  //
  // 3. {ownerPhone} pour sendReport() → UserRepository
  //    Tenant n'a PAS de champ ownerPhone (pas dans Tenant.java)
  //    Pour MVP: ownerPhone obtenu via UserTenantMembership ou UserRepository
  //    Pattern: trouver l'OWNER du tenant via findByRoleAndTenantSchemaName(OWNER, schemaName)
  //    ⚠ UserRepository n'a pas encore findByRole() → ajouter OU utiliser ownerPhone
  //      stocké en config (application.properties: keevo.whatsapp.stub.owner.phone)
  //    Pour MVP avec NoOpWhatsAppAdapter: la valeur est loggée seulement — pas d'envoi réel
  //    Approche MVP recommandée: ownerPhone = System.getProperty("keevo.owner.whatsapp", "+243000000000")
  ```
- [x] **6.6** Créer `ClosureReportStrategy.java` (interface) + `ManualReportStrategy.java` + `AutoReportStrategy.java`
  ```java
  // interface: String buildReport(DayClosure closure, String storeName, String employeeName);
  // ManualReportStrategy: "✅ Clôture manuelle à {HH}h{mm}"
  // AutoReportStrategy: "⏰ Rapport auto-généré (clôture oubliée)"
  ```

### Task 7 — Implémentation backend : adapters persistence

- [x] **7.1** Créer `DayClosureJpaEntity.java`
  ```java
  // @Entity @Table(name = "day_closures")
  // id UUID PK, store_id UUID, actor_id UUID, closed_at TIMESTAMP WITH TIME ZONE,
  // total_sales INT, total_revenue INT, top_product_id VARCHAR(36),
  // top_product_name VARCHAR(200), top_product_qty INT,
  // cash_amount INT, momo_amount INT,
  // pending_sales_count INT, pending_sales_total INT,
  // is_automatic BOOLEAN DEFAULT FALSE, created_at TIMESTAMP WITH TIME ZONE
  ```
- [x] **7.2** Créer `DayClosureSpringRepository.java` (JPA)
  ```java
  // boolean existsByStoreIdAndClosedAtBetween(UUID storeId, Instant start, Instant end)
  // List<DayClosureJpaEntity> findByStoreIdAndClosedAtBetween(UUID, Instant, Instant)
  ```
- [x] **7.3** Créer `DayClosureRepositoryAdapter.java`
  ```java
  // Implémente DayClosureRepository. Mapping domain ↔ JPA entity.
  ```
- [x] **7.4** Ajouter à `SaleSpringRepository.java`
  ```java
  // Page<SaleJpaEntity> findByStoreIdAndOccurredAtBetween(UUID, Instant, Instant, Pageable)
  // Page<SaleJpaEntity> findByStoreIdAndEmployeeIdAndOccurredAtBetween(UUID, UUID, Instant, Instant, Pageable)
  // Page<SaleJpaEntity> findByStoreIdAndOccurredAtBetweenAndStatus(UUID, Instant, Instant, String, Pageable)
  // ⚠ Utiliser `occurredAt` (date métier de la vente) — pas `createdAt` (date d'insertion DB)
  ```
- [x] **7.5** Ajouter à `SaleRepositoryAdapter.java`
  ```java
  // Page<Sale> findSalesHistory(GetSalesHistoryUseCase.SalesHistoryQuery query) // délègue
  // + déclarer dans SaleRepository.java (port out)
  ```
- [x] **7.6** Créer `NoOpWhatsAppAdapter.java`
  ```java
  // @Primary @Component
  // Implémente WhatsAppPort — log.info("WhatsApp report [STUB]: to={} text={}")
  ```

### Task 8 — Implémentation backend : REST controller + DDL

- [x] **8.1** Créer `DayClosureController.java`
  ```java
  // POST /api/v1/day-closures → @PreAuthorize("hasAnyRole('OWNER','EMPLOYEE')")
  //   Body: { storeId UUID (optionnel EMPLOYEE — utilisera JWT storeId) }
  //   On success: 201 + DayClosureSummaryDto
  //   Si 409 DAY_ALREADY_CLOSED: 409 + error body
  // GET /api/v1/day-closures → @PreAuthorize("hasRole('OWNER')")
  //   Params: storeId, date (yyyy-MM-dd)
  //   Returns: List<DayClosureResponseDto>
  ```
- [x] **8.2** Créer `SaleHistoryController.java`
  ```java
  // GET /api/v1/sales/history → @PreAuthorize("hasAnyRole('OWNER','EMPLOYEE')")
  //   Params: storeId, from (ISO), to (ISO), page=0, size=50
  //   EMPLOYEE: force employeeId = JWT sub
  //   Returns: Page<SaleResponseDto>
  ```
- [x] **8.3** Créer `SaleResponseDto.java`, `DayClosureSummaryDto.java`, `DayClosureResponseDto.java`, `DayClosureRequestDto.java`
- [x] **8.4** DDL dans `TenantSchemaProvisioner.java`
  ```sql
  CREATE TABLE IF NOT EXISTS day_closures (
    id UUID PRIMARY KEY,
    store_id UUID NOT NULL,
    actor_id UUID NOT NULL,
    closed_at TIMESTAMPTZ NOT NULL,
    total_sales INT NOT NULL DEFAULT 0,
    total_revenue INT NOT NULL DEFAULT 0,
    top_product_id VARCHAR(36),
    top_product_name VARCHAR(200),
    top_product_qty INT DEFAULT 0,
    cash_amount INT NOT NULL DEFAULT 0,
    momo_amount INT NOT NULL DEFAULT 0,
    pending_sales_count INT NOT NULL DEFAULT 0,
    pending_sales_total INT NOT NULL DEFAULT 0,
    is_automatic BOOLEAN NOT NULL DEFAULT FALSE,
    created_at TIMESTAMPTZ NOT NULL DEFAULT NOW()
  );
  CREATE INDEX idx_day_closures_store_date ON day_closures(store_id, closed_at);
  ```
- [x] **8.5** Ajouter la migration dans `TenantSchemaSyncService.ensureRequiredIndexes()` pour les tenants existants

### Task 9 — TDD RED : Tests Flutter (dart test)

- [x] **9.1** Créer `day_close_button_test.dart`
  ```dart
  // dayCloseButton_whenNoSalesChanged_showsNoBadge()
  // dayCloseButton_whenSalesExist_showsBadgeWithCount()
  // dayCloseButton_whenAlreadyClosed_isDisabled()
  // dayCloseButton_tap_opensDaySummaryBottomSheet()
  ```
- [x] **9.2** Créer `day_summary_bottom_sheet_test.dart`
  ```dart
  // daySummarySheet_displaysCorrectRevenue()
  // daySummarySheet_displaysTopProduct()
  // daySummarySheet_displaysCashAndMomoBreakdown()
  // daySummarySheet_displaysPendingLine_whenExists()
  // daySummarySheet_hidePendingLine_whenNoPending()
  // daySummarySheet_autoCloses_after5Seconds()
  // daySummarySheet_confirm_callsCloseDayUseCase()
  ```
- [x] **9.3** Créer `sales_history_page_test.dart`
  ```dart
  // salesHistoryPage_loadsCurrentDaySales()
  // salesHistoryPage_filterThisWeek_returnsCorrect()
  // salesHistoryPage_filterThisMonth_returnsCorrect()
  // salesHistoryPage_tapSale_navigatesToDetail()
  // salesHistoryPage_showsPendingBadge_forPendingSales()
  // salesHistoryPage_showsCancelledBadge_forCancelledSales()
  ```
- [x] **9.4** Créer `sale_detail_page_test.dart`
  ```dart
  // saleDetailPage_displaysAllItems()
  // saleDetailPage_displaysDiscount_whenApplied()
  // saleDetailPage_displaysClientName_whenLinked()
  // saleDetailPage_displaysPaymentMode()
  ```
- [x] **9.5** Créer `day_closure_service_test.dart`
  ```dart
  // dayClosureService_computeSummary_countOnlyCompletedSales()
  // dayClosureService_topProduct_returnsHighestQtyProduct()
  // dayClosureService_paymentBreakdown_sumsByMode()
  // dayClosureService_pendingRevenue_excludedFromTotal()
  ```

### Task 10 — Implémentation Flutter : domain layer

- [x] **10.1** Créer `day_closure_model.dart`
  ```dart
  // class DayClosureSummary {
  //   final int totalSales, totalRevenue, cashAmount, momoAmount;
  //   final String? topProductId, topProductName;
  //   final int topProductQty, pendingSalesCount, pendingSalesTotal;
  // }
  // class DayClosure {
  //   final String id, storeId, actorId;
  //   final DateTime closedAt;
  //   final DayClosureSummary summary;
  //   final bool isAutomatic;
  // }
  ```
- [x] **10.2** Créer `day_closure_repository.dart` (interface)
  ```dart
  // abstract interface class DayClosureRepository {
  //   Future<DayClosureSummary> computeTodaySummary(String storeId, String employeeId);
  //   Future<void> saveClosureLocally(DayClosure closure);
  //   Future<bool> hasClosureToday(String storeId);
  //   Future<DayClosure?> getLastClosure(String storeId);
  // }
  ```
- [x] **10.3** Créer `close_day_usecase.dart`
  ```dart
  // class CloseDayUseCase {
  //   Future<DayClosureSummary> execute(String storeId, String actorId);
  //   // 1. computeTodaySummary → 2. saveClosureLocally → 3. enqueue sync
  // }
  ```
- [x] **10.4** Créer `get_sales_history_usecase.dart`
  ```dart
  // class GetSalesHistoryUseCase {
  //   Future<List<Sale>> execute({
  //     required String storeId, required String employeeId,
  //     required DateTime from, required DateTime to,
  //   });
  // }
  ```
- [x] **10.5** Créer `sales_history_filter.dart` (value object — requis par `salesHistoryProvider`)
  ```dart
  // lib/features/pos/domain/model/sales_history_filter.dart
  // enum DateFilterType { today, thisWeek, thisMonth, custom }
  // class SalesHistoryFilter {
  //   final String storeId;
  //   final String employeeId;
  //   final DateTime from;
  //   final DateTime to;
  //   final DateFilterType filterType;
  //   const SalesHistoryFilter({
  //     required this.storeId, required this.employeeId,
  //     required this.from, required this.to, required this.filterType,
  //   });
  //   @override bool operator ==(Object other) => ...  // pour FutureProvider.family
  //   @override int get hashCode => Object.hash(storeId, employeeId, from, to, filterType);
  // }
  ```

### Task 11 — Implémentation Flutter : data layer

- [x] **11.1** Ajouter table `day_closures` au schéma Drift dans `app_database.dart`
  ```dart
  // class DayClosures extends Table {
  //   TextColumn get id => text()();
  //   TextColumn get storeId => text()();
  //   TextColumn get actorId => text()();
  //   DateTimeColumn get closedAt => dateTime()();
  //   IntColumn get totalSales => integer()();
  //   IntColumn get totalRevenue => integer()();
  //   TextColumn get topProductId => text().nullable()();
  //   TextColumn get topProductName => text().nullable()();
  //   IntColumn get topProductQty => integer()();
  //   IntColumn get cashAmount => integer()();
  //   IntColumn get momoAmount => integer()();
  //   IntColumn get pendingSalesCount => integer()();
  //   IntColumn get pendingSalesTotal => integer()();
  //   BoolColumn get isAutomatic => boolean().withDefault(const Constant(false))();
  //   BoolColumn get synced => boolean().withDefault(const Constant(false))();
  //   DateTimeColumn get createdAt => dateTime()();
  //   @override Set<Column> get primaryKey => {id};
  // }
  // Ajouter DayClosures à @DriftDatabase(tables: [...])
  ```
- [x] **11.2** Créer `local_day_closure_datasource.dart`
  ```dart
  // insertClosure(DayClosure) — insère en Drift + sync_queue entry
  // hasClosureToday(storeId) — SELECT COUNT WHERE date = today
  // getLastClosure(storeId) — ORDER BY closedAt DESC LIMIT 1
  // computeSummary(storeId, employeeId) — calcul agregats depuis sales Drift
  ```
- [x] **11.3** Créer `remote_day_closure_datasource.dart`
  ```dart
  // pushClosure(DayClosure) — POST /api/v1/day-closures
  // fetchSalesHistory(storeId, employeeId, from, to, page, size) — GET /api/v1/sales/history
  ```
- [x] **11.4** Créer `day_closure_repository_impl.dart`
  ```dart
  // implements DayClosureRepository
  // computeTodaySummary: requêtes Drift locales
  // saveClosureLocally: insertClosure local
  // hasClosureToday: local check
  ```
- [x] **11.5** Étendre `SaleRepository` + `SaleRepositoryImpl` avec :
  ```dart
  // getSalesHistory(storeId, employeeId, from, to) — lecture Drift locale
  // en SaleRepositoryImpl: requête SQL sur sales JOIN sale_items filtrée date + employeeId
  ```

### Task 12 — Implémentation Flutter : presentation layer

- [x] **12.1** Créer `DayCloseButton` widget
  ```dart
  // lib/features/pos/presentation/widget/day_close_button.dart
  // ConsumerWidget — watch dayClosureStateProvider
  // States: DayCloseButtonState { available, closed, noSales }
  // Closed state: icône check-circle verte + "Journée clôturée ✅" + disabled
  // Available state: bouton indigo + icône lune + badge count (todaySalesCountProvider)
  // noSales state: disponible mais badge non affiché
  ```
- [x] **12.2** Créer `DaySummaryBottomSheet` widget
  ```dart
  // lib/features/pos/presentation/widget/day_summary_bottom_sheet.dart
  // showModalBottomSheet static method
  // Contenu: totaux, top produit, breakdown Cash/MoMo, ligne pending (conditionnelle)
  // Auto-dismiss: Timer(5s) + LinearProgressIndicator horizontal
  // CTAs: "Confirmer la clôture" (calls closeDayNotifier) + "Annuler"
  ```
- [x] **12.3** Créer `SalesHistoryPage`
  ```dart
  // lib/features/pos/presentation/page/sales_history_page.dart
  // Route: /pos/sales-history
  // ConsumerStatefulWidget — watch salesHistoryProvider(filter)
  // DateFilter enum: today, thisWeek, thisMonth, custom
  // Filtres: Row de chips (Today + semaine + mois + icône calendrier pour custom)
  // ListView.builder: SaleHistoryCard par vente
  // Pull-to-refresh: rafraîchit salesHistoryProvider
  // Ventes pending: badge amber, cancelled: badge red
  ```
- [x] **12.4** Créer `SaleHistoryCard` widget
  ```dart
  // lib/features/pos/presentation/widget/sale_history_card.dart
  // ListTile: leading=icône paiement, title=total + heure,
  //           subtitle=client? + nb articles,
  //           trailing=status badge (completed=null, pending=amber, cancelled=red)
  // onTap → /pos/sale-detail/{id}
  ```
- [x] **12.5** Créer `SaleDetailPage`
  ```dart
  // lib/features/pos/presentation/page/sale_detail_page.dart
  // Route: /pos/sale-detail/:saleId (extra: Sale)
  // Affiche: heure, montant total, remise, mode de paiement, client
  // ListView items: nom produit, qté × prix unitaire = sous-total
  // Status badge en haut (completed/pending/cancelled)
  ```
- [x] **12.6** Créer `DayCloseSuccessOverlay` (overlay plein-écran 1.5s)
  ```dart
  // lib/features/pos/presentation/widget/day_close_success_overlay.dart
  // Gradient indigo + texte "Bonne soirée Loïc ! 🌙" centré
  // AnimatedOpacity fade-in → auto-dismiss après 1.5s → context.pop()
  ```
- [x] **12.7** Créer providers Riverpod
  ```dart
  // lib/features/pos/presentation/provider/day_closure_providers.dart
  //
  // dayClosureRepositoryProvider = Provider<DayClosureRepository>
  // closeDayUseCaseProvider = Provider<CloseDayUseCase>
  // getSalesHistoryUseCaseProvider = Provider<GetSalesHistoryUseCase>
  //
  // todaySalesCountProvider = FutureProvider.family<int, String>(storeId)
  //   → comptes sales COMPLETED depuis startOfDay pour ce storeId
  //   → exclu PENDING_VALIDATION et CANCELLED
  //
  // dayClosureStateProvider = FutureProvider.family<DayCloseButtonState, String>(storeId)
  //   → hasClosureToday(storeId) → closed
  //   → todaySalesCount == 0 → noSales
  //   → else → available
  //
  // salesHistoryProvider = FutureProvider.family<List<Sale>, SalesHistoryFilter>
  //   (storeId + employeeId + from + to)
  //
  // closeDayNotifier = AsyncNotifier (manual trigger → closeDay + invalidate providers)
  //
  // autoClosureNotificationProvider = FutureProvider<String?>
  //   → lit kAutoClosureNotifiedDate depuis SharedPreferences
  //   → retourne le message de notification si date != aujourd'hui
  ```

### Task 13 — Intégration PosPage : ajout DayCloseButton + icône historique

- [x] **13.1** Modifier `pos_page.dart` : intégrer `DayCloseButton` dans la `Stack`
  ```dart
  // Dans Stack children, APRÈS CustomScrollView:
  // Positioned(bottom: 80, right: 16, child: DayCloseButton(storeId: storeId))
  // ⚠ Bottom: 80 pour laisser de la place au CartPill (bottom: 0–56)
  ```
- [x] **13.2** Ajouter icône historique dans SliverAppBar (Row à côté de SyncIndicator)
  ```dart
  // IconButton(icon: Icon(Icons.history_rounded, color: Colors.white), ...)
  // onPressed: () => context.push('/pos/sales-history')
  ```
- [x] **13.3** Snackbar auto-closure notification dans `PosPage.initState()`
  ```dart
  // WidgetsBinding.instance.addPostFrameCallback((_) async {
  //   final msg = await ref.read(autoClosureNotificationProvider.future);
  //   if (msg != null && mounted) ScaffoldMessenger.showSnackBar(...)
  //   await prefs.setString(kAutoClosureNotifiedDate, today);
  // });
  ```

### Task 14 — Router : nouvelles routes

- [x] **14.1** Ajouter dans `app_router.dart`
  ```dart
  // GoRoute(path: '/pos/sales-history', builder: (ctx, state) => const SalesHistoryPage())
  // GoRoute(path: '/pos/sale-detail/:saleId', builder: (ctx, state) {
  //   final sale = state.extra as Sale;
  //   return SaleDetailPage(sale: sale);
  // })
  ```

### Task 15 — Tests cURL itératifs (backend) — RED → GREEN

  ```bash
  # Fichier: keevo/scripts/curl-tests-story-4-4.sh
  # Pré-requis: backend UP sur http://localhost:8080
  # Auth: auto-register + login + select-tenant (copie de story-4-1)
  # Variables: TOKEN, STORE_ID, EMPLOYEE_TOKEN, EMPLOYEE_STORE_ID
  #
  # STEP 01 — GET /sales/history (aujourd'hui) — EMPLOYEE → 200, seulement ses ventes
  # STEP 02 — GET /sales/history (aujourd'hui) — OWNER → 200, toutes les ventes du store
  # STEP 03 — POST /day-closures — EMPLOYEE sans vente → 201 (totalSales=0)
  # STEP 04 — POST /day-closures — re-soumission même jour → 409 DAY_ALREADY_CLOSED
  # STEP 05 — GET /day-closures?storeId=X&date=today — OWNER → 200, liste 1 clôture
  # STEP 06 — GET /day-closures?storeId=X&date=today — EMPLOYEE → 403
  # STEP 07 — Créer vente COMPLETED puis GET /sales/history → vente apparaît
  # STEP 08 — GET /sales/history avec from/to date — filtrage correct
  # STEP 09 — POST /day-closures avec ventes → 201 totalSales=1, totalRevenue=correct
  # STEP 10 — Vérifier format rapport WhatsApp dans logs (NoOpWhatsAppAdapter)
  ```

### Task 16 — AppConstants & migrations

- [x] **16.1** Ajouter dans `app_constants.dart`
  ```dart
  const kLastClosureDate    = 'kLastClosureDate';     // yyyy-MM-dd
  const kLastClosureStoreId = 'kLastClosureStoreId';
  const kAutoClosureNotifiedDate = 'kAutoClosureNotifiedDate'; // yyyy-MM-dd
  ```
- [x] **16.2** Mettre à jour `app_database.dart` : incrémenter `schemaVersion` + `MigrationStrategy` pour ajouter la table `day_closures`

---

## Dev Notes

### Architecture hexagonale backend

- Nouveau bounded context dans `commerce/sale/` (pas de nouveau package racine — Day Closure fait partie du domaine Sale)
- `DayClosureController` → `CloseDayUseCase` → `CloseDayService` → `DayClosureRepository` (port out) → `DayClosureRepositoryAdapter` (JPA)
- `SaleHistoryController` → `GetSalesHistoryUseCase` → `GetSalesHistoryService` → `SaleRepository.findSalesHistory()` (port out déjà existant)
- Toutes les classes de domaine (model, events, ports) sont **pure Java** — zéro import Spring ni JPA
- `@Transactional` uniquement sur les services applicatifs

### GoF patterns — conformité avec le projet

| Pattern | Précédent dans le projet | Cette story |
|---------|--------------------------|-------------|
| Observer | `SalePendingValidationEvent` → `AuditEventListener` | `DayClosedEvent` → `DayClosureWhatsAppListener` + `AuditEventListener` |
| Builder | `SaleFactory` | `DayClosureSummaryBuilder` |
| Strategy | `DiscountStrategy` (Story 4.2) | `ClosureReportStrategy` (Manual vs Auto) |
| Template Method | `TenantSchemaProvisioner` | `DayClosureAggregateService.computeSummary()` |
| State | `SaleStatus` (State pattern) | `DayCloseButtonState` |
| Command | `RecordSaleCommand`, `ValidateSaleCommand` | `CloseDayCommand` |

### Flutter — Drift schema migration

- Incrémenter `schemaVersion` de la valeur actuelle + 1
- Dans `MigrationStrategy.onUpgrade`: `await m.createTable(dayClosures)` pour la migration from
- Ne jamais `DROP TABLE` — forward-only migrations

### Flutter — Riverpod autoDispose

- Tous les providers liés à POS utilisent `.autoDispose` (pattern établi Story 4.1→4.3)
- `salesHistoryProvider` = `FutureProvider.autoDispose.family` paramétré par `SalesHistoryFilter`
- `dayClosureStateProvider` invalidé après `closeDayNotifier.closeDay()` pour forcer le rechargement du `DayCloseButton`

### Tests — conventions existantes

- Backend: `@SpringBootTest` → lent; préférer `@ExtendWith(MockitoExtension.class)` pour les tests service/domain
- Flutter: `flutter_test` + `mocktail` (pas `mockito`) — convention du projet (Story 3.5 + 4.3)
- Tous les tests unitaires dans `test/` mirror de `lib/` — ex: `test/features/pos/presentation/widget/day_close_button_test.dart`

### RBAC

- `POST /api/v1/day-closures` : OWNER + EMPLOYEE (avec storeId JWT authoritative pour EMPLOYEE)
- `GET /api/v1/day-closures` : OWNER uniquement (403 pour EMPLOYEE)
- `GET /api/v1/sales/history` : OWNER + EMPLOYEE (EMPLOYEE limité à ses propres ventes)
- Conformité avec le pattern `requireOwnerOrForbid()` établi en Story 3.5

### ErrorCode.java — nouveaux codes à ajouter

```java
DAY_ALREADY_CLOSED,   // 409
```

### FR42 — Scope MVP vs implémentation complète

Le FR42 de l'epic spécifie : `totalCount`, `totalRevenue`, `revenueByPaymentMode`, **`averageBasketSize`**, **`top 3 products`** by quantity.

**Cette story (MVP)** implémente uniquement :
- `topProduct` : **1 seul produit** (nom + quantité) — cohérent avec le format du rapport WhatsApp (1 ligne `📦 Top produit`)
- **`averageBasketSize` non implémenté** — déféré à la Story 7.x (Rapports & Dashboard)

`DayClosureSummaryBuilder.build()` calcule `top1` uniquement. L'extension à `top3` + `averageBasketSize` est prévue en Story 7.x sans breaking change (ajout de champs nullable à `DayClosureSummary`).

### Exclusion PENDING_VALIDATION (AC12 story 4.3)

- La clôture n'agrège QUE les ventes `COMPLETED`
- Les ventes `PENDING_VALIDATION` sont comptées séparément dans `pendingSalesCount` / `pendingSalesTotal`
- Le rapport WhatsApp inclut la ligne `🔶` uniquement si `pendingSalesCount > 0`

### Project Structure Notes

**Nouveaux fichiers backend :**
```
commerce/sale/
  domain/model/
    DayClosure.java
    DayClosureSummary.java
    DayClosedEvent.java
  domain/port/in/
    CloseDayUseCase.java
    GetSalesHistoryUseCase.java
  domain/port/out/
    DayClosureRepository.java
    WhatsAppPort.java
  application/service/
    CloseDayService.java
    DayClosureSummaryBuilder.java
    GetSalesHistoryService.java
    DayClosureAutoScheduler.java
    DayClosureWhatsAppListener.java
    ClosureReportStrategy.java (interface)
    ManualReportStrategy.java
    AutoReportStrategy.java
  adapter/in/rest/
    DayClosureController.java
    SaleHistoryController.java
    dto/
      DayClosureRequestDto.java
      DayClosureSummaryDto.java
      DayClosureResponseDto.java
      SaleResponseDto.java
  adapter/out/persistence/
    entity/DayClosureJpaEntity.java
    jpa/DayClosureSpringRepository.java
    impl/DayClosureRepositoryAdapter.java
    impl/NoOpWhatsAppAdapter.java
```

**Nouveaux fichiers Flutter :**
```
lib/features/pos/
  domain/model/day_closure_model.dart
  domain/model/sales_history_filter.dart     ← value object (SalesHistoryFilter + DateFilterType enum)
  domain/repository/day_closure_repository.dart
  domain/usecase/close_day_usecase.dart
  domain/usecase/get_sales_history_usecase.dart
  data/datasource/local_day_closure_datasource.dart
  data/datasource/remote_day_closure_datasource.dart
  data/repository/day_closure_repository_impl.dart
  presentation/provider/day_closure_providers.dart
  presentation/widget/day_close_button.dart
  presentation/widget/day_summary_bottom_sheet.dart
  presentation/widget/day_close_success_overlay.dart
  presentation/widget/sale_history_card.dart
  presentation/page/sales_history_page.dart
  presentation/page/sale_detail_page.dart
```

**Fichiers modifiés :**
```
lib/core/storage/app_database.dart          (ajouter DayClosures table + migration)
lib/core/storage/app_constants.dart         (kLastClosureDate, kLastClosureStoreId, kAutoClosureNotifiedDate)
lib/core/router/app_router.dart             (routes /pos/sales-history, /pos/sale-detail/:saleId)
lib/features/pos/presentation/page/pos_page.dart (DayCloseButton + icône historique)
lib/features/pos/domain/repository/sale_repository.dart (getSalesHistory)
lib/features/pos/data/repository/sale_repository_impl.dart (getSalesHistory impl)
keevo/backend:
  SaleSpringRepository.java  (nouvelles méthodes occurredAt queries)
  SaleRepositoryAdapter.java (findSalesHistory)
  SaleRepository.java        (port out — findSalesHistory)
  TenantSchemaProvisioner.java (DDL day_closures)
  TenantSchemaSyncService.java (migration)
  ErrorCode.java             (DAY_ALREADY_CLOSED)
  TenantRepository.java      (com.keevo.identity.auth.domain.port.out — AJOUTER findAll(): List<Tenant>)
  TenantRepositoryAdapter.java (com.keevo.identity.auth.adapter.out.persistence.impl — implém. findAll() via TenantSpringRepository.findAll())
```

> **Note cross-domain (DayClosureAutoScheduler):** `StoreRepository` (`com.keevo.store.store.domain.port.out.StoreRepository`) est injecté dans le scheduler. `findAllActive()` est déjà disponible ✅. `StoreRepository` ne figure pas dans la liste des fichiers modifiés — aucun changement requis.

**Nouveaux tests backend :**
```
test/java/com/keevo/commerce/sale/
  domain/model/DayClosureTest.java
  domain/model/DayClosureSummaryTest.java
  domain/model/DayClosedEventTest.java
  application/service/CloseDayServiceTest.java
  application/service/GetSalesHistoryServiceTest.java
  application/service/DayClosureSummaryBuilderTest.java
  application/service/DayClosureAutoSchedulerTest.java
  adapter/in/rest/DayClosureControllerTest.java
  adapter/in/rest/SaleHistoryControllerTest.java
```

**Nouveaux tests Flutter :**
```
test/features/pos/presentation/widget/day_close_button_test.dart
test/features/pos/presentation/widget/day_summary_bottom_sheet_test.dart
test/features/pos/presentation/widget/sale_history_card_test.dart
test/features/pos/presentation/page/sales_history_page_test.dart
test/features/pos/presentation/page/sale_detail_page_test.dart
test/features/pos/domain/usecase/close_day_usecase_test.dart
test/features/pos/domain/usecase/get_sales_history_usecase_test.dart
test/features/pos/data/datasource/local_day_closure_datasource_test.dart
```

**Script cURL :**
```
keevo/scripts/curl-tests-story-4-4.sh
```

### References

- [Source: `_bmad-output/planning-artifacts/epics/epic-4-point-de-vente-pos.md`#Story 4.4]
- [Source: `_bmad-output/planning-artifacts/ux-design-specification.md`#Flow 8 — Clôture Journalière]
- [Source: `_bmad-output/planning-artifacts/ux-design-specification.md`#Flow 24 — Consulter Historique Ventes]
- [Source: `_bmad-output/planning-artifacts/architecture.md`#Flutter architectural rules]
- [Source: `_bmad-output/implementation-artifacts/4-3-vente-brouillon-produits-draft-validation-admin.md`#AC12 — Day-close exclusion]
- [Source: `keevo/backend/src/main/java/com/keevo/commerce/sale/domain/model/SaleStatus.java`]
- [Source: `keevo/backend/src/main/java/com/keevo/commerce/sale/adapter/out/persistence/jpa/SaleSpringRepository.java`]
- [Source: `keevo/backend/src/main/java/com/keevo/commerce/sale/adapter/out/persistence/impl/SaleRepositoryAdapter.java`]
- [Source: `keevo/app/lib/features/pos/domain/repository/sale_repository.dart`]
- [Source: `keevo/app/lib/features/pos/presentation/provider/pos_providers.dart`]
- [Source: `keevo/app/lib/features/pos/presentation/page/pos_page.dart`]
- [Source: `keevo/app/lib/features/pos/presentation/page/sale_success_page.dart`]
- [Source: `keevo/app/lib/core/storage/app_constants.dart`]
- [Source: `keevo/app/lib/core/router/app_router.dart`]

---

## Dev Agent Record

### Agent Model Used

Claude Sonnet 4.5 — story creation session 2026-03-18

### Debug Log References

### Completion Notes List

### File List

**Backend (Java) — New Files:**
- `keevo/backend/src/main/java/com/keevo/commerce/sale/domain/model/DayClosure.java`
- `keevo/backend/src/main/java/com/keevo/commerce/sale/domain/model/DayClosureSummary.java`
- `keevo/backend/src/main/java/com/keevo/commerce/sale/domain/model/DayClosedEvent.java`
- `keevo/backend/src/main/java/com/keevo/commerce/sale/domain/port/in/CloseDayUseCase.java`
- `keevo/backend/src/main/java/com/keevo/commerce/sale/domain/port/in/GetSalesHistoryUseCase.java`
- `keevo/backend/src/main/java/com/keevo/commerce/sale/domain/port/out/DayClosureRepository.java`
- `keevo/backend/src/main/java/com/keevo/commerce/sale/domain/port/out/WhatsAppPort.java`
- `keevo/backend/src/main/java/com/keevo/commerce/sale/application/service/CloseDayService.java`
- `keevo/backend/src/main/java/com/keevo/commerce/sale/application/service/DayClosureSummaryBuilder.java`
- `keevo/backend/src/main/java/com/keevo/commerce/sale/application/service/GetSalesHistoryService.java`
- `keevo/backend/src/main/java/com/keevo/commerce/sale/application/service/DayClosureAutoScheduler.java`
- `keevo/backend/src/main/java/com/keevo/commerce/sale/application/service/DayClosureWhatsAppListener.java`
- `keevo/backend/src/main/java/com/keevo/commerce/sale/application/service/ClosureReportStrategy.java`
- `keevo/backend/src/main/java/com/keevo/commerce/sale/application/service/ManualReportStrategy.java`
- `keevo/backend/src/main/java/com/keevo/commerce/sale/application/service/AutoReportStrategy.java`
- `keevo/backend/src/main/java/com/keevo/commerce/sale/adapter/out/persistence/entity/DayClosureJpaEntity.java`
- `keevo/backend/src/main/java/com/keevo/commerce/sale/adapter/out/persistence/jpa/DayClosureSpringRepository.java`
- `keevo/backend/src/main/java/com/keevo/commerce/sale/adapter/out/persistence/impl/DayClosureRepositoryAdapter.java`
- `keevo/backend/src/main/java/com/keevo/commerce/sale/adapter/out/messaging/NoOpWhatsAppAdapter.java`
- `keevo/backend/src/main/java/com/keevo/commerce/sale/adapter/in/rest/DayClosureController.java`
- `keevo/backend/src/main/java/com/keevo/commerce/sale/adapter/in/rest/SaleHistoryController.java`
- `keevo/backend/src/main/java/com/keevo/commerce/sale/adapter/in/rest/dto/CloseDayRequestDto.java`
- `keevo/backend/src/main/java/com/keevo/commerce/sale/adapter/in/rest/dto/DayClosureResponseDto.java`
- `keevo/backend/src/main/java/com/keevo/commerce/sale/adapter/in/rest/dto/DayClosureSummaryDto.java`
- `keevo/backend/src/main/java/com/keevo/commerce/sale/adapter/in/rest/dto/SaleHistoryItemDto.java`
- `keevo/backend/src/main/java/com/keevo/commerce/sale/adapter/in/rest/dto/SaleHistoryPageDto.java`
- `keevo/backend/src/main/java/com/keevo/commerce/sale/adapter/in/rest/dto/SaleItemHistoryDto.java`

**Backend (Java) — Tests:**
- `keevo/backend/src/test/java/com/keevo/commerce/sale/domain/DayClosureTest.java`
- `keevo/backend/src/test/java/com/keevo/commerce/sale/domain/DayClosureSummaryTest.java`
- `keevo/backend/src/test/java/com/keevo/commerce/sale/domain/DayClosedEventTest.java`
- `keevo/backend/src/test/java/com/keevo/commerce/sale/application/CloseDayServiceTest.java`
- `keevo/backend/src/test/java/com/keevo/commerce/sale/application/DayClosureAutoSchedulerTest.java`
- `keevo/backend/src/test/java/com/keevo/commerce/sale/application/DayClosureSummaryBuilderTest.java`
- `keevo/backend/src/test/java/com/keevo/commerce/sale/application/GetSalesHistoryServiceTest.java`
- `keevo/backend/src/test/java/com/keevo/commerce/sale/adapter/in/rest/DayClosureControllerTest.java`
- `keevo/backend/src/test/java/com/keevo/commerce/sale/adapter/in/rest/SaleHistoryControllerTest.java`

**Backend (Java) — Modified:**
- `keevo/backend/src/main/java/com/keevo/commerce/sale/adapter/out/persistence/impl/SaleRepositoryAdapter.java` (added findSalesHistory)
- `keevo/backend/src/main/java/com/keevo/commerce/sale/adapter/out/persistence/jpa/SaleSpringRepository.java` (added occurredAt queries)
- `keevo/backend/src/main/java/com/keevo/commerce/sale/domain/port/out/SaleRepository.java` (added findSalesHistory port)
- `keevo/backend/src/main/java/com/keevo/identity/auth/adapter/out/persistence/impl/TenantRepositoryAdapter.java` (added findAll)
- `keevo/backend/src/main/java/com/keevo/identity/auth/domain/port/out/TenantRepository.java` (added findAll)
- `keevo/backend/src/main/java/com/keevo/shared/domain/exception/ErrorCode.java` (added DAY_ALREADY_CLOSED)
- `keevo/backend/src/main/java/com/keevo/shared/infrastructure/persistence/TenantSchemaProvisioner.java` (added day_closures DDL)
- `keevo/backend/src/main/java/com/keevo/shared/infrastructure/persistence/TenantSchemaSyncService.java` (added migration)
- `keevo/backend/src/main/java/com/keevo/shared/infrastructure/web/GlobalExceptionHandler.java`
- `keevo/backend/src/test/java/com/keevo/commerce/sale/adapter/out/persistence/SaleRepositoryAdapterTest.java`

**Flutter (Dart) — New Files:**
- `keevo/app/lib/core/storage/day_closures_table.dart`
- `keevo/app/lib/features/pos/domain/model/day_closure_model.dart`
- `keevo/app/lib/features/pos/domain/model/sales_history_filter.dart`
- `keevo/app/lib/features/pos/domain/repository/day_closure_repository.dart`
- `keevo/app/lib/features/pos/domain/usecase/close_day_usecase.dart`
- `keevo/app/lib/features/pos/domain/usecase/get_sales_history_usecase.dart`
- `keevo/app/lib/features/pos/data/datasource/local_day_closure_datasource.dart`
- `keevo/app/lib/features/pos/data/datasource/remote_day_closure_datasource.dart`
- `keevo/app/lib/features/pos/data/repository/day_closure_repository_impl.dart`
- `keevo/app/lib/features/pos/presentation/provider/day_closure_providers.dart`
- `keevo/app/lib/features/pos/presentation/widget/day_close_button.dart`
- `keevo/app/lib/features/pos/presentation/widget/day_summary_bottom_sheet.dart`
- `keevo/app/lib/features/pos/presentation/widget/day_close_success_overlay.dart`
- `keevo/app/lib/features/pos/presentation/page/sales_history_page.dart`
- `keevo/app/lib/features/pos/presentation/page/sale_detail_page.dart`

**Flutter (Dart) — Tests:**
- `keevo/app/test/features/pos/presentation/widget/day_close_button_test.dart`
- `keevo/app/test/features/pos/presentation/widget/day_summary_bottom_sheet_test.dart`
- `keevo/app/test/features/pos/presentation/page/sales_history_page_test.dart`
- `keevo/app/test/features/pos/presentation/page/sale_detail_page_test.dart`
- `keevo/app/test/features/pos/data/datasource/local_day_closure_datasource_test.dart`

**Flutter (Dart) — Modified:**
- `keevo/app/lib/core/router/app_router.dart` (added /pos/sales-history routes)
- `keevo/app/lib/core/storage/app_database.dart` (added day_closures table, schema v14)
- `keevo/app/lib/features/pos/data/repository/sale_repository_impl.dart` (added getSalesHistory)
- `keevo/app/lib/features/pos/domain/repository/sale_repository.dart` (added getSalesHistory)
- `keevo/app/lib/features/pos/presentation/page/pos_page.dart` (integrated DayCloseButton + history icon + auto-closure snackbar)
- `keevo/app/lib/features/pos/presentation/provider/pos_providers.dart`

**Scripts:**
- `keevo/scripts/curl-tests-story-4-4.sh` (new — backend integration tests)

**Documentation:**
- `_bmad-output/implementation-artifacts/4-4-cloture-journaliere-historique-des-ventes.md` (this file)
- `_bmad-output/implementation-artifacts/sprint-status.yaml` (updated 4-4 status)
