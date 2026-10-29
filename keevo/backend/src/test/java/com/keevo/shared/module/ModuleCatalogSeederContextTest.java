package com.keevo.shared.module;

import com.keevo.shared.module.application.ModuleCatalogSeeder;
import com.keevo.shared.module.application.port.ModuleDefinitionPort;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.transaction.PlatformTransactionManager;

import java.io.IOException;
import java.net.URL;
import java.net.URLClassLoader;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verifyNoInteractions;

/**
 * Story 13.1 — fail-fast at context level: an invalid manifest on the context's classpath (the shipped ten plus a
 * fixture {@code keevo/modules/ghosty/module.json} requiring an unknown module) makes the context fail with
 * {@link InvalidModuleManifestException} naming the file and the field, before the catalogue is touched.
 */
class ModuleCatalogSeederContextTest {

    @TempDir
    Path classpathRoot;

    @Test
    void invalidManifestFailsTheContextBeforeAnyWrite() throws IOException {
        Path manifest = classpathRoot.resolve("keevo/modules/ghosty/module.json");
        Files.createDirectories(manifest.getParent());
        Files.writeString(manifest, """
                {"id":"ghosty","version":"1.0.0","displayName":"Fantôme","category":"business","requires":["core","ghost"]}""");
        ModuleDefinitionPort port = mock(ModuleDefinitionPort.class);
        PlatformTransactionManager transactionManager = mock(PlatformTransactionManager.class);

        try (URLClassLoader loader = new URLClassLoader(new URL[]{classpathRoot.toUri().toURL()},
                getClass().getClassLoader())) {
            new ApplicationContextRunner()
                    .withClassLoader(loader)
                    .withBean(ModuleDefinitionPort.class, () -> port)
                    .withBean(PlatformTransactionManager.class, () -> transactionManager)
                    .withUserConfiguration(ModuleCatalogSeeder.class)
                    .run(context -> {
                        assertThat(context).hasFailed();
                        assertThat(context.getStartupFailure()).rootCause()
                                .isInstanceOf(InvalidModuleManifestException.class)
                                .hasMessageContaining("keevo/modules/ghosty/module.json")
                                .hasMessageContaining("field 'requires'")
                                .hasMessageContaining("unknown module 'ghost'");
                    });
        }
        verifyNoInteractions(port, transactionManager);
    }
}
