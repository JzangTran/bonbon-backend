package com.bonbon.backend.common.config;

import io.swagger.v3.oas.models.Components;
import io.swagger.v3.oas.models.OpenAPI;
import io.swagger.v3.oas.models.info.Info;
import io.swagger.v3.oas.models.security.SecurityRequirement;
import io.swagger.v3.oas.models.security.SecurityScheme;
import com.bonbon.backend.common.security.CurrentPrincipal;
import org.springdoc.core.utils.SpringDocUtils;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * The API contract the web and mobile clients generate their types from.
 * Served only where {@code springdoc.api-docs.enabled=true} (dev profile).
 */
@Configuration
class OpenApiConfig {

    private static final String BEARER = "bearerAuth";

    static {
        // Resolved from the access token by CurrentPrincipalArgumentResolver, not sent by clients.
        SpringDocUtils.getConfig().addRequestWrapperToIgnore(CurrentPrincipal.class);
    }

    @Bean
    OpenAPI bonbonOpenApi() {
        return new OpenAPI()
                .info(new Info().title("bonbon API").version("v1")
                        .description("Hyperlocal food ordering: customer, seller and admin endpoints."))
                .components(new Components().addSecuritySchemes(BEARER, new SecurityScheme()
                        .type(SecurityScheme.Type.HTTP).scheme("bearer").bearerFormat("JWT")))
                .addSecurityItem(new SecurityRequirement().addList(BEARER));
    }
}
