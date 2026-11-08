package com.keevo.acceptance;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.keevo.invoicing.invoice.adapter.in.scheduling.InvoiceOverdueJob;
import com.keevo.invoicing.invoice.domain.port.out.InvoicePaymentRepository;
import com.keevo.shared.capability.notification.DeliveryResult;
import com.keevo.shared.capability.notification.SmsMessage;
import com.keevo.shared.capability.notification.SmsSender;
import com.keevo.shared.capability.notification.TenantMessaging;
import com.keevo.shared.domain.time.KeevoZones;
import com.keevo.shared.infrastructure.persistence.FullContextIntegrationTest;
import com.keevo.shared.infrastructure.persistence.TenantContext;
import com.keevo.shared.infrastructure.security.JwtTokenProvider;
import com.keevo.shared.infrastructure.security.TestAccess;
import com.keevo.shared.module.ModuleRegistry;
import com.keevo.shared.module.application.port.PlanModuleRowStore;
import org.apache.pdfbox.Loader;
import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.pdfbox.text.PDFTextStripper;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Disabled;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.MethodOrderer;
import org.junit.jupiter.api.Order;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestInstance;
import org.junit.jupiter.api.TestMethodOrder;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.context.ApplicationContext;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.http.HttpMethod;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.test.util.AopTestUtils;
import org.springframework.test.util.ReflectionTestUtils;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import java.nio.charset.StandardCharsets;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.time.YearMonth;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ThreadLocalRandom;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;

/**
 * Story 20.12 — acceptance test « parcours Bref sur Keevo » (gate G1 of the merge): the day of a Bref owner replayed on
 * the Keevo API through the real HTTP chain (production security filter, real JWTs issued by the OTP sign-in, module
 * gates, PostgreSQL, the real 19.8 quota counters), every step asserted at the REST surface the app uses:
 * <ol>
 *   <li>OTP sign-in by phone → company created → onboarding « Documents » with the shop → PRO plan (super-admin) →
 *       the owner activates Facturation, Dépenses and Finance in « Mes modules » → seller profile;</li>
 *   <li>quote (no number, no quota) → conversion (next gapless {@code FAC-} number, documents quota consumed once,
 *       idempotent);</li>
 *   <li>deposit → instalment;</li>
 *   <li>overdue job at a fixed clock (the next morning) → « En retard » + the owner's « Factures en retard »;</li>
 *   <li>instalment settled → PAID;</li>
 *   <li>SMS sending — <b>needs Story 20-8</b>, disabled until it is merged;</li>
 *   <li>expense → finance summary (exact revenue excl. VAT, expenses, result, margin) and dashboard revenue;</li>
 *   <li>public link → the PDF opens without login;</li>
 *   <li>routed item (review 20-3): a quote whose invoice is cancelled stays CONVERTED — as in Bref, where the quote
 *       becomes the sale and a cancelled sale has no way back: the owner starts a new quote; revenue unchanged;</li>
 *   <li>every quota counter and the exact revenue at the end of the day.</li>
 * </ol>
 * And the symmetric Keevo journey, which must stay green: POS sale → automatic invoice (bridge) → day closure, the
 * revenue counted once (dashboard, closure, finance) and no documents quota consumed.
 *
 * <p>The steps share one tenant: each runs only when the previous one passed (run the whole class). Amounts are
 * checked by hand in the comments: line 100 000 HT, VAT 19.25 % = 19 250, TTC 119 250.
 */
@AutoConfigureMockMvc
@Transactional(propagation = Propagation.NOT_SUPPORTED)
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
@TestMethodOrder(MethodOrderer.OrderAnnotation.class)
@DisplayName("Parcours Bref sur Keevo — test d'acceptation (Story 20.12)")
class BrefJourneyAcceptanceFullContextTest extends FullContextIntegrationTest {

    private static final Duration WAIT = Duration.ofSeconds(20);
    private static final AtomicInteger IP = new AtomicInteger(1);

    // hand-checked amounts of the Bref journey
    private static final long LINE_HT = 100_000;
    private static final long VAT = 19_250;        // 100 000 × 19.25 % (exact, no rounding)
    private static final long TTC = 119_250;       // 100 000 + 19 250
    private static final long DEPOSIT = 50_000;
    private static final long INSTALMENT = 69_250; // 119 250 − 50 000
    private static final long EXPENSE = 30_000;
    private static final long NET = 70_000;        // CA HT 100 000 − dépenses 30 000
    private static final String MARGIN_PCT = "70"; // 70 000 / 100 000
    private static final String EXPENSE_RATIO_PCT = "30";
    private static final long POS_SALE = 2_000;    // 2 × 1 000, cash, not VAT-registered

    @Autowired MockMvc mockMvc;
    @Autowired ObjectMapper objectMapper;
    @Autowired JdbcTemplate jdbc;
    @Autowired PasswordEncoder passwordEncoder;
    @Autowired JwtTokenProvider jwtTokenProvider;
    @Autowired ModuleRegistry registry;
    @Autowired PlanModuleRowStore liveTenants;
    @Autowired InvoicePaymentRepository invoicePayments;
    @Autowired ApplicationEventPublisher publisher;
    @Autowired PlatformTransactionManager transactionManager;
    @Autowired ApplicationContext context;

    private final LocalDate today = LocalDate.now(KeevoZones.WAT);
    private final String year = String.valueOf(today.getYear());
    private final String month = YearMonth.from(today).toString();
    private final List<UUID> tenants = new ArrayList<>();
    private String adminToken;
    private int passed;
    private boolean smsSent;

    // Bref tenant
    private Owner bref;
    private String quoteId;
    private String invoiceId;
    private String invoiceNumber;
    private String instalmentId;

    @BeforeAll
    void setUp() {
        adminToken = TestAccess.token(jdbc, jwtTokenProvider, UUID.randomUUID(), "public", "SUPER_ADMIN");
    }

    @AfterAll
    void cleanUp() {
        for (UUID tenantId : tenants) {
            jdbc.update("DELETE FROM public.tenant_modules WHERE tenant_id = ?", tenantId);
            jdbc.update("DELETE FROM public.tenant_module_activation WHERE tenant_id = ?", tenantId);
            jdbc.update("DELETE FROM public.usage_counters WHERE tenant_id = ?", tenantId);
        }
        TenantContext.clear();
        SecurityContextHolder.clearContext();
    }

    // ── 1. sign-in, shop, plan, modules ─────────────────────────────────────────────────────────────────

    @Test
    @Order(1)
    @DisplayName("1 — Connexion OTP → entreprise créée → boutique (parcours Documents) → offre PRO → modules activés "
            + "par le propriétaire → profil vendeur")
    void step01_otpSignInAndShop() throws Exception {
        bref = signUp("Étape 1", "Documents", "DOCUMENT", "Atelier Ngo Carrelage");
        String s = "Étape 1";
        JsonNode modules = json(s + " — Mes modules", call(bref.token, HttpMethod.GET, "/api/v1/tenants/me/modules",
                null), 200);
        assertThat(modules.path("meta").path("navigationPreset").asText()).as(s + " — profil d'interface")
                .isEqualTo("DOCUMENT");
        for (String id : List.of("invoicing", "expenses", "finance", "reporting")) {
            assertThat(module(modules, id).path("state").asText()).as(s + " — module " + id).isEqualTo("ENABLED");
        }
        sellerProfile(s, bref, true);
        assertQuotas(s, bref, 0, 0);
        passed = 1;
    }

    // ── 2. quote → conversion ───────────────────────────────────────────────────────────────────────────

    @Test
    @Order(2)
    @DisplayName("2 — Devis (sans numéro ni quota) → conversion : FAC- suivant sans trou, quota de documents "
            + "consommé une seule fois, conversion idempotente")
    void step02_quoteThenConversion() throws Exception {
        requirePrevious(1, "Étape 2");
        String s = "Étape 2";
        JsonNode quote = data(s + " — création du devis", call(bref.token, HttpMethod.POST, "/api/v1/invoices",
                document("QUOTE", "Société Ndolo", "+237690001212", "Pose de carrelage 40 m²", LINE_HT)), 201);
        quoteId = quote.path("id").asText();
        assertThat(quote.path("kind").asText()).as(s).isEqualTo("QUOTE");
        assertThat(quote.path("status").asText()).as(s).isEqualTo("DRAFT");
        assertThat(quote.path("quoteReference").asText()).as(s + " — référence DV-").matches("DV-[0-9A-F]{8}");
        assertThat(quote.path("number").isNull()).as(s + " — un devis n'a pas de numéro légal").isTrue();
        assertThat(quote.path("totalTtc").asLong()).as(s + " — TTC").isEqualTo(TTC);
        assertQuotas(s + " — le devis ne consomme pas de quota", bref, 0, 0);

        MvcResult converted = call(bref.token, HttpMethod.POST, "/api/v1/invoices/" + quoteId + "/convert", null);
        JsonNode invoice = data(s + " — conversion", converted, 201);
        invoiceId = invoice.path("id").asText();
        invoiceNumber = invoice.path("number").asText();
        assertThat(invoiceNumber).as(s + " — premier numéro de l'année, sans trou").isEqualTo("FAC-" + year + "-000001");
        assertThat(invoice.path("kind").asText()).as(s).isEqualTo("INVOICE");
        assertThat(invoice.path("status").asText()).as(s).isEqualTo("ISSUED");
        assertThat(invoice.path("issueDate").asText()).as(s + " — date d'émission = jour de conversion")
                .isEqualTo(today.toString());
        assertThat(invoice.path("sourceQuoteId").asText()).as(s).isEqualTo(quoteId);
        assertThat(invoice.path("totalHt").asLong()).as(s + " — HT").isEqualTo(LINE_HT);
        assertThat(invoice.path("totalVat").asLong()).as(s + " — TVA").isEqualTo(VAT);
        assertThat(invoice.path("totalTtc").asLong()).as(s + " — TTC").isEqualTo(TTC);
        assertThat(invoice.path("paymentState").asText()).as(s).isEqualTo("UNPAID");
        assertQuotas(s + " — quota consommé à la conversion", bref, 1, 0);

        JsonNode again = data(s + " — conversion rejouée", call(bref.token, HttpMethod.POST,
                "/api/v1/invoices/" + quoteId + "/convert", null), 200);
        assertThat(again.path("id").asText()).as(s + " — même facture").isEqualTo(invoiceId);
        JsonNode converted2 = get(bref, quoteId);
        assertThat(converted2.path("status").asText()).as(s + " — devis converti").isEqualTo("CONVERTED");
        assertThat(converted2.path("convertedInvoiceId").asText()).as(s).isEqualTo(invoiceId);
        assertQuotas(s + " — rejouer ne consomme rien", bref, 1, 0);
        passed = 2;
    }

    // ── 3. deposit → instalment ─────────────────────────────────────────────────────────────────────────

    @Test
    @Order(3)
    @DisplayName("3 — Acompte de 50 000 → partiellement payée ; échéance de 69 250 programmée aujourd'hui")
    void step03_depositThenInstalment() throws Exception {
        requirePrevious(2, "Étape 3");
        String s = "Étape 3";
        Map<String, Object> deposit = new LinkedHashMap<>();
        deposit.put("paymentId", UUID.randomUUID().toString());
        deposit.put("amount", DEPOSIT);
        deposit.put("paidAt", today.toString());
        deposit.put("method", "Mobile Money");
        deposit.put("note", "Acompte");
        JsonNode partial = data(s + " — acompte", call(bref.token, HttpMethod.POST,
                "/api/v1/invoices/" + invoiceId + "/payments", deposit), 201);
        assertThat(partial.path("paymentState").asText()).as(s).isEqualTo("PARTIAL");
        assertThat(partial.path("amountPaid").asLong()).as(s).isEqualTo(DEPOSIT);

        instalmentId = UUID.randomUUID().toString();
        Map<String, Object> instalment = new LinkedHashMap<>();
        instalment.put("paymentId", instalmentId);
        instalment.put("amount", INSTALMENT);
        instalment.put("dueDate", today.toString());
        JsonNode scheduled = data(s + " — échéance", call(bref.token, HttpMethod.POST,
                "/api/v1/invoices/" + invoiceId + "/payments", instalment), 201);
        assertThat(scheduled.path("paymentState").asText()).as(s + " — un paiement ne met jamais en retard")
                .isEqualTo("PARTIAL");
        assertThat(scheduled.path("payments")).as(s + " — acompte + échéance").hasSize(2);
        assertThat(scheduled.path("amountDue").asLong()).as(s).isEqualTo(TTC);
        passed = 3;
    }

    // ── 4. overdue ──────────────────────────────────────────────────────────────────────────────────────

    @Test
    @Order(4)
    @DisplayName("4 — Le traitement du lendemain matin (horloge fixe) passe la facture « En retard » et prévient le "
            + "propriétaire")
    void step04_overdueJobAtFixedClock() throws Exception {
        requirePrevious(3, "Étape 4");
        String s = "Étape 4";
        Clock nextMorning = Clock.fixed(today.plusDays(1).atTime(8, 0).atZone(KeevoZones.WAT).toInstant(),
                KeevoZones.WAT);
        InvoiceOverdueJob job = new InvoiceOverdueJob(liveTenants, invoicePayments, registry, publisher,
                transactionManager, nextMorning);
        InvoiceOverdueJob.Report report = job.run();
        assertThat(report.overdue()).as(s + " — au moins la facture du parcours : " + report).isPositive();

        JsonNode overdue = get(bref, invoiceId);
        assertThat(overdue.path("paymentState").asText()).as(s).isEqualTo("OVERDUE");
        assertThat(overdue.path("paymentStateLabel").asText()).as(s).isEqualTo("En retard");
        assertThat(data(s + " — liste « En retard »", call(bref.token, HttpMethod.GET,
                "/api/v1/invoices?paymentState=OVERDUE", null), 200))
                .extracting(n -> n.path("id").asText()).containsExactly(invoiceId);

        await().atMost(WAIT).untilAsserted(() -> {
            JsonNode inbox = data(s + " — notifications", call(bref.token, HttpMethod.GET, "/api/v1/notifications",
                    null), 200);
            List<JsonNode> late = new ArrayList<>();
            inbox.forEach(n -> {
                if ("INVOICE_OVERDUE".equals(n.path("type").asText())) {
                    late.add(n);
                }
            });
            assertThat(late).as(s + " — une notification « Factures en retard »").hasSize(1);
            assertThat(late.get(0).path("title").asText()).isEqualTo("Factures en retard");
            assertThat(late.get(0).path("body").asText())
                    .isEqualTo("1 facture(s) en retard de paiement : " + invoiceNumber);
        });

        job.run(); // a second run finds nothing new for this company: no second notification
        assertThat(get(bref, invoiceId).path("paymentState").asText()).isEqualTo("OVERDUE");
        JsonNode inbox = data(s + " — notifications", call(bref.token, HttpMethod.GET, "/api/v1/notifications", null),
                200);
        long overdueNotifications = 0;
        for (JsonNode n : inbox) {
            if ("INVOICE_OVERDUE".equals(n.path("type").asText())) {
                overdueNotifications++;
            }
        }
        assertThat(overdueNotifications).as(s + " — un second passage ne renotifie pas").isEqualTo(1);
        passed = 4;
    }

    // ── 5. payment → PAID ───────────────────────────────────────────────────────────────────────────────

    @Test
    @Order(5)
    @DisplayName("5 — L'échéance est réglée → facture payée")
    void step05_paymentThenPaid() throws Exception {
        requirePrevious(4, "Étape 5");
        String s = "Étape 5";
        JsonNode paid = data(s + " — règlement de l'échéance", call(bref.token, HttpMethod.POST,
                "/api/v1/invoices/" + invoiceId + "/payments/" + instalmentId + "/settle",
                Map.of("method", "Espèces")), 200);
        assertThat(paid.path("paymentState").asText()).as(s).isEqualTo("PAID");
        assertThat(paid.path("status").asText()).as(s).isEqualTo("PAID");
        assertThat(paid.path("amountPaid").asLong()).as(s).isEqualTo(TTC);
        assertThat(paid.path("paidAt").isNull()).as(s + " — paid_at historique renseigné").isFalse();
        passed = 5;
    }

    // ── 6. SMS (Story 20-8) ─────────────────────────────────────────────────────────────────────────────

    /**
     * Needs Story 20-8 ({@code POST /invoices/{id}/send} with {@code channel: "SMS"}, bean {@code smsInvoiceChannel}).
     * A capturing fake SMS sender replaces the provider (never a real SMS). Enabled when 20-8 is merged.
     */
    @Test
    @Order(6)
    @Disabled("Story 20-8 (envoi SMS / e-mail) pas encore fusionnée : à activer au rebase sur 20-8")
    @DisplayName("6 — Envoi par SMS : texte avec le lien public, quota SMS décompté après succès du fournisseur")
    void step06_sendBySms() throws Exception {
        requirePrevious(5, "Étape 6");
        String s = "Étape 6";
        FakeSms sms = new FakeSms();
        Map<Object, Object> originals = new LinkedHashMap<>();
        Object messaging = AopTestUtils.getUltimateTargetObject(context.getBean(TenantMessaging.class));
        Object channel = AopTestUtils.getUltimateTargetObject(context.getBean("smsInvoiceChannel"));
        try {
            for (Object target : List.of(messaging, channel)) {
                originals.put(target, ReflectionTestUtils.getField(target, "smsSender"));
                ReflectionTestUtils.setField(target, "smsSender", sms);
            }
            JsonNode sent = data(s + " — envoi SMS", call(bref.token, HttpMethod.POST,
                    "/api/v1/invoices/" + invoiceId + "/send", Map.of("channel", "SMS", "phone", "+237690001212")),
                    200);
            assertThat(sent.path("sendCount").asInt()).as(s).isEqualTo(1);
            assertThat(sent.path("lastSentChannel").asText()).as(s).isEqualTo("SMS");
            assertThat(sms.sent).as(s + " — un SMS parti").hasSize(1);
            SmsMessage message = sms.sent.get(0);
            assertThat(message.to()).as(s).isEqualTo("+237690001212");
            assertThat(message.text()).as(s + " — lien public dans le texte").contains("/api/v1/invoices/");
            String link = message.text().substring(message.text().indexOf("/api/v1/")).split("\\s")[0];
            assertThat(pdfText(call(null, HttpMethod.GET, link, null))).as(s + " — le lien du SMS ouvre le PDF")
                    .contains(invoiceNumber);
            smsSent = true;
            assertQuotas(s + " — SMS décompté après succès", bref, 1, 1);
        } finally {
            originals.forEach((target, original) -> ReflectionTestUtils.setField(target, "smsSender", original));
        }
    }

    // ── 7. expense → finance ────────────────────────────────────────────────────────────────────────────

    @Test
    @Order(7)
    @DisplayName("7 — Dépense « Loyer » de 30 000 → résumé finance exact (CA HT, dépenses, résultat, marge) et CA du "
            + "tableau de bord")
    void step07_expenseThenFinance() throws Exception {
        requirePrevious(5, "Étape 7");
        String s = "Étape 7";
        Map<String, Object> expense = new LinkedHashMap<>();
        expense.put("amount", EXPENSE);
        expense.put("category", "rent");
        expense.put("expenseDate", today.toString());
        expense.put("label", "Loyer de l'atelier");
        expense.put("paymentMethod", "mobile_money");
        JsonNode created = data(s + " — dépense", call(bref.token, HttpMethod.POST, "/api/v1/expenses", expense),
                201);
        assertThat(created.path("categoryLabel").asText()).as(s).isEqualTo("Loyer");
        assertThat(created.path("storeId").asText()).as(s + " — boutique du propriétaire")
                .isEqualTo(bref.storeId.toString());

        assertBrefRevenue(s);
        passed = 7;
    }

    // ── 8. public link ──────────────────────────────────────────────────────────────────────────────────

    @Test
    @Order(8)
    @DisplayName("8 — Le lien public ouvre le PDF de la facture, sans connexion")
    void step08_publicLinkOpensPdf() throws Exception {
        requirePrevious(7, "Étape 8");
        String s = "Étape 8";
        JsonNode link = data(s + " — lien public", call(bref.token, HttpMethod.POST,
                "/api/v1/invoices/" + invoiceId + "/public-link", null), 200);
        assertThat(link.path("validityDays").asInt()).as(s + " — 30 jours (compte Keevo)").isEqualTo(30);
        String url = link.path("url").asText();
        int i = url.indexOf("/api/v1/");
        assertThat(i).as(url).isPositive();
        String text = pdfText(call(null, HttpMethod.GET, url.substring(i), null));
        assertThat(text).as(s + " — numéro").contains(invoiceNumber);
        assertThat(text).as(s + " — client").contains("Société Ndolo");
        passed = 8;
    }

    // ── 9. routed item: a converted quote whose invoice is cancelled ────────────────────────────────────

    @Test
    @Order(9)
    @DisplayName("9 — Devis converti puis facture annulée : le devis reste « Converti » (comme Bref), le "
            + "propriétaire refait un devis ; CA inchangé, numéro et quota consommés")
    void step09_cancelledConvertedQuote() throws Exception {
        requirePrevious(8, "Étape 9");
        String s = "Étape 9";
        String second = data(s + " — second devis", call(bref.token, HttpMethod.POST, "/api/v1/invoices",
                document("QUOTE", "Awa Traoré", "+237690003434", "Faïence cuisine", 40_000)), 201)
                .path("id").asText();
        JsonNode invoice = data(s + " — conversion", call(bref.token, HttpMethod.POST,
                "/api/v1/invoices/" + second + "/convert", null), 201);
        String cancelledId = invoice.path("id").asText();
        assertThat(invoice.path("number").asText()).as(s + " — numéro suivant").isEqualTo("FAC-" + year + "-000002");
        JsonNode cancelled = data(s + " — annulation de la facture", call(bref.token, HttpMethod.POST,
                "/api/v1/invoices/" + cancelledId + "/cancel", Map.of("reason", "Le client a renoncé")), 200);
        assertThat(cancelled.path("status").asText()).as(s).isEqualTo("CANCELLED");
        assertThat(cancelled.path("number").asText()).as(s + " — le numéro reste pris (pas de trou)")
                .isEqualTo("FAC-" + year + "-000002");

        JsonNode quote = get(bref, second);
        assertThat(quote.path("status").asText()).as(s + " — le devis reste converti").isEqualTo("CONVERTED");
        assertThat(quote.path("convertedInvoiceId").asText()).as(s).isEqualTo(cancelledId);
        JsonNode replay = data(s + " — reconvertir rend la facture annulée, sans nouveau numéro", call(bref.token,
                HttpMethod.POST, "/api/v1/invoices/" + second + "/convert", null), 200);
        assertThat(replay.path("id").asText()).as(s).isEqualTo(cancelledId);
        assertThat(status(call(bref.token, HttpMethod.POST, "/api/v1/invoices/" + second + "/cancel",
                Map.of("reason", "Doublon")))).as(s + " — un devis converti ne s'annule pas").isEqualTo(409);

        // the way forward, as in Bref: a new quote for the same client (no duplicate endpoint, see the spec)
        JsonNode fresh = data(s + " — nouveau devis", call(bref.token, HttpMethod.POST, "/api/v1/invoices",
                document("QUOTE", "Awa Traoré", "+237690003434", "Faïence cuisine (révisé)", 35_000)), 201);
        assertThat(fresh.path("status").asText()).as(s).isEqualTo("DRAFT");
        assertQuotas(s + " — la conversion annulée a consommé un document, le nouveau devis aucun", bref, 2,
                smsSent ? 1 : 0);
        assertBrefRevenue(s + " — facture annulée et devis hors CA");
        passed = 9;
    }

    // ── 10. end of the day: every counter ───────────────────────────────────────────────────────────────

    @Test
    @Order(10)
    @DisplayName("10 — Fin de journée : compteurs de quotas, compteur légal, CA et résultat exacts")
    void step10_endOfDayCounters() throws Exception {
        requirePrevious(9, "Étape 10");
        String s = "Étape 10";
        assertQuotas(s, bref, 2, smsSent ? 1 : 0);
        JsonNode list = data(s + " — factures", call(bref.token, HttpMethod.GET, "/api/v1/invoices?size=100", null),
                200);
        List<String> numbers = new ArrayList<>();
        list.forEach(n -> numbers.add(n.path("number").asText() + "|" + n.path("status").asText()));
        assertThat(numbers).as(s + " — numérotation continue")
                .containsExactlyInAnyOrder("FAC-" + year + "-000001|PAID", "FAC-" + year + "-000002|CANCELLED");
        JsonNode quotes = data(s + " — devis", call(bref.token, HttpMethod.GET, "/api/v1/invoices?kind=QUOTE", null),
                200);
        List<String> states = new ArrayList<>();
        quotes.forEach(n -> states.add(n.path("status").asText()));
        assertThat(states).as(s).containsExactlyInAnyOrder("CONVERTED", "CONVERTED", "DRAFT");
        assertBrefRevenue(s);
        passed = 10;
    }

    // ── symmetric Keevo journey ─────────────────────────────────────────────────────────────────────────

    @Test
    @Order(20)
    @DisplayName("Keevo — vente caisse → facture automatique (pont) → clôture de journée ; CA compté une seule fois, "
            + "aucun quota de documents")
    void keevo_posSaleAutoInvoiceDayClosure() throws Exception {
        String s = "Parcours Keevo";
        Owner pos = signUp(s, "Caisse", "POS", "Quincaillerie Etoa");
        sellerProfile(s, pos, false);

        Map<String, Object> product = new LinkedHashMap<>();
        product.put("name", "Savon de Marseille");
        product.put("price", 1000);
        UUID productId = UUID.fromString(data(s + " — produit", call(pos.token, HttpMethod.POST, "/api/v1/products",
                product), 201).path("id").asText());
        data(s + " — entrée de stock", call(pos.token, HttpMethod.POST, "/api/v1/products/" + productId
                + "/stock/entry", Map.of("storeId", pos.storeId, "quantity", 50, "notes", "Réception")), 201);

        UUID saleId = UUID.randomUUID();
        MvcResult sale = call(pos.token, HttpMethod.POST, "/api/v1/sales", saleBody(saleId, productId, pos.storeId));
        assertThat(status(sale)).as(s + " — vente : " + body(sale)).isEqualTo(201);

        await().atMost(WAIT).untilAsserted(() -> assertThat(data(s + " — facture de la vente", call(pos.token,
                HttpMethod.GET, "/api/v1/invoices?saleId=" + saleId, null), 200)).as(s + " — facture automatique")
                .hasSize(1));
        JsonNode auto = data(s, call(pos.token, HttpMethod.GET, "/api/v1/invoices?saleId=" + saleId, null), 200)
                .get(0);
        assertThat(auto.path("status").asText()).as(s + " — facture payée").isEqualTo("PAID");
        assertThat(auto.path("totalTtc").asLong()).as(s + " — TTC = total de la vente").isEqualTo(POS_SALE);
        assertThat(auto.path("number").asText()).as(s).isEqualTo("FAC-" + year + "-000001");
        assertThat(auto.path("storeId").asText()).as(s + " — boutique de la vente").isEqualTo(pos.storeId.toString());

        // a sale received in the last COMMIT_SAFETY_MARGIN belongs to the next closure (v1s-13-13): the day is closed
        // later, as at the end of a real day
        Thread.sleep(com.keevo.commerce.api.ClosureWindow.COMMIT_SAFETY_MARGIN.plusSeconds(1).toMillis());
        JsonNode closure = data(s + " — clôture de journée", call(pos.token, HttpMethod.POST, "/api/v1/day-closures",
                Map.of("storeId", pos.storeId)), 201);
        assertThat(closure.path("summary").path("totalSales").asLong()).as(s + " — une vente").isEqualTo(1);
        assertThat(closure.path("summary").path("totalRevenue").asLong()).as(s + " — CA de la clôture")
                .isEqualTo(POS_SALE);

        JsonNode dashboard = data(s + " — tableau de bord", call(pos.token, HttpMethod.GET, "/api/v1/dashboard/summary",
                null), 200);
        assertThat(dashboard.path("todayCA").asLong()).as(s + " — CA du jour compté une seule fois (pas 4 000)")
                .isEqualTo(POS_SALE);
        JsonNode finance = data(s + " — finance", call(pos.token, HttpMethod.GET, "/api/v1/finance/summary?period="
                + month, null), 200);
        assertThat(finance.path("revenue").path("ttc").decimalValue()).as(s + " — CA finance une seule fois")
                .isEqualByComparingTo(String.valueOf(POS_SALE));
        assertThat(finance.path("revenue").path("invoiceCount").asLong()).as(s + " — une transaction").isEqualTo(1);
        assertQuotas(s + " — la facture automatique ne consomme pas de quota", pos, 0, 0);
    }

    // ── shared assertions ───────────────────────────────────────────────────────────────────────────────

    /** Revenue of the Bref tenant after step 7: one PAID invoice of 119 250 TTC (19 250 VAT), one expense of 30 000. */
    private void assertBrefRevenue(String s) throws Exception {
        JsonNode f = data(s + " — résumé finance", call(bref.token, HttpMethod.GET, "/api/v1/finance/summary?period="
                + month, null), 200);
        JsonNode revenue = f.path("revenue");
        assertThat(revenue.path("ttc").decimalValue()).as(s + " — CA TTC").isEqualByComparingTo(String.valueOf(TTC));
        assertThat(revenue.path("tax").decimalValue()).as(s + " — TVA").isEqualByComparingTo(String.valueOf(VAT));
        assertThat(revenue.path("excludingTax").decimalValue()).as(s + " — CA HT")
                .isEqualByComparingTo(String.valueOf(LINE_HT));
        assertThat(revenue.path("paid").decimalValue()).as(s + " — payé").isEqualByComparingTo(String.valueOf(TTC));
        assertThat(revenue.path("unpaid").decimalValue()).as(s + " — reste à encaisser").isZero();
        assertThat(revenue.path("invoiceCount").asLong()).as(s + " — une facture").isEqualTo(1);
        assertThat(f.path("expenses").path("total").decimalValue()).as(s + " — dépenses")
                .isEqualByComparingTo(String.valueOf(EXPENSE));
        assertThat(f.path("expenses").path("count").asLong()).as(s).isEqualTo(1);
        assertThat(f.path("result").path("net").decimalValue()).as(s + " — résultat")
                .isEqualByComparingTo(String.valueOf(NET));
        assertThat(f.path("result").path("marginPct").decimalValue()).as(s + " — marge").isEqualByComparingTo(MARGIN_PCT);
        assertThat(f.path("result").path("expenseRatioPct").decimalValue()).as(s + " — poids des dépenses")
                .isEqualByComparingTo(EXPENSE_RATIO_PCT);
        assertThat(f.path("byCategory").get(0).path("categoryLabel").asText()).as(s).isEqualTo("Loyer");

        JsonNode dashboard = data(s + " — tableau de bord", call(bref.token, HttpMethod.GET,
                "/api/v1/dashboard/summary", null), 200);
        assertThat(dashboard.path("todayCA").asLong()).as(s + " — CA du jour (TTC)").isEqualTo(TTC);
    }

    /** {@code GET /api/v1/subscription/me} — the MONTH counters of documents and SMS (« Mon plan »). */
    private void assertQuotas(String s, Owner owner, long documents, long sms) throws Exception {
        JsonNode sub = data(s + " — Mon plan", call(owner.token, HttpMethod.GET, "/api/v1/subscription/me", null), 200);
        assertThat(used(sub, "DOCUMENTS")).as(s + " — documents utilisés ce mois").isEqualTo(documents);
        assertThat(used(sub, "SMS")).as(s + " — SMS utilisés ce mois").isEqualTo(sms);
    }

    private static long used(JsonNode subscription, String metric) {
        for (JsonNode q : subscription.path("quotas")) {
            if (metric.equals(q.path("metric").asText()) && "MONTH".equals(q.path("period").asText())) {
                return q.path("used").asLong();
            }
        }
        return 0;
    }

    // ── sign-up through the real API ────────────────────────────────────────────────────────────────────

    /** An owner of a fresh company: access token, tenant, shop. */
    private record Owner(String token, UUID tenantId, String schema, UUID storeId) {
    }

    /**
     * OTP by phone → verify → create-company → onboarding (sale flow, shop name) → PRO plan by the super-admin →
     * Facturation, Dépenses and Finance activated by the owner in « Mes modules ».
     */
    private Owner signUp(String s, String flowLabel, String saleFlow, String shopName) throws Exception {
        String phone = "+2376" + (50_000_000 + ThreadLocalRandom.current().nextInt(49_999_999));
        assertThat(status(anonymous("/api/v1/auth/otp/request", Map.of("identifier", phone))))
                .as(s + " — demande du code").isEqualTo(200);
        // the code is never readable: the stored hash is replaced by the hash of a known code
        int updated = jdbc.update("UPDATE public.otp_challenges SET code_hash = ? WHERE identifier = ? "
                + "AND consumed_at IS NULL", passwordEncoder.encode("123456"), phone);
        assertThat(updated).as(s + " — défi OTP enregistré pour " + phone).isEqualTo(1);
        MvcResult verified = anonymous("/api/v1/auth/otp/verify", Map.of("identifier", phone, "code", "123456"));
        assertThat(status(verified)).as(s + " — vérification : " + body(verified)).isEqualTo(200);
        JsonNode session = objectMapper.readTree(body(verified));
        assertThat(session.path("memberships")).as(s + " — nouveau compte sans entreprise").isEmpty();

        MvcResult created = anonymous("/api/v1/auth/create-company", Map.of("loginToken",
                session.path("loginToken").asText(), "deviceId", "acceptance-" + saleFlow, "deviceLabel", "Redmi"));
        assertThat(status(created)).as(s + " — création de l'entreprise : " + body(created)).isEqualTo(201);
        JsonNode company = objectMapper.readTree(body(created));
        String token = company.path("token").asText();
        UUID tenantId = UUID.fromString(company.path("tenantId").asText());
        tenants.add(tenantId);
        String schema = jdbc.queryForObject("SELECT schema_name FROM public.tenants WHERE id = ?", String.class,
                tenantId);

        Map<String, Object> onboarding = new LinkedHashMap<>();
        onboarding.put("sectorType", saleFlow.equals("POS") ? "HARDWARE" : "HOME_APPLIANCES");
        onboarding.put("storeName", shopName);
        onboarding.put("defaultSaleFlow", saleFlow);
        MvcResult done = call(token, HttpMethod.POST, "/api/v1/onboarding/complete", onboarding);
        assertThat(status(done)).as(s + " — boutique (parcours " + flowLabel + ") : " + body(done)).isEqualTo(200);
        JsonNode stores = data(s + " — boutiques", call(token, HttpMethod.GET, "/api/v1/stores", null), 200);
        assertThat(stores).as(s + " — une boutique").hasSize(1);
        assertThat(stores.get(0).path("name").asText()).as(s).isEqualTo(shopName);
        UUID storeId = UUID.fromString(stores.get(0).path("id").asText());

        Map<String, Object> plan = new LinkedHashMap<>();
        plan.put("planType", "PRO");
        plan.put("expiresAt", Instant.now().plus(Duration.ofDays(30)).toString());
        MvcResult pro = call(adminToken, HttpMethod.POST, "/api/v1/admin/subscriptions/" + schema + "/activate", plan);
        assertThat(status(pro)).as(s + " — offre PRO : " + body(pro)).isEqualTo(200);
        registry.evict(schema);

        for (String module : List.of("invoicing", "expenses", "finance")) {
            JsonNode activated = data(s + " — activation de " + module, call(token, HttpMethod.PUT,
                    "/api/v1/tenants/me/modules/" + module + "/activation", Map.of("activated", true)), 200);
            assertThat(activated.path("inPlan").asBoolean()).as(s + " — " + module + " inclus dans PRO").isTrue();
            assertThat(activated.path("state").asText()).as(s + " — " + module).isEqualTo("ENABLED");
        }
        return new Owner(token, tenantId, schema, storeId);
    }

    /** The seller profile (« Mon commerce »): VAT-registered at 19.25 % for the Bref tenant, not for the POS one. */
    private void sellerProfile(String s, Owner owner, boolean vatRegistered) throws Exception {
        Map<String, Object> settings = new LinkedHashMap<>();
        settings.put("legalName", vatRegistered ? "Atelier Ngo Carrelage SARL" : "Quincaillerie Etoa");
        settings.put("address", "Rue Joss, Akwa, Douala");
        settings.put("phone", "+237699000000");
        settings.put("vatRegistered", vatRegistered);
        settings.put("vatRate", 19.25);
        settings.put("pricesIncludeVat", !vatRegistered);
        data(s + " — profil vendeur", call(owner.token, HttpMethod.PUT, "/api/v1/invoicing/settings", settings), 200);
    }

    // ── HTTP helpers ────────────────────────────────────────────────────────────────────────────────────

    private void requirePrevious(int previous, String step) {
        assertThat(passed).as(step + " — l'étape " + previous + " doit d'abord réussir (lancer la classe entière, les "
                + "étapes partagent le même commerce)").isGreaterThanOrEqualTo(previous);
    }

    /** A distinct client IP per call: the per-IP limits of the sign-in are shared by the cached context. */
    private MvcResult anonymous(String path, Object body) throws Exception {
        return mockMvc.perform(MockMvcRequestBuilders.post(path).contentType(MediaType.APPLICATION_JSON)
                .header("X-Forwarded-For", "10.20.12." + IP.getAndIncrement())
                .content(objectMapper.writeValueAsString(body))).andReturn();
    }

    private MvcResult call(String token, HttpMethod method, String path, Object body) throws Exception {
        var request = MockMvcRequestBuilders.request(method, path);
        if (token != null) {
            request.header("Authorization", "Bearer " + token);
        }
        if (body != null) {
            request.contentType(MediaType.APPLICATION_JSON).content(objectMapper.writeValueAsString(body));
        }
        return mockMvc.perform(request).andReturn();
    }

    private static int status(MvcResult r) {
        return r.getResponse().getStatus();
    }

    private static String body(MvcResult r) throws Exception {
        return r.getResponse().getContentAsString(StandardCharsets.UTF_8);
    }

    private JsonNode json(String step, MvcResult r, int expectedStatus) throws Exception {
        assertThat(status(r)).as(step + " : " + body(r)).isEqualTo(expectedStatus);
        return objectMapper.readTree(body(r));
    }

    private JsonNode data(String step, MvcResult r, int expectedStatus) throws Exception {
        return json(step, r, expectedStatus).path("data");
    }

    private JsonNode get(Owner owner, String id) throws Exception {
        return data("GET /invoices/" + id, call(owner.token, HttpMethod.GET, "/api/v1/invoices/" + id, null), 200);
    }

    private static JsonNode module(JsonNode list, String moduleId) {
        for (JsonNode m : list.path("data")) {
            if (m.path("moduleId").asText().equals(moduleId)) {
                return m;
            }
        }
        throw new AssertionError("module " + moduleId + " absent de la liste");
    }

    private static String pdfText(MvcResult r) throws Exception {
        assertThat(status(r)).as(body(r)).isEqualTo(200);
        assertThat(r.getResponse().getContentType()).isEqualTo("application/pdf");
        try (PDDocument doc = Loader.loadPDF(r.getResponse().getContentAsByteArray())) {
            return new PDFTextStripper().getText(doc).replace(' ', ' ').replace(' ', ' ');
        }
    }

    private static Map<String, Object> document(String kind, String clientName, String clientPhone,
                                                String description, long unitPrice) {
        Map<String, Object> line = new LinkedHashMap<>();
        line.put("description", description);
        line.put("quantity", 1);
        line.put("unitPrice", unitPrice);
        line.put("vatTreatment", "STANDARD");
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("kind", kind);
        body.put("client", Map.of("name", clientName, "phone", clientPhone));
        body.put("lines", List.of(line));
        return body;
    }

    /** A {@code POST /api/v1/sales} body: 2 × 1 000 F, cash. */
    private static Map<String, Object> saleBody(UUID id, UUID productId, UUID storeId) {
        Map<String, Object> item = new LinkedHashMap<>();
        item.put("productId", productId);
        item.put("productName", "Savon de Marseille");
        item.put("catalogueUnitPrice", 1000);
        item.put("appliedUnitPrice", 1000);
        item.put("quantity", 2);
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("saleId", id);
        body.put("paymentMode", "CASH");
        body.put("storeId", storeId);
        body.put("discountAmount", 0);
        body.put("items", List.of(item));
        return body;
    }

    /** Captures the SMS that would leave (never a real provider). */
    static final class FakeSms implements SmsSender {
        final List<SmsMessage> sent = Collections.synchronizedList(new ArrayList<>());

        @Override
        public boolean isEnabled() {
            return true;
        }

        @Override
        public DeliveryResult send(SmsMessage message) {
            sent.add(message);
            return DeliveryResult.deliveredBy("fake");
        }
    }
}
