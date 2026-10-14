package com.keevo.catalog.product.adapter.in.web;

import com.keevo.catalog.product.application.usecase.ProductPhotoUseCase;
import com.keevo.catalog.product.domain.port.out.ProductPhotoStorage.StoredPhoto;
import com.keevo.shared.domain.exception.DomainException;
import com.keevo.shared.domain.exception.ErrorCode;
import com.keevo.shared.infrastructure.persistence.TenantContext;
import com.keevo.shared.infrastructure.security.JwtAuthFilter;
import com.keevo.shared.infrastructure.security.SecurityConfig;
import jakarta.servlet.FilterChain;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.context.annotation.Import;
import org.springframework.mock.web.MockMultipartFile;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.GrantedAuthority;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

import java.util.List;
import java.util.UUID;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.multipart;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

/**
 * {@link ProductPhotoController} behind the real {@link SecurityConfig} chain: authentication,
 * roles, tenant taken from the session (never from the request), French errors.
 */
@WebMvcTest(ProductPhotoController.class)
@Import(SecurityConfig.class)
@DisplayName("ProductPhotoController")
class ProductPhotoControllerTest {

    private static final String SCHEMA = "kv_abc123";

    @Autowired MockMvc mockMvc;
    @MockitoBean ProductPhotoUseCase useCase;
    @MockitoBean JwtAuthFilter jwtAuthFilter;

    private final UUID productId = UUID.randomUUID();

    @AfterEach
    void tearDown() {
        SecurityContextHolder.clearContext();
        TenantContext.clear();
    }

    private MockMultipartFile jpeg() {
        return new MockMultipartFile("file", "photo.jpg", "image/jpeg", new byte[]{(byte) 0xFF, (byte) 0xD8, (byte) 0xFF});
    }

    @Test
    @DisplayName("anonymous upload → 401, use case never called")
    void anonymous_401() throws Exception {
        filterAsAnonymous();

        mockMvc.perform(multipart("/api/v1/products/{id}/photo", productId).file(jpeg()))
                .andExpect(status().isUnauthorized());
        verifyNoInteractions(useCase);
    }

    @Test
    @DisplayName("authenticated without OWNER/EMPLOYEE role → 403")
    void noRole_403() throws Exception {
        filterAs(List.of(new SimpleGrantedAuthority("ROLE_SUPER_ADMIN")));

        mockMvc.perform(get("/api/v1/products/{id}/photo", productId))
                .andExpect(status().isForbidden());
        verifyNoInteractions(useCase);
    }

    @Test
    @DisplayName("EMPLOYEE upload → 200 {photoUrl}, the tenant is the session's")
    void employeeUpload_200() throws Exception {
        filterAs(List.of(new SimpleGrantedAuthority("ROLE_EMPLOYEE")));
        when(useCase.upload(eq(SCHEMA), eq(productId), any(), any())).thenReturn("/api/v1/products/x/photo?v=1");

        mockMvc.perform(multipart("/api/v1/products/{id}/photo", productId).file(jpeg()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.photoUrl").value("/api/v1/products/x/photo?v=1"));
        verify(useCase).upload(eq(SCHEMA), eq(productId), any(),
                argThat(a -> "EMPLOYEE".equals(a.role())));
    }

    @Test
    @DisplayName("declared type GIF → 415 PRODUCT_PHOTO_INVALID with a French message")
    void gif_415() throws Exception {
        filterAs(List.of(new SimpleGrantedAuthority("ROLE_OWNER")));
        MockMultipartFile gif = new MockMultipartFile("file", "a.gif", "image/gif", "GIF89a".getBytes());

        mockMvc.perform(multipart("/api/v1/products/{id}/photo", productId).file(gif))
                .andExpect(status().isUnsupportedMediaType())
                .andExpect(jsonPath("$.domainCode").value("PRODUCT_PHOTO_INVALID"))
                .andExpect(jsonPath("$.error").value("Format de photo non pris en charge (JPEG, PNG ou WebP)"));
        verifyNoInteractions(useCase);
    }

    @Test
    @DisplayName("too large → 413 with « La photo est trop lourde (200 Ko maximum) »")
    void tooLarge_413() throws Exception {
        filterAs(List.of(new SimpleGrantedAuthority("ROLE_OWNER")));
        when(useCase.upload(any(), any(), any(), any()))
                .thenThrow(new DomainException(ErrorCode.PRODUCT_PHOTO_TOO_LARGE, "big"));

        mockMvc.perform(multipart("/api/v1/products/{id}/photo", productId).file(jpeg()))
                .andExpect(status().isPayloadTooLarge())
                .andExpect(jsonPath("$.error").value("La photo est trop lourde (200 Ko maximum)"));
    }

    @Test
    @DisplayName("GET → image bytes with their type, private cache, nosniff")
    void get_returnsBytes() throws Exception {
        filterAs(List.of(new SimpleGrantedAuthority("ROLE_EMPLOYEE")));
        when(useCase.read(SCHEMA, productId)).thenReturn(new StoredPhoto(new byte[]{1, 2, 3}, "image/jpeg"));

        mockMvc.perform(get("/api/v1/products/{id}/photo", productId))
                .andExpect(status().isOk())
                .andExpect(content().contentType("image/jpeg"))
                .andExpect(content().bytes(new byte[]{1, 2, 3}))
                .andExpect(header().string("Cache-Control", org.hamcrest.Matchers.containsString("private")))
                .andExpect(header().string("X-Content-Type-Options", "nosniff"));
    }

    @Test
    @DisplayName("GET of a product of another tenant → 404 (looked up in the session's schema only)")
    void get_otherTenant_404() throws Exception {
        filterAs(List.of(new SimpleGrantedAuthority("ROLE_OWNER")));
        when(useCase.read(SCHEMA, productId))
                .thenThrow(new DomainException(ErrorCode.PRODUCT_NOT_FOUND, "nope"));

        mockMvc.perform(get("/api/v1/products/{id}/photo", productId))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.error").value("Produit introuvable"));
    }

    @Test
    @DisplayName("DELETE → 200; employee on a draft → 403")
    void delete_ok_and_draftForbidden() throws Exception {
        filterAs(List.of(new SimpleGrantedAuthority("ROLE_OWNER")));
        mockMvc.perform(delete("/api/v1/products/{id}/photo", productId))
                .andExpect(status().isOk());
        verify(useCase).delete(eq(SCHEMA), eq(productId), any());

        doThrow(new DomainException(ErrorCode.FORBIDDEN, "draft"))
                .when(useCase).delete(any(), any(), any());
        mockMvc.perform(delete("/api/v1/products/{id}/photo", productId))
                .andExpect(status().isForbidden());
    }

    // ── Helpers ───────────────────────────────────────────────────────────────

    private void filterAs(List<GrantedAuthority> authorities) throws Exception {
        doAnswer(inv -> {
            var auth = new UsernamePasswordAuthenticationToken(UUID.randomUUID(), null, authorities);
            SecurityContextHolder.getContext().setAuthentication(auth);
            TenantContext.setCurrentTenant(SCHEMA);
            inv.getArgument(2, FilterChain.class).doFilter(inv.getArgument(0), inv.getArgument(1));
            return null;
        }).when(jwtAuthFilter).doFilter(any(), any(), any());
    }

    private void filterAsAnonymous() throws Exception {
        doAnswer(inv -> {
            inv.getArgument(2, FilterChain.class).doFilter(inv.getArgument(0), inv.getArgument(1));
            return null;
        }).when(jwtAuthFilter).doFilter(any(), any(), any());
    }
}
