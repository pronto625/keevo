package com.keevo.reporting.export.adapter.in.rest;

import com.keevo.reporting.export.SyntheticExportProvider;
import com.keevo.reporting.export.application.service.ExportDatasetRegistry;
import com.keevo.reporting.export.domain.model.ExportFormat;
import com.keevo.reporting.export.domain.model.ExportJob;
import com.keevo.reporting.export.domain.model.ExportRequest;
import com.keevo.reporting.export.domain.port.in.DownloadExportUseCase;
import com.keevo.reporting.export.domain.port.in.ListExportsUseCase;
import com.keevo.reporting.export.domain.port.in.RequestExportUseCase;
import com.keevo.shared.application.port.export.ExportFilter;
import com.keevo.shared.domain.exception.DomainException;
import com.keevo.shared.domain.exception.ErrorCode;
import com.keevo.shared.infrastructure.persistence.TenantContext;
import com.keevo.shared.infrastructure.web.GlobalExceptionHandler;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.http.MediaType;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.RequestBuilder;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

import java.io.ByteArrayInputStream;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@ExtendWith(MockitoExtension.class)
@DisplayName("ExportController (v1s-18-1)")
class ExportControllerTest {

    private static final String TENANT = "kv_abc123";
    private static final String CSV_BODY = "{\"datasets\":[\"stores\"],\"format\":\"CSV\"}";

    @Mock RequestExportUseCase requestUseCase;
    @Mock ListExportsUseCase listUseCase;
    @Mock DownloadExportUseCase downloadUseCase;

    private MockMvc mvc;
    private final UUID actor = UUID.randomUUID();

    @BeforeEach
    void setUp() {
        ExportDatasetRegistry registry = new ExportDatasetRegistry(
                List.of(new SyntheticExportProvider("stores", "Boutiques", 1)));
        mvc = MockMvcBuilders.standaloneSetup(
                        new ExportController(requestUseCase, listUseCase, downloadUseCase, registry),
                        new ExportDownloadController(downloadUseCase))
                .setControllerAdvice(new GlobalExceptionHandler())
                .build();
        TenantContext.setCurrentTenant(TENANT);
    }

    @AfterEach
    void tearDown() {
        SecurityContextHolder.clearContext();
        TenantContext.clear();
    }

    private void as(String role) {
        SecurityContextHolder.getContext().setAuthentication(new UsernamePasswordAuthenticationToken(
                actor, null, List.of(new SimpleGrantedAuthority("ROLE_" + role))));
    }

    private static ExportJob pendingJob() {
        return ExportJob.pending(UUID.randomUUID(), UUID.randomUUID(), ExportFormat.XLSX, List.of("stores"),
                ExportFilter.none(), "keevo-stores-20261012-1330.xlsx", "x", Instant.parse("2026-10-12T12:30:00Z"));
    }

    private static ExportJob readyJob() {
        ExportJob j = pendingJob();
        j.start(Instant.now());
        j.complete(10, 100, TENANT + "/" + j.getId() + ".xlsx", Instant.now(), Instant.now().plus(Duration.ofHours(24)));
        return j;
    }

    @Test
    @DisplayName("EMPLOYEE → 403 FORBIDDEN on every export route, no use case reached")
    void employeeForbiddenEverywhere() throws Exception {
        as("EMPLOYEE");
        UUID id = UUID.randomUUID();
        List<RequestBuilder> routes = List.of(
                get("/api/v1/exports/datasets"),
                post("/api/v1/exports").contentType(MediaType.APPLICATION_JSON).content(CSV_BODY),
                get("/api/v1/exports"),
                get("/api/v1/exports/" + id),
                get("/api/v1/exports/" + id + "/file"));
        for (RequestBuilder r : routes) {
            mvc.perform(r).andExpect(status().isForbidden()).andExpect(jsonPath("$.domainCode").value("FORBIDDEN"));
        }
        verifyNoInteractions(requestUseCase, listUseCase, downloadUseCase);
    }

    @Test
    @DisplayName("GET /datasets → keys, labels, typed columns")
    void datasets() throws Exception {
        as("OWNER");
        mvc.perform(get("/api/v1/exports/datasets"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data[0].key").value("stores"))
                .andExpect(jsonPath("$.data[0].label").value("Boutiques"))
                .andExpect(jsonPath("$.data[0].columns[2].type").value("AMOUNT_XAF"))
                .andExpect(jsonPath("$.data[0].storeFilter").value(true))
                .andExpect(jsonPath("$.data[0].periodFilter").value(true))
                .andExpect(jsonPath("$.data[0].criteria").isEmpty());
    }

    @Test
    @DisplayName("GET /datasets → store / period filter flags and criteria keys (sorted) per dataset (v1s-18-2)")
    void datasetsFilterMetadata() throws Exception {
        as("OWNER");
        SyntheticExportProvider reference = new SyntheticExportProvider("products", "Produits", 1) {
            @Override public boolean supportsPeriodFilter() { return false; }
            @Override public boolean supportsStoreFilter() { return false; }
        };
        SyntheticExportProvider audit = new SyntheticExportProvider("audit_log", "Journal d'audit", 1) {
            @Override public java.util.Set<String> criteriaKeys() { return java.util.Set.of("userId", "q", "action"); }
        };
        MockMvc m = MockMvcBuilders.standaloneSetup(new ExportController(requestUseCase, listUseCase, downloadUseCase,
                        new ExportDatasetRegistry(List.of(reference, audit))))
                .setControllerAdvice(new GlobalExceptionHandler()).build();
        m.perform(get("/api/v1/exports/datasets"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data[?(@.key=='products')].storeFilter").value(false))
                .andExpect(jsonPath("$.data[?(@.key=='products')].periodFilter").value(false))
                .andExpect(jsonPath("$.data[?(@.key=='audit_log')].storeFilter").value(true))
                .andExpect(jsonPath("$.data[?(@.key=='audit_log')].criteria[*]").value(
                        org.hamcrest.Matchers.contains("action", "q", "userId")));
    }

    @Test
    @DisplayName("POST with criteria → parsed into the filter (trimmed, blank dropped); malformed criteria → 400 VALIDATION_ERROR")
    void postCriteria() throws Exception {
        as("OWNER");
        UUID user = UUID.randomUUID();
        when(requestUseCase.request(eq(TENANT), eq(actor), any(), any())).thenAnswer(inv -> {
            RequestExportUseCase.DirectOutput d = inv.getArgument(3);
            d.open("text/csv; charset=UTF-8", "keevo-audit_log.csv").write('x');
            return new RequestExportUseCase.Direct(UUID.randomUUID(), "keevo-audit_log.csv", 0);
        });
        mvc.perform(post("/api/v1/exports").contentType(MediaType.APPLICATION_JSON)
                        .content("{\"datasets\":[\"audit_log\"],\"format\":\"CSV\",\"criteria\":{\"userId\":\" " + user
                                + "\",\"action\":\"SALE_CREATED\",\"q\":\"\"}}"))
                .andExpect(status().isOk());
        ArgumentCaptor<ExportRequest> req = ArgumentCaptor.forClass(ExportRequest.class);
        verify(requestUseCase).request(eq(TENANT), eq(actor), req.capture(), any());
        assertThat(req.getValue().filter().criteria())
                .containsExactlyInAnyOrderEntriesOf(java.util.Map.of("userId", user.toString(), "action", "SALE_CREATED"));

        for (String criteria : List.of("{\"userId\":\"nope\"}", "{\"entityId\":\"42\"}",
                "{\"q\":\"" + "x".repeat(101) + "\"}", "{\"action\":\"" + "A".repeat(81) + "\"}",
                "{\"bad key\":\"x\"}")) {
            mvc.perform(post("/api/v1/exports").contentType(MediaType.APPLICATION_JSON)
                            .content("{\"datasets\":[\"audit_log\"],\"format\":\"CSV\",\"criteria\":" + criteria + "}"))
                    .andExpect(status().isBadRequest())
                    .andExpect(jsonPath("$.domainCode").value("VALIDATION_ERROR"));
        }
        // a criterion the requested dataset does not accept (stores has none)
        mvc.perform(post("/api/v1/exports").contentType(MediaType.APPLICATION_JSON)
                        .content("{\"datasets\":[\"stores\"],\"format\":\"CSV\",\"criteria\":{\"action\":\"X\"}}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.domainCode").value("VALIDATION_ERROR"));
        verify(requestUseCase, org.mockito.Mockito.times(1)).request(any(), any(), any(), any());
    }

    @Test
    @DisplayName("POST direct → 200 file with content type and attachment filename; request parsed with filters")
    void postDirect() throws Exception {
        as("OWNER");
        UUID store = UUID.randomUUID();
        when(requestUseCase.request(eq(TENANT), eq(actor), any(), any())).thenAnswer(inv -> {
            RequestExportUseCase.DirectOutput d = inv.getArgument(3);
            OutputStream os = d.open("text/csv; charset=UTF-8", "keevo-stores-20261012-1330.csv");
            os.write("﻿Nom\r\nA\r\n".getBytes(StandardCharsets.UTF_8));
            return new RequestExportUseCase.Direct(UUID.randomUUID(), "keevo-stores-20261012-1330.csv", 1);
        });

        mvc.perform(post("/api/v1/exports").contentType(MediaType.APPLICATION_JSON)
                        .content("{\"datasets\":[\"stores\"],\"format\":\"csv\",\"storeId\":\"" + store
                                + "\",\"from\":\"2026-10-01T00:00:00Z\",\"to\":\"2026-10-12T00:00:00Z\"}"))
                .andExpect(status().isOk())
                .andExpect(header().string("Content-Type", "text/csv; charset=UTF-8"))
                .andExpect(header().string("Content-Disposition",
                        "attachment; filename=\"keevo-stores-20261012-1330.csv\""))
                .andExpect(content().bytes("﻿Nom\r\nA\r\n".getBytes(StandardCharsets.UTF_8)));

        ArgumentCaptor<ExportRequest> req = ArgumentCaptor.forClass(ExportRequest.class);
        verify(requestUseCase).request(eq(TENANT), eq(actor), req.capture(), any());
        assertThat(req.getValue().format()).isEqualTo(ExportFormat.CSV);
        assertThat(req.getValue().datasets()).containsExactly("stores");
        assertThat(req.getValue().filter()).isEqualTo(new ExportFilter(store,
                Instant.parse("2026-10-01T00:00:00Z"), Instant.parse("2026-10-12T00:00:00Z")));
    }

    @Test
    @DisplayName("POST background → 202 with the PENDING job")
    void postBackground() throws Exception {
        as("OWNER");
        ExportJob job = pendingJob();
        when(requestUseCase.request(any(), any(), any(), any())).thenReturn(new RequestExportUseCase.Background(job));

        mvc.perform(post("/api/v1/exports").contentType(MediaType.APPLICATION_JSON)
                        .content("{\"datasets\":[\"stores\"],\"format\":\"XLSX\"}"))
                .andExpect(status().isAccepted())
                .andExpect(jsonPath("$.data.id").value(job.getId().toString()))
                .andExpect(jsonPath("$.data.status").value("PENDING"))
                .andExpect(jsonPath("$.data.format").value("XLSX"))
                .andExpect(jsonPath("$.data.downloadUrl").doesNotExist());
    }

    @Test
    @DisplayName("POST malformed → 400 VALIDATION_ERROR (empty / duplicate datasets, no or unknown format, bad uuid / instant, from > to)")
    void postValidation() throws Exception {
        as("OWNER");
        for (String body : List.of(
                "{\"datasets\":[],\"format\":\"CSV\"}",
                "{\"format\":\"CSV\"}",
                "{\"datasets\":[\"stores\",\"stores\"],\"format\":\"CSV\"}",
                "{\"datasets\":[\"stores\"]}",
                "{\"datasets\":[\"stores\"],\"format\":\"DOC\"}",
                "{\"datasets\":[\"stores\"],\"format\":\"CSV\",\"storeId\":\"nope\"}",
                "{\"datasets\":[\"stores\"],\"format\":\"CSV\",\"from\":\"yesterday\"}",
                "{\"datasets\":[\"stores\"],\"format\":\"CSV\",\"from\":\"2026-10-12T00:00:00Z\",\"to\":\"2026-10-01T00:00:00Z\"}")) {
            mvc.perform(post("/api/v1/exports").contentType(MediaType.APPLICATION_JSON).content(body))
                    .andExpect(status().isBadRequest())
                    .andExpect(jsonPath("$.domainCode").value("VALIDATION_ERROR"));
        }
        verifyNoInteractions(requestUseCase);
    }

    @Test
    @DisplayName("POST domain errors → 400 dataset unknown / 403 plan feature (with details.feature) / 429 rate limit")
    void postDomainErrors() throws Exception {
        as("OWNER");
        when(requestUseCase.request(any(), any(), any(), any()))
                .thenThrow(new DomainException(ErrorCode.EXPORT_DATASET_UNKNOWN, "x"))
                .thenThrow(new DomainException(ErrorCode.PLAN_FEATURE_REQUIRED, "x", Map.of("feature", "EXPORT_XLSX")))
                .thenThrow(new DomainException(ErrorCode.RATE_LIMIT_EXCEEDED, "x"))
                .thenThrow(new DomainException(ErrorCode.EXPORT_FORMAT_UNSUPPORTED, "x"))
                .thenThrow(new DomainException(ErrorCode.EXPORT_QUEUE_FULL, "x"));

        mvc.perform(post("/api/v1/exports").contentType(MediaType.APPLICATION_JSON).content(CSV_BODY))
                .andExpect(status().isBadRequest()).andExpect(jsonPath("$.domainCode").value("EXPORT_DATASET_UNKNOWN"));
        mvc.perform(post("/api/v1/exports").contentType(MediaType.APPLICATION_JSON).content(CSV_BODY))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.domainCode").value("PLAN_FEATURE_REQUIRED"))
                .andExpect(jsonPath("$.details.feature").value("EXPORT_XLSX"));
        mvc.perform(post("/api/v1/exports").contentType(MediaType.APPLICATION_JSON).content(CSV_BODY))
                .andExpect(status().isTooManyRequests());
        mvc.perform(post("/api/v1/exports").contentType(MediaType.APPLICATION_JSON).content(CSV_BODY))
                .andExpect(status().isBadRequest()).andExpect(jsonPath("$.domainCode").value("EXPORT_FORMAT_UNSUPPORTED"));
        mvc.perform(post("/api/v1/exports").contentType(MediaType.APPLICATION_JSON).content(CSV_BODY))
                .andExpect(status().isTooManyRequests())
                .andExpect(jsonPath("$.domainCode").value("EXPORT_QUEUE_FULL"));
    }

    @Test
    @DisplayName("POST more than 20 datasets → 400 VALIDATION_ERROR")
    void tooManyDatasets() throws Exception {
        as("OWNER");
        StringBuilder keys = new StringBuilder();
        for (int i = 0; i < 21; i++) keys.append(i == 0 ? "" : ",").append("\"d").append(i).append('"');
        mvc.perform(post("/api/v1/exports").contentType(MediaType.APPLICATION_JSON)
                        .content("{\"datasets\":[" + keys + "],\"format\":\"CSV\"}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.domainCode").value("VALIDATION_ERROR"));
        verifyNoInteractions(requestUseCase);
    }

    @Test
    @DisplayName("direct export failing before the response is committed → file headers dropped, JSON error")
    void directFailureResetsHeaders() throws Exception {
        as("OWNER");
        when(requestUseCase.request(any(), any(), any(), any())).thenAnswer(inv -> {
            RequestExportUseCase.DirectOutput d = inv.getArgument(3);
            d.open("text/csv; charset=UTF-8", "keevo-stores.csv"); // headers set, nothing flushed
            throw new DomainException(ErrorCode.EXPORT_DATASET_UNKNOWN, "x");
        });

        mvc.perform(post("/api/v1/exports").contentType(MediaType.APPLICATION_JSON).content(CSV_BODY))
                .andExpect(status().isBadRequest())
                .andExpect(header().doesNotExist("Content-Disposition"))
                .andExpect(content().contentTypeCompatibleWith(MediaType.APPLICATION_JSON))
                .andExpect(jsonPath("$.domainCode").value("EXPORT_DATASET_UNKNOWN"));
    }

    @Test
    @DisplayName("GET /exports → newest first, downloadUrl only for READY")
    void list() throws Exception {
        as("OWNER");
        ExportJob ready = readyJob();
        ExportJob pending = pendingJob();
        when(listUseCase.list()).thenReturn(List.of(ready, pending));
        when(downloadUseCase.signedDownloadPath(TENANT, ready.getId(), ready.getExpiresAt()))
                .thenReturn("/api/v1/exports/download?token=abc.def");

        mvc.perform(get("/api/v1/exports"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data[0].status").value("READY"))
                .andExpect(jsonPath("$.data[0].downloadUrl").value("/api/v1/exports/download?token=abc.def"))
                .andExpect(jsonPath("$.data[0].rowCount").value(10))
                .andExpect(jsonPath("$.data[1].status").value("PENDING"))
                .andExpect(jsonPath("$.data[1].downloadUrl").doesNotExist());
    }

    @Test
    @DisplayName("GET /{id}/file → 200 stream; 410 EXPORT_EXPIRED; 409 EXPORT_NOT_READY; 404 EXPORT_NOT_FOUND")
    void file() throws Exception {
        as("OWNER");
        UUID ok = UUID.randomUUID();
        UUID expired = UUID.randomUUID();
        UUID notReady = UUID.randomUUID();
        UUID unknown = UUID.randomUUID();
        when(downloadUseCase.open(ok)).thenReturn(new DownloadExportUseCase.ExportFile("keevo-stores.xlsx",
                "application/vnd.openxmlformats-officedocument.spreadsheetml.sheet", 3,
                new ByteArrayInputStream(new byte[]{1, 2, 3})));
        when(downloadUseCase.open(expired)).thenThrow(new DomainException(ErrorCode.EXPORT_EXPIRED, "x"));
        when(downloadUseCase.open(notReady)).thenThrow(new DomainException(ErrorCode.EXPORT_NOT_READY, "x"));
        when(downloadUseCase.open(unknown)).thenThrow(new DomainException(ErrorCode.EXPORT_NOT_FOUND, "x"));

        mvc.perform(get("/api/v1/exports/" + ok + "/file"))
                .andExpect(status().isOk())
                .andExpect(header().string("Content-Disposition", "attachment; filename=\"keevo-stores.xlsx\""))
                .andExpect(header().string("Content-Type",
                        "application/vnd.openxmlformats-officedocument.spreadsheetml.sheet"))
                .andExpect(content().bytes(new byte[]{1, 2, 3}));
        mvc.perform(get("/api/v1/exports/" + expired + "/file"))
                .andExpect(status().isGone()).andExpect(jsonPath("$.domainCode").value("EXPORT_EXPIRED"));
        mvc.perform(get("/api/v1/exports/" + notReady + "/file"))
                .andExpect(status().isConflict()).andExpect(jsonPath("$.domainCode").value("EXPORT_NOT_READY"));
        mvc.perform(get("/api/v1/exports/" + unknown + "/file"))
                .andExpect(status().isNotFound()).andExpect(jsonPath("$.domainCode").value("EXPORT_NOT_FOUND"));
    }

    @Test
    @DisplayName("GET /{id} unknown → 404 EXPORT_NOT_FOUND")
    void getUnknown() throws Exception {
        as("OWNER");
        UUID id = UUID.randomUUID();
        when(listUseCase.get(id)).thenThrow(new DomainException(ErrorCode.EXPORT_NOT_FOUND, "x"));
        mvc.perform(get("/api/v1/exports/" + id)).andExpect(status().isNotFound());
    }

    @Test
    @DisplayName("signed download: tenant context = token tenant during open, cleared after; 403 invalid; 410 expired")
    void signedDownload() throws Exception {
        TenantContext.clear();
        UUID job = UUID.randomUUID();
        AtomicReference<String> tenantDuringOpen = new AtomicReference<>();
        when(downloadUseCase.verifyLink("good")).thenReturn(new DownloadExportUseCase.LinkTarget("kv_zzz999", job));
        when(downloadUseCase.open(job)).thenAnswer(inv -> {
            tenantDuringOpen.set(TenantContext.getCurrentTenant());
            return new DownloadExportUseCase.ExportFile("f.csv", "text/csv; charset=UTF-8", 2,
                    new ByteArrayInputStream(new byte[]{65, 66}));
        });
        when(downloadUseCase.verifyLink("bad")).thenThrow(new DomainException(ErrorCode.EXPORT_LINK_INVALID, "x"));
        when(downloadUseCase.verifyLink("old")).thenThrow(new DomainException(ErrorCode.EXPORT_EXPIRED, "x"));

        mvc.perform(get("/api/v1/exports/download").param("token", "good"))
                .andExpect(status().isOk())
                .andExpect(content().bytes(new byte[]{65, 66}));
        assertThat(tenantDuringOpen.get()).isEqualTo("kv_zzz999");
        assertThat(TenantContext.getCurrentTenant()).isNull();

        mvc.perform(get("/api/v1/exports/download").param("token", "bad"))
                .andExpect(status().isForbidden()).andExpect(jsonPath("$.domainCode").value("EXPORT_LINK_INVALID"));
        mvc.perform(get("/api/v1/exports/download").param("token", "old"))
                .andExpect(status().isGone()).andExpect(jsonPath("$.domainCode").value("EXPORT_EXPIRED"));
    }
}
