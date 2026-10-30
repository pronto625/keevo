package com.keevo.identity.company;

import com.keevo.identity.company.adapter.out.image.ImageIoCompanyLogoNormalizer;
import com.keevo.identity.company.adapter.out.storage.LocalDiskCompanyLogoStorage;
import com.keevo.identity.company.application.service.CompanyProfileService;
import com.keevo.identity.company.domain.port.out.CompanyProfileRepository;
import com.keevo.shared.capability.business.CompanyIdentity;
import com.keevo.shared.domain.exception.DomainException;
import com.keevo.shared.event.catalog.onboarding.CompanyProfileUpdatedEvent;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import javax.imageio.ImageIO;
import java.awt.image.BufferedImage;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.nio.file.Path;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

@DisplayName("CompanyProfileService — company identity (Story 14.2)")
class CompanyProfileServiceTest {

    private static final String SCHEMA = "kv_cmp001";
    private static final UUID TENANT = UUID.randomUUID();
    private static final UUID OWNER = UUID.randomUUID();

    @TempDir
    Path dir;

    /** In-memory public.tenants row. */
    private final Map<String, CompanyProfileRepository.TenantIdentity> rows = new HashMap<>();
    private final List<Object> events = new ArrayList<>();

    private final CompanyProfileRepository repository = new CompanyProfileRepository() {
        @Override
        public Optional<TenantIdentity> find(String tenantSchema) {
            return Optional.ofNullable(rows.get(tenantSchema));
        }

        @Override
        public boolean updateDesignation(String tenantSchema, String designation) {
            TenantIdentity t = rows.get(tenantSchema);
            rows.put(tenantSchema, new TenantIdentity(t.id(), t.code(), t.name(), designation, t.status()));
            return true;
        }
    };

    private CompanyProfileService service() {
        return new CompanyProfileService(repository, new LocalDiskCompanyLogoStorage(dir),
                new ImageIoCompanyLogoNormalizer(600), events::add, 200 * 1024,
                Clock.fixed(Instant.parse("2026-10-30T10:00:00Z"), ZoneOffset.UTC));
    }

    private void tenant(String name, String designation, String status) {
        rows.put(SCHEMA, new CompanyProfileRepository.TenantIdentity(TENANT, "KV-CMP001", name, designation, status));
    }

    private static byte[] png(int w, int h) throws IOException {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        ImageIO.write(new BufferedImage(w, h, BufferedImage.TYPE_INT_ARGB), "png", out);
        return out.toByteArray();
    }

    @Test
    @DisplayName("display rule: designation › shop name › « Mon commerce » (a name equal to the code never shows)")
    void displayRule() {
        CompanyProfileService s = service();
        tenant("Boutique Awa", "  Tech Centre SARL ", "ACTIVE");
        assertThat(s.displayName(SCHEMA)).isEqualTo("Tech Centre SARL");
        tenant("Boutique Awa", "   ", "ACTIVE");
        assertThat(s.displayName(SCHEMA)).isEqualTo("Boutique Awa");
        tenant("KV-CMP001", null, "ACTIVE");
        assertThat(s.displayName(SCHEMA)).isEqualTo(CompanyIdentity.DEFAULT_NAME);
        assertThat(s.get(SCHEMA).shopName()).isNull();
        tenant(null, null, "ACTIVE");
        assertThat(s.displayName(SCHEMA)).isEqualTo("Mon commerce");
        assertThat(s.displayName("kv_absent")).isEqualTo("Mon commerce");
    }

    @Test
    @DisplayName("designation: blank stored as null, audited only when it changes; > 150 chars refused")
    void designation() {
        CompanyProfileService s = service();
        tenant("Boutique Awa", null, "ACTIVE");
        CompanyProfileService.CompanyProfile p = s.updateDesignation(SCHEMA, " Tech Centre SARL ", OWNER);
        assertThat(p.designation()).isEqualTo("Tech Centre SARL");
        assertThat(p.displayName()).isEqualTo("Tech Centre SARL");
        assertThat(rows.get(SCHEMA).designation()).isEqualTo("Tech Centre SARL");
        assertThat(events).hasSize(1);
        CompanyProfileUpdatedEvent e = (CompanyProfileUpdatedEvent) events.get(0);
        assertThat(e.after()).containsEntry("designation", "Tech Centre SARL");
        assertThat(e.before()).isEmpty();
        assertThat(e.displayName()).isEqualTo("Tech Centre SARL");

        s.updateDesignation(SCHEMA, "Tech Centre SARL", OWNER);
        assertThat(events).hasSize(1); // unchanged → no audit

        assertThat(p.shopName()).isEqualTo("Boutique Awa"); // known even with a designation
        p = s.updateDesignation(SCHEMA, "  ", OWNER);
        assertThat(p.designation()).isNull();
        assertThat(p.displayName()).isEqualTo("Boutique Awa");
        assertThat(rows.get(SCHEMA).designation()).isNull();

        assertThatThrownBy(() -> s.updateDesignation(SCHEMA, "x".repeat(151), OWNER))
                .isInstanceOf(DomainException.class);
    }

    @Test
    @DisplayName("logo: PNG stored (downscaled to 600 px), replaced, deleted; too large / not an image → 422 codes")
    void logo() throws IOException {
        CompanyProfileService s = service();
        tenant("Boutique Awa", null, "ACTIVE");
        assertThat(s.get(SCHEMA).hasLogo()).isFalse();
        assertThat(s.hasLogo(SCHEMA)).isFalse();
        assertThat(s.logo(SCHEMA)).isEmpty();

        assertThat(s.uploadLogo(SCHEMA, png(1200, 300), OWNER).hasLogo()).isTrue();
        CompanyIdentity.Logo stored = s.logo(SCHEMA).orElseThrow();
        assertThat(stored.contentType()).isEqualTo("image/png");
        BufferedImage img = ImageIO.read(new ByteArrayInputStream(stored.content()));
        assertThat(img.getWidth()).isEqualTo(600);
        assertThat(img.getHeight()).isEqualTo(150);
        assertThat(stored.dataUri()).startsWith("data:image/png;base64,");
        assertThat(s.hasLogo(SCHEMA)).isTrue();

        s.uploadLogo(SCHEMA, png(100, 100), OWNER);
        assertThat(((CompanyProfileUpdatedEvent) events.get(events.size() - 1)).after())
                .containsEntry("logo", "Remplacé");

        assertThat(s.deleteLogo(SCHEMA, OWNER).hasLogo()).isFalse();
        assertThat(s.logo(SCHEMA)).isEmpty();
        assertThatThrownBy(() -> s.readLogo(SCHEMA)).isInstanceOf(DomainException.class)
                .satisfies(e -> assertThat(((DomainException) e).getDomainCode()).isEqualTo("COMPANY_LOGO_NOT_FOUND"));

        assertThatThrownBy(() -> s.uploadLogo(SCHEMA, new byte[300 * 1024], OWNER))
                .satisfies(e -> assertThat(((DomainException) e).getDomainCode()).isEqualTo("COMPANY_LOGO_TOO_LARGE"));
        assertThatThrownBy(() -> s.uploadLogo(SCHEMA, "GIF89a not a png image".getBytes(), OWNER))
                .satisfies(e -> assertThat(((DomainException) e).getDomainCode()).isEqualTo("COMPANY_LOGO_INVALID"));
    }

    @Test
    @DisplayName("designation write hitting no tenant row → TENANT_NOT_FOUND, nothing audited")
    void designationLostRow() {
        tenant("Awa", null, "ACTIVE");
        CompanyProfileService s = new CompanyProfileService(new CompanyProfileRepository() {
            @Override
            public Optional<TenantIdentity> find(String tenantSchema) {
                return Optional.ofNullable(rows.get(tenantSchema));
            }

            @Override
            public boolean updateDesignation(String tenantSchema, String designation) {
                return false;
            }
        }, new LocalDiskCompanyLogoStorage(dir), new ImageIoCompanyLogoNormalizer(600), events::add, 200 * 1024,
                Clock.systemUTC());
        assertThatThrownBy(() -> s.updateDesignation(SCHEMA, "Tech", OWNER))
                .satisfies(e -> assertThat(((DomainException) e).getDomainCode()).isEqualTo("TENANT_NOT_FOUND"));
        assertThat(events).isEmpty();
    }

    @Test
    @DisplayName("normalizer: re-encoded logo over 300 KB → opaque PNG becomes JPEG, transparent one refused")
    void normalizedSizeCap() throws IOException {
        java.util.Random random = new java.util.Random(42);
        BufferedImage opaque = new BufferedImage(600, 600, BufferedImage.TYPE_INT_ARGB);
        BufferedImage transparent = new BufferedImage(600, 600, BufferedImage.TYPE_INT_ARGB);
        for (int y = 0; y < 600; y++) {
            for (int x = 0; x < 600; x++) {
                int rgb = random.nextInt(0x1000000);
                opaque.setRGB(x, y, 0xFF000000 | rgb);
                transparent.setRGB(x, y, (random.nextInt(256) << 24) | rgb);
            }
        }
        ImageIoCompanyLogoNormalizer normalizer = new ImageIoCompanyLogoNormalizer(600);
        CompanyIdentity.Logo jpeg = normalizer.normalize(encode(opaque));
        assertThat(jpeg.contentType()).isEqualTo("image/jpeg");
        assertThat(jpeg.content().length).isLessThanOrEqualTo(ImageIoCompanyLogoNormalizer.MAX_NORMALIZED_BYTES);
        assertThatThrownBy(() -> normalizer.normalize(encode(transparent)))
                .satisfies(e -> assertThat(((DomainException) e).getDomainCode()).isEqualTo("COMPANY_LOGO_TOO_HEAVY"));
    }

    private static byte[] encode(BufferedImage image) throws IOException {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        ImageIO.write(image, "png", out);
        return out.toByteArray();
    }

    @Test
    @DisplayName("storage: both logo.png and logo.jpg present (crash) → the newest is read")
    void newestFileWins() throws IOException {
        LocalDiskCompanyLogoStorage storage = new LocalDiskCompanyLogoStorage(dir);
        java.nio.file.Path tenantDir = java.nio.file.Files.createDirectories(dir.resolve(SCHEMA));
        java.nio.file.Path jpg = java.nio.file.Files.write(tenantDir.resolve("logo.jpg"), new byte[]{1});
        java.nio.file.Path png = java.nio.file.Files.write(tenantDir.resolve("logo.png"), new byte[]{2});
        java.nio.file.Files.setLastModifiedTime(png, java.nio.file.attribute.FileTime.fromMillis(1_000_000));
        java.nio.file.Files.setLastModifiedTime(jpg, java.nio.file.attribute.FileTime.fromMillis(2_000_000));
        assertThat(storage.read(SCHEMA).orElseThrow().contentType()).isEqualTo("image/jpeg");
        java.nio.file.Files.setLastModifiedTime(png, java.nio.file.attribute.FileTime.fromMillis(3_000_000));
        assertThat(storage.read(SCHEMA).orElseThrow().contentType()).isEqualTo("image/png");
        assertThat(storage.exists(SCHEMA)).isTrue();
    }

    @Test
    @DisplayName("isActive: only an ACTIVE tenant")
    void active() {
        CompanyProfileService s = service();
        tenant("Awa", null, "ACTIVE");
        assertThat(s.isActive(SCHEMA)).isTrue();
        tenant("Awa", null, "SUSPENDED");
        assertThat(s.isActive(SCHEMA)).isFalse();
        assertThat(s.isActive("kv_absent")).isFalse();
    }
}
