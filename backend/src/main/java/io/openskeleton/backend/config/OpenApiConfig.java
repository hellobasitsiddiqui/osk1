package io.openskeleton.backend.config;

import io.swagger.v3.oas.models.OpenAPI;
import io.swagger.v3.oas.models.info.Info;
import io.swagger.v3.oas.models.info.License;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * Base OpenAPI metadata for the generated spec (title/version/description/licence).
 * springdoc auto-generates the paths from the controllers; this just stamps the
 * document-level info so /v3/api-docs and the Swagger UI are properly labelled.
 * The interactive UI is enabled in non-prod and disabled in prod (see the
 * springdoc config in application.yml / application-prod.yml).
 */
@Configuration
public class OpenApiConfig {

    @Bean
    public OpenAPI openSkeletonOpenAPI() {
        return new OpenAPI().info(new Info()
                .title("OpenSkeleton API")
                .version("v1")
                .description("Generated API documentation for the OpenSkeleton backend.")
                .license(new License().name("MIT")));
    }
}
