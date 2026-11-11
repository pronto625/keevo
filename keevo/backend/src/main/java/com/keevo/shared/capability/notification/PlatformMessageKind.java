package com.keevo.shared.capability.notification;

/**
 * Story 19.9c — the kinds of WhatsApp messages Keevo sends from its platform number (everything except sign-in /
 * reset codes, which use {@link WhatsAppSender#sendCode}). Each kind maps to one Meta template of messaging's
 * catalogue; a caller names the kind and passes the template parameters, never a Meta template name, so renaming a
 * template after Meta's review only touches the catalogue.
 *
 * <p>{@link #parameterCount()} is the number of template variables ({@code {{1}}}…) of the kind: a
 * {@link PlatformWhatsAppMessage} with another count is a programming error.
 */
public enum PlatformMessageKind {

    /** Daily report of a store (automatic, manual, resent, retried, onboarding test). Detail = report text. */
    DAILY_REPORT(1),
    /** Weekly report. Detail = report text. */
    WEEKLY_REPORT(1),
    /** Combined daily summary of every store. Detail = report text. */
    MULTI_STORE_REPORT(1),
    /** Cash register closure report. Detail = closure text. */
    DAY_CLOSURE(1),
    /** Low stock alert (one product or a batch). Detail = alert text. */
    STOCK_ALERT(1),
    /** Products created by an employee. Detail = recap text. */
    PRODUCTS_CREATED(1),
    /** Stock changed by an employee. Detail = recap text. */
    STOCK_CHANGED(1),
    /** Prices changed by an employee. Detail = recap text. */
    PRICES_CHANGED(1),
    /** Sale draft pending / validated / upgraded. Detail = title and body. */
    SALE_FOLLOW_UP(1),
    /** Paid modules expired or disabled. Detail = notice text. */
    MODULE_LICENCE(1),
    /** Plan activated, downgraded or expired. Detail = notice text. */
    PLAN_CHANGE(1),
    /** Plan or trial about to end. Detail = reminder text. */
    PLAN_REMINDER(1),
    /** Company account suspended by the super-admin. No variable. */
    ACCOUNT_SUSPENDED(0),
    /** Company account reactivated by the super-admin. No variable. */
    ACCOUNT_REACTIVATED(0),
    /** Devices silent for several days. Parameter = number of days. */
    SYNC_SILENCE(1),
    /** A person was added to a company. Parameters = company name, role label. */
    MEMBER_ADDED(2),
    /** Company deletion requested. Parameter = deletion date (dd/MM/yyyy). */
    DELETION_SCHEDULED(1),
    /** Company deleted. No variable. */
    ACCOUNT_DELETED(0),
    /**
     * Invoice / quote link sent to a customer from the platform number (fallback of the company's own WhatsApp
     * Business, 21.5). Parameters = customer name, « facture » | « devis », reference, seller, amount, link.
     */
    DOCUMENT_LINK(6);

    private final int parameterCount;

    PlatformMessageKind(int parameterCount) {
        this.parameterCount = parameterCount;
    }

    public int parameterCount() {
        return parameterCount;
    }
}
