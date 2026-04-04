package com.keevo.commerce.sale.application.service;

import com.keevo.commerce.sale.domain.model.DayClosedEvent;
import com.keevo.messaging.whatsapp.domain.port.out.WhatsAppPort;
import com.keevo.identity.auth.domain.port.out.UserRepository;
import com.keevo.store.store.domain.port.out.StoreRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.event.EventListener;
import org.springframework.stereotype.Component;

import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.Locale;

/**
 * DayClosureWhatsAppListener — GoF Observer pattern, listens to DayClosedEvent.
 * Story 4.4 — Clôture Journalière & Historique des Ventes
 *
 * <p>Builds and sends WhatsApp report to owner using ClosureReportStrategy.
 *
 * <p>MVP: Uses NoOpWhatsAppAdapter (logs only, no real sending).
 *
 * <p>DEPRECATED by story 7.2 — replaced by EndOfDayReportListener.
 * Disabled by default; re-enable with keevo.reporting.legacy-listener=true.
 */
@ConditionalOnProperty(name = "keevo.reporting.legacy-listener", havingValue = "true", matchIfMissing = false)
@Component
public class DayClosureWhatsAppListener {

    private static final Logger log = LoggerFactory.getLogger(DayClosureWhatsAppListener.class);
    private static final ZoneId WAT_ZONE = ZoneId.of("Africa/Lagos");
    private static final DateTimeFormatter FR_DATE = DateTimeFormatter.ofPattern("d MMMM yyyy", Locale.FRENCH);
    private static final DateTimeFormatter TIME_FORMAT = DateTimeFormatter.ofPattern("HH'h'mm");

    private final WhatsAppPort whatsAppPort;
    private final StoreRepository storeRepository;
    private final ClosureReportStrategy manualReportStrategy;
    private final ClosureReportStrategy autoReportStrategy;

    // MVP: owner phone from system property or default
    private static final String DEFAULT_OWNER_PHONE = "+243000000000";

    public DayClosureWhatsAppListener(WhatsAppPort whatsAppPort,
                                      StoreRepository storeRepository) {
        this.whatsAppPort = whatsAppPort;
        this.storeRepository = storeRepository;
        this.manualReportStrategy = new ManualReportStrategy();
        this.autoReportStrategy = new AutoReportStrategy();
    }

    @EventListener
    public void onDayClosed(DayClosedEvent event) {
        log.info("DayClosedEvent received: storeId={}, isAutomatic={}",
                event.storeId(), event.isAutomatic());

        // Get store name
        String storeName = storeRepository.findById(event.storeId())
                .map(s -> s.name())
                .orElse("Boutique #" + event.storeId().toString().substring(0, 8));

        // MVP: employee name from phone (User.fullName not available in this story)
        String employeeName = "Employé";

        // Choose strategy based on isAutomatic
        ClosureReportStrategy strategy = event.isAutomatic() ? autoReportStrategy : manualReportStrategy;

        // Build report
        String report = strategy.buildReport(event, storeName, employeeName);

        // Get owner phone (MVP: system property or default)
        String ownerPhone = System.getProperty("keevo.owner.whatsapp", DEFAULT_OWNER_PHONE);

        // Send via WhatsAppPort (NoOp for MVP — logs only)
        whatsAppPort.sendReport(ownerPhone, report);
    }
}
