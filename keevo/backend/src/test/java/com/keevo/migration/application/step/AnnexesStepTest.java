package com.keevo.migration.application.step;

import com.keevo.assistant.api.AiConversationImportPort;
import com.keevo.messaging.api.NotificationImportPort;
import com.keevo.migration.application.ImportRun;
import com.keevo.migration.application.MigrationUnitOfWork;
import com.keevo.migration.application.ModuleTables;
import com.keevo.migration.domain.model.BlockedCase;
import com.keevo.migration.domain.model.BrefAccount;
import com.keevo.migration.domain.model.BrefAnnexes;
import com.keevo.migration.domain.model.BrefSnapshot;
import com.keevo.migration.domain.model.SourceKind;
import com.keevo.migration.domain.port.out.BrefAnnexesSourcePort;
import com.keevo.migration.domain.port.out.MigrationRecordStore;
import com.keevo.whatsappcloud.api.LegacyWhatsAppImportPort;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.time.Clock;
import java.time.Instant;
import java.util.HashMap;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyCollection;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

@DisplayName("24.6 — AnnexesStep check (section read once, no key → blocked before any write)")
class AnnexesStepTest {

    private static final Instant T = Instant.parse("2026-10-01T08:00:00Z");
    private final BrefAnnexesSourcePort source = mock(BrefAnnexesSourcePort.class);
    private final LegacyWhatsAppImportPort whatsapp = mock(LegacyWhatsAppImportPort.class);
    private final MigrationUnitOfWork uow = mock(MigrationUnitOfWork.class);
    private final UUID owner = UUID.randomUUID();
    private AnnexesStep step;

    private ImportRun run() {
        when(source.kind()).thenReturn(SourceKind.DATABASE);
        step = new AnnexesStep(List.of(source), whatsapp, mock(AiConversationImportPort.class),
                mock(NotificationImportPort.class), mock(MigrationRecordStore.class), uow, mock(ModuleTables.class),
                Clock.systemUTC());
        BrefAccount a = new BrefAccount(owner, "owner", null, false, "+237600000001", true, null, null, "A", "B", null,
                true, false, "email", null, T, T, null);
        ImportRun run = new ImportRun(new BrefSnapshot(a, List.of(), List.of(), SourceKind.DATABASE),
                UUID.randomUUID(), UUID.randomUUID(), new HashMap<>());
        run.tenant(UUID.randomUUID(), "kv_test246");
        return run;
    }

    private BrefAnnexes withAccount() {
        return new BrefAnnexes(new BrefAnnexes.WhatsAppAccount(UUID.randomUUID(), owner, "1111", "2222", null, null,
                "token", "connected", T, T), List.of(), List.of(), List.of(), List.of(), List.of(), List.of(),
                List.of());
    }

    @Test
    @DisplayName("a connected account while the server has no encryption key: ANNEX_INVALID, nothing written")
    void noKeyBlocks() {
        ImportRun run = run();
        when(source.load(any(), anyCollection())).thenReturn(withAccount());
        when(whatsapp.canStoreTokens()).thenReturn(false);
        List<BlockedCase> cases = step.check(run);
        assertThat(cases).extracting(BlockedCase::code).containsExactly("ANNEX_INVALID");
        verifyNoInteractions(uow);
    }

    @Test
    @DisplayName("with a key: no case; the section the check read is the one the run uses (one source read)")
    void keyPresent() {
        ImportRun run = run();
        when(source.load(any(), anyCollection())).thenReturn(BrefAnnexes.empty());
        when(whatsapp.canStoreTokens()).thenReturn(true);
        assertThat(step.check(run)).isEmpty();
        step.run(run); // empty section: nothing to write
        verify(source, times(1)).load(any(), anyCollection());
        verifyNoInteractions(uow);
    }
}
