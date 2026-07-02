package io.openskeleton.backend.web;

import org.springframework.context.annotation.Configuration;
import org.springframework.http.MediaType;
import org.springframework.web.servlet.config.annotation.ContentNegotiationConfigurer;
import org.springframework.web.servlet.config.annotation.WebMvcConfigurer;

/**
 * MVC configuration enforcing the API-wide "JSON only, never XML" policy (TM-72).
 *
 * <p><b>Why:</b> a prior build served XML to browsers because a Jackson XML
 * message converter was on the classpath and Spring honoured the browser's
 * {@code Accept: application/xml}. Here we tell the content-negotiation manager to
 * <b>ignore the Accept header entirely</b> and always fall back to JSON, so no
 * caller — browser or otherwise — can ever coerce a non-JSON representation, and a
 * pure {@code Accept: application/xml} yields JSON (200) rather than a 406.
 * This is the app-wide guarantee; individual endpoints no longer need to pin
 * {@code produces} for this reason (the /health pin from OSK-31 is now redundant
 * but harmless).
 */
@Configuration
public class WebConfig implements WebMvcConfigurer {

    @Override
    public void configureContentNegotiation(ContentNegotiationConfigurer configurer) {
        configurer.defaultContentType(MediaType.APPLICATION_JSON).ignoreAcceptHeader(true);
    }
}
