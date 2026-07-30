package com.keevo.shared.infrastructure.web;

import io.swagger.v3.oas.models.Components;
import io.swagger.v3.oas.models.OpenAPI;
import io.swagger.v3.oas.models.info.Contact;
import io.swagger.v3.oas.models.info.Info;
import io.swagger.v3.oas.models.info.License;
import io.swagger.v3.oas.models.security.SecurityRequirement;
import io.swagger.v3.oas.models.security.SecurityScheme;
import io.swagger.v3.oas.models.servers.Server;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import java.util.List;

/**
 * OpenApiConfig — Springdoc OpenAPI 3.1 configuration.
 *
 * <p>Generates documentation accessible at:
 * <ul>
 *   <li>Swagger UI: {@code /swagger-ui.html}</li>
 *   <li>OpenAPI JSON: {@code /v3/api-docs}</li>
 *   <li>OpenAPI YAML: {@code /v3/api-docs.yaml}</li>
 * </ul>
 *
 * <p>Security scheme: Bearer JWT (populated in Story 1.3).
 * Public endpoints (/api/v1/auth/**) do not require the Bearer token.
 */
@Configuration
public class OpenApiConfig {

    private static final String BEARER_SCHEME = "bearerAuth";

    @Bean
    public OpenAPI keevoOpenAPI() {
        return new OpenAPI()
            .servers(List.of(                                    // ← AJOUTER
                new Server().url("http://localhost:4500")        // ← AJOUTER
                    .description("Local dev"),                   // ← AJOUTER
                new Server().url("https://keevo.ad2s.cm")        // ← AJOUTER
                    .description("Production")                   // ← AJOUTER
            )) 
            .info(new Info()
                .title("Keevo API")
                .description("""
                    RESTful API for Keevo — inventory and sales management for merchants in Cameroon.

                    **Authentication**: After registration (Story 1.2) and login (Story 1.3),
                    include the JWT token in the `Authorization: Bearer <token>` header for all
                    protected endpoints.

                    **Multi-tenant**: Every request beyond /auth/** operates within the tenant
                    context derived from the JWT `tenantId` claim.
                    """)
                .version("1.0.0")
                .contact(new Contact()
                    .name("Keevo Development Team")
                    .email("dev@keevo.cm"))
                .license(new License()
                    .name("Proprietary")
                    .url("https://keevo.cm")))
            .addSecurityItem(new SecurityRequirement().addList(BEARER_SCHEME))
            .components(new Components()
                .addSecuritySchemes(BEARER_SCHEME, new SecurityScheme()
                    .name(BEARER_SCHEME)
                    .type(SecurityScheme.Type.HTTP)
                    .scheme("bearer")
                    .bearerFormat("JWT")
                    .description("JWT token obtained from POST /api/v1/auth/login (Story 1.3). " +
                                 "Registration endpoint does NOT require this token.")));
    }
}
