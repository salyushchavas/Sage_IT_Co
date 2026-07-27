package com.spire.backend.config;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.web.cors.CorsConfiguration;
import org.springframework.web.cors.CorsConfigurationSource;
import org.springframework.web.cors.UrlBasedCorsConfigurationSource;

import java.util.List;

@Configuration
public class CorsConfig {

    @Value("${cors.allowed-origins}")
    private String allowedOrigins;

    @Bean
    public CorsConfigurationSource corsConfigurationSource() {
        CorsConfiguration config = new CorsConfiguration();
        // setAllowedOriginPatterns (not setAllowedOrigins) so wildcard
        // entries like https://*.vercel.app work alongside allow-credentials.
        // Spring forbids setAllowedOrigins("*") + allowCredentials(true),
        // but the *Patterns variant is the supported escape hatch.
        //
        // Origins from CORS_ALLOWED_ORIGINS PLUS the canonical Sage IT
        // domains, always included so a missing/partial env var can't lock
        // the real site out of its own API (this bit the consultant portal:
        // www.sageitco.com wasn't in the env list -> preflight 403). The
        // apex is listed explicitly because https://*.sageitco.com does
        // NOT match a bare apex.
        java.util.LinkedHashSet<String> origins = new java.util.LinkedHashSet<>();
        if (allowedOrigins != null) {
            for (String o : allowedOrigins.split(",")) {
                String trimmed = o.trim();
                if (!trimmed.isEmpty()) origins.add(trimmed);
            }
        }
        origins.add("https://sageitco.com");
        origins.add("https://www.sageitco.com");
        origins.add("https://*.sageitco.com");
        config.setAllowedOriginPatterns(new java.util.ArrayList<>(origins));
        config.setAllowedMethods(List.of("GET", "POST", "PUT", "DELETE", "PATCH", "OPTIONS"));
        config.setAllowedHeaders(List.of("*"));
        // X-Preview-Error carries the server-side reason a PDF render failed.
        // It must be listed here: this CorsConfiguration SETS the
        // Access-Control-Expose-Headers response header, so a controller that
        // adds its own copy is overridden and the browser hides the header.
        config.setExposedHeaders(List.of("Authorization", "Content-Type", "X-Preview-Error"));
        config.setAllowCredentials(true);
        config.setMaxAge(3600L);

        UrlBasedCorsConfigurationSource source = new UrlBasedCorsConfigurationSource();
        source.registerCorsConfiguration("/**", config);
        return source;
    }
}
