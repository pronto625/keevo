package com.keevo.identity.appversion.application.service;

import com.keevo.identity.api.AppVersionAdminPort;
import com.keevo.identity.appversion.domain.model.AppPlatform;
import com.keevo.identity.appversion.domain.model.AppVersionPolicy;
import com.keevo.identity.appversion.domain.port.out.AppVersionPolicyStore;
import com.keevo.shared.domain.AppVersion;
import com.keevo.shared.domain.exception.DomainException;
import com.keevo.shared.domain.exception.ErrorCode;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

import java.net.URI;
import java.time.Clock;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

/**
 * Story 19.10 — super-admin edit of {@code public.app_versions} (Command with validation): every input is validated
 * (French reason, nothing saved on a refusal), then the row is read and upserted in one short transaction, and the
 * change is logged {@code [ADMIN AUDIT]} with the values before and after (plus {@code updated_by} /
 * {@code updated_at} on the row). The table holds a handful of rows: the list is one query.
 */
@Service
public class AppVersionAdminService implements AppVersionAdminPort {

    private static final Logger log = LoggerFactory.getLogger(AppVersionAdminService.class);

    /** {@code app_versions.min_version / latest_version} are {@code VARCHAR(20)}. */
    static final int MAX_VERSION = 20;
    static final int MAX_STORE_URL = 500;
    static final int MAX_MESSAGE = 500;

    private final AppVersionPolicyStore store;
    private final TransactionTemplate tx;
    private final Clock clock;

    public AppVersionAdminService(AppVersionPolicyStore store, PlatformTransactionManager transactionManager,
                                  Clock clock) {
        this.store = store;
        this.tx = new TransactionTemplate(transactionManager);
        this.clock = clock;
    }

    @Override
    public List<AppVersionPolicyView> list() {
        return store.findAll().stream().map(AppVersionAdminService::view).toList();
    }

    @Override
    public SaveResult save(String identity, String platform, String minVersion, String latestVersion,
                           String storeUrl, String message, UUID actorId) {
        if (!AppVersionPolicy.validIdentity(identity)) {
            throw invalid("l'identifiant de l'application doit contenir uniquement des minuscules, des chiffres et "
                    + "des tirets, " + AppVersionPolicy.MAX_IDENTITY + " caractères au plus (exemple : bref)");
        }
        AppPlatform appPlatform = AppPlatform.parse(platform);
        if (appPlatform == null) {
            throw invalid("la plateforme doit être ios, android ou web");
        }
        String min = version(minVersion);
        String latest = version(latestVersion);
        if (min != null && latest != null
                && AppVersion.tryParse(latest).orElseThrow().isBefore(AppVersion.tryParse(min).orElseThrow())) {
            throw invalid("la version minimale dépasse la dernière version");
        }
        String url = storeUrl(storeUrl);
        String text = blankToNull(message);
        if (text != null && text.length() > MAX_MESSAGE) {
            throw invalid("le message ne doit pas dépasser " + MAX_MESSAGE + " caractères");
        }
        AppVersionPolicy policy = new AppVersionPolicy(identity, appPlatform, min, latest, url, text,
                Instant.now(clock), actorId);
        record Saved(Optional<AppVersionPolicy> before, boolean created) {
        }
        Saved saved = tx.execute(status -> {
            Optional<AppVersionPolicy> before = store.find(identity, appPlatform);
            return new Saved(before, store.upsert(policy));
        });
        log.info("[ADMIN AUDIT] App version changed: actor={} identity={} platform={} before={} after={}", actorId,
                identity, appPlatform.code(), saved.before().map(AppVersionAdminService::describe).orElse("none"),
                describe(policy));
        return new SaveResult(view(policy), saved.created());
    }

    @Override
    public void delete(String identity, String platform, UUID actorId) {
        AppPlatform appPlatform = AppPlatform.parse(platform);
        if (!AppVersionPolicy.validIdentity(identity) || appPlatform == null) {
            throw notFound();
        }
        Optional<AppVersionPolicy> before = tx.execute(status -> store.delete(identity, appPlatform));
        if (before == null || before.isEmpty()) {
            throw notFound();
        }
        log.info("[ADMIN AUDIT] App version changed: actor={} identity={} platform={} before={} after={}", actorId,
                identity, appPlatform.code(), describe(before.get()), "none");
    }

    // ── helpers ─────────────────────────────────────────────────────────────

    /** A stored version: a plain release ({@code -pre} / {@code +build} refused: they would compare as the release). */
    private static String version(String value) {
        String v = blankToNull(value);
        if (v != null && (v.length() > MAX_VERSION || !AppVersion.isRelease(v))) {
            throw invalid("la version doit être de la forme 2.1.0");
        }
        return v;
    }

    private static String storeUrl(String value) {
        String url = blankToNull(value);
        if (url == null) {
            return null;
        }
        if (url.length() > MAX_STORE_URL) {
            throw invalid("le lien du store ne doit pas dépasser " + MAX_STORE_URL + " caractères");
        }
        if (!url.regionMatches(true, 0, "https://", 0, "https://".length()) || !hasHost(url)) {
            throw invalid("le lien du store doit être une adresse commençant par https://");
        }
        return url;
    }

    private static boolean hasHost(String url) {
        try {
            String host = new URI(url).getHost();
            return host != null && !host.isBlank();
        } catch (Exception e) {
            return false;
        }
    }

    private static String blankToNull(String value) {
        return value == null || value.isBlank() ? null : value.strip();
    }

    private static AppVersionPolicyView view(AppVersionPolicy p) {
        return new AppVersionPolicyView(p.appIdentity(), p.platform().code(), p.minVersion(), p.latestVersion(),
                p.storeUrl(), p.message(), p.updatedAt());
    }

    /** The audited values of a policy (no secret in it; line breaks flattened so one change stays one log line). */
    private static String describe(AppVersionPolicy p) {
        return String.format("{min=%s, latest=%s, storeUrl=%s, message=%s}", p.minVersion(), p.latestVersion(),
                oneLine(p.storeUrl()), p.message() != null ? "«" + oneLine(p.message()) + "»" : null);
    }

    private static String oneLine(String value) {
        return value == null ? null : value.replaceAll("[\\r\\n\\t]", " ");
    }

    private static DomainException invalid(String reason) {
        String sentence = Character.toUpperCase(reason.charAt(0)) + reason.substring(1);
        return DomainException.userFacing(ErrorCode.APP_VERSION_INVALID, sentence, Map.of("reason", reason));
    }

    private static DomainException notFound() {
        return new DomainException(ErrorCode.APP_VERSION_NOT_FOUND);
    }
}
