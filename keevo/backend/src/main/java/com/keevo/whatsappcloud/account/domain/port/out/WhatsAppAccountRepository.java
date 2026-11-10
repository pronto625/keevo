package com.keevo.whatsappcloud.account.domain.port.out;

import com.keevo.whatsappcloud.account.domain.model.RoutedAccount;
import com.keevo.whatsappcloud.account.domain.model.WhatsAppAccount;

import java.util.Collection;
import java.util.List;
import java.util.Optional;

/** Story 21.5 — the companies' WhatsApp Business accounts ({@code public.whatsapp_accounts}, V68). */
public interface WhatsAppAccountRepository {

    /** The account of the company of schema {@code tenantSchema} (PK lookup through the tenant's unique schema). */
    Optional<WhatsAppAccount> find(String tenantSchema);

    /** Live companies (not DELETED) owning these number ids — one indexed {@code = ANY} lookup. */
    List<RoutedAccount> routeByPhoneNumberIds(Collection<String> phoneNumberIds);

    /** Live companies (not DELETED) whose account belongs to these WABA ids — one indexed {@code = ANY} lookup. */
    List<RoutedAccount> routeByWabaIds(Collection<String> wabaIds);

    /**
     * Inserts or replaces the company's account (one per company).
     *
     * @return false when the number is already connected to another company (unique number id), nothing written
     */
    boolean save(WhatsAppAccount account);

    /** Deletes the account of {@code phoneNumberId} when its company is DELETED (the number is free again). */
    void deleteOfDeletedTenants(String phoneNumberId);

    /** Deletes the company's account; returns it when there was one. */
    Optional<WhatsAppAccount> delete(String tenantSchema);
}
